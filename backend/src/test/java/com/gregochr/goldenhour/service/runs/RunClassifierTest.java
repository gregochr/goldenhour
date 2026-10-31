package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.service.RunCompletion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure tests for the group, name, status and note rules the Runs list serves.
 */
class RunClassifierTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);

    private static JobRunEntity.JobRunEntityBuilder closed(int succeeded, int failed) {
        return JobRunEntity.builder().runType(RunType.WEATHER).startedAt(NOW.minusHours(1))
                .completedAt(NOW.minusMinutes(30)).succeeded(succeeded).failed(failed)
                .locationsProcessed(succeeded + failed).triggeredManually(false);
    }

    private static JobRunEntity.JobRunEntityBuilder open(RunType type, LocalDateTime startedAt) {
        return JobRunEntity.builder().runType(type).startedAt(startedAt).triggeredManually(false);
    }

    @ParameterizedTest
    @EnumSource(RunType.class)
    @DisplayName("every RunType maps to a group and a name, for both batch types")
    void everyRunTypeMaps(RunType type) {
        assertThat(RunClassifier.groupOf(type, null)).isNotNull();
        assertThat(RunClassifier.groupOf(type, BatchType.AURORA)).isNotNull();
        assertThat(RunClassifier.jobName(type, null, null)).isNotBlank();
        assertThat(RunClassifier.staleCeiling(type)).isPositive();
    }

    @Test
    @DisplayName("groups: batches by batch type, aurora, tide, briefing, other; unwritten types go to OTHER")
    void groupMapping() {
        assertThat(RunClassifier.groupOf(RunType.SCHEDULED_BATCH, BatchType.FORECAST)).isEqualTo(RunGroup.BATCHES);
        assertThat(RunClassifier.groupOf(RunType.SCHEDULED_BATCH, null)).isEqualTo(RunGroup.BATCHES);
        assertThat(RunClassifier.groupOf(RunType.SCHEDULED_BATCH, BatchType.AURORA)).isEqualTo(RunGroup.AURORA);
        assertThat(RunClassifier.groupOf(RunType.AURORA_EVALUATION, null)).isEqualTo(RunGroup.AURORA);
        assertThat(RunClassifier.groupOf(RunType.TIDE, null)).isEqualTo(RunGroup.TIDE);
        assertThat(RunClassifier.groupOf(RunType.BRIEFING, null)).isEqualTo(RunGroup.BRIEFING);
        List<RunType> others = List.of(RunType.WEATHER, RunType.LIGHT_POLLUTION, RunType.ASK,
                RunType.ASK_READY, RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM, RunType.BRIEFING_GLOSS,
                RunType.BRIEFING_BEST_BET, RunType.AURORA_GLOSS, RunType.BLUEBELL_GLOSS);
        for (RunType other : others) {
            assertThat(RunClassifier.groupOf(other, null)).as(other.name()).isEqualTo(RunGroup.OTHER);
        }
    }

    @Test
    @DisplayName("names: scheduled, JFDI and aurora batches; cycles")
    void names() {
        assertThat(RunClassifier.jobName(RunType.SCHEDULED_BATCH, BatchType.FORECAST, null))
                .isEqualTo("Scheduled batch");
        assertThat(RunClassifier.jobName(RunType.SCHEDULED_BATCH, BatchType.FORECAST, "A, EVALUATE_ALL"))
                .isEqualTo("JFDI batch");
        assertThat(RunClassifier.jobName(RunType.SCHEDULED_BATCH, BatchType.FORECAST, "SKIP_EXISTING"))
                .isEqualTo("Scheduled batch");
        assertThat(RunClassifier.jobName(RunType.SCHEDULED_BATCH, BatchType.AURORA, "EVALUATE_ALL"))
                .isEqualTo("Aurora batch");
        assertThat(RunClassifier.cycleName(CycleType.NIGHTLY)).isEqualTo("Overnight cycle");
        assertThat(RunClassifier.cycleName(CycleType.INTRADAY)).isEqualTo("Afternoon refresh");
        assertThat(RunClassifier.cycleName(CycleType.EVENING)).isEqualTo("Evening sunrise refresh");
    }

    @Test
    @DisplayName("an open run is RUNNING until its ceiling: 26h for a batch, 6h for the rest")
    void staleCeilings() {
        assertThat(RunClassifier.classifyJob(open(RunType.WEATHER, NOW.minusHours(5)).build(), NOW).status())
                .isEqualTo(RunListStatus.RUNNING);
        RunClassifier.Outcome staleOther =
                RunClassifier.classifyJob(open(RunType.WEATHER, NOW.minusHours(6)).build(), NOW);
        assertThat(staleOther.status()).isEqualTo(RunListStatus.FAILED);
        assertThat(staleOther.note()).isEqualTo("Did not finish");
        assertThat(staleOther.tone()).isEqualTo(NoteTone.ERROR);

        assertThat(RunClassifier.classifyJob(open(RunType.SCHEDULED_BATCH, NOW.minusHours(25)).build(), NOW)
                .status()).isEqualTo(RunListStatus.RUNNING);
        assertThat(RunClassifier.classifyJob(open(RunType.SCHEDULED_BATCH, NOW.minusHours(26)).build(), NOW)
                .status()).isEqualTo(RunListStatus.FAILED);
        assertThat(RunClassifier.longestStaleCeiling()).isEqualTo(RunClassifier.staleCeiling(RunType.SCHEDULED_BATCH));
    }

    @Test
    @DisplayName("0/0 is DONE, or FAILED with the reason when the note is a RunCompletion reason")
    void zeroByZero() {
        assertThat(RunClassifier.classifyJob(closed(0, 0).build(), NOW).status()).isEqualTo(RunListStatus.DONE);
        assertThat(RunClassifier.classifyJob(closed(0, 0).notes("Disposition-only cycle").build(), NOW).status())
                .isEqualTo(RunListStatus.DONE);
        assertThat(RunClassifier.classifyJob(closed(0, 0).notes("Anthropic batch: msgbatch_1").build(), NOW)
                .note()).isNull();

        List<String> reasons = List.of(RunCompletion.REASON_RUN_STOPPED, RunCompletion.REASON_EVALUATION_FAILED,
                RunCompletion.REASON_NOT_FINISHED, RunCompletion.REASON_UNEXPECTED);
        for (String reason : reasons) {
            RunClassifier.Outcome outcome = RunClassifier.classifyJob(closed(0, 0).notes(reason).build(), NOW);
            assertThat(outcome.status()).isEqualTo(RunListStatus.FAILED);
            assertThat(outcome.note()).isEqualTo(reason);
            assertThat(outcome.tone()).isEqualTo(NoteTone.ERROR);
        }
    }

    @Test
    @DisplayName("null counts read as zero")
    void nullCounts() {
        JobRunEntity run = JobRunEntity.builder().runType(RunType.TIDE).startedAt(NOW.minusHours(1))
                .completedAt(NOW.minusMinutes(5)).triggeredManually(false).build();
        assertThat(RunClassifier.classifyJob(run, NOW).status()).isEqualTo(RunListStatus.DONE);
    }

    @Test
    @DisplayName("ratio rule: all ok DONE, 80% edge PARTIAL, just under FAILED, with the errored note")
    void ratioRule() {
        assertThat(RunClassifier.classifyJob(closed(10, 0).build(), NOW).status()).isEqualTo(RunListStatus.DONE);

        RunClassifier.Outcome edge = RunClassifier.classifyJob(closed(8, 2).build(), NOW);
        assertThat(edge.status()).isEqualTo(RunListStatus.PARTIAL);
        assertThat(edge.note()).isEqualTo("2 requests errored");
        assertThat(edge.tone()).isEqualTo(NoteTone.WARN);

        RunClassifier.Outcome under = RunClassifier.classifyJob(closed(79, 21).build(), NOW);
        assertThat(under.status()).isEqualTo(RunListStatus.FAILED);
        assertThat(under.note()).isEqualTo("21 requests errored");
        assertThat(under.tone()).isEqualTo(NoteTone.ERROR);

        assertThat(RunClassifier.classifyJob(closed(0, 3).build(), NOW).status()).isEqualTo(RunListStatus.FAILED);
        assertThat(RunClassifier.classifyJob(closed(10, 0).notes("Anthropic batch: x").build(), NOW).note())
                .isNull();
    }

    @Test
    @DisplayName("Ask runs have no duration; others do")
    void noDuration() {
        assertThat(RunClassifier.hasNoDuration(RunType.ASK)).isTrue();
        assertThat(RunClassifier.hasNoDuration(RunType.ASK_READY)).isTrue();
        assertThat(RunClassifier.hasNoDuration(RunType.TIDE)).isFalse();
    }

    @Test
    @DisplayName("cycles map their status, with waiting-on while running and the failure reason otherwise")
    void cycleStatus() {
        PipelineRunEntity cycle = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse("2026-10-31T01:00:00Z"));
        cycle.setWaitingOn("forecast batch set (2 of 4 complete)");
        RunClassifier.Outcome running = RunClassifier.classifyCycle(cycle);
        assertThat(running.status()).isEqualTo(RunListStatus.RUNNING);
        assertThat(running.note()).isEqualTo("forecast batch set (2 of 4 complete)");
        assertThat(running.tone()).isEqualTo(NoteTone.INFO);

        cycle.setWaitingOn(" ");
        assertThat(RunClassifier.classifyCycle(cycle).note()).isNull();
        assertThat(RunClassifier.classifyCycle(cycle).tone()).isNull();

        cycle.setStatus(PipelineRunStatus.COMPLETED);
        cycle.setFailureReason("ignored when done");
        assertThat(RunClassifier.classifyCycle(cycle).status()).isEqualTo(RunListStatus.DONE);
        assertThat(RunClassifier.classifyCycle(cycle).note()).isNull();

        cycle.setStatus(PipelineRunStatus.DEGRADED);
        cycle.setFailureReason("2 batches failed");
        RunClassifier.Outcome degraded = RunClassifier.classifyCycle(cycle);
        assertThat(degraded.status()).isEqualTo(RunListStatus.DEGRADED);
        assertThat(degraded.note()).isEqualTo("2 batches failed");
        assertThat(degraded.tone()).isEqualTo(NoteTone.WARN);

        cycle.setStatus(PipelineRunStatus.FAILED);
        assertThat(RunClassifier.classifyCycle(cycle).status()).isEqualTo(RunListStatus.FAILED);
        assertThat(RunClassifier.classifyCycle(cycle).tone()).isEqualTo(NoteTone.ERROR);
    }

    @Test
    @DisplayName("only DEGRADED, PARTIAL and FAILED need attention")
    void needsAttention() {
        assertThat(RunListStatus.RUNNING.needsAttention()).isFalse();
        assertThat(RunListStatus.DONE.needsAttention()).isFalse();
        assertThat(RunListStatus.DEGRADED.needsAttention()).isTrue();
        assertThat(RunListStatus.PARTIAL.needsAttention()).isTrue();
        assertThat(RunListStatus.FAILED.needsAttention()).isTrue();
    }
}
