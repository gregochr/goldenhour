package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.runs.SchedulerJobCostService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The expensive facts of one job, for the Scheduler job panel only (never polled): its typical duration and its
 * Claude spend over the last 30 days (scheduler-redesign-plan.md D7).
 *
 * <p><b>Typical duration</b> has one source per job so the app shows one "typical": a forecast cycle job uses the
 * run-based median the solar preview already serves ({@link SolarSchedulePreviewService#medianDurationSeconds},
 * {@code basis: "runs"}); every other job uses the median of {@code completed_at - fired_at} over its completed
 * {@code SUCCEEDED} and {@code PARTIAL} fires in the log ({@code basis: "fires"}), and only from
 * {@value #MIN_FIRE_SAMPLES} samples, so a weekly job (two fires in the fortnight's log) answers null. A cycle's
 * fire duration is never used: it is the time to submit, not the time to finish.
 *
 * <p><b>Claude cost</b> is null for a job the catalogue says does not use Claude; see
 * {@link SchedulerJobCostService} for how each job that does is priced.
 */
@Service
public class SchedulerJobDetailService {

    /** The fewest completed fires a fire-log median is taken over. */
    static final int MIN_FIRE_SAMPLES = 3;

    /** {@code typicalDurationBasis} for a cycle's run-based median. */
    static final String BASIS_RUNS = "runs";

    /** {@code typicalDurationBasis} for a fire-log median. */
    static final String BASIS_FIRES = "fires";

    private static final Set<SchedulerFireStatus> COMPLETED_OK =
            Set.of(SchedulerFireStatus.SUCCEEDED, SchedulerFireStatus.PARTIAL);

    /**
     * One job's panel-only detail.
     *
     * @param jobKey                the job key
     * @param typicalDurationSeconds the typical duration in whole seconds, or null when there is not enough history
     * @param typicalDurationBasis  {@code runs}, {@code fires}, or null with no duration
     * @param claudeCost30d         the Claude spend over the last 30 days, or null for a job that does not use Claude
     */
    public record Detail(String jobKey, Long typicalDurationSeconds, String typicalDurationBasis,
            SchedulerJobCostService.ClaudeCost claudeCost30d) {
    }

    private final SchedulerJobConfigRepository configs;
    private final SchedulerFireLogRepository fireLog;
    private final SolarSchedulePreviewService preview;
    private final SchedulerJobCostService costService;

    /**
     * Constructs the service.
     *
     * @param configs     the scheduler job rows, to tell an unknown job from a quiet one
     * @param fireLog     the scheduler fire log
     * @param preview     the source of the cycles' run-based median duration
     * @param costService prices a job's Claude spend
     */
    public SchedulerJobDetailService(SchedulerJobConfigRepository configs, SchedulerFireLogRepository fireLog,
            SolarSchedulePreviewService preview, SchedulerJobCostService costService) {
        this.configs = configs;
        this.fireLog = fireLog;
        this.preview = preview;
        this.costService = costService;
    }

    /**
     * The detail of one job.
     *
     * @param jobKey the job key
     * @return the detail, or empty when no such job exists
     */
    public Optional<Detail> detail(String jobKey) {
        if (configs.findByJobKey(jobKey).isEmpty()) {
            return Optional.empty();
        }
        Long seconds;
        String basis;
        Optional<CycleType> cycle = ForecastCycleJobs.allCycleJobs().stream().filter(j -> j.key().equals(jobKey))
                .map(ForecastCycleJobs.Job::cycleType).findFirst();
        if (cycle.isPresent()) {
            seconds = preview.medianDurationSeconds(cycle.get());
            basis = seconds == null ? null : BASIS_RUNS;
        } else {
            seconds = fireMedianSeconds(jobKey);
            basis = seconds == null ? null : BASIS_FIRES;
        }
        boolean usesClaude = SchedulerJobCatalogue.find(jobKey).map(SchedulerJobCatalogue.Entry::usesClaude)
                .orElse(false);
        return Optional.of(new Detail(jobKey, seconds, basis,
                usesClaude ? costService.claudeCost30d(jobKey) : null));
    }

    private Long fireMedianSeconds(String jobKey) {
        List<Long> seconds = fireLog
                .findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc(jobKey, COMPLETED_OK)
                .stream()
                .filter(f -> f.getFiredAt() != null && f.getCompletedAt() != null)
                .map(SchedulerJobDetailService::seconds)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (seconds.size() < MIN_FIRE_SAMPLES) {
            return null;
        }
        int n = seconds.size();
        return n % 2 == 1 ? seconds.get(n / 2) : (seconds.get(n / 2 - 1) + seconds.get(n / 2)) / 2;
    }

    private static long seconds(SchedulerFireLogEntity fire) {
        return Duration.between(fire.getFiredAt(), fire.getCompletedAt()).getSeconds();
    }
}
