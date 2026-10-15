package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the reader had been shown for one slot at the moment of its event: the forecast
 * <em>as of the event</em>, resolved one way for everything that needs to know.
 *
 * <p>Two readers must agree, and this class is why they do (outcome-rating plan §2 #1, Codex round 9
 * on #1185): the provenance an outcome rating is recorded against ({@code OutcomeRatingService}),
 * and the forecast SERVE path for a passed slot ({@code ForecastController.serveRange}, O2), so the
 * star the past-window UI draws is the star the record captures.
 *
 * <p><b>Step 1 — the SERVED result.</b> For a regioned place, {@link
 * EvaluationViewService#getLiveScoresForEnrichment} — the one serve-time resolution the map and the
 * Plan tab read. It counts only if it had <em>arrived</em> by the event: its {@code evaluatedAt}
 * must be at or before it (a Batch API result submitted before the event can land a day after it,
 * and was not what the reader saw; a legacy result with no {@code evaluatedAt} is skipped). ⚠️ For a
 * <b>canopy-bearing</b> place (a WOODLAND or BLUEBELL type) {@code evaluatedAt} is not proof: an
 * OPEN_FELL recombination keeps the SKY result's {@code evaluatedAt} while the composite star exists
 * only once the bluebell lane merges, so the availability instant is the cached entry's region-level
 * last write ({@link BriefingEvaluationService#getCachedEntryUpdatedAt}), which must also be at or
 * before the event. A later write by ANY place of the region sends the canopy place to step 2 — the
 * safe direction, since a guessed composite would calibrate against a star never seen. The run is
 * the result's {@code submittedAt} (the forecast's own age), else its {@code evaluatedAt}; the cycle
 * id is the {@code pipeline_run} whose {@code trigger_time} EQUALS that {@code submittedAt}, else
 * null (an ad-hoc batch, a synchronous run, a legacy row).
 *
 * <p><b>Step 2 — the {@code forecast_evaluation} candidate</b>, resolved on EVERY call whether or not
 * step 1 answered: the latest row (by submission instant) that has something to say (a rating or a
 * triage reason), was SCORED by the event ({@code scored_at IS NOT NULL AND scored_at <= event}; a row
 * with no {@code scored_at} is never a candidate — its submission instant is no proof it had
 * arrived), and was not retracted as of the event: the slot's latest {@code SKIPPED_STABILITY}
 * recorded at or before the event retracts any row whose SUBMISSION instant precedes it, exactly the
 * comparison {@code EvaluationViewService.isRetractedByStabilitySkip} makes at serve time ({@code
 * scored_at} serves the availability bound alone, because a row submitted before a skip but scored
 * after it was never served). A skip recorded AFTER the event is ignored. Its id is the
 * {@code forecastEvaluationId}; when step 1 did not answer it also supplies the run (its submission
 * instant, no cycle id).
 *
 * <p><b>The star the reader saw</b> ({@link AsOfEvent#forecastRating}): step 1's served {@code
 * rating} when it answered; else the step-2 row's {@code rating} ONLY for a place with no canopy
 * type, and <em>null</em> for a canopy-bearing place — its served star may have been recombined from
 * a sky and a bluebell result the cache has since replaced, no table keeps the combined value, and
 * the sky row's star would be a number the reader never saw. Null means "not recoverable".
 *
 * <p><b>Batch-friendly.</b> {@link #resolveAll} resolves a whole roster's slot-day from one read of
 * the day's scored rows, one read of its stability skips and one bulk read of the served results; the
 * per-slot answers come from memory. {@link #resolve} is the one-place form, using the single-region
 * served read instead of the all-regions bulk one.
 *
 * <p>Never a roster-wide "latest cycle before the event" (plan §4 #2): a cycle that failed, skipped
 * the slot or landed nothing is not a forecast the reader saw.
 */
@Service
public class AsOfEventResolver {

    private final EvaluationViewService evaluationViewService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final ForecastRunDispositionRepository dispositionRepository;
    private final PipelineRunRepository pipelineRunRepository;

    /**
     * Creates the resolver.
     *
     * @param evaluationViewService        the serve-time resolution of a slot's result (step 1)
     * @param briefingEvaluationService    the durable cache entry's last-write instant (step 1, canopy)
     * @param forecastEvaluationRepository the scored-row pool (step 2)
     * @param dispositionRepository        the stability-skip evidence (step 2)
     * @param pipelineRunRepository        the cycle a served result's instant names
     */
    public AsOfEventResolver(EvaluationViewService evaluationViewService,
            BriefingEvaluationService briefingEvaluationService,
            ForecastEvaluationRepository forecastEvaluationRepository,
            ForecastRunDispositionRepository dispositionRepository,
            PipelineRunRepository pipelineRunRepository) {
        this.evaluationViewService = evaluationViewService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.dispositionRepository = dispositionRepository;
        this.pipelineRunRepository = pipelineRunRepository;
    }

    /**
     * The forecast as of an event.
     *
     * @param result               step 1's served result, or null when step 1 did not answer
     * @param forecastEvaluationId the step-2 candidate's id, or null when there is none (set whether
     *                             or not step 1 answered)
     * @param runAt                the forecast's own age: the served result's {@code submittedAt}
     *                             (else {@code evaluatedAt}), or the step-2 row's submission instant
     *                             when step 1 did not answer; null when nothing was available
     * @param pipelineRunId        the cycle whose trigger instant equals the served result's {@code
     *                             submittedAt}, or null
     * @param forecastRating       the star the reader saw (see the class javadoc), or null when
     *                             nothing was served or it is not recoverable
     * @param forecastRow          the step-2 candidate row itself, or null — what the serve path
     *                             serves for a passed slot (with {@code forecastRating} as its
     *                             rating); an extension of the plan's five fields so the serve path
     *                             never has to fetch the row again
     */
    public record AsOfEvent(BriefingEvaluationResult result, Long forecastEvaluationId, Instant runAt,
            Long pipelineRunId, Integer forecastRating, ForecastEvaluationEntity forecastRow) {

        /** Nothing was available by the event: every field null. */
        public static final AsOfEvent NONE = new AsOfEvent(null, null, null, null, null, null);
    }

    /**
     * Whether a place can have a recombined (sky + bluebell) or canopy-only star: it carries a
     * WOODLAND or BLUEBELL type.
     *
     * @param location the place
     * @return true for a canopy-bearing place
     */
    public static boolean isCanopyBearing(LocationEntity location) {
        return location.getLocationType() != null
                && (location.getLocationType().contains(LocationType.WOODLAND)
                    || location.getLocationType().contains(LocationType.BLUEBELL));
    }

    /**
     * Resolves one place's slot as of its event.
     *
     * @param location     the place
     * @param date         the event date
     * @param type         SUNRISE or SUNSET
     * @param eventInstant this place's own event instant; null resolves to {@link AsOfEvent#NONE}
     * @return the forecast as of the event; never null
     */
    public AsOfEvent resolve(LocationEntity location, LocalDate date, TargetType type,
            Instant eventInstant) {
        if (eventInstant == null) {
            return AsOfEvent.NONE;
        }
        Map<Long, AsOfEvent> resolved = resolveSlots(List.of(new Slot(location, eventInstant)), date, type,
                region -> evaluationViewService.getLiveScoresForEnrichment(region, date, type));
        return resolved.getOrDefault(location.getId(), AsOfEvent.NONE);
    }

    /**
     * Resolves many places' slot-day as of their own events, in a handful of queries however many
     * places there are.
     *
     * @param locations    the places
     * @param date         the event date
     * @param type         SUNRISE or SUNSET
     * @param eventInstants each place's own event instant; a null instant resolves to nothing
     * @return location id to its forecast as of the event; a place with no event instant, or nothing
     *         available, maps to {@link AsOfEvent#NONE}
     */
    public Map<Long, AsOfEvent> resolveAll(Collection<LocationEntity> locations, LocalDate date,
            TargetType type, Function<LocationEntity, Instant> eventInstants) {
        List<Slot> slots = new ArrayList<>();
        Map<Long, AsOfEvent> out = new LinkedHashMap<>();
        for (LocationEntity location : locations) {
            Instant event = eventInstants.apply(location);
            if (event == null) {
                out.put(location.getId(), AsOfEvent.NONE);
            } else {
                slots.add(new Slot(location, event));
            }
        }
        if (slots.isEmpty()) {
            return out;
        }
        Lazy<Map<String, Map<String, BriefingEvaluationResult>>> bulk = new Lazy<>(
                () -> evaluationViewService.getLiveScoresForEnrichmentBulk(date, date, Set.of(type)));
        out.putAll(resolveSlots(slots, date, type,
                region -> bulk.get().getOrDefault(region + "|" + date + "|" + type, Map.of())));
        return out;
    }

    private record Slot(LocationEntity location, Instant event) {
    }

    private Map<Long, AsOfEvent> resolveSlots(List<Slot> slots, LocalDate date, TargetType type,
            Function<String, Map<String, BriefingEvaluationResult>> servedByRegion) {
        Instant latestEvent = slots.stream().map(Slot::event).max(Instant::compareTo).orElseThrow();
        List<Long> ids = slots.stream().map(s -> s.location().getId()).toList();
        List<String> names = slots.stream().map(s -> s.location().getName()).toList();

        // Step 2's pool, read once: scored by the latest event of interest, newest submission first.
        Map<Long, List<ForecastEvaluationEntity>> rowsByLocation = new HashMap<>();
        for (ForecastEvaluationEntity row : forecastEvaluationRepository
                .findScoredByEventInstant(ids, date, type, latestEvent)) {
            rowsByLocation.computeIfAbsent(row.getLocation().getId(), k -> new ArrayList<>()).add(row);
        }
        Map<String, List<Instant>> skipsByName = new HashMap<>();
        for (Object[] skip : dispositionRepository
                .findStabilitySkipInstantsUpTo(names, date, type.name(), latestEvent)) {
            skipsByName.computeIfAbsent((String) skip[0], k -> new ArrayList<>()).add((Instant) skip[1]);
        }

        Map<String, Map<String, BriefingEvaluationResult>> servedMemo = new HashMap<>();
        Map<String, Optional<Instant>> entryMemo = new HashMap<>();
        Map<Instant, Optional<Long>> runIdMemo = new HashMap<>();

        Map<Long, AsOfEvent> out = new LinkedHashMap<>();
        for (Slot slot : slots) {
            LocationEntity location = slot.location();
            Instant event = slot.event();
            boolean canopy = isCanopyBearing(location);

            ForecastEvaluationEntity candidate = candidate(
                    rowsByLocation.getOrDefault(location.getId(), List.of()),
                    skipsByName.getOrDefault(location.getName(), List.of()), event);

            BriefingEvaluationResult served = null;
            if (location.getRegion() != null) {
                String region = location.getRegion().getName();
                BriefingEvaluationResult live = servedMemo
                        .computeIfAbsent(region, servedByRegion)
                        .get(location.getName());
                if (arrivedBy(live, event) && (!canopy || entryWrittenBy(entryMemo, region, date, type, event))) {
                    served = live;
                }
            }

            Long candidateId = candidate != null ? candidate.getId() : null;
            if (served != null) {
                Instant runAt = served.submittedAt() != null ? served.submittedAt() : served.evaluatedAt();
                Long runId = served.submittedAt() == null ? null
                        : runIdMemo.computeIfAbsent(served.submittedAt(), this::cycleOf).orElse(null);
                out.put(location.getId(),
                        new AsOfEvent(served, candidateId, runAt, runId, served.rating(), candidate));
            } else if (candidate != null) {
                Integer star = canopy ? null : candidate.getRating();
                out.put(location.getId(), new AsOfEvent(null, candidateId,
                        candidate.getForecastRunAt().toInstant(ZoneOffset.UTC), null, star, candidate));
            } else {
                out.put(location.getId(), AsOfEvent.NONE);
            }
        }
        return out;
    }

    /** Step 1's availability gate: the served result had ARRIVED by the event. */
    private static boolean arrivedBy(BriefingEvaluationResult live, Instant event) {
        return live != null && live.evaluatedAt() != null && !live.evaluatedAt().isAfter(event);
    }

    /**
     * A canopy-bearing place's additional gate: its region's cached entry was last written at or
     * before the event (read once per region). No durable row, or a later write, fails it.
     */
    private boolean entryWrittenBy(Map<String, Optional<Instant>> memo, String region, LocalDate date,
            TargetType type, Instant event) {
        return memo.computeIfAbsent(region,
                        r -> briefingEvaluationService.getCachedEntryUpdatedAt(r, date, type))
                .filter(at -> !at.isAfter(event)).isPresent();
    }

    /**
     * Step 2: the latest row with something to say, scored by the event and not retracted as of it.
     * {@code rows} arrive newest submission first, so the first passing row is the answer.
     */
    private static ForecastEvaluationEntity candidate(List<ForecastEvaluationEntity> rows,
            List<Instant> skipInstants, Instant event) {
        Instant skip = skipInstants.stream().filter(at -> !at.isAfter(event)).max(Instant::compareTo)
                .orElse(null);
        for (ForecastEvaluationEntity row : rows) {
            if (row.getScoredAt() == null || row.getScoredAt().isAfter(event)
                    || !EvaluationViewService.hasSomethingToSay(row)) {
                continue;
            }
            if (EvaluationViewService.isRetractedByStabilitySkip(
                    row.getForecastRunAt().toInstant(ZoneOffset.UTC), skip)) {
                continue;
            }
            return row;
        }
        return null;
    }

    private Optional<Long> cycleOf(Instant triggerTime) {
        return pipelineRunRepository.findFirstByTriggerTimeOrderByIdAsc(triggerTime)
                .map(PipelineRunEntity::getId);
    }

    /** A value computed on first use and then kept — the bulk read is only made if a slot needs it. */
    private static final class Lazy<T> {

        private final Supplier<T> source;
        private T value;
        private boolean done;

        private Lazy(Supplier<T> source) {
            this.source = source;
        }

        private T get() {
            if (!done) {
                value = source.get();
                done = true;
            }
            return value;
        }
    }
}
