package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * Unit tests for the factory methods on {@link ResultContext} and {@link ClaudeBatchOutcome}.
 */
class OutcomeFactoriesTest {

    @Test
    @DisplayName("ResultContext.forBatch with a pipeline run carries it and no submission instant")
    void forBatch_withPipelineRun_hasNoSubmissionInstant() {
        ResultContext context = ResultContext.forBatch(7L, "msgbatch_x", 249L, BatchTriggerSource.SCHEDULED);

        assertThat(context.jobRunId()).isEqualTo(7L);
        assertThat(context.batchId()).isEqualTo("msgbatch_x");
        assertThat(context.pipelineRunId()).isEqualTo(249L);
        assertThat(context.submissionInstant()).isNull();
        assertThat(context.triggerSource()).isEqualTo(BatchTriggerSource.SCHEDULED);
    }

    @Test
    @DisplayName("ResultContext.forBatch with a submission instant carries pipeline run and instant")
    void forBatch_withSubmissionInstant_carriesBoth() {
        Instant at = Instant.parse("2026-04-16T01:05:00Z");

        ResultContext context = ResultContext.forBatch(7L, "msgbatch_x", 249L, at, BatchTriggerSource.ADMIN);

        assertThat(context.pipelineRunId()).isEqualTo(249L);
        assertThat(context.submissionInstant()).isEqualTo(at);
    }

    @Test
    @DisplayName("ResultContext.forSync has no batch id, pipeline run or submission instant")
    void forSync_hasOnlyJobRunAndTrigger() {
        ResultContext context = ResultContext.forSync(7L, BatchTriggerSource.ADMIN);

        assertThat(context.jobRunId()).isEqualTo(7L);
        assertThat(context.batchId()).isNull();
        assertThat(context.pipelineRunId()).isNull();
        assertThat(context.submissionInstant()).isNull();
    }

    @Test
    @DisplayName("ClaudeBatchOutcome.failure with billed usage keeps the usage and has no raw text or model")
    void failure_withBilledUsage_keepsUsage() {
        TokenUsage billed = new TokenUsage(500, 200, 0, 1000);

        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure(
                "fc-1-2026-04-16-SUNRISE", "REFUSAL", "refusal", "declined", billed);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.status()).isEqualTo("REFUSAL");
        assertThat(outcome.errorType()).isEqualTo("refusal");
        assertThat(outcome.errorMessage()).isEqualTo("declined");
        assertThat(outcome.tokenUsage()).isSameAs(billed);
        assertThat(outcome.rawText()).isNull();
        assertThat(outcome.model()).isNull();
    }

    @Test
    @DisplayName("ClaudeBatchOutcome.failure without usage carries none")
    void failure_withoutUsage_hasNone() {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.failure("id", "EXPIRED", "expired", "late");

        assertThat(outcome.tokenUsage()).isNull();
    }
}
