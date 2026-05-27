package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingRatingStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists the best-bet picks the briefing phase produces against the
 * pipeline run that produced them, snapshotting each pick's numeric rating
 * so consecutive runs can be compared.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The intraday refresh's value is unproven until observed: does an
 * intraday run change Plan A or Plan B versus the morning's nightly run?
 * That comparison is the "do I need to replan?" signal intraday exists
 * for. Answering it requires both runs' picks to be stored side by side —
 * that is what this service writes. The numeric
 * {@link BestBet}-region average rating (snapshotted from cached Claude
 * evaluations at persist time) is the comparison primitive; the headline
 * prose alone is too noisy to compare run-to-run.
 *
 * <h2>Failure semantics</h2>
 *
 * <p>Persistence is best-effort. A DB failure on one pick is logged at
 * WARN and the loop continues with the next pick; a top-level failure
 * never propagates to the orchestrator (the briefing phase must not fail
 * just because the pick record could not be written). When no cached
 * Claude scores exist for a pick's (region, date, event_type), the
 * {@code claudeAverageRating} field is left null and the cross-run
 * comparison gracefully degrades to the confidence-level field.
 *
 * <p>The service is stateless and Spring-singleton; concurrent invocation
 * is safe because each call writes a fresh row per pick (no read-then-write
 * window on existing rows).
 */
@Service
public class PipelineRunPickService {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRunPickService.class);

    /**
     * Length cap for the {@code differs_by} CSV column. Equal to the schema
     * column width — anything longer (which would require >9 dimensions) is
     * a data error, not a length concern in practice.
     */
    private static final int DIFFERS_BY_MAX_LENGTH = 50;

    private final PipelineRunPickRepository repository;
    private final BriefingEvaluationService briefingEvaluationService;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param repository                pick repository
     * @param briefingEvaluationService source of the cached Claude scores used to
     *                                  snapshot the pick's numeric rating
     * @param clock                     injectable clock for deterministic tests
     */
    public PipelineRunPickService(PipelineRunPickRepository repository,
            BriefingEvaluationService briefingEvaluationService, Clock clock) {
        this.repository = repository;
        this.briefingEvaluationService = briefingEvaluationService;
        this.clock = clock;
    }

    /**
     * Persists the given picks against the given pipeline run.
     *
     * <p>A null or empty pick list is a legitimate no-op (the briefing produced
     * no recommendation — e.g. all regions in STANDDOWN with no fallback).
     * A null {@code pipelineRunId} is a programmer error — logged at WARN and
     * skipped, never throws.
     *
     * <p>Per-pick failures are caught and counted; the method always returns
     * normally so the orchestrator's BRIEFING phase is never aborted by a
     * pick-row persist problem.
     *
     * @param pipelineRunId the cycle that produced these picks
     * @param picks         the picks emitted by the briefing's best-bet advisor
     *                      (normally 1 or 2 items; may be empty)
     */
    public void persist(Long pipelineRunId, List<BestBet> picks) {
        if (pipelineRunId == null) {
            LOG.warn("[PICK] Refusing to persist {} picks — null pipelineRunId",
                    picks == null ? 0 : picks.size());
            return;
        }
        if (picks == null || picks.isEmpty()) {
            LOG.info("[PICK] No picks to persist for pipelineRunId={} "
                    + "(briefing produced empty recommendation set)", pipelineRunId);
            return;
        }

        Instant now = clock.instant();
        int saved = 0;
        int failed = 0;
        for (BestBet pick : picks) {
            try {
                PipelineRunPickEntity entity = buildEntity(pipelineRunId, pick, now);
                repository.save(entity);
                saved++;
            } catch (RuntimeException e) {
                LOG.warn("[PICK] Failed to persist rank-{} pick for pipelineRunId={}: {}",
                        pick.rank(), pipelineRunId, e.getMessage());
                failed++;
            }
        }
        LOG.info("[PICK] Persisted {}/{} picks for pipelineRunId={} (failed={})",
                saved, picks.size(), pipelineRunId, failed);
    }

    /**
     * Builds the entity for a single pick, including parsing the event date
     * out of the composite event id and snapshotting the region's
     * claudeAverageRating at the moment of persistence.
     */
    private PipelineRunPickEntity buildEntity(Long pipelineRunId, BestBet pick, Instant now) {
        PipelineRunPickEntity entity = new PipelineRunPickEntity();
        entity.setPipelineRunId(pipelineRunId);
        entity.setPickRank(pick.rank());
        entity.setHeadline(pick.headline());
        entity.setDetail(pick.detail());
        entity.setEventId(pick.event());
        entity.setRegion(pick.region());
        entity.setConfidence(pick.confidence() != null ? pick.confidence().name() : null);
        entity.setRelationship(pick.relationship());
        entity.setDiffersBy(formatDiffersBy(pick.differsBy()));
        entity.setRecordedAt(now);

        LocalDate eventDate = parseEventDate(pick.event());
        entity.setEventDate(eventDate);
        entity.setEventType(pick.eventType());

        entity.setClaudeAverageRating(lookupAverageRating(pick, eventDate));
        return entity;
    }

    /**
     * Parses {@code "yyyy-MM-dd"} from the composite event id
     * (e.g. {@code "2026-05-28_sunset"} → {@code 2026-05-28}). Returns null for
     * stay-home picks (null event id) or any malformed input — the field is
     * nullable in the schema, and a missing date does not invalidate the rest
     * of the row.
     */
    static LocalDate parseEventDate(String eventId) {
        if (eventId == null) {
            return null;
        }
        int sep = eventId.indexOf('_');
        if (sep <= 0) {
            return null;
        }
        try {
            return LocalDate.parse(eventId.substring(0, sep));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Snapshots the region's {@code claudeAverageRating} for this pick's slot
     * by mirroring the exact computation
     * {@code BriefingBestBetAdvisor.appendClaudeScores} performs when building
     * the rollup the advisor scored from. Returns null when:
     *
     * <ul>
     *   <li>The pick has no region (stay-home recommendation).</li>
     *   <li>The pick has no parseable event date.</li>
     *   <li>The pick's event type is not SUNRISE or SUNSET (notably aurora
     *       picks, which don't have a region-level per-event Claude score).</li>
     *   <li>No cached scores exist for the (region, date, event_type) slot.</li>
     * </ul>
     */
    Double lookupAverageRating(BestBet pick, LocalDate eventDate) {
        if (pick.region() == null || eventDate == null || pick.eventType() == null) {
            return null;
        }
        TargetType targetType = parseTargetType(pick.eventType());
        if (targetType == null) {
            return null;
        }
        Map<String, BriefingEvaluationResult> cached =
                briefingEvaluationService.getCachedScores(pick.region(), eventDate, targetType);
        if (cached.isEmpty()) {
            return null;
        }
        List<BriefingRatingStats.Entry> entries = cached.values().stream()
                .map(r -> new BriefingRatingStats.Entry(r.locationName(), r.rating()))
                .toList();
        BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                entries, pick.region(), eventDate, targetType);
        return stats.isEmpty() ? null : stats.averageRating();
    }

    /**
     * Parses the lowercase event-type string the advisor emits (e.g.
     * {@code "sunrise"}, {@code "sunset"}, {@code "aurora"}) back to a
     * {@link TargetType}. Returns null for aurora and any unknown value —
     * those have no region-level cached rating to snapshot.
     */
    static TargetType parseTargetType(String eventType) {
        if (eventType == null) {
            return null;
        }
        try {
            return TargetType.valueOf(eventType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Formats the differsBy list to a compact CSV (e.g. {@code "DATE,EVENT"})
     * for storage. Returns null for null or empty input so the column stays
     * NULL rather than an empty string.
     */
    static String formatDiffersBy(List<DiffersBy> differsBy) {
        if (differsBy == null || differsBy.isEmpty()) {
            return null;
        }
        String joined = differsBy.stream()
                .map(DiffersBy::name)
                .collect(Collectors.joining(","));
        if (joined.length() > DIFFERS_BY_MAX_LENGTH) {
            return joined.substring(0, DIFFERS_BY_MAX_LENGTH);
        }
        return joined;
    }
}
