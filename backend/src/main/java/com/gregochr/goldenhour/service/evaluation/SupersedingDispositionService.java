package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Answers "has a later pipeline run already decided AGAINST this slot" — round 14 of the
 * verdict-minimum-sample review series, replacing round 13's design in full after a Codex review
 * tested it against production and found both its discriminator and its disposition allow-list
 * wrong.
 *
 * <h2>The rule</h2>
 *
 * <p>A disposition supersedes a result when the disposition's {@code created_at} is at or after
 * the trigger time of the FIRST {@code pipeline_run} triggered after the result's own
 * {@code submittedAt}. No join to {@code forecast_batch} or {@code job_run} is involved at all —
 * {@link PipelineRunRepository#findTriggerTimesAfter} answers "is there a later cycle, and when did
 * it start" purely from the {@code pipeline_run} table, which exists for every triggered cycle
 * regardless of whether that cycle ever produced a batch, and
 * {@link ForecastRunDispositionRepository#findSupersedingDispositions} compares the resolved boundary
 * against the disposition's own native {@code created_at} — no cross-table timing inference.
 *
 * <p>⚠️ <b>Why round 13's three-entity join was wrong, confirmed against production.</b> A Codex
 * review read pipeline run 249 (INTRADAY, 2026-09-29, trigger 14:00:00 UTC): all three Anthropic
 * batch submissions failed with HTTP 500, so {@code ScheduledBatchEvaluationService
 * #persistCycleDispositions} anchored the cycle's 589 dispositions to a disposition-only "anchor
 * run" job_run with NO {@code forecast_batch} row at all — {@code forecast_batch} holds zero rows
 * for that job_run_id, confirmed in production; over five days, 589 of 13,120 disposition rows join
 * to no batch at all. Round 13's join could not see these dispositions in EITHER direction: it
 * could neither supersede a stale result with them (which, for the 76 genuine {@code
 * SKIPPED_TRIAGED} rows in that batch, was itself a defect) nor — had it somehow reached them —
 * would it have been safe to treat the 510 {@code EVALUATED} rows as decisions against anything,
 * since {@code EVALUATED} records only that a candidate was included for submission, never that a
 * result was produced. This class fixes both: the trigger-time rule needs no {@code forecast_batch}
 * row to exist at all, and the disposition query only ever asks for the two categories below.
 *
 * <h2>The disposition allow-list — explicit, not "everything except"</h2>
 *
 * <p>Exactly two of the twelve {@link com.gregochr.goldenhour.entity.DispositionCategory} values
 * supersede a result. Enumerated in full, because a category added later must be individually
 * argued onto this list, never fall onto it by omission:
 * <ul>
 *   <li>{@code SKIPPED_STABILITY} — <b>IN.</b> The nightly Gate 4 skip: a positive decision that the
 *       pipeline declined to re-score this slot at all. The original #940 retraction category.</li>
 *   <li>{@code SKIPPED_TRIAGED} — <b>IN.</b> The weather triage stand-down: a positive decision that
 *       the slot was looked at and rejected on cloud/rain/visibility grounds.</li>
 *   <li>{@code EVALUATED} — <b>OUT.</b> Records inclusion for submission, never a result. The
 *       production case above (510 rows, zero results) is the concrete proof: superseding on this
 *       would have left the slot unrated where the stale-but-real prior rating should have stood.</li>
 *   <li>{@code FORCE_EVALUATED} — <b>OUT.</b> The same reasoning as {@code EVALUATED} — a force-eval
 *       rescue's submission can fail exactly like an ordinary one.</li>
 *   <li>{@code SKIPPED_HARD_CONSTRAINT} — <b>OUT.</b> Not on the two-item allow-list this round
 *       builds; it IS a positive stand-down (an unrevisable physical constraint), which makes it an
 *       arguable future candidate, but adding it was not asked for and is left to an owner decision
 *       rather than taken silently here.</li>
 *   <li>{@code SKIPPED_NO_PROMPT} — <b>OUT.</b> Structural routing (no prompt exists for this
 *       location type yet), not a decision that an existing rating is now wrong.</li>
 *   <li>{@code SKIPPED_CACHED} — <b>OUT.</b> A region-level cache reuse, not a decision about any
 *       one slot — the same reasoning {@link ForecastRunDispositionRepository
 *       #findLatestNonCachedDispositions} already documents.</li>
 *   <li>{@code SKIPPED_PAST_DATE} — <b>OUT.</b> The date has simply passed; says nothing about the
 *       rating.</li>
 *   <li>{@code SKIPPED_TRAVEL_DAY} — <b>OUT.</b> Operator unavailability, not a weather or rating
 *       decision.</li>
 *   <li>{@code SKIPPED_UNKNOWN_LOCATION} — <b>OUT.</b> A resolution failure; three of the 589 rows
 *       in the production case were this category, alongside 510 {@code EVALUATED}, and neither is
 *       evidence a rating is now wrong.</li>
 *   <li>{@code SKIPPED_ERROR} — <b>OUT.</b> An exception during data assembly, not a decision.</li>
 *   <li>{@code SKIPPED_NO_REFRESH_NEEDED} — <b>OUT.</b> "A later look is already guaranteed" — the
 *       opposite of a decision against the slot.</li>
 * </ul>
 *
 * <h2>Same-cycle safety</h2>
 *
 * <p>A cycle's own disposition for a slot it just decided is written a short time AFTER that
 * cycle's own trigger — always BEFORE the next cycle's trigger, since cycles are hours apart and
 * disposition persistence is near-instant. Because {@link #isSuperseded} and
 * {@link #supersededLocations} resolve "the first trigger strictly after this result's own
 * submittedAt", a same-cycle disposition's {@code created_at} always falls strictly before that
 * boundary and is correctly read as simultaneous with its own result, never later.
 *
 * <h2>Query cost</h2>
 *
 * <p>{@link #supersededLocations} (the merge-level bulk check {@code BriefingEvaluationService}
 * uses): one query in the common case ({@link PipelineRunRepository#findTriggerTimesAfter} returns
 * empty — no cycle has started since the earliest submission among the results being checked, which
 * is the ordinary, non-delayed case since a fresh result's {@code submittedAt} is recent), two when
 * a later cycle exists at all (the same call, plus one bulk
 * {@link ForecastRunDispositionRepository#findSupersedingDispositions} covering every location in
 * the call). Results in one merge call may carry different {@code submittedAt} values; the trigger
 * list is loaded once from the EARLIEST of them and each result's own "next trigger" is resolved
 * from that one in-memory list, never a second trigger-time query per result.
 *
 * <p>{@link #isSuperseded} (the per-response fallback gate in front of the {@code forecast_score}
 * dual write — see {@code ForecastResultHandler}'s own class javadoc for why the write cannot be
 * hoisted ahead of the streaming Batch API reader): one query in the common case (same trigger-time
 * check, now for a single result), two when a later cycle exists FOR THAT ONE SUBMISSION — a
 * per-response query in the rare case, accepted as the explained cost of the fallback rather than a
 * true batch-wide hoist.
 */
@Service
public class SupersedingDispositionService {

    private final PipelineRunRepository pipelineRunRepository;
    private final ForecastRunDispositionRepository forecastRunDispositionRepository;

    /**
     * One incoming result's location and submission instant, keyed for a bulk
     * {@link #supersededLocations} check.
     *
     * @param locationName the location name
     * @param submittedAt  the result's own submission instant, or {@code null} if unknown
     */
    public record LocatedSubmission(String locationName, Instant submittedAt) {
    }

    /**
     * Constructs the service.
     *
     * @param pipelineRunRepository            resolves "is there a later cycle, and when"
     * @param forecastRunDispositionRepository resolves "did that later cycle decide against this
     *                                         slot" — the two-value allow-list
     */
    public SupersedingDispositionService(PipelineRunRepository pipelineRunRepository,
            ForecastRunDispositionRepository forecastRunDispositionRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.forecastRunDispositionRepository = forecastRunDispositionRepository;
    }

    /**
     * Bulk check for a merge call covering possibly many locations, all for the same date and
     * event type. See the class javadoc for the full rule and the query-cost breakdown.
     *
     * @param submissions the incoming results' own (locationName, submittedAt) pairs
     * @param date        the slots' evaluation date
     * @param eventType   the slots' event type
     * @return the subset of {@code submissions}' location names that are superseded
     */
    public Set<String> supersededLocations(List<LocatedSubmission> submissions, LocalDate date,
            TargetType eventType) {
        List<LocatedSubmission> known = submissions.stream()
                .filter(s -> s.submittedAt() != null)
                .toList();
        if (known.isEmpty()) {
            return Set.of();
        }
        Instant earliest = known.stream()
                .map(LocatedSubmission::submittedAt)
                .min(Instant::compareTo)
                .orElseThrow();
        List<Instant> laterTriggers = pipelineRunRepository.findTriggerTimesAfter(earliest);
        if (laterTriggers.isEmpty()) {
            // Phase 1 fast path: no cycle has started since the earliest of these submissions, so
            // none of them can possibly be superseded. The common case — one cheap query.
            return Set.of();
        }
        Map<String, Instant> nextTriggerByLocation = new HashMap<>();
        Instant minNextTriggerNeeded = null;
        for (LocatedSubmission s : known) {
            Instant next = firstAfter(laterTriggers, s.submittedAt());
            if (next != null) {
                nextTriggerByLocation.put(s.locationName(), next);
                if (minNextTriggerNeeded == null || next.isBefore(minNextTriggerNeeded)) {
                    minNextTriggerNeeded = next;
                }
            }
        }
        if (nextTriggerByLocation.isEmpty()) {
            return Set.of();
        }
        List<String> locationNames = new ArrayList<>(nextTriggerByLocation.keySet());
        List<Object[]> rows = forecastRunDispositionRepository.findSupersedingDispositions(
                date, eventType.name(), locationNames, minNextTriggerNeeded);
        Map<String, Instant> latestDispositionByLocation = new HashMap<>();
        for (Object[] row : rows) {
            String locationName = (String) row[0];
            Instant createdAt = (Instant) row[1];
            latestDispositionByLocation.merge(locationName, createdAt,
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        Set<String> superseded = new HashSet<>();
        for (Map.Entry<String, Instant> entry : nextTriggerByLocation.entrySet()) {
            Instant dispositionAt = latestDispositionByLocation.get(entry.getKey());
            if (dispositionAt != null && !dispositionAt.isBefore(entry.getValue())) {
                superseded.add(entry.getKey());
            }
        }
        return superseded;
    }

    /**
     * Single-location check — the per-response fallback gate in front of the {@code forecast_score}
     * dual write. See the class javadoc's query-cost section for why this is a per-response check
     * rather than a true batch-wide hoist, and why that is an accepted, explained trade-off.
     *
     * @param locationName the slot's location name
     * @param date         the slot's evaluation date
     * @param eventType    the slot's event type
     * @param submittedAt  the result's own submission instant, or {@code null} if unknown
     * @return {@code true} if this exact slot is superseded
     */
    public boolean isSuperseded(String locationName, LocalDate date, TargetType eventType,
            Instant submittedAt) {
        if (submittedAt == null) {
            return false;
        }
        List<Instant> laterTriggers = pipelineRunRepository.findTriggerTimesAfter(submittedAt);
        if (laterTriggers.isEmpty()) {
            return false;
        }
        Instant nextTrigger = firstAfter(laterTriggers, submittedAt);
        if (nextTrigger == null) {
            return false;
        }
        return forecastRunDispositionRepository.existsSupersedingDisposition(
                Objects.requireNonNull(locationName, "locationName"), date, eventType.name(),
                nextTrigger);
    }

    /**
     * The first entry in an ascending list that is strictly after {@code threshold}, or
     * {@code null} if none. {@code laterTriggers} is already filtered to entries after the EARLIEST
     * submission among a batch of results, so a specific result's own threshold may be later than
     * some entries in the list — this walks past those to find the correct boundary for it.
     */
    private static Instant firstAfter(List<Instant> ascendingLaterTriggers, Instant threshold) {
        for (Instant candidate : ascendingLaterTriggers) {
            if (candidate.isAfter(threshold)) {
                return candidate;
            }
        }
        return null;
    }
}
