package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AppBuild;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The upsert behind {@code PUT /api/outcome/ratings}: one row per {@code (user, location, event date,
 * event type)}, replaced in place by a later rating.
 *
 * <p>⚠️ <b>No {@code @Transactional} — on this class or on {@link #upsert} — and that is the whole
 * point.</b> The shape is find-or-insert: check the row exists, insert it with a plain native
 * {@code INSERT} (the local H2 database has no {@code ON CONFLICT}), and when two requests race to
 * create the same slot the loser's insert violates {@code uq_outcome_rating_slot}; the loser catches
 * that, sees the row exists now, and updates it. That catch works only when the INSERT is its own
 * transaction. Inside one enclosing transaction a Postgres unique violation aborts it, and the
 * re-check that follows fails with "current transaction is aborted" — a breakage invisible to H2 and
 * to a {@code @DataJpaTest} that wraps each test in a transaction. So the transaction boundary lives
 * on the repository's two write methods and nowhere above them; the race test runs outside any
 * transaction ({@code NOT_SUPPORTED}) and the CI-only migration test races this REAL store on
 * Postgres. {@code AskUsageStore} is the precedent (plan §1 #8); a caller that adds a transaction
 * around {@link #upsert} breaks it silently.
 *
 * <p>Two dependencies (the repository and the build identity), so a test builds the real store over
 * the real repository and races it. Every write stamps {@code app_build} (V182) with the running
 * release — on an update too, because the rating itself was made again by this build.
 */
@Service
public class OutcomeRatingStore {

    private static final Logger LOG = LoggerFactory.getLogger(OutcomeRatingStore.class);

    private final OutcomeRatingRepository repository;
    private final AppBuild appBuild;

    /**
     * Creates the store.
     *
     * @param repository the {@code outcome_rating} table
     * @param appBuild   the running build, stamped as {@code app_build} on every write
     */
    public OutcomeRatingStore(OutcomeRatingRepository repository, AppBuild appBuild) {
        this.repository = repository;
        this.appBuild = appBuild;
    }

    /**
     * The forecast a rating is recorded against — the provenance columns, all nullable.
     *
     * @param pipelineRunId        the cycle the served forecast came from, or null
     * @param forecastRunAt        the served forecast's submission instant, or null
     * @param forecastEvaluationId the {@code forecast_evaluation} row available by the event, or null
     * @param forecastRating       the predicted star the reader saw, or null when not recoverable
     */
    public record RatedRun(Long pipelineRunId, Instant forecastRunAt, Long forecastEvaluationId,
            Integer forecastRating) {

        /** No forecast behind the slot: every provenance column null. */
        public static final RatedRun NONE = new RatedRun(null, null, null, null);
    }

    /**
     * Records a rating with no tag, replacing the user's earlier one for the slot (and its tag).
     *
     * @param userId     the rating user
     * @param locationId the location
     * @param date       the event date
     * @param type       SUNRISE or SUNSET
     * @param rating     1–5, or null for "didn't see it"
     * @param eventTime  the solar event instant for this location
     * @param run        the forecast the rating is recorded against ({@link RatedRun#NONE} for none)
     * @param now        stamped as {@code updated_at} (and {@code created_at} on a first rating)
     * @see #upsert(long, long, LocalDate, TargetType, Integer, SeenTag, Instant, RatedRun, Instant)
     */
    public void upsert(long userId, long locationId, LocalDate date, TargetType type, Integer rating,
            Instant eventTime, RatedRun run, Instant now) {
        upsert(userId, locationId, date, type, rating, null, eventTime, run, now);
    }

    /**
     * Records a rating, replacing the user's earlier one for the slot.
     *
     * <p>{@code seen} is written as given - null clears the stored tag. Whether an earlier tag is kept
     * is the caller's decision ({@code OutcomeRatingService}), made against the row it already read.
     *
     * @param userId     the rating user
     * @param locationId the location
     * @param date       the event date
     * @param type       SUNRISE or SUNSET
     * @param rating     1–5, or null for "didn't see it"
     * @param seen       what the reader says they saw, or null for no tag
     * @param eventTime  the solar event instant for this location
     * @param run        the forecast the rating is recorded against ({@link RatedRun#NONE} for none)
     * @param now        stamped as {@code updated_at} (and {@code created_at} on a first rating)
     * @throws DataIntegrityViolationException when an insert fails and the row still does not exist
     *         (the user or location is gone: a foreign-key violation, not a lost race)
     * @throws IllegalStateException when the row vanished between the check and the update
     */
    public void upsert(long userId, long locationId, LocalDate date, TargetType type, Integer rating,
            SeenTag seen, Instant eventTime, RatedRun run, Instant now) {
        if (!repository.existsByUserIdAndLocationIdAndEventDateAndEventType(userId, locationId, date, type)) {
            try {
                repository.insertRow(userId, locationId, date, type.name(), rating, eventTime,
                        run.pipelineRunId(), run.forecastRunAt(), run.forecastEvaluationId(),
                        run.forecastRating(), appBuild.value(), now,
                        seen == null ? null : seen.name());
                return;
            } catch (DataIntegrityViolationException e) {
                if (!repository.existsByUserIdAndLocationIdAndEventDateAndEventType(
                        userId, locationId, date, type)) {
                    throw e;
                }
                LOG.debug("Lost the race to create user {}'s rating of location {} {} {}; updating theirs",
                        userId, locationId, date, type);
            }
        }
        int updated = repository.updateRow(userId, locationId, date, type, rating, eventTime,
                run.pipelineRunId(), run.forecastRunAt(), run.forecastEvaluationId(),
                run.forecastRating(), appBuild.value(), now, seen);
        if (updated != 1) {
            throw new IllegalStateException("Could not store user " + userId + "'s rating of location "
                    + locationId + " " + date + " " + type);
        }
    }
}
