package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.FailureKind;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.service.notification.AdminAlertService.DisabledLocation;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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
 * after the settle transaction has committed (and after the settle lock is released), so an admin is
 * never told about a disable that rolled back.
 *
 * <p><b>Writes are column-scoped</b> ({@code LocationRepository#recordFailure},
 * {@code #autoDisable}, {@code #resetFailureCounts}), never a whole-entity save: a save would write
 * every column, including the tide, type and solar-event sets, from whatever copy this class held and
 * could undo an admin's concurrent edit of the same place. The failure count is incremented by the
 * database ({@code SET consecutive_failures = COALESCE(consecutive_failures, 0) + 1}), and the
 * threshold decision reads back the value that update produced rather than computing it from an
 * entity snapshot read earlier: the update, not the snapshot, is the authority on the count.
 *
 * <p><b>Serialised and ordered.</b> An admin can start another cycle (the scheduler's Run now) while
 * an earlier cycle's batch tail is still waiting, so two settles can be due at once, or arrive out
 * of trigger order. This is a single-instance application, so an in-JVM rule is enough, and it is
 * deliberately a {@link ReentrantLock} rather than {@code synchronized}: the settle does blocking
 * database work and the app runs on virtual threads, where a monitor would pin the carrier thread.
 * <ul>
 *   <li><b>Serialised.</b> The whole settle, resolve, apply and the transaction's commit, runs under
 *       one lock, so two settles never interleave and one can never read a counter the other has
 *       written but not yet committed. The commit is inside the lock on purpose: a
 *       {@link TransactionTemplate} wraps the work, rather than {@code @Transactional} on the method,
 *       which would release the lock before the commit.</li>
 *   <li><b>Two modes, chosen by trigger order.</b> {@link SettleMode#FULL} counts failures and
 *       applies resets; {@link SettleMode#RESETS_ONLY} applies only the resets from successes and
 *       triage and counts nothing. A cycle is settled FULL only when it is settled at its own tail
 *       AND its trigger time is newer than the newest trigger time among the cycles already settled
 *       ({@code PipelineRunRepository#findNewestSettledTriggerTime}). Any cycle settled later than
 *       that (an older cycle after a newer one, a replay after a failed settle, one recovered at
 *       startup or by the sweep) is settled RESETS_ONLY and then claimed, the mode and the reason
 *       logged at INFO. Counters are only meaningful in trigger order: applying an older cycle's
 *       failure after a newer cycle's success had reset the counter would start a streak that the
 *       intervening success should have broken, and could later disable a place that has since been
 *       working; but its successes are still true and still worth applying, since a place that got
 *       through in a cycle that never reached its own settle must not keep a stale count.</li>
 *   <li><b>Exactly once, and durable.</b> The cycle is claimed by a conditional update
 *       ({@code PipelineRunRepository#claimFailureSettle}: {@code SET failures_settled_at = :now
 *       WHERE id = :id AND failures_settled_at IS NULL}; one row updated means claimed) in the SAME
 *       transaction as the counter writes, before the evidence is resolved. A repeat claim updates
 *       nothing and is refused, so a cycle is never counted twice; and because the claim commits or
 *       rolls back with the counts, a process stopped mid-settle leaves the cycle unclaimed. The
 *       orchestrator settles every resumed run on its way to the briefing, so that cycle is settled
 *       on resume, and a run already settled is not settled again. Nothing about this is held in
 *       memory, so a restart loses nothing.</li>
 *   <li><b>Never while a batch is still polling.</b> A run is not claimed, by the sweep or by a
 *       tail settle, while any of its FORECAST batches is outside
 *       {@link ForecastBatchEntity.BatchStatus#TERMINAL} (still SUBMITTED, so the poller can yet
 *       write results). A run restarted during batch submission is marked FAILED yet its persisted
 *       batches are still polled; claiming it early would settle it with no result evidence and
 *       results that land later could never reset a counter. Such a run is deferred (INFO, naming
 *       the batch) and left unclaimed for a later sweep once its batches are terminal. AURORA
 *       batches are ignored: {@code CycleLocationOutcomeResolver} reads only FORECAST batches.</li>
 *   <li><b>Retried, durably.</b> A settle that fails leaves its cycle unclaimed (the claim rolls
 *       back with the counts), and the orchestrator completes the run regardless. {@link
 *       #sweepUnsettledRuns()} finds every such run (unclaimed, triggered within
 *       {@link #SWEEP_WINDOW}, not still RUNNING) at application startup and at the start of every
 *       tail settle, and settles each in trigger order, RESETS_ONLY.</li>
 * </ul>
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
     * How far back the sweep looks for pipeline runs whose settle was never claimed. Seven days: far
     * longer than any outage a retry should cover (a cycle runs twice a day, and the three-failure
     * threshold means anything older could not change a disable decision that matters), short enough
     * that the first sweep after the claim column was introduced walks a bounded set of runs.
     */
    public static final Duration SWEEP_WINDOW = Duration.ofDays(7);

    private final CycleLocationOutcomeResolver outcomeResolver;
    private final LocationRepository locationRepository;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    private final PipelineRunRepository pipelineRunRepository;

    private final ForecastBatchRepository forecastBatchRepository;

    /**
     * Held across a whole settle, commit included, so the read-then-write of the counters, the
     * newest-settled read and the claim never interleave with another settle in this JVM.
     */
    private final ReentrantLock settleLock = new ReentrantLock();

    /**
     * Constructs the service.
     *
     * @param outcomeResolver     derives each place's evidence for a cycle from recorded data
     * @param locationRepository  column-scoped counter and disable writes
     * @param adminAlertService   the admin email channel
     * @param clock               injected clock for the failure timestamp and the reason's date
     * @param transactionManager  runs each settle in one transaction held inside the settle lock
     * @param pipelineRunRepository the durable claim and ordering state on {@code pipeline_run}
     * @param forecastBatchRepository finds a cycle's forecast batches that are still being polled
     */
    public LocationFailureService(CycleLocationOutcomeResolver outcomeResolver,
            LocationRepository locationRepository, AdminAlertService adminAlertService,
            Clock clock, PlatformTransactionManager transactionManager,
            PipelineRunRepository pipelineRunRepository,
            ForecastBatchRepository forecastBatchRepository) {
        this.outcomeResolver = outcomeResolver;
        this.locationRepository = locationRepository;
        this.adminAlertService = adminAlertService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.pipelineRunRepository = pipelineRunRepository;
        this.forecastBatchRepository = forecastBatchRepository;
    }

    /**
     * Settles one pipeline cycle: claims it, resolves each place's evidence from the cycle's recorded
     * data and applies the failure-counting rule, in one transaction, under the settle lock (see the
     * class javadoc). Called by {@code PipelineOrchestrator} for every run that reaches the briefing,
     * including a run resumed after a restart. First sweeps earlier unclaimed runs
     * ({@link #sweepUnsettledRuns()}), then settles this cycle: {@link SettleMode#FULL} when its
     * trigger time is newer than the newest settled cycle, {@link SettleMode#RESETS_ONLY} otherwise.
     * A cycle with a forecast batch still being polled is never claimed (see the class javadoc).
     * A cycle already settled is refused and logged. A failure rolls the claim back (the cycle stays
     * unclaimed for the next sweep) and propagates. Any admin alert goes out only after the
     * transaction has committed and the lock has been released.
     *
     * @param run the pipeline run being settled
     */
    public void settleCycle(PipelineRunEntity run) {
        List<Runnable> alerts;
        settleLock.lock();
        try {
            sweepLocked(run.getId());
            alerts = transactionTemplate.execute(status -> {
                List<Runnable> pending = new ArrayList<>();
                settleClaimed(run, true, pending);
                return pending;
            });
        } finally {
            settleLock.unlock();
        }
        if (alerts != null) {
            alerts.forEach(Runnable::run);
        }
    }

    /**
     * The durable retry: settles every pipeline run whose settle was never claimed, in trigger
     * order, in {@link SettleMode#RESETS_ONLY} mode (see the class javadoc). Run once at application
     * startup (after the orchestrator has resumed running cycles) and at the start of every tail
     * settle, under the same lock, so a cycle whose settle failed, or that was never settled
     * (a process stop, a safety timeout, a failure before the briefing), is picked up by the next
     * sweep instead of staying unsettled for ever.
     *
     * <p>Considers runs triggered within {@link #SWEEP_WINDOW} only and never a run still RUNNING
     * (its own tail settles it). Each run settles in its own transaction: a run that fails again is
     * logged at ERROR and left unclaimed for the next sweep, and does not stop the others.
     *
     * <p>The first deploy of the migration that added the claim column leaves every recent run
     * unsettled, so the first sweep walks all of them; that is harmless: RESETS_ONLY only zeroes
     * the counter of a place that got through in that cycle, and every counter is zero at that point.
     *
     * @return how many runs this sweep settled
     */
    public int sweepUnsettledRuns() {
        settleLock.lock();
        try {
            return sweepLocked(null);
        } finally {
            settleLock.unlock();
        }
    }

    /**
     * Sweeps with the settle lock already held.
     *
     * @param exceptRunId a run to leave out (the one being settled at its own tail), or null
     */
    private int sweepLocked(Long exceptRunId) {
        Instant since = clock.instant().minus(SWEEP_WINDOW);
        List<PipelineRunEntity> unsettled;
        try {
            unsettled = pipelineRunRepository.findUnsettledSince(since, PipelineRunStatus.RUNNING);
        } catch (RuntimeException e) {
            LOG.error("Location failure sweep: could not list unsettled pipeline runs, will retry "
                    + "at the next sweep: {}", LogSanitizer.sanitize(e.getMessage()), e);
            return 0;
        }
        int settled = 0;
        for (PipelineRunEntity unsettledRun : unsettled) {
            if (exceptRunId != null && exceptRunId.equals(unsettledRun.getId())) {
                continue;
            }
            try {
                Boolean claimed = transactionTemplate.execute(
                        status -> settleClaimed(unsettledRun, false, new ArrayList<>()));
                if (Boolean.TRUE.equals(claimed)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                LOG.error("Location failure sweep: pipeline run {} could not be settled, left "
                        + "unclaimed for the next sweep: {}", unsettledRun.getId(),
                        LogSanitizer.sanitize(e.getMessage()), e);
            }
        }
        return settled;
    }

    /**
     * Claims a run and applies its evidence in the chosen mode. Runs inside the settle transaction,
     * with the settle lock held; a throw rolls the claim back with the counts, leaving the run
     * unclaimed.
     *
     * @param tail {@code true} when called from the run's own tail settle (eligible for FULL),
     *             {@code false} for a sweep (always RESETS_ONLY)
     * @return {@code true} if this call claimed and settled the run; {@code false} if it was
     *         deferred (a forecast batch still polling) or already claimed
     */
    private boolean settleClaimed(PipelineRunEntity run, boolean tail, List<Runnable> alerts) {
        Long runId = run.getId();
        Instant trigger = run.getTriggerTime();
        List<ForecastBatchEntity> polling = forecastBatchRepository
                .findByPipelineRunIdAndBatchTypeAndStatusNotIn(runId,
                        ForecastBatchEntity.BatchType.FORECAST,
                        ForecastBatchEntity.BatchStatus.TERMINAL);
        if (!polling.isEmpty()) {
            LOG.info("Pipeline run {}: location failure settle deferred, forecast batch {} is "
                    + "still {} and the poller may yet write results; the run is left unclaimed "
                    + "for a later sweep", runId, polling.get(0).getAnthropicBatchId(),
                    polling.get(0).getStatus());
            return false;
        }
        Instant newestSettled = pipelineRunRepository.findNewestSettledTriggerTime();
        if (pipelineRunRepository.claimFailureSettle(runId, clock.instant()) == 0) {
            LOG.info("Pipeline run {}: location failures already settled, not counting again",
                    runId);
            return false;
        }
        SettleMode mode;
        String why;
        if (!tail) {
            mode = SettleMode.RESETS_ONLY;
            why = "settled by the sweep, not at its own tail";
        } else if (trigger != null && newestSettled != null && !trigger.isAfter(newestSettled)) {
            mode = SettleMode.RESETS_ONLY;
            why = "a newer cycle (triggered " + newestSettled + ") has already been settled, and "
                    + "counting an older cycle after it could restart a streak that cycle's "
                    + "success had broken";
        } else {
            mode = SettleMode.FULL;
            why = "settled at its own tail, newer than every settled cycle";
        }
        LOG.info("Pipeline run {} (triggered {}): location failure settle mode {}: {}", runId,
                trigger, mode, why);
        applyEvidence(runId, run.getCycleType(), trigger, outcomeResolver.resolve(runId), mode,
                alerts);
        return true;
    }

    /**
     * How a cycle's evidence is applied. {@link #FULL} counts failures (and applies resets);
     * {@link #RESETS_ONLY} applies only the resets from successes and triage, never a failure.
     */
    enum SettleMode {
        /** Count failures, disable at the threshold, and reset places that got through. */
        FULL,
        /** Reset places that got through; count nothing, disable nothing, alert nobody. */
        RESETS_ONLY
    }

    private void applyEvidence(Long runId, CycleType cycleType, Instant triggerTime,
            Map<Long, CyclePlaceEvidence> evidence, SettleMode mode, List<Runnable> alerts) {
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
        if (mode == SettleMode.RESETS_ONLY || failed.isEmpty()) {
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
            if (locationRepository.recordFailure(id, failedAt) == 0) {
                continue;
            }
            // Read back what the database produced; the snapshot above is for the name only.
            int count = locationRepository.findConsecutiveFailuresById(id);
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
            reportCapExceeded(runId, cycleType, triggerTime, qualifying, alerts);
            return;
        }
        disable(runId, cycleType, triggerTime, qualifying, failedAt, alerts);
    }

    private void disable(Long runId, CycleType cycleType, Instant triggerTime,
            List<Counted> qualifying, LocalDateTime failedAt, List<Runnable> alerts) {
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
        alerts.add(() -> {
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
            List<Counted> qualifying, List<Runnable> alerts) {
        List<String> names = qualifying.stream().map(c -> c.location().getName()).toList();
        LOG.error("Pipeline run {}: {} places reached {} consecutive failed cycles in one cycle, "
                + "more than the cap of {}, something systemic is wrong, NO place disabled: {}",
                runId, names.size(), AUTO_DISABLE_THRESHOLD, MAX_DISABLED_PER_CYCLE,
                LogSanitizer.sanitize(String.join(", ", names)));
        alerts.add(() -> {
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
     * Builds the fixed-shape reason stored on a disabled place, e.g. {@code "Auto-disabled after 3
     * consecutive failed scheduled runs (last 2026-10-02: data could not be collected)."}. Built
     * only from a count, a date and a {@link FailureKind} phrase, never from an exception message.
     */
    private static String disabledReason(int count, LocalDateTime failedAt, FailureKind kind) {
        return "Auto-disabled after " + count + " consecutive failed scheduled runs (last "
                + failedAt.toLocalDate() + ": " + kind.phrase() + ").";
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
