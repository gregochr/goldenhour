package com.gregochr.goldenhour.testsupport.promptusage;

import com.gregochr.goldenhour.model.TokenUsage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Counts the tokens the paid prompt-regression tests spend, per requested model, and mirrors the counts to a
 * per-JVM shard file (docs/engineering/ci-spend-plan.md, C1).
 *
 * <p>Token counts only: no prompt text, model output, keys or headers are ever held or written. Several JVMs
 * record in one CI job (surefire forks, and the sky job runs two Maven invocations), so each JVM owns one shard,
 * {@code shard-<pid>-<uuid>.json}, rewritten whole after every recorded call (temp file, then atomic move). A job
 * cancelled mid-run therefore still leaves its spend so far, and {@code scripts/prompt-regression-usage.py} sums
 * the shards. With {@code PROMPT_REGRESSION_USAGE_DIR} unset nothing is written.
 *
 * <p>Thread-safe: Ask records from worker threads.
 */
public final class PromptUsageRecorder {

    /** The environment variable naming the directory the shards go in. */
    public static final String USAGE_DIR_ENV = "PROMPT_REGRESSION_USAGE_DIR";

    private static final PromptUsageRecorder SHARED = new PromptUsageRecorder(directoryFromEnvironment());

    private final Path directory;
    private final Path shard;
    private final Map<String, Counters> byModel = new TreeMap<>();

    /**
     * Creates a recorder.
     *
     * @param directory where this JVM's shard goes, or null to record in memory only
     */
    public PromptUsageRecorder(Path directory) {
        this.directory = directory;
        this.shard = directory == null ? null
                : directory.resolve("shard-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".json");
    }

    /**
     * The process-wide recorder, writing to {@code $PROMPT_REGRESSION_USAGE_DIR} when that is set.
     *
     * @return the shared recorder
     */
    public static PromptUsageRecorder shared() {
        return SHARED;
    }

    /**
     * Records one successful call.
     *
     * @param model the requested model id
     * @param usage the call's (clamped) token usage
     */
    public synchronized void recordCall(String model, TokenUsage usage) {
        Counters c = byModel.computeIfAbsent(model, m -> new Counters());
        c.calls++;
        c.input += usage.inputTokens();
        c.output += usage.outputTokens();
        c.cacheRead += usage.cacheReadInputTokens();
        c.cacheWrite += usage.cacheCreationInputTokens();
        c.cacheWrite1h += usage.cacheCreationOneHourTokens();
        writeShard();
    }

    /**
     * Records one call that threw. A client timeout can still be billed, so the spend is a slight under-count.
     *
     * @param model the requested model id
     */
    public synchronized void recordError(String model) {
        byModel.computeIfAbsent(model, m -> new Counters()).erroredCalls++;
        writeShard();
    }

    /**
     * The shard file this recorder writes, if it has a directory.
     *
     * @return the shard path, or null when nothing is written
     */
    public Path shardPath() {
        return shard;
    }

    /**
     * The shard's JSON as it stands now: counts per model, nothing else.
     *
     * @return the JSON text
     */
    public synchronized String toJson() {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, Counters> e : byModel.entrySet()) {
            Counters c = e.getValue();
            rows.add("{\"model\":\"" + escape(e.getKey()) + "\",\"calls\":" + c.calls
                    + ",\"erroredCalls\":" + c.erroredCalls + ",\"input\":" + c.input
                    + ",\"output\":" + c.output + ",\"cacheRead\":" + c.cacheRead
                    + ",\"cacheWrite\":" + c.cacheWrite + ",\"cacheWrite1h\":" + c.cacheWrite1h + "}");
        }
        return "{\"models\":[" + String.join(",", rows) + "]}\n";
    }

    private void writeShard() {
        if (shard == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve(shard.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, shard, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Recording must never fail a paid test: the usage is simply not captured.
            System.err.println("prompt usage shard not written: " + e.getClass().getSimpleName());
        }
    }

    private static Path directoryFromEnvironment() {
        String dir = System.getenv(USAGE_DIR_ENV);
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Mutable per-model counters, guarded by the recorder's lock. */
    private static final class Counters {
        private long calls;
        private long erroredCalls;
        private long input;
        private long output;
        private long cacheRead;
        private long cacheWrite;
        private long cacheWrite1h;
    }
}
