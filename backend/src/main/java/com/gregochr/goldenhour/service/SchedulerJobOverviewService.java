package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cheap, per-poll facts the Scheduler screen shows about every job: its newest fire, its next three fires and
 * the page-level summary (scheduler-redesign-plan.md D7).
 *
 * <p>Polled every 30 seconds, so the fire log is read <em>once</em> for all jobs ({@link #lastFires}), never once
 * per job, and a job's next fires come from the scheduler's own arithmetic
 * ({@link DynamicSchedulerService#firesBetween}, {@link DynamicSchedulerService#calculateNextFireTime}).
 */
@Service
public class SchedulerJobOverviewService {

    /** How many upcoming fires a job lists. */
    static final int NEXT_FIRES = 3;

    /** The first window searched for a job's next fires; enough for any job that fires daily or more often. */
    static final Duration NEAR_WINDOW = Duration.ofHours(36);

    /** The wider window searched when the near one holds fewer than {@value #NEXT_FIRES}: enough for a weekly job. */
    static final Duration FAR_WINDOW = Duration.ofDays(8);

    private static final Duration DAY = Duration.ofHours(24);

    /**
     * A job's newest fire, as served under {@code lastFire}.
     *
     * @param firedAt     when the fire started
     * @param completedAt when it ended, or null while it runs
     * @param status      {@code RUNNING}, {@code SUCCEEDED}, {@code PARTIAL}, {@code FAILED} or {@code SKIPPED}
     * @param summary     the one-line outcome, or null
     * @param trigger     {@code SCHEDULED}, {@code MANUAL} or {@code CATCH_UP}
     */
    public record LastFire(Instant firedAt, Instant completedAt, String status, String summary, String trigger) {
    }

    /**
     * The soonest upcoming non-repeating fire.
     *
     * @param jobKey      the job key
     * @param displayName the job's display name
     * @param at          when it fires
     */
    public record NextScheduled(String jobKey, String displayName, Instant at) {
    }

    /**
     * The page-level counts.
     *
     * @param total           how many jobs there are
     * @param byStatus        jobs per {@code SchedulerJobStatus} (every status present, zero when none)
     * @param byType          jobs per stored schedule type (the three known types always present)
     * @param usesClaude      jobs whose catalogue entry says they spend Claude money
     * @param lastFireFailed  jobs whose newest fire is {@code FAILED}
     * @param nextScheduled   the soonest next fire among ACTIVE, non-repeating jobs, or null when there is none
     */
    public record Summary(int total, Map<String, Integer> byStatus, Map<String, Integer> byType, int usesClaude,
            int lastFireFailed, NextScheduled nextScheduled) {
    }

    private final SchedulerFireLogRepository fireLog;
    private final DynamicSchedulerService scheduler;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param fireLog   the scheduler fire log
     * @param scheduler the dynamic scheduler, for its fire arithmetic
     * @param clock     the application clock
     */
    public SchedulerJobOverviewService(SchedulerFireLogRepository fireLog, DynamicSchedulerService scheduler,
            Clock clock) {
        this.fireLog = fireLog;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * The newest fire of every job that has fired, from one query.
     *
     * @return the newest fire by job key; a job that never fired is absent
     */
    public Map<String, LastFire> lastFires() {
        Map<String, SchedulerFireLogEntity> newest = new LinkedHashMap<>();
        for (SchedulerFireLogEntity row : fireLog.findNewestPerJob()) {
            newest.merge(row.getJobKey(), row,
                    (a, b) -> b.getId() != null && a.getId() != null && b.getId() > a.getId() ? b : a);
        }
        Map<String, LastFire> result = new LinkedHashMap<>();
        newest.forEach((key, row) -> result.put(key, new LastFire(row.getFiredAt(), row.getCompletedAt(),
                row.getStatus().name(), row.getSummary(), row.getTriggerKind().name())));
        return result;
    }

    /**
     * The next three fires of a job, soonest first. Empty for a paused or config-disabled job and for one whose
     * schedule cannot be computed. A fixed-delay job lists its next fire and the two delays after it.
     *
     * @param config the job config
     * @return up to three instants, ascending
     */
    public List<Instant> nextFires(SchedulerJobConfigEntity config) {
        if (config.getStatus() != SchedulerJobStatus.ACTIVE) {
            return List.of();
        }
        if (config.getScheduleType() == ScheduleType.FIXED_DELAY) {
            return fixedDelayFires(config);
        }
        Instant now = clock.instant();
        List<Instant> fires = scheduler.firesBetween(config, now, now.plus(NEAR_WINDOW));
        if (fires.size() < NEXT_FIRES) {
            fires = scheduler.firesBetween(config, now, now.plus(FAR_WINDOW));
        }
        return fires.stream().limit(NEXT_FIRES).toList();
    }

    private List<Instant> fixedDelayFires(SchedulerJobConfigEntity config) {
        Instant next = scheduler.calculateNextFireTime(config);
        if (next == null) {
            return List.of();
        }
        Long delayMs = config.getFixedDelayMs();
        List<Instant> fires = new ArrayList<>();
        fires.add(next);
        for (int i = 1; i < NEXT_FIRES && delayMs != null && delayMs > 0; i++) {
            fires.add(next.plusMillis(delayMs * i));
        }
        return fires;
    }

    /**
     * The page-level summary of every job.
     *
     * @return the counts and the soonest non-repeating fire
     */
    public Summary summary() {
        List<SchedulerJobConfigEntity> configs = scheduler.getAll();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (SchedulerJobStatus status : SchedulerJobStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (ScheduleType type : ScheduleType.values()) {
            byType.put(type.name(), 0);
        }
        Map<String, LastFire> lastFires = lastFires();
        int claude = 0;
        int failed = 0;
        for (SchedulerJobConfigEntity config : configs) {
            byStatus.merge(config.getStatus().name(), 1, Integer::sum);
            byType.merge(config.getScheduleTypeRaw() == null ? "" : config.getScheduleTypeRaw(), 1, Integer::sum);
            if (SchedulerJobCatalogue.find(config.getJobKey()).map(SchedulerJobCatalogue.Entry::usesClaude)
                    .orElse(false)) {
                claude++;
            }
            LastFire last = lastFires.get(config.getJobKey());
            if (last != null && SchedulerFireStatus.FAILED.name().equals(last.status())) {
                failed++;
            }
        }
        return new Summary(configs.size(), byStatus, byType, claude, failed, nextScheduled(configs));
    }

    private NextScheduled nextScheduled(List<SchedulerJobConfigEntity> configs) {
        Instant now = clock.instant();
        return configs.stream()
                .filter(c -> c.getStatus() == SchedulerJobStatus.ACTIVE)
                .filter(c -> c.getScheduleType() != ScheduleType.FIXED_DELAY)
                .filter(c -> !DynamicSchedulerService.exceedsRepeatingThreshold(
                        scheduler.firesBetween(c, now, now.plus(DAY)).size()))
                .map(c -> new NextScheduled(c.getJobKey(), c.getDisplayName(), scheduler.calculateNextFireTime(c)))
                .filter(n -> n.at() != null)
                .min(Comparator.comparing(NextScheduled::at))
                .orElse(null);
    }
}
