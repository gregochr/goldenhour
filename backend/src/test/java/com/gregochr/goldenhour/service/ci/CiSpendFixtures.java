package com.gregochr.goldenhour.service.ci;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JSON and zip builders for the CI-spend tests: what GitHub's Actions API answers and what the usage artifact holds.
 */
final class CiSpendFixtures {

    /** The Sonnet 5.5 model id the C1 recorder keys usage by. */
    static final String SONNET_55_ID = "claude-sonnet-5-5";

    private CiSpendFixtures() {
    }

    /** A one-run listing. */
    static String runsJson(long id, int latestAttempt) {
        return "{\"total_count\":1,\"workflow_runs\":[{\"id\":" + id + ",\"run_attempt\":" + latestAttempt + "}]}";
    }

    /** An empty listing. */
    static String noRunsJson() {
        return "{\"total_count\":0,\"workflow_runs\":[]}";
    }

    /** A listing of runs 1..count, each on attempt 1 (for the pagination test). */
    static String manyRunsJson(int firstId, int count) {
        StringBuilder sb = new StringBuilder("{\"total_count\":1000,\"workflow_runs\":[");
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":").append(firstId + i).append(",\"run_attempt\":1}");
        }
        return sb.append("]}").toString();
    }

    /** One attempt of a run, as the attempts endpoint answers. */
    static String attemptJson(String status, String event, String sha, String displayTitle, String pullRequests) {
        return "{\"id\":1,\"status\":\"" + status + "\",\"conclusion\":\"success\",\"event\":\"" + event
                + "\",\"head_sha\":\"" + sha + "\",\"display_title\":\"" + displayTitle + "\",\"pull_requests\":"
                + pullRequests + ",\"run_started_at\":\"2026-11-27T09:00:00Z\","
                + "\"updated_at\":\"2026-11-27T11:30:00Z\"}";
    }

    /** The same attempt with an explicit last-updated time. */
    static String attemptJsonUpdatedAt(String updatedAt, String pullRequests) {
        return attemptJson("completed", "pull_request", "0123456789abcdef0123456789abcdef01234567", "feat: a title",
                pullRequests).replace("2026-11-27T11:30:00Z", updatedAt);
    }

    /** One job with a paid step; {@code stepStartedAt} may be null (the step never started). */
    static String jobJson(String name, int runAttempt, String conclusion, String stepName, String stepStartedAt) {
        return "{\"name\":\"" + name + "\",\"run_attempt\":" + runAttempt + ",\"conclusion\":\"" + conclusion
                + "\",\"started_at\":\"2026-11-27T10:00:00Z\",\"completed_at\":\"2026-11-27T10:05:00Z\","
                + "\"steps\":[{\"name\":\"Set up JDK 25\",\"started_at\":\"2026-11-27T10:00:30Z\"},"
                + "{\"name\":\"" + stepName + "\",\"started_at\":"
                + (stepStartedAt == null ? "null" : "\"" + stepStartedAt + "\"") + "}]}";
    }

    /** A job listing. */
    static String jobsJson(String... jobs) {
        return "{\"total_count\":" + jobs.length + ",\"jobs\":[" + String.join(",", jobs) + "]}";
    }

    /** A one-artifact listing. */
    static String artifactsJson(String name, long id, boolean expired, long sizeInBytes) {
        return "{\"total_count\":1,\"artifacts\":[{\"id\":" + id + ",\"name\":\"" + name + "\",\"expired\":" + expired
                + ",\"size_in_bytes\":" + sizeInBytes + "}]}";
    }

    /** An empty artifact listing. */
    static String noArtifactsJson() {
        return "{\"total_count\":0,\"artifacts\":[]}";
    }

    /** One model entry of the artifact. */
    static String modelJson(String id, int calls, int errored, long input, long output, long cacheRead,
            long cacheWrite, long cacheWrite1h) {
        return "{\"model\":\"" + id + "\",\"calls\":" + calls + ",\"erroredCalls\":" + errored + ",\"input\":"
                + input + ",\"output\":" + output + ",\"cacheRead\":" + cacheRead + ",\"cacheWrite\":" + cacheWrite
                + ",\"cacheWrite1h\":" + cacheWrite1h + "}";
    }

    /** The artifact document for a suite, run and attempt, with the given models array content. */
    static String artifactDoc(String suite, long runId, int attempt, String models) {
        return "{\"schema\":1,\"suite\":\"" + suite + "\",\"runId\":" + runId + ",\"runAttempt\":" + attempt
                + ",\"headSha\":\"abc\",\"pr\":1912,\"startedAt\":\"2026-11-27T10:00:00Z\","
                + "\"finishedAt\":\"2026-11-27T10:05:00Z\",\"complete\":true,\"cases\":42,\"failed\":3,"
                + "\"models\":[" + models + "]}";
    }

    /** A zip holding one entry. */
    static byte[] zip(String entryName, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A zip holding two entries. */
    static byte[] twoEntryZip(String first, String second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(first));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(second));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
