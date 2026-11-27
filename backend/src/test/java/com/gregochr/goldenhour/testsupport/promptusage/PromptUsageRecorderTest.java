package com.gregochr.goldenhour.testsupport.promptusage;

import com.gregochr.goldenhour.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PromptUsageRecorder}.
 */
class PromptUsageRecorderTest {

    private static final String SONNET = "claude-sonnet-5-5";
    private static final String HAIKU = "claude-haiku-4-5";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("sums the clamped usage per model and counts errors separately")
    void sumsPerModel() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);
        recorder.recordCall(SONNET, new TokenUsage(10, 20, 30, 40, 5));
        recorder.recordCall(SONNET, new TokenUsage(1, 2, 3, 4, 99));
        recorder.recordCall(HAIKU, new TokenUsage(7, 8, 0, 0));
        recorder.recordError(HAIKU);

        assertThat(recorder.toJson()).isEqualTo("{\"models\":["
                + "{\"model\":\"claude-haiku-4-5\",\"calls\":1,\"erroredCalls\":1,\"input\":7,\"output\":8,"
                + "\"cacheRead\":0,\"cacheWrite\":0,\"cacheWrite1h\":0},"
                + "{\"model\":\"claude-sonnet-5-5\",\"calls\":2,\"erroredCalls\":0,\"input\":11,\"output\":22,"
                + "\"cacheRead\":44,\"cacheWrite\":33,\"cacheWrite1h\":8}]}\n");
    }

    @Test
    @DisplayName("writes nothing when no directory is configured")
    void noDirectoryWritesNothing() throws IOException {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);
        recorder.recordCall(SONNET, new TokenUsage(1, 1, 0, 0));

        assertThat(recorder.shardPath()).isNull();
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("creates the directory and rewrites its one shard after every call, leaving no temp file")
    void rewritesShardAfterEveryCall() throws IOException {
        Path dir = tmp.resolve("not-yet").resolve("usage");
        PromptUsageRecorder recorder = new PromptUsageRecorder(dir);

        recorder.recordCall(SONNET, new TokenUsage(1, 2, 0, 0));
        assertThat(Files.readString(recorder.shardPath())).contains("\"calls\":1").contains("\"input\":1");

        recorder.recordCall(SONNET, new TokenUsage(3, 4, 0, 0));
        assertThat(Files.readString(recorder.shardPath())).contains("\"calls\":2").contains("\"input\":4");

        recorder.recordError(SONNET);
        assertThat(Files.readString(recorder.shardPath())).contains("\"erroredCalls\":1");

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names).hasSize(1);
            assertThat(names.get(0)).matches("shard-\\d+-[0-9a-f-]{36}\\.json");
        }
    }

    @Test
    @DisplayName("two recorders in one directory write two shards")
    void eachRecorderOwnsAShard() throws IOException {
        PromptUsageRecorder a = new PromptUsageRecorder(tmp);
        PromptUsageRecorder b = new PromptUsageRecorder(tmp);
        a.recordCall(SONNET, new TokenUsage(1, 1, 0, 0));
        b.recordCall(SONNET, new TokenUsage(1, 1, 0, 0));

        assertThat(a.shardPath()).isNotEqualTo(b.shardPath());
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).hasSize(2);
        }
    }

    @Test
    @DisplayName("an unwritable directory never fails the recording")
    void unwritableDirectoryIsSwallowed() throws IOException {
        Path blocker = Files.writeString(tmp.resolve("file"), "x");
        PromptUsageRecorder recorder = new PromptUsageRecorder(blocker.resolve("sub"));

        recorder.recordCall(SONNET, new TokenUsage(5, 6, 0, 0));

        assertThat(recorder.toJson()).contains("\"input\":5");
    }

    @Test
    @DisplayName("escapes quotes and backslashes in a model id")
    void escapesModelId() {
        PromptUsageRecorder recorder = new PromptUsageRecorder(null);
        recorder.recordCall("a\"b\\c", new TokenUsage(1, 1, 0, 0));

        assertThat(recorder.toJson()).contains("\"model\":\"a\\\"b\\\\c\"");
    }

    @Test
    @DisplayName("concurrent recording loses no counts")
    void concurrentRecordingLosesNothing() throws Exception {
        PromptUsageRecorder recorder = new PromptUsageRecorder(tmp);
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    recorder.recordCall(SONNET, new TokenUsage(1, 2, 3, 4, 1));
                    recorder.recordError(SONNET);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int total = threads * perThread;
        assertThat(Files.readString(recorder.shardPath())).isEqualTo("{\"models\":["
                + "{\"model\":\"claude-sonnet-5-5\",\"calls\":" + total + ",\"erroredCalls\":" + total
                + ",\"input\":" + total + ",\"output\":" + (2 * total) + ",\"cacheRead\":" + (4 * total)
                + ",\"cacheWrite\":" + (3 * total) + ",\"cacheWrite1h\":" + total + "}]}\n");
    }

    @Test
    @DisplayName("the shared recorder is one process-wide instance")
    void sharedIsASingleton() {
        assertThat(PromptUsageRecorder.shared()).isSameAs(PromptUsageRecorder.shared());
    }
}
