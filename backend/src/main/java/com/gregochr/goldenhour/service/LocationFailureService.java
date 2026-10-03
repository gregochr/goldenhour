package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.FailureKind;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.service.notification.AdminAlertService.DisabledLocation;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Counts consecutive failed scheduled cycles per place and auto-disables a place that keeps failing.
 *
 * <p>This is the rule CLAUDE.md always claimed ("auto-disable after 3 failures") and the code never
 * had. It is deliberately conservative, because a disabled place vanishes for every user from the
 * Plan, the map, the briefing and the hot topics, and because on 2026-09-29 all 510 batch candidates
 * failed at once (every Anthropic submission answered 500): a naive counter would have disabled the
 * whole roster in one night.
 *
 * <p><b>Which runs.</b> Pipeline cycles (nightly and intraday), settled once each by
 * {@code PipelineOrchestrator} after the cycle's batch results and its retry have landed. The
 * Operations-tab forecast buttons and the map's Run Forecast are hand-started runs on the
 * synchronous engine: they never reach this class, and their batches are never tagged with a
 * pipeline run, so {@link CycleLocationOutcomeResolver} resolves them to nothing. <b>The
 * scheduler's "Run now" is different and does count:</b> it runs the identical pipeline cycle
 * through {@code DynamicSchedulerService.triggerNow} and nothing records the trigger, so it counts
 * like a scheduled cycle, and three presses inside a day could disable a place. The owner accepted
 * that.
 *
 * <p><b>What counts.</b> A place that did not get through (no usable Claude result and no triage
 * this cycle) and had a collection error or a failed Claude result counts as failed in the cycle
 * only if the places LIKE it mostly got through. Like means the same kind of evidence, never the
 * whole roster:
 * <ul>
 *   <li>For a failed Claude result in lane L (sky, woodland or bluebell): the other places that
 *       received a Claude result, good or bad, in that same lane this cycle; "got through" means a
 *       success in that lane. Triage-only places, which never touched Claude, and places that
 *       succeeded only in other lanes are not evidence either way. So a fault confined to one lane
 *       (a woodland parser regression failing every {@code wd-} result while 200 sky places score)
 *       is judged against the woodland places only, finds none getting through, and counts
 *       nobody, where a roster-wide ratio would have disabled up to five woodland-only sites for a
 *       code bug.</li>
 *   <li>For a collection error: the other places whose collection ran this cycle (anything other
 *       than "nothing recorded"); "got through" means collection did not error for them.</li>
 * </ul>
 * "At least half" is inclusive: with ten like places, five through and five failed counts; four
 * through and six failed does not. No like place at all offers no evidence that the pipeline was
 * working, so it counts nobody. A place that failed in two lanes counts once, and either qualifying
 * lane suffices.
 *
 * <p><b>Counting.</b> One count per place per cycle, never per slot. A place that got through resets
 * its counter to zero, even in a cycle that is otherwise treated as systemic. A place with nothing
 * recorded keeps its counter untouched.
 *
 * <p><b>Disabling.</b> A place whose counter reaches {@link #AUTO_DISABLE_THRESHOLD} is disabled and
 * the admin is told. If more than {@link #MAX_DISABLED_PER_CYCLE} places qualify in one cycle, none is
 * disabled, an ERROR is logged and the admin is told that something systemic is wrong; the counters
 * still advance, so the alert repeats each cycle until the cause is fixed. The alert is sent only
 * after the settle transaction has committed, so an admin is never told about a disable that rolled
 * back.
 *
 * <p><b>Writes are column-scoped</b> ({@code LocationRepository#recordFailure},
 * {@code #autoDisable}, {@code #resetFailureCounts}), never a whole-entity save: a save would write
 * every column, including the tide, type and solar-event sets, from whatever copy this class held and
 * could undo an admin's concurrent edit of the same place.
 *
 * <p><b>Idempotence.</b> {@link #settleCycle} remembers, in memory, the last
 * {@value #REMEMBERED_CYCLES} pipeline run ids it has settled and refuses a repeat, whatever order
 * cycles settle in (an admin's Run now while another cycle waits can settle a newer cycle before an
 * older one, and the older must still count). In memory is enough because the orchestrator settles
 * inside the BRIEFING phase, after that phase row has been started: a process restart resumes a run
 * that is already in BRIEFING without re-entering the settle, so a restart can lose a settle (a
 * missed count, the safe direction) but cannot repeat one.
 */
@Service
public class LocationFailureService {

    private static final Logger LOG = LoggerFactory.getLogger(LocationFailureService.class);

    /**
     * Consecutive counted failed cycles that disable a place. Three, so two bad nights (a transient
     * Open-Meteo or Anthropic problem that cleared) never remove a place, while a place broken for
     * a whole day's two cycles plus the next still goes within two days.
     */
    public static final int AUTO_DISABLE_THRESHOLD = 3;

    /**
     * Most places one cycle may disable. When more qualify together, something systemic is far more
     * likely than that many places breaking at once, and disabling them would empty the roster for
     * every user, so none is disabled and the admin is told instead. Five is a handful: more than
     * any plausible coincidence of independently broken places, far fewer than a roster of ~250.
     */
    public static final int MAX_DISABLED_PER_CYCLE = 5;

    /**
     * How many settled pipeline run ids are remembered. Cycles run twice a day, so 200 is months of
     * history and a few kilobytes; the point is only to bound the set.
     */
    static final int REMEMBERED_CYCLES = 200;

    private final CycleLocationOutcomeResolver outcomeResolver;
    private final LocationRepository locationRepository;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    /** Settled pipeline run ids, oldest first; guarded by {@code this} (see {@link #claim}). */
    private final Set<Long> settledCycles = new LinkedHashSet<>();

    /**
     * Constructs the service.
     *
     * @param outcomeResolver     derives each place's evidence for a cycle from recorded data
     * @param locationRepository  column-scoped counter and disable writes
     * @param adminAlertService   the admin email channel
     * @param clock               injected clock for the failure timestamp and the reason's date
     */
    public LocationFailureService(CycleLocationOutcomeResolver outcomeResolver,
            LocationRepository locationRepository, AdminAlertService adminAlertService,
            Clock clock) {
        this.outcomeResolver = outcomeResolver;
        this.locationRepository = locationRepository;
        this.adminAlertService = adminAlertService;
        this.clock = clock;
    }

    /**
     * Settles one pipeline cycle: resolves each place's evidence from the cycle's recorded data and
     * applies the failure-counting rule. Called once per cycle by {@code PipelineOrchestrator}. Any
     * admin alert is registered to go out only after this transaction commits.
     *
     * @param run the pipeline run being settled
     */
    @Transactional
    public void settleCycle(PipelineRunEntity run) {
        Long runId = run.getId();
        if (!claim(runId)) {
            LOG.info("Pipeline run {}: location failures already settled, not counting again",
                    runId);
            return;
        }
        applyEvidence(runId, run.getCycleType(), run.getTriggerTime(),
                outcomeResolver.resolve(runId));
    }

    /**
     * Forgets every settled cycle. Test hook, so a cached Spring context shared between test
     * classes is left clean.
     */
    synchronized void forgetSettledCycles() {
        settledCycles.clear();
    }

    private void applyEvidence(Long runId, CycleType cycleType, Instant triggerTime,
            Map<Long, CyclePlaceEvidence> evidence) {
        List<Long> gotThrough = evidence.entrySet().stream()
                .filter(e -> e.getValue().gotThrough()).map(Map.Entry::getKey).toList();
        List<Long> failed = evidence.entrySet().stream()
                .filter(e -> e.getValue().failed()).map(Map.Entry::getKey).toList();

        if (!gotThrough.isEmpty()) {
            int reset = locationRepository.resetFailureCounts(gotThrough);
            if (reset > 0) {
                LOG.info("Pipeline run {}: {} place(s) got through, consecutive failure counter "
                        + "reset to 0", runId, reset);
            }
        }
        if (failed.isEmpty()) {
            return;
        }

        LikeEvidence like = LikeEvidence.of(evidence.values());
        Map<Long, FailureKind> counting = new java.util.LinkedHashMap<>();
        for (Long id : failed) {
            FailureKind kind = like.countableKind(evidence.get(id));
            if (kind != null) {
                counting.put(id, kind);
            }
        }
        if (counting.size() < failed.size()) {
            LOG.warn("Pipeline run {}: {} of {} failed place(s) not counted, too few comparable "
                    + "places got through (same result lane for a Claude failure, same collection "
                    + "step for a collection error), treated as systemic",
                    runId, failed.size() - counting.size(), failed.size());
        }
        if (counting.isEmpty()) {
            return;
        }

        LocalDateTime failedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Long> countingIds = List.copyOf(counting.keySet());
        Map<Long, LocationEntity> byId = locationRepository.findAllById(countingIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, l -> l));
        List<Counted> counted = new ArrayList<>();
        for (Long id : countingIds) {
            LocationEntity location = byId.get(id);
            if (location == null || !location.isEnabled()) {
                continue;
            }
            int count = (location.getConsecutiveFailures() == null
                    ? 0 : location.getConsecutiveFailures()) + 1;
            locationRepository.recordFailure(id, count, failedAt);
            LOG.info("Pipeline run {}: location '{}' failed this cycle ({}), consecutive failures "
                            + "now {}", runId, LogSanitizer.sanitize(location.getName()),
                    counting.get(id), count);
            counted.add(new Counted(location, count, counting.get(id)));
        }

        List<Counted> qualifying = counted.stream()
                .filter(c -> c.count() >= AUTO_DISABLE_THRESHOLD)
                .sorted(Comparator.comparing(c -> c.location().getName()))
                .toList();
        if (qualifying.isEmpty()) {
            return;
        }
        if (qualifying.size() > MAX_DISABLED_PER_CYCLE) {
            reportCapExceeded(runId, cycleType, triggerTime, qualifying);
            return;
        }
        disable(runId, cycleType, triggerTime, qualifying, failedAt);
    }

    private void disable(Long runId, CycleType cycleType, Instant triggerTime,
            List<Counted> qualifying, LocalDateTime failedAt) {
        List<DisabledLocation> disabled = new ArrayList<>();
        for (Counted c : qualifying) {
            String reason = disabledReason(c.count(), failedAt, c.kind());
            if (locationRepository.autoDisable(c.location().getId(), c.count(), failedAt,
                    reason) > 0) {
                LOG.warn("Pipeline run {}: location '{}' AUTO-DISABLED: {}", runId,
                        LogSanitizer.sanitize(c.location().getName()), reason);
                disabled.add(new DisabledLocation(c.location().getName(), reason));
            }
        }
        if (disabled.isEmpty()) {
            return;
        }
        afterCommit(() -> {
            try {
                adminAlertService.sendLocationsAutoDisabledAlert(
                        runId, cycleType, triggerTime, disabled);
            } catch (RuntimeException e) {
                LOG.warn("Pipeline run {}: auto-disable alert dispatch raised an exception, logged "
                        + "and ignored (the places stay disabled): {}", runId,
                        LogSanitizer.sanitize(e.getMessage()));
            }
        });
    }

    private void reportCapExceeded(Long runId, CycleType cycleType, Instant triggerTime,
            List<Counted> qualifying) {
        List<String> names = qualifying.stream().map(c -> c.location().getName()).toList();
        LOG.error("Pipeline run {}: {} places reached {} consecutive failed cycles in one cycle, "
                + "more than the cap of {}, something systemic is wrong, NO place disabled: {}",
                runId, names.size(), AUTO_DISABLE_THRESHOLD, MAX_DISABLED_PER_CYCLE,
                LogSanitizer.sanitize(String.join(", ", names)));
        afterCommit(() -> {
            try {
                adminAlertService.sendLocationDisableCapAlert(
                        runId, cycleType, triggerTime, names, MAX_DISABLED_PER_CYCLE);
            } catch (RuntimeException e) {
                LOG.warn("Pipeline run {}: auto-disable cap alert dispatch raised an exception, "
                        + "logged and ignored: {}", runId, LogSanitizer.sanitize(e.getMessage()));
            }
        });
    }

    /**
     * Runs the action once the surrounding transaction has committed. Under a transaction the action
     * is registered as an {@code afterCommit} callback, so a rollback or a failed commit sends
     * nothing; with no transaction active (never in production, where {@link #settleCycle} is
     * transactional) it runs immediately.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Builds the fixed-shape reason stored on a disabled place, e.g. {@code "Auto-disabled after 3
     * consecutive failed scheduled runs (last 2026-10-02: data could not be collected)."}. Built
     * only from a count, a date and a {@link FailureKind} phrase, never from an exception message.
     */
    private static String disabledReason(int count, LocalDateTime failedAt, FailureKind kind) {
        return "Auto-disabled after " + count + " consecutive failed scheduled runs (last "
                + failedAt.toLocalDate() + ": " + kind.phrase() + ").";
    }

    /**
     * Claims a cycle for settling.
     *
     * @return {@code true} if this cycle has not been settled (among the remembered ones)
     */
    private synchronized boolean claim(Long pipelineRunId) {
        if (!settledCycles.add(pipelineRunId)) {
            return false;
        }
        if (settledCycles.size() > REMEMBERED_CYCLES) {
            Iterator<Long> oldest = settledCycles.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    private record Counted(LocationEntity location, int count, FailureKind kind) {
    }

    /**
     * Per-population tallies of one cycle's evidence, against which a failed place is compared
     * (see the class javadoc for the populations).
     */
    private static final class LikeEvidence {
        private final int collectionAttempted;
        private final int collectionOk;
        private final Map<Lane, int[]> lanes;

        private LikeEvidence(int collectionAttempted, int collectionOk, Map<Lane, int[]> lanes) {
            this.collectionAttempted = collectionAttempted;
            this.collectionOk = collectionOk;
            this.lanes = lanes;
        }

        static LikeEvidence of(Iterable<CyclePlaceEvidence> all) {
            int attempted = 0;
            int ok = 0;
            Map<Lane, int[]> lanes = new java.util.EnumMap<>(Lane.class);
            for (Lane lane : Lane.values()) {
                lanes.put(lane, new int[2]);
            }
            for (CyclePlaceEvidence e : all) {
                if (e.attempted()) {
                    attempted++;
                    if (!e.collectionFailed()) {
                        ok++;
                    }
                }
                for (Lane lane : Lane.values()) {
                    if (e.hasResultIn(lane)) {
                        lanes.get(lane)[0]++;
                    }
                    if (e.succeededIn(lane)) {
                        lanes.get(lane)[1]++;
                    }
                }
            }
            return new LikeEvidence(attempted, ok, lanes);
        }

        /**
         * The kind of failure that may be counted for a failed place, or {@code null} if no kind of
         * failure it has is backed by enough like places that got through. A collection error is
         * preferred when it qualifies.
         */
        FailureKind countableKind(CyclePlaceEvidence place) {
            if (place.collectionFailed()
                    && mostlyThrough(collectionAttempted - 1, collectionOk)) {
                return FailureKind.COLLECTION;
            }
            for (Lane lane : place.failedLanes()) {
                int[] tally = lanes.get(lane);
                if (mostlyThrough(tally[0] - 1, tally[1])) {
                    return FailureKind.EVALUATION;
                }
            }
            return null;
        }

        /**
         * At least half (inclusive) of the other like places got through, and there is at least one.
         * The failed place is never itself in the through count, so the caller has already taken it
         * out of the attempted count.
         */
        private static boolean mostlyThrough(int othersAttempted, int throughCount) {
            return othersAttempted > 0 && throughCount * 2 >= othersAttempted;
        }
    }
}
