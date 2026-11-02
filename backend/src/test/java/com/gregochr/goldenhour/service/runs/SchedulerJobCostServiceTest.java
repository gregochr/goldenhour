package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link SchedulerJobCostService} prices each Claude job from the shared loader's rows: cycles with their
 * briefing and ask-ready children, the aurora batch, the scheduled sky eval, and the no-cost-path answer.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerJobCostServiceTest {

    private static final Instant NOW = Instant.parse("2027-03-05T12:00:00Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(30));
    private static final Instant START = Instant.parse("2027-03-01T03:00:00Z");

    @Mock
    private RunsLoader loader;

    private SchedulerJobCostService service() {
        return new SchedulerJobCostService(loader, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void loaded(DeepRun... rows) {
        when(loader.load(FROM, NOW)).thenReturn(new RunsLoader.LoadedRuns(List.of(rows), null));
    }

    private static DeepRun cycle(long id, CycleType type, double gbp, boolean complete) {
        return new DeepRun("cycle-" + id, RunDeepKind.CYCLE, RunDeepType.ofCycle(type), StatKey.ofCycle(type), null,
                "Cycle", null, START, START.plusSeconds(600), 600L, 1L, complete, gbp, RunDeepStatus.DONE, null,
                null, null, id, null, 1, 10, null, null, false);
    }

    private static DeepRun child(long jobId, RunDeepType type, RunType runType, long parent, double gbp) {
        return new DeepRun("job-" + jobId, RunDeepKind.JOB, type, StatKey.ofJob(runType, null), runType, "Child",
                null, START, START.plusSeconds(60), 60L, 1L, true, gbp, RunDeepStatus.DONE, null, null, parent, null,
                jobId, null, null, null, null, false);
    }

    private static DeepRun auroraBatch(long jobId, double gbp, boolean manual, boolean complete) {
        return new DeepRun("job-" + jobId, RunDeepKind.JOB, RunDeepType.OTHER,
                StatKey.ofJob(RunType.SCHEDULED_BATCH, null), RunType.SCHEDULED_BATCH, "Aurora batch", null, START,
                START.plusSeconds(60), 60L, 1L, complete, gbp, RunDeepStatus.DONE, null, null, null, null, jobId,
                null, null, null, null, manual);
    }

    private static DeepRun skyEval(long id, double gbp, boolean manual) {
        return new DeepRun("eval-" + id, RunDeepKind.SKY_EVAL, RunDeepType.SKY_EVAL, StatKey.SKY_EVAL, null,
                "Sky eval", null, START, START.plusSeconds(60), 60L, 1L, true, gbp, RunDeepStatus.DONE, null, null,
                null, null, null, null, null, null, null, manual);
    }

    @Test
    @DisplayName("a cycle job sums its own cycles plus their briefing and ask-ready children, per cycle run")
    void cycle_includesChainedChildren() {
        loaded(cycle(1, CycleType.NIGHTLY, 10.00, true), cycle(2, CycleType.NIGHTLY, 20.00, true),
                cycle(3, CycleType.INTRADAY, 99.00, true),
                child(11, RunDeepType.BRIEFING, RunType.BRIEFING, 1, 0.50),
                child(12, RunDeepType.ASK_READY, RunType.ASK_READY, 2, 1.25),
                child(13, RunDeepType.BRIEFING, RunType.BRIEFING, 3, 7.00),
                child(14, RunDeepType.OTHER, RunType.TIDE, 1, 8.00));

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("near_term_batch_evaluation");

        assertThat(cost.gbp()).isEqualByComparingTo("31.75");
        assertThat(cost.runs()).isEqualTo(2);
        assertThat(cost.perRun()).isEqualByComparingTo("15.88");
        assertThat(cost.complete()).isTrue();
        assertThat(cost.includes()).containsExactly("batches", "briefing", "askReady");
        assertThat(cost.inclManual()).isTrue();
        assertThat(cost.reason()).isNull();
    }

    @Test
    @DisplayName("the intraday and evening jobs each take their own cycle type")
    void cycle_eachJobTakesItsOwnType() {
        loaded(cycle(1, CycleType.NIGHTLY, 10.00, true), cycle(2, CycleType.INTRADAY, 4.00, true),
                cycle(3, CycleType.EVENING, 2.00, true));

        assertThat(service().claudeCost30d("intraday_forecast_refresh").gbp()).isEqualByComparingTo("4.00");
        assertThat(service().claudeCost30d("evening_sunrise_refresh").gbp()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("a cost still accruing in a cycle or its child marks the figure incomplete")
    void cycle_incompleteCostIsFlagged() {
        loaded(cycle(1, CycleType.NIGHTLY, 10.00, false));

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("near_term_batch_evaluation");

        assertThat(cost.complete()).isFalse();
        assertThat(cost.gbp()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a cycle with no runs in the window costs 0.00 with no per-run figure")
    void cycle_noRuns() {
        loaded();

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("near_term_batch_evaluation");

        assertThat(cost.gbp()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cost.runs()).isZero();
        assertThat(cost.perRun()).isNull();
        assertThat(cost.complete()).isTrue();
    }

    @Test
    @DisplayName("the aurora batch counts only aurora batch job runs, and says whether an admin started one")
    void aurora_countsAuroraBatches() {
        loaded(auroraBatch(1, 1.00, false, true), auroraBatch(2, 2.00, true, true),
                cycle(3, CycleType.NIGHTLY, 50.00, true),
                child(4, RunDeepType.OTHER, RunType.TIDE, 1, 9.00));

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("aurora_batch_evaluation");

        assertThat(cost.gbp()).isEqualByComparingTo("3.00");
        assertThat(cost.runs()).isEqualTo(2);
        assertThat(cost.perRun()).isEqualByComparingTo("1.50");
        assertThat(cost.includes()).containsExactly("batches");
        assertThat(cost.inclManual()).isTrue();
    }

    @Test
    @DisplayName("scheduled-only aurora batches report inclManual false, and an open batch is incomplete")
    void aurora_scheduledOnly() {
        loaded(auroraBatch(1, 1.00, false, false));

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("aurora_batch_evaluation");

        assertThat(cost.inclManual()).isFalse();
        assertThat(cost.complete()).isFalse();
    }

    @Test
    @DisplayName("the sky eval counts only its scheduled runs")
    void skyEval_scheduledRunsOnly() {
        loaded(skyEval(1, 2.50, false), skyEval(2, 2.60, false), skyEval(3, 40.00, true));

        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("sky_rating_eval");

        assertThat(cost.gbp()).isEqualByComparingTo("5.10");
        assertThat(cost.runs()).isEqualTo(2);
        assertThat(cost.perRun()).isEqualByComparingTo("2.55");
        assertThat(cost.includes()).containsExactly("evalRuns");
        assertThat(cost.inclManual()).isFalse();
    }

    @Test
    @DisplayName("a job with no cost path answers gbp null with a reason, and reads nothing")
    void unmappedJob_hasReasonAndNoRead() {
        SchedulerJobCostService.ClaudeCost cost = service().claudeCost30d("astro_conditions_refresh");

        assertThat(cost.gbp()).isNull();
        assertThat(cost.reason()).isEqualTo(SchedulerJobCostService.NO_COST_PATH);
        assertThat(cost.runs()).isNull();
        assertThat(cost.includes()).isEmpty();
        verifyNoInteractions(loader);
    }
}
