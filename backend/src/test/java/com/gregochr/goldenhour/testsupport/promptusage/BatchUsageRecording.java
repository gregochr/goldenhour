package com.gregochr.goldenhour.testsupport.promptusage;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.model.TokenUsage;

import java.util.List;

/**
 * Records the tokens a real-API batch test spent, read from its {@code api_call_log} rows, into a
 * {@link PromptUsageRecorder} (docs/engineering/ci-spend-plan.md, C4).
 *
 * <p>The daily real-API smoke sends its request through the Batch API with the production client bean, so
 * {@link RecordingAnthropicApiClient} never sees it. Its batch result handler logged one row per request instead.
 *
 * <p>The recording is valid only for a request that uses no 1-hour cache write: {@code api_call_log} cannot store
 * that portion, so it is recorded as zero. The smoke's request is built by {@code buildForecastRequest}, the plain
 * path with no 1-hour TTL. Other batch paths do use the 1-hour TTL, so this helper must not be reused for them.
 *
 * <p>Never throws: recording must not turn an inconclusive skip or a pass into an error.
 */
public final class BatchUsageRecording {

    private BatchUsageRecording() {
    }

    /**
     * Records every batch row that carries a model and token counts. Null token columns count as zero. Rows that are
     * not batch rows, or have no model, are ignored. With no qualifying row nothing is recorded, so no shard is
     * written: no row means unknown, never zero.
     *
     * @param rows     the logged calls
     * @param recorder where the usage goes
     */
    public static void record(List<ApiCallLogEntity> rows, PromptUsageRecorder recorder) {
        try {
            for (ApiCallLogEntity row : rows) {
                if (!Boolean.TRUE.equals(row.getIsBatch()) || row.getEvaluationModel() == null
                        || (row.getInputTokens() == null && row.getOutputTokens() == null)) {
                    continue;
                }
                recorder.recordCall(row.getEvaluationModel().getModelId(), new TokenUsage(
                        orZero(row.getInputTokens()), orZero(row.getOutputTokens()),
                        orZero(row.getCacheCreationInputTokens()), orZero(row.getCacheReadInputTokens()), 0));
            }
        } catch (RuntimeException e) {
            System.err.println("batch usage not recorded: " + e.getClass().getSimpleName());
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
