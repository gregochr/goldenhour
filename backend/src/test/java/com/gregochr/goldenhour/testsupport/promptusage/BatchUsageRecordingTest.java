package com.gregochr.goldenhour.testsupport.promptusage;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BatchUsageRecording}.
 */
class BatchUsageRecordingTest {

    @TempDir
    Path tmp;

    private static ApiCallLogEntity row(boolean batch, EvaluationModel model, Long input, Long output, Long write,
            Long read) {
        ApiCallLogEntity e = new ApiCallLogEntity();
        e.setIsBatch(batch);
        e.setEvaluationModel(model);
        e.setInputTokens(input);
        e.setOutputTokens(output);
        e.setCacheCreationInputTokens(write);
        e.setCacheReadInputTokens(read);
        return e;
    }

    @Test
    @DisplayName("records a batch row keyed on the model's API id, with no 1-hour cache write")
    void recordsBatchRow() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);

        BatchUsageRecording.record(List.of(row(true, EvaluationModel.HAIKU, 900L, 120L, 30L, 40L)), recorder);

        assertThat(recorder.toJson()).isEqualTo("{\"models\":[{\"model\":\"" + EvaluationModel.HAIKU.getModelId()
                + "\",\"calls\":1,\"erroredCalls\":0,\"input\":900,\"output\":120,\"cacheRead\":40,"
                + "\"cacheWrite\":30,\"cacheWrite1h\":0}]}\n");
    }

    @Test
    @DisplayName("treats null cache columns as zero")
    void nullCacheColumnsAreZero() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);

        BatchUsageRecording.record(List.of(row(true, EvaluationModel.HAIKU, 5L, 6L, null, null)), recorder);

        assertThat(recorder.toJson()).contains("\"input\":5,\"output\":6,\"cacheRead\":0,\"cacheWrite\":0");
    }

    @Test
    @DisplayName("ignores non-batch rows, rows without a model and rows without token counts")
    void ignoresUnusableRows() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);

        BatchUsageRecording.record(List.of(
                row(false, EvaluationModel.HAIKU, 1L, 1L, 0L, 0L),
                row(true, null, 1L, 1L, 0L, 0L),
                row(true, EvaluationModel.HAIKU, null, null, null, null)), recorder);

        assertThat(recorder.toJson()).isEqualTo("{\"models\":[]}\n");
    }

    @Test
    @DisplayName("writes no shard when there is no usable row: no row means unknown, never zero")
    void noRowWritesNoShard() throws Exception {
        PromptUsageRecorder recorder = new PromptUsageRecorder(tmp);

        BatchUsageRecording.record(List.of(), recorder);

        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("writes a shard when a row exists")
    void rowWritesShard() throws Exception {
        PromptUsageRecorder recorder = new PromptUsageRecorder(tmp);

        BatchUsageRecording.record(List.of(row(true, EvaluationModel.HAIKU, 1L, 2L, 0L, 0L)), recorder);

        assertThat(Files.readString(recorder.shardPath())).contains(EvaluationModel.HAIKU.getModelId());
    }

    @Test
    @DisplayName("never throws, even when the rows themselves misbehave")
    void neverThrows() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);
        List<ApiCallLogEntity> hostile = new ArrayList<>() {
            @Override
            public java.util.Iterator<ApiCallLogEntity> iterator() {
                throw new IllegalStateException("boom");
            }
        };

        BatchUsageRecording.record(hostile, recorder);

        assertThat(recorder.toJson()).isEqualTo("{\"models\":[]}\n");
    }
}
