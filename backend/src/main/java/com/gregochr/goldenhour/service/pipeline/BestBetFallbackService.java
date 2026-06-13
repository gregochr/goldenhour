package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serves the fail-safe best-bet fallback: when the current cycle's advisor FAILED, the API
 * substitutes the most recent <em>successful</em> run's picks, labelled stale, rather than
 * showing a misleading empty state on what may have been a strong day.
 *
 * <p>The fallback is freshness-bounded so it can never resurrect a useless pick:
 * <ul>
 *   <li>an event that has already passed is excluded — at day granularity, since the pick row
 *       persists {@code event_date} but not the event's time of day;</li>
 *   <li>a pick older than {@code photocast.best-bet.fallback-max-age-hours} is excluded — beyond
 *       the ceiling the API falls through to the honest empty state instead.</li>
 * </ul>
 *
 * <p>Pick rows exist only for {@code SUCCESS_WITH_PICKS} runs (the orchestrator's persist gate),
 * so a row's presence already implies it came from a successful run.
 */
@Service
public class BestBetFallbackService {

    private static final Logger LOG = LoggerFactory.getLogger(BestBetFallbackService.class);

    /** Display zone for the "has the event passed?" / day-name computations. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final PipelineRunPickRepository pickRepository;
    private final Clock clock;
    private final int maxAgeHours;

    /**
     * Constructs the fallback service.
     *
     * @param pickRepository repository of persisted per-run picks
     * @param clock          injectable clock (UTC in production) for deterministic tests
     * @param maxAgeHours    age ceiling in hours — a pick recorded longer ago than this is not
     *                       surfaced ({@code photocast.best-bet.fallback-max-age-hours}, default 30,
     *                       ≈ one nightly-plus-intraday cycle of headroom)
     */
    public BestBetFallbackService(PipelineRunPickRepository pickRepository, Clock clock,
            @Value("${photocast.best-bet.fallback-max-age-hours:30}") int maxAgeHours) {
        this.pickRepository = pickRepository;
        this.clock = clock;
        this.maxAgeHours = maxAgeHours;
    }

    /**
     * Returns the most recent successful run's picks if one is fresh enough to surface as a
     * stale fallback, or an empty list if none qualifies (caller then shows the honest empty
     * state).
     *
     * @return the fallback picks (rank-ordered), or empty if no fresh-enough prior pick exists
     */
    public List<BestBet> findFreshFallback() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, LONDON);
        Instant minRecordedAt = now.minus(Duration.ofHours(maxAgeHours));

        List<PipelineRunPickEntity> candidates =
                pickRepository.findFreshFallbackCandidates(today, minRecordedAt);
        if (candidates.isEmpty()) {
            LOG.info("[BEST-BET FALLBACK] No fresh-enough prior pick (within {}h, event not "
                    + "passed) — serving honest empty state", maxAgeHours);
            return List.of();
        }

        // Candidates are newest-recorded first; all rows of one run share recorded_at, so the
        // most recent run's picks sit contiguously at the front. Take exactly that run's set.
        Long runId = candidates.get(0).getPipelineRunId();
        List<BestBet> picks = new ArrayList<>();
        for (PipelineRunPickEntity row : candidates) {
            if (!runId.equals(row.getPipelineRunId())) {
                break;
            }
            picks.add(toBestBet(row, today));
        }
        LOG.info("[BEST-BET FALLBACK] Serving {} stale pick(s) from run {} (recorded {})",
                picks.size(), runId, candidates.get(0).getRecordedAt());
        return List.copyOf(picks);
    }

    /**
     * Maps a persisted pick row back to a {@link BestBet} for display. The event time of day is
     * not persisted, so {@code eventTime} is null (the banner simply omits it); {@code dayName}
     * is recomputed from the event date relative to today.
     */
    private BestBet toBestBet(PipelineRunPickEntity row, LocalDate today) {
        return new BestBet(
                row.getPickRank(),
                row.getHeadline(),
                row.getDetail(),
                row.getEventId(),
                row.getRegion(),
                Confidence.fromString(row.getConfidence()),
                null,
                dayName(row.getEventDate(), today),
                row.getEventType(),
                null,
                row.getRelationship(),
                parseDiffersBy(row.getDiffersBy()));
    }

    /**
     * Renders the display day name for an event date relative to today (Today / Tomorrow / the
     * weekday name). Null when the row has no event date (e.g. a stay-home pick).
     */
    private static String dayName(LocalDate eventDate, LocalDate today) {
        if (eventDate == null) {
            return null;
        }
        if (eventDate.equals(today)) {
            return "Today";
        }
        if (eventDate.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return eventDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /**
     * Parses the stored {@code differs_by} CSV (e.g. {@code "DATE,EVENT"}) back to a list,
     * silently dropping any unrecognised token.
     */
    private static List<DiffersBy> parseDiffersBy(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<DiffersBy> result = new ArrayList<>();
        for (String token : csv.split(",")) {
            DiffersBy dim = DiffersBy.fromString(token.trim());
            if (dim != null) {
                result.add(dim);
            }
        }
        return result;
    }

    /**
     * Exposes the configured age ceiling (hours) for logging/tests.
     *
     * @return the fallback age ceiling in hours
     */
    int maxAgeHours() {
        return maxAgeHours;
    }
}
