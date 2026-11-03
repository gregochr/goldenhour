package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a scheduled job spent on Claude over the last 30 days, for the Scheduler job panel
 * (scheduler-redesign-plan.md D7).
 *
 * <p>It reads through the same {@link RunsLoader} the Runs Day, Calendar and Cost views use, so a figure here is
 * the figure there: each run priced with its own recorded rate by {@link RunFx}, an unknown cost marking the answer
 * incomplete and never counted as zero. Nothing is summed here that the loader has not already converted.
 *
 * <ul>
 *   <li>A forecast cycle job: its pipeline runs (member batch runs folded in) <em>plus</em> the briefing and
 *       ask-ready runs chained to them ({@code includes: ["batches","briefing","askReady"]}). {@code pipeline_run}
 *       has no manual flag, so a manual cycle counts ({@code inclManual: true}).</li>
 *   <li>{@code aurora_batch_evaluation}: the top-level {@code SCHEDULED_BATCH} runs of an aurora batch, the
 *       mapping {@link SchedulerRunsService} uses ({@code includes: ["batches"]}); {@code inclManual} says whether
 *       any counted run was started by an admin.</li>
 *   <li>{@code sky_rating_eval}: the eval runs with {@code trigger_source = SCHEDULED}
 *       ({@code includes: ["evalRuns"]}, {@code inclManual: false}).</li>
 *   <li>Any other key: {@code gbp: null} with a {@code reason}. (The catalogue's other Claude users are all
 *       covered above, so this is the answer for a job no cost path exists for.)</li>
 * </ul>
 */
@Service
public class SchedulerJobCostService {

    /** The look-back window, in days. */
    static final int WINDOW_DAYS = 30;

    /** The reason served for a job with no cost path. */
    static final String NO_COST_PATH = "This job's Claude spend is not recorded against its own runs.";

    /** The key of the sky rating eval job. */
    static final String SKY_EVAL_JOB = "sky_rating_eval";

    private static final Set<RunDeepType> CYCLE_CHILDREN = Set.of(RunDeepType.BRIEFING, RunDeepType.ASK_READY);


    /**
     * A job's Claude spend over the last 30 days.
     *
     * @param gbp       the spend in pounds to the penny, or null when no cost path exists (see {@code reason})
     * @param complete  false when a counted run's cost is still unknown (a batch still open); null with no path
     * @param runs      the runs counted (for a cycle: cycles, not their briefing and ask-ready children)
     * @param includes  what the figure includes
     * @param inclManual whether runs an admin started may be counted
     * @param perRun    {@code gbp} divided by {@code runs}, or null when there are no runs
     * @param reason    why {@code gbp} is null, otherwise null
     */
    public record ClaudeCost(BigDecimal gbp, Boolean complete, Integer runs, List<String> includes,
            Boolean inclManual, BigDecimal perRun, String reason) {
    }

    private final RunsLoader loader;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param loader the shared Runs loader
     * @param clock  the application clock
     */
    SchedulerJobCostService(RunsLoader loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
    }

    /**
     * The job's Claude spend over the 30 days to now.
     *
     * @param jobKey the scheduler job key
     * @return the spend, or a {@code gbp: null} answer with a reason when no cost path exists for the key
     */
    public ClaudeCost claudeCost30d(String jobKey) {
        CycleType cycle = ForecastCycleJobs.allCycleJobs().stream().filter(j -> j.key().equals(jobKey))
                .map(ForecastCycleJobs.Job::cycleType).findFirst().orElse(null);
        boolean aurora = SchedulerRunsService.AURORA_BATCH_JOB.equals(jobKey);
        boolean skyEval = SKY_EVAL_JOB.equals(jobKey);
        if (cycle == null && !aurora && !skyEval) {
            return new ClaudeCost(null, null, null, List.of(), null, null, NO_COST_PATH);
        }
        Instant now = clock.instant();
        List<DeepRun> rows = loader.load(now.minus(Duration.ofDays(WINDOW_DAYS)), now).rows();
        if (cycle != null) {
            return cycleCost(rows, cycle);
        }
        if (aurora) {
            String auroraName = RunClassifier.jobName(RunType.SCHEDULED_BATCH, BatchType.AURORA, null);
            List<DeepRun> counted = rows.stream().filter(r -> r.kind() == RunDeepKind.JOB
                    && r.runType() == RunType.SCHEDULED_BATCH && auroraName.equals(r.name())).toList();
            return cost(counted, counted.size(), List.of("batches"), counted.stream().anyMatch(DeepRun::manual));
        }
        List<DeepRun> counted = rows.stream()
                .filter(r -> r.kind() == RunDeepKind.SKY_EVAL && !r.manual()).toList();
        return cost(counted, counted.size(), List.of("evalRuns"), false);
    }

    private ClaudeCost cycleCost(List<DeepRun> rows, CycleType cycle) {
        List<DeepRun> cycles = rows.stream()
                .filter(r -> r.kind() == RunDeepKind.CYCLE && r.key().cycle() == cycle).toList();
        Set<Long> cycleIds = cycles.stream().map(DeepRun::cycleId).collect(Collectors.toSet());
        List<DeepRun> counted = new ArrayList<>(cycles);
        rows.stream().filter(r -> r.parentCycleId() != null && cycleIds.contains(r.parentCycleId())
                && CYCLE_CHILDREN.contains(r.type())).forEach(counted::add);
        return cost(counted, cycles.size(), List.of("batches", "briefing", "askReady"), true);
    }

    private static ClaudeCost cost(List<DeepRun> counted, int runs, List<String> includes, boolean inclManual) {
        double pounds = counted.stream().mapToDouble(DeepRun::costGbp).sum();
        boolean complete = counted.stream().allMatch(DeepRun::costComplete);
        BigDecimal gbp = RunFx.pence(pounds);
        BigDecimal perRun = runs == 0 ? null : RunFx.pence(pounds / runs);
        return new ClaudeCost(gbp, complete, runs, includes, inclManual,
                perRun, null);
    }
}
