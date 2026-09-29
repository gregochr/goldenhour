package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import com.gregochr.goldenhour.service.evaluation.BestBetRanker;
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
 *       the ceiling the API falls through to the honest empty state instead;</li>
 *   <li><b>a pick naming a region the CURRENT briefing no longer trusts is excluded</b> (round 10,
 *       P1-B) — {@link #isNowIneligible} re-checks each stored pick's region against the verdict-
 *       minimum-sample rule as it stands NOW, not as it stood when the pick was persisted. A stored
 *       pick's own region was eligible when it was crowned (the live path already enforces that);
 *       what can change between then and a later FAILED cycle serving this fallback is the region
 *       ITSELF — a re-enrichment can move a region from sufficient to insufficient (a location
 *       dropped out, a force-evaluation exemption expired) without ever repersisting the pick row,
 *       so resurrecting it blind would show a bet the current data no longer backs.</li>
 * </ul>
 *
 * <p>Pick rows exist only for {@code SUCCESS_WITH_PICKS} runs (the orchestrator's persist gate),
 * so a row's presence already implies it came from a successful run.
 *
 * <p>⚠️ <b>Dropping a stored pick follows the same rule every other removal site in this feature
 * uses — {@link BestBetRanker#afterRemoval} (round 11).</b> The round-10 cut above filtered
 * ineligible rows with a bare {@code continue}, so a run whose rank 1 went ineligible but whose
 * rank 2 stayed eligible would serve rank 2 ALONE — a stored "Also Good", written and persisted as
 * the runner-up to a headline that has just been dropped, rendered as the block's only pick. A
 * Codex review of the round-10 commit caught it: rank 2's {@code headline}/{@code detail} were
 * composed to read as a distinct alternative to rank 1 (see {@code BestBetPromptText}'s ALSO GOOD
 * SELECTION RULE), never rewritten on promotion (nothing here calls Claude again), so it would have
 * announced itself as "a second strong window" or "a separate opportunity" while being the only
 * window on screen. {@link #findFreshFallback} now builds the run's FULL original pick list before
 * filtering, and hands both the original and the filtered list to {@link
 * BestBetRanker#afterRemoval}: losing rank 1 withdraws the whole stale set (the fallback answers
 * with the same "no usable fallback" empty result it already uses when nothing qualifies at all);
 * losing only rank 2 keeps rank 1, unrenumbered and untouched.
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
     * @param currentDays the CURRENT briefing's days — already re-enriched with today's
     *                    {@code sampleSufficient}/{@code forcedSample} — used to re-validate each
     *                    stored pick's region against the verdict-minimum-sample rule as it stands
     *                    now (round 10, P1-B); see the class javadoc
     * @return the fallback picks (rank-ordered), or empty if no fresh-enough, still-eligible prior
     *         pick exists
     */
    public List<BestBet> findFreshFallback(List<BriefingDay> currentDays) {
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
        List<PipelineRunPickEntity> runRows = new ArrayList<>();
        for (PipelineRunPickEntity row : candidates) {
            if (!runId.equals(row.getPipelineRunId())) {
                break;
            }
            runRows.add(row);
        }
        // The FULL original set, before any filtering, so afterRemoval can tell whether rank 1
        // specifically was the one dropped — a bare filter-and-continue (round 10's shape) cannot
        // ask that question, which is how a lone, orphaned rank 2 slipped through.
        List<BestBet> original = runRows.stream().map(row -> toBestBet(row, today)).toList();
        List<BestBet> kept = new ArrayList<>();
        for (PipelineRunPickEntity row : runRows) {
            if (isNowIneligible(row, currentDays)) {
                LOG.info("[BEST-BET FALLBACK] Dropping stale pick region='{}' event='{}' — no "
                        + "longer verdict-eligible in the current briefing", row.getRegion(),
                        row.getEventId());
            } else {
                kept.add(toBestBet(row, today));
            }
        }
        List<BestBet> picks = BestBetRanker.afterRemoval(original, kept);
        if (picks.isEmpty()) {
            if (kept.isEmpty()) {
                LOG.info("[BEST-BET FALLBACK] Every pick from run {} is now verdict-ineligible — "
                        + "serving honest empty state instead of a stale resurrection", runId);
            } else {
                LOG.info("[BEST-BET FALLBACK] Run {}'s headline pick is now verdict-ineligible — "
                        + "withdrawing the whole stale set rather than serving its runner-up's "
                        + "prose, written relative to that headline, as the block's only pick",
                        runId);
            }
            return List.of();
        }
        LOG.info("[BEST-BET FALLBACK] Serving {} stale pick(s) from run {} (recorded {})",
                picks.size(), runId, candidates.get(0).getRecordedAt());
        return picks;
    }

    /**
     * Whether a stored pick's region has since become verdict-ineligible in the CURRENT briefing
     * — the {@link BriefingRegion#verdictEligible()} test the live advisor path already applies at
     * crowning time (round 10, P1-B; see {@code BestBetRanker#dropIneligiblePicks}).
     *
     * <p>A stay-home pick ({@code region == null}) or an aurora pick (no {@link TargetType} the
     * briefing's per-event-type structure recognises) carries no region-level sky sample to be
     * insufficient, so both are always eligible here — the same exemption {@code
     * BestBetRanker#isColourExempt} grants on the live path. A region/date/event combination that
     * no longer appears anywhere in {@code currentDays} (rolled off the render window, or the day's
     * shape changed since the pick was recorded) is treated as eligible too: that is a genuinely
     * unknown state, not a stated ineligibility, and the existing freshness bound (event-not-passed,
     * {@code fallback-max-age-hours}) is what retires a pick whose data has moved on, not this check.
     *
     * @param row         the stored pick row being considered for the fallback
     * @param currentDays the current briefing's days to check the region against
     * @return true only when the region is FOUND in the current briefing and is NOT verdict-eligible
     */
    private boolean isNowIneligible(PipelineRunPickEntity row, List<BriefingDay> currentDays) {
        String region = row.getRegion();
        LocalDate eventDate = row.getEventDate();
        TargetType targetType = PipelineRunPickService.parseTargetType(row.getEventType());
        if (region == null || eventDate == null || targetType == null || currentDays == null) {
            return false;
        }
        for (BriefingDay day : currentDays) {
            if (!eventDate.equals(day.date())) {
                continue;
            }
            for (BriefingEventSummary summary : day.eventSummaries()) {
                if (summary.targetType() != targetType) {
                    continue;
                }
                for (BriefingRegion candidate : summary.regions()) {
                    if (region.equals(candidate.regionName())) {
                        return !candidate.verdictEligible();
                    }
                }
            }
        }
        return false;
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
