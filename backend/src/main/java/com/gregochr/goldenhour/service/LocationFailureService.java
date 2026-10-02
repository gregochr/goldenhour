package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.model.CyclePlaceOutcome;
import com.gregochr.goldenhour.model.CyclePlaceOutcome.FailureKind;
import com.gregochr.goldenhour.model.CyclePlaceOutcome.Status;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.service.notification.AdminAlertService.DisabledLocation;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * <p><b>Which runs.</b> Only scheduled pipeline cycles (nightly and intraday), settled once each by
 * {@code PipelineOrchestrator} after the cycle's batch results and its retry have landed. A
 * hand-started run (the synchronous engine behind the {@code ForecastController} endpoints) never
 * reaches this class, and its batches are never tagged with a pipeline run, so
 * {@link CycleLocationOutcomeResolver} resolves them to nothing.
 *
 * <p><b>What counts.</b> A place counts as failed in a cycle only if it
 * {@link Status#FAILED failed} <em>and</em> at least half of the other places attempted in that
 * cycle ({@link Status#GOT_THROUGH got through} plus {@code FAILED}, the place itself excluded) got
 * through. An outage that fails everything counts for nobody. "At least half" is inclusive: with ten
 * other attempted places, five through and five failed counts; four through and six failed does
 * not. A cycle with no other attempted place at all offers no evidence that the pipeline was
 * working, so it counts nobody either.
 *
 * <p><b>Counting.</b> One count per place per cycle, never per slot (the resolver has already folded
 * every slot and lane into one outcome). A place that got through resets its counter to zero, even in
 * a cycle that is otherwise treated as systemic. A place not attempted keeps its counter untouched.
 *
 * <p><b>Disabling.</b> A place whose counter reaches {@link #AUTO_DISABLE_THRESHOLD} is disabled and
 * the admin is told. If more than {@link #MAX_DISABLED_PER_CYCLE} places qualify in one cycle, none is
 * disabled, an ERROR is logged and the admin is told that something systemic is wrong; the counters
 * still advance, so the alert repeats each cycle until the cause is fixed.
 *
 * <p><b>Writes are column-scoped</b> ({@code LocationRepository#recordFailure},
 * {@code #autoDisable}, {@code #resetFailureCounts}), never a whole-entity save: a save would write
 * every column, including the tide, type and solar-event sets, from whatever copy this class held and
 * could undo an admin's concurrent edit of the same place.
 *
 * <p><b>Idempotence.</b> {@link #settleCycle} remembers the highest pipeline run id it has settled,
 * in memory, and ignores that id and anything older. In memory is enough because the orchestrator
 * settles inside the BRIEFING phase, after that phase row has been started: a process restart
 * resumes a run that is already in BRIEFING without re-entering the settle, so a restart can lose a
 * settle (a missed count, the safe direction) but cannot repeat one. The marker covers the remaining
 * case, a second call within one process. Pipeline run ids are autoincrement, so "older than the
 * last settled" also keeps a late-settling old cycle from counting after a newer one.
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

    private final CycleLocationOutcomeResolver outcomeResolver;
    private final LocationRepository locationRepository;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    /** Highest pipeline run id already settled; guarded by {@code this} (see {@link #claim}). */
    private long lastSettledPipelineRunId = Long.MIN_VALUE;

    /**
     * Constructs the service.
     *
     * @param outcomeResolver     derives each place's outcome for a cycle from recorded data
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
     * Settles one scheduled cycle: resolves each place's outcome from the cycle's recorded data and
     * applies the failure-counting rule. Called once per cycle by {@code PipelineOrchestrator}.
     *
     * @param run the pipeline run being settled
     */
    @Transactional
    public void settleCycle(PipelineRunEntity run) {
        Long runId = run.getId();
        if (!claim(runId)) {
            LOG.info("Pipeline run {}: location failures already settled (or a newer cycle has been) "
                    + "— not counting again", runId);
            return;
        }
        Map<Long, CyclePlaceOutcome> outcomes = outcomeResolver.resolve(runId);
        applyOutcomes(runId, run.getCycleType(), run.getTriggerTime(), outcomes);
    }

    /**
     * Applies the rule to already-resolved outcomes. Exposed to the package so the rule can be
     * exercised on literal outcomes; production reaches it only through {@link #settleCycle}.
     *
     * @param runId       the cycle's pipeline run id
     * @param cycleType   the cycle's type, for the alert
     * @param triggerTime when the cycle started, for the alert
     * @param outcomes    outcome per location id
     */
    void applyOutcomes(Long runId, CycleType cycleType, Instant triggerTime,
            Map<Long, CyclePlaceOutcome> outcomes) {
        List<Long> gotThrough = idsWith(outcomes, Status.GOT_THROUGH);
        List<Long> failed = idsWith(outcomes, Status.FAILED);

        if (!gotThrough.isEmpty()) {
            int reset = locationRepository.resetFailureCounts(gotThrough);
            if (reset > 0) {
                LOG.info("Pipeline run {}: {} place(s) got through — consecutive failure counter "
                        + "reset to 0", runId, reset);
            }
        }
        if (failed.isEmpty()) {
            return;
        }
        if (!othersMostlyGotThrough(gotThrough.size(), failed.size())) {
            LOG.warn("Pipeline run {}: {} place(s) failed but only {} of the {} other attempted "
                    + "place(s) got through — treated as systemic, no failure counted for anyone",
                    runId, failed.size(), gotThrough.size(), gotThrough.size() + failed.size() - 1);
            return;
        }

        LocalDateTime failedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<Long, LocationEntity> byId = locationRepository.findAllById(failed).stream()
                .collect(Collectors.toMap(LocationEntity::getId, l -> l));
        List<Counted> counted = new ArrayList<>();
        for (Long id : failed) {
            LocationEntity location = byId.get(id);
            if (location == null || !location.isEnabled()) {
                continue;
            }
            int count = (location.getConsecutiveFailures() == null
                    ? 0 : location.getConsecutiveFailures()) + 1;
            locationRepository.recordFailure(id, count, failedAt);
            LOG.info("Pipeline run {}: location '{}' failed this cycle ({}) — consecutive failures "
                            + "now {}", runId, LogSanitizer.sanitize(location.getName()),
                    outcomes.get(id).failureKind(), count);
            counted.add(new Counted(location, count, outcomes.get(id).failureKind()));
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

    /**
     * Whether enough of the other attempted places got through for a failure to count: at least half
     * of them (inclusive), and at least one. A failed place is never itself in the through count, so
     * the figures are identical for every failed place in the cycle.
     */
    private static boolean othersMostlyGotThrough(int throughCount, int failedCount) {
        int othersAttempted = throughCount + failedCount - 1;
        return othersAttempted > 0 && throughCount * 2 >= othersAttempted;
    }

    private void disable(Long runId, CycleType cycleType, Instant triggerTime,
            List<Counted> qualifying, LocalDateTime failedAt) {
        List<DisabledLocation> disabled = new ArrayList<>();
        for (Counted c : qualifying) {
            String reason = disabledReason(c.count(), failedAt, c.kind());
            if (locationRepository.autoDisable(c.location().getId(), c.count(), failedAt,
                    reason) > 0) {
                LOG.warn("Pipeline run {}: location '{}' AUTO-DISABLED — {}", runId,
                        LogSanitizer.sanitize(c.location().getName()), reason);
                disabled.add(new DisabledLocation(c.location().getName(), reason));
            }
        }
        if (disabled.isEmpty()) {
            return;
        }
        try {
            adminAlertService.sendLocationsAutoDisabledAlert(runId, cycleType, triggerTime, disabled);
        } catch (RuntimeException e) {
            LOG.warn("Pipeline run {}: auto-disable alert dispatch raised an exception — logged and "
                    + "ignored (the places stay disabled): {}", runId,
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    private void reportCapExceeded(Long runId, CycleType cycleType, Instant triggerTime,
            List<Counted> qualifying) {
        List<String> names = qualifying.stream().map(c -> c.location().getName()).toList();
        LOG.error("Pipeline run {}: {} places reached {} consecutive failed cycles in one cycle, "
                + "more than the cap of {} — something systemic is wrong, NO place disabled: {}",
                runId, names.size(), AUTO_DISABLE_THRESHOLD, MAX_DISABLED_PER_CYCLE,
                LogSanitizer.sanitize(String.join(", ", names)));
        try {
            adminAlertService.sendLocationDisableCapAlert(runId, cycleType, triggerTime, names,
                    MAX_DISABLED_PER_CYCLE);
        } catch (RuntimeException e) {
            LOG.warn("Pipeline run {}: auto-disable cap alert dispatch raised an exception — logged "
                    + "and ignored: {}", runId, LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /**
     * Builds the fixed-shape reason stored on a disabled place, e.g. {@code "Auto-disabled after 3
     * consecutive failed scheduled runs (last 2026-10-02: weather data could not be fetched)."}.
     * Built only from a count, a date and a {@link FailureKind} phrase, never from an exception
     * message.
     */
    private static String disabledReason(int count, LocalDateTime failedAt, FailureKind kind) {
        return "Auto-disabled after " + count + " consecutive failed scheduled runs (last "
                + failedAt.toLocalDate() + ": " + kind.phrase() + ").";
    }

    private static List<Long> idsWith(Map<Long, CyclePlaceOutcome> outcomes, Status status) {
        return outcomes.entrySet().stream()
                .filter(e -> e.getValue().status() == status)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Claims a cycle for settling.
     *
     * @return {@code true} if this is the first settle of this cycle and no newer cycle has been
     *         settled; {@code false} otherwise
     */
    private synchronized boolean claim(Long pipelineRunId) {
        if (pipelineRunId <= lastSettledPipelineRunId) {
            return false;
        }
        lastSettledPipelineRunId = pipelineRunId;
        return true;
    }

    private record Counted(LocationEntity location, int count, FailureKind kind) {
    }
}
