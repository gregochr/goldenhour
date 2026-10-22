package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OutcomeRatingDto;
import com.gregochr.goldenhour.model.SeenChoice;
import com.gregochr.goldenhour.model.SlotRatingRow;
import com.gregochr.goldenhour.model.SlotRatingsDto;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import com.gregochr.goldenhour.service.OutcomeRatingStore.RatedRun;
import com.gregochr.goldenhour.service.batch.ForecastDispositionService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Records and reads the per-user "how did it look?" rating of a past sunrise or sunset
 * ({@code docs/engineering/outcome-rating-plan.md} §2 #1).
 *
 * <p>⚠️ <b>Deliberately NOT {@code @Transactional}</b>, and nothing it calls may make it so: {@link
 * OutcomeRatingStore#upsert} relies on its INSERT being its own transaction so a lost race's unique
 * violation can be caught and turned into an update (see that class). A transaction here would
 * swallow the store back into one.
 *
 * <p>The forecast a rating is recorded against is whatever {@link AsOfEventResolver} says the reader
 * had been shown at the event — the same resolution the forecast serve path uses for a passed slot,
 * so the star the past-window UI draws is the star the record captures.
 */
@Service
public class OutcomeRatingService {

    /**
     * How old an event may be and still be rated: {@code RETENTION_DAYS − FORECAST_HORIZON_DAYS − 1}
     * = 30 − 5 − 1 = 24 days.
     *
     * <p>The as-of-event resolution retracts a row using the slot's {@code SKIPPED_STABILITY}
     * disposition, and {@code forecast_run_disposition} is pruned by the disposition's own {@code
     * created_at} at {@link ForecastDispositionService#RETENTION_DAYS} (30 days) while {@code
     * forecast_evaluation} is never pruned. A stability skip for an event is recorded up to {@link
     * RunType#FORECAST_HORIZON_DAYS} (5) days BEFORE it ({@code NightlyEligibilityPolicy} records every
     * T+4-and-beyond rejection as one, and the candidate window runs to T+5), so for an event 25–30
     * days old the skip may already be deleted and a retracted row would resolve as served. The extra
     * day covers the prune deleting by instant rather than by UK date. The Map reaches only T-4, so
     * the bound costs no real use.
     */
    public static final int MAX_RATING_AGE_DAYS =
            ForecastDispositionService.RETENTION_DAYS - RunType.FORECAST_HORIZON_DAYS - 1;

    private final OutcomeRatingStore store;
    private final OutcomeRatingRepository repository;
    private final AppUserRepository userRepository;
    private final LocationRepository locationRepository;
    private final SolarEventFreshness solarEventFreshness;
    private final AsOfEventResolver asOfEventResolver;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param store               the find-or-insert upsert
     * @param repository          the {@code outcome_rating} table (reads)
     * @param userRepository      resolves the authenticated username
     * @param locationRepository  resolves the location rated
     * @param solarEventFreshness the per-location sunrise/sunset instant
     * @param asOfEventResolver   what the reader had been shown at the event
     * @param clock               "now" for the has-it-happened and too-old checks (a PUT is never
     *                            rewound)
     */
    public OutcomeRatingService(OutcomeRatingStore store, OutcomeRatingRepository repository,
            AppUserRepository userRepository, LocationRepository locationRepository,
            SolarEventFreshness solarEventFreshness, AsOfEventResolver asOfEventResolver, Clock clock) {
        this.store = store;
        this.repository = repository;
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.solarEventFreshness = solarEventFreshness;
        this.asOfEventResolver = asOfEventResolver;
        this.clock = clock;
    }

    /**
     * Records (or replaces) a user's rating of a window that has already happened, leaving any
     * earlier {@code seen} tag as it is.
     *
     * @param username   the authenticated user
     * @param locationId the location rated
     * @param date       the event date
     * @param type       SUNRISE or SUNSET
     * @param rating     1–5, or null for "didn't see it"
     * @return the stored rating with the provenance it was recorded against
     * @see #rate(String, Long, LocalDate, TargetType, Integer, SeenChoice)
     */
    public OutcomeRatingDto rate(String username, Long locationId, LocalDate date, TargetType type,
            Integer rating) {
        return rate(username, locationId, date, type, rating, null);
    }

    /**
     * Records (or replaces) a user's rating of a window that has already happened.
     *
     * <p><b>The {@code seen} contract</b> (valley-mist-plan M3): {@code null} (the property was not
     * sent) keeps the earlier tag; {@link SeenChoice#CLEAR} (an explicit JSON {@code null}) clears it;
     * a choice holding a tag sets it. The one exception to "keeps": an earlier tag that cannot stand
     * beside the new rating - {@code NOT_SEEN} beside a star, or a sighting beside "didn't see it" -
     * is cleared rather than kept, since it described the answer being replaced. The tag is
     * independent of the star otherwise.
     *
     * <p><b>Column-scoped writes.</b> An existing row is never read-modified-written: the request
     * picks ONE statement that writes only its own columns (see {@code OutcomeRatingStore.Write}), so a
     * tag tap and a star tap that overlap cannot restore each other's stale value. A tag tap whose star
     * has since changed underneath it is refused by V187's CHECK and answers 409.
     *
     * @param username   the authenticated user
     * @param locationId the location rated
     * @param date       the event date
     * @param type       SUNRISE or SUNSET
     * @param rating     1–5, or null for "didn't see it"
     * @param seen       what the reader says they saw: absent, a clear, or a tag
     * @return the stored rating with the provenance it was recorded against
     * @throws IllegalArgumentException when an argument is missing or out of range, {@code seen} is
     *         {@code NOT_SEEN} beside a star or a sighting beside "didn't see it", the event has not
     *         passed (this place's own event time plus the 30-minute afterglow, on the real clock), or
     *         it is older than {@link #MAX_RATING_AGE_DAYS}
     * @throws NoSuchElementException  when the user or the location does not exist
     */
    public OutcomeRatingDto rate(String username, Long locationId, LocalDate date, TargetType type,
            Integer rating, SeenChoice seen) {
        requireSlot(locationId, date, type);
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        SeenTag requested = seen == null ? null : seen.tag();
        if (requested != null && !agrees(requested, rating)) {
            throw new IllegalArgumentException(requested == SeenTag.NOT_SEEN
                    ? "seen NOT_SEEN goes with \"didn't see it\", not a star rating"
                    : "a \"didn't see it\" rating can only carry seen NOT_SEEN");
        }
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new NoSuchElementException("Location not found: " + locationId));

        Instant now = clock.instant();
        LocalDateTime eventUtc = solarEventFreshness.eventTime(location, date, type);
        if (!PlanWindowProjector.hasPassed(eventUtc, LocalDateTime.ofInstant(now, ZoneOffset.UTC))) {
            throw new IllegalArgumentException("That " + word(type) + " has not happened yet");
        }
        if (date.isBefore(ForecastHorizon.today(clock).minusDays(MAX_RATING_AGE_DAYS))) {
            throw new IllegalArgumentException("That " + word(type) + " is too old to rate");
        }

        Instant eventTime = eventUtc.toInstant(ZoneOffset.UTC);
        Optional<OutcomeRatingEntity> earlier = repository
                .findByUserIdAndLocationIdAndEventDateAndEventType(user.getId(), locationId, date, type);
        RatedRun run = earlier
                .filter(e -> e.getForecastRunAt() != null)
                .map(OutcomeRatingService::provenanceOf)
                .orElseGet(() -> resolve(location, date, type, eventTime));
        // Which columns an existing row's update may write - chosen from the REQUEST alone, never from the
        // row read above (that copy can be stale by the time the statement runs):
        //   no seen sent            -> RATING: the star; a compatible tag stays, a contradicted one goes (in SQL)
        //   seen sent, with a star  -> TAG: the tag alone, so a tag tap never carries a stale star back
        //   seen sent, no star      -> BOTH: "didn't see it" and its tag are one answer, one statement
        OutcomeRatingStore.Write mode = seen == null ? OutcomeRatingStore.Write.RATING
                : rating != null ? OutcomeRatingStore.Write.TAG : OutcomeRatingStore.Write.BOTH;
        store.upsert(user.getId(), locationId, date, type, rating, requested, mode, eventTime, run, now);
        if (earlier.isEmpty()) {
            return new OutcomeRatingDto(locationId, location.getName(), date, type, rating, now,
                    run.forecastRunAt(), run.pipelineRunId(), run.forecastRating(), requested);
        }
        // An update wrote only some columns, so the answer is the row as stored, not the request.
        OutcomeRatingEntity stored = repository
                .findByUserIdAndLocationIdAndEventDateAndEventType(user.getId(), locationId, date, type)
                .orElseThrow(() -> new IllegalStateException("Rating vanished after it was stored"));
        return new OutcomeRatingDto(locationId, location.getName(), date, type, stored.getRating(),
                stored.getUpdatedAt(), stored.getForecastRunAt(), stored.getPipelineRunId(),
                stored.getForecastRating(), stored.getSeen());
    }

    /**
     * The caller's own ratings over a date range. Location names are joined with one lookup over the
     * distinct ids, never per row.
     *
     * @param username the authenticated user
     * @param from     first event date (inclusive)
     * @param to       last event date (inclusive)
     * @return the caller's ratings, oldest event first; empty for an account that has rated nothing
     * @throws NoSuchElementException when the user does not exist
     */
    public List<OutcomeRatingDto> myRatings(String username, LocalDate from, LocalDate to) {
        AppUserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        List<OutcomeRatingEntity> rows =
                repository.findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(user.getId(), from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = rows.stream().map(OutcomeRatingEntity::getLocationId).collect(Collectors.toSet());
        Map<Long, String> names = locationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(LocationEntity::getId, LocationEntity::getName));
        return rows.stream()
                .map(r -> new OutcomeRatingDto(r.getLocationId(), names.get(r.getLocationId()),
                        r.getEventDate(), r.getEventType(), r.getRating(), r.getUpdatedAt(),
                        r.getForecastRunAt(), r.getPipelineRunId(), r.getForecastRating(), r.getSeen()))
                .toList();
    }

    /**
     * Every user's rating of one window, newest first, with the forecast they were recorded against.
     * A window with no ratings still carries its run, resolved afresh.
     *
     * @param callerUsername the authenticated admin (their own row is marked {@code mine})
     * @param locationId     the location
     * @param date           the event date
     * @param type           SUNRISE or SUNSET
     * @return the window's ratings
     * @throws IllegalArgumentException when an argument is missing or the type is not SUNRISE/SUNSET
     * @throws NoSuchElementException   when the caller or the location does not exist
     */
    public SlotRatingsDto slotRatings(String callerUsername, Long locationId, LocalDate date,
            TargetType type) {
        requireSlot(locationId, date, type);
        AppUserEntity caller = userRepository.findByUsername(callerUsername)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new NoSuchElementException("Location not found: " + locationId));

        List<SlotRatingRow> rows = repository.findAllForSlot(locationId, date, type);
        List<SlotRatingsDto.Row> out = rows.stream()
                .map(r -> new SlotRatingsDto.Row(r.username(), r.rating(), r.updatedAt(),
                        r.userId().equals(caller.getId()), r.seen()))
                .toList();
        if (!rows.isEmpty()) {
            // The stored values: the event time survives a later change to the solar library, and the
            // run is the one every row was resolved against when it was written.
            SlotRatingRow newest = rows.getFirst();
            return new SlotRatingsDto(newest.eventTime(), newest.forecastRunAt(), newest.pipelineRunId(),
                    out);
        }
        LocalDateTime eventUtc = solarEventFreshness.eventTime(location, date, type);
        Instant eventTime = eventUtc == null ? null : eventUtc.toInstant(ZoneOffset.UTC);
        AsOfEvent asOf = asOfEventResolver.resolve(location, date, type, eventTime);
        return new SlotRatingsDto(eventTime, asOf.runAt(), asOf.pipelineRunId(), out);
    }

    /**
     * The forecast a change of mind keeps. The event is fixed, so the answer recorded the first time is
     * the answer; resolving again can only be WORSE — the served-result step reads an in-memory cache
     * that a restart empties for every past date, which would overwrite a full record (cycle, star)
     * with a degraded one. A row recorded with no forecast at all is resolved afresh.
     */
    private static RatedRun provenanceOf(OutcomeRatingEntity earlier) {
        return new RatedRun(earlier.getPipelineRunId(), earlier.getForecastRunAt(),
                earlier.getForecastEvaluationId(), earlier.getForecastRating());
    }

    private RatedRun resolve(LocationEntity location, LocalDate date, TargetType type, Instant eventTime) {
        AsOfEvent asOf = asOfEventResolver.resolve(location, date, type, eventTime);
        return new RatedRun(asOf.pipelineRunId(), asOf.runAt(), asOf.forecastEvaluationId(),
                asOf.forecastRating());
    }

    /**
     * Whether a tag can stand beside a rating: {@code NOT_SEEN} goes with "didn't see it" (no star)
     * and only with it, and every other tag goes with a star. The same pairing V187's CHECK states.
     */
    private static boolean agrees(SeenTag tag, Integer rating) {
        return (tag == SeenTag.NOT_SEEN) == (rating == null);
    }

    private static void requireSlot(Long locationId, LocalDate date, TargetType type) {
        if (locationId == null || date == null) {
            throw new IllegalArgumentException("locationId and eventDate are required");
        }
        if (type != TargetType.SUNRISE && type != TargetType.SUNSET) {
            throw new IllegalArgumentException("eventType must be SUNRISE or SUNSET");
        }
    }

    private static String word(TargetType type) {
        return type == TargetType.SUNRISE ? "sunrise" : "sunset";
    }
}
