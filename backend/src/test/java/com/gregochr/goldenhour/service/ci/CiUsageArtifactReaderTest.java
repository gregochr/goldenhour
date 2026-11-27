package com.gregochr.goldenhour.service.ci;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CiUsageArtifactReader}: the happy path and every rule of the plan's "Untrusted input"
 * section, each rejected with a fixed reason that never echoes file content.
 */
class CiUsageArtifactReaderTest {

    private static final long RUN_ID = 123456789L;
    private static final int ATTEMPT = 2;
    private static final String SECRET_MARKER = "TOP-SECRET-FILE-CONTENT";

    private final CiUsageArtifactReader reader = new CiUsageArtifactReader();

    private static String model(String id, String calls, String tokens) {
        return "{\"model\":\"" + id + "\",\"calls\":" + calls + ",\"erroredCalls\":1,\"input\":" + tokens
                + ",\"output\":38000,\"cacheRead\":434000,\"cacheWrite\":21000,\"cacheWrite1h\":9000}";
    }

    private static String doc(String schema, String suite, String runId, String attempt, String models) {
        return "{\"schema\":" + schema + ",\"suite\":\"" + suite + "\",\"runId\":" + runId + ",\"runAttempt\":"
                + attempt + ",\"headSha\":\"abc\",\"pr\":1912,\"startedAt\":\"2026-11-27T10:00:00Z\","
                + "\"finishedAt\":\"2026-11-27T10:05:00Z\",\"complete\":true,\"cases\":42,\"failed\":3,\"models\":["
                + models + "]}";
    }

    private static String valid(String models) {
        return doc("1", "sky", String.valueOf(RUN_ID), String.valueOf(ATTEMPT), models);
    }

    private static String validDoc() {
        return valid(model("claude-sonnet-5-5", "42", "612000"));
    }

    private static byte[] zip(String entryName, String content) {
        return zipOf(new String[] {entryName}, new String[] {content});
    }

    private static byte[] zipOf(String[] names, String[] contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] skyZip(String json) {
        return zip(CiUsageArtifactReader.entryName("sky"), json);
    }

    private void assertRejected(byte[] zip, String reasonPart) {
        assertThatThrownBy(() -> reader.read(zip, "sky", RUN_ID, ATTEMPT))
                .isInstanceOf(InvalidArtifactException.class)
                .hasMessageContaining(reasonPart)
                .message().doesNotContain(SECRET_MARKER);
    }

    @Test
    @DisplayName("a valid artifact reads into counts, with identity left to the API")
    void readsAValidArtifact() throws InvalidArtifactException {
        CiUsageArtifactReader.Usage usage = reader.read(skyZip(validDoc()), "sky", RUN_ID, ATTEMPT);

        assertThat(usage.cases()).isEqualTo(42);
        assertThat(usage.failed()).isEqualTo(3);
        assertThat(usage.models()).containsExactly(new CiUsageArtifactReader.ModelUsage("claude-sonnet-5-5", 42, 1,
                612_000L, 38_000L, 434_000L, 21_000L, 9_000L));
    }

    @Test
    @DisplayName("an artifact with no models and no case counts is valid (the suite recorded nothing)")
    void readsEmptyModelsAndNullCases() throws InvalidArtifactException {
        String json = "{\"schema\":1,\"suite\":\"ask\",\"runId\":123456789,\"runAttempt\":2,\"headSha\":null,"
                + "\"pr\":null,\"startedAt\":null,\"finishedAt\":\"2026-11-27T10:05:00Z\",\"complete\":false,"
                + "\"cases\":null,\"failed\":null,\"models\":[]}";

        CiUsageArtifactReader.Usage usage = reader.read(zip(CiUsageArtifactReader.entryName("ask"), json), "ask",
                RUN_ID, ATTEMPT);

        assertThat(usage.models()).isEmpty();
        assertThat(usage.cases()).isNull();
        assertThat(usage.failed()).isNull();
    }

    @Test
    @DisplayName("an unsupported schema, another suite, run id or attempt are each rejected")
    void rejectsIdentityMismatches() {
        assertRejected(skyZip(doc("2", "sky", "123456789", "2", "")), "schema");
        assertRejected(skyZip(doc("null", "sky", "123456789", "2", "")), "schema");
        assertRejected(skyZip(doc("1", "ask", "123456789", "2", "")), "suite");
        assertRejected(skyZip(doc("1", "sky", "999", "2", "")), "run id");
        assertRejected(skyZip(doc("1", "sky", "null", "2", "")), "run id");
        assertRejected(skyZip(doc("1", "sky", "123456789", "1", "")), "run attempt");
        assertRejected(skyZip(doc("1", "sky", "123456789", "null", "")), "run attempt");
    }

    @Test
    @DisplayName("more than ten models, a bad or repeated model id, or a null entry are rejected")
    void rejectsModelListProblems() {
        StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",").append(model("m" + i, "1", "1"));
        }
        assertRejected(skyZip(valid(eleven.toString())), "more than");
        assertRejected(skyZip(valid(model("Claude_Sonnet", "1", "1"))), "model id");
        assertRejected(skyZip(valid(model("a".repeat(65), "1", "1"))), "model id");
        assertRejected(skyZip(valid(model("", "1", "1"))), "model id");
        assertRejected(skyZip(valid("null")), "model id");
        assertRejected(skyZip(valid("{\"calls\":1,\"erroredCalls\":0,\"input\":1,\"output\":1,\"cacheRead\":1,"
                + "\"cacheWrite\":1,\"cacheWrite1h\":0}")), "model id");
        assertRejected(skyZip(valid(model("m1", "1", "1") + "," + model("m1", "1", "1"))), "twice");
        assertRejected(skyZip(doc("1", "sky", "123456789", "2", "").replace("\"models\":[]", "\"models\":null")),
                "models is missing");
    }

    @Test
    @DisplayName("negative, huge, missing or non-integer counts are rejected")
    void rejectsBadCounts() {
        assertRejected(skyZip(valid(model("m1", "1", "-1"))), "input");
        assertRejected(skyZip(valid(model("m1", "1", "100000001"))), "input");
        assertRejected(skyZip(valid(model("m1", "-1", "1"))), "calls");
        assertRejected(skyZip(valid(model("m1", "5001", "1"))), "calls");
        assertRejected(skyZip(valid(model("m1", "1", "null"))), "input");
        assertRejected(skyZip(valid(model("m1", "1.5", "1"))), "schema");
        assertRejected(skyZip(valid(model("m1", "1", "1.5"))), "schema");
        assertRejected(skyZip(valid(model("m1", "\"1\"", "1"))), "schema");
        assertRejected(skyZip(valid(model("m1", "1", "99999999999999999999"))), "schema");
        assertRejected(skyZip(validDoc().replace("\"cases\":42", "\"cases\":-5")), "cases");
        assertRejected(skyZip(validDoc().replace("\"failed\":3", "\"failed\":100001")), "failed");
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"erroredCalls\":1", "\"erroredCalls\":5001"))),
                "erroredCalls");
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"output\":38000", "\"output\":-1"))), "output");
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"cacheRead\":434000", "\"cacheRead\":-1"))),
                "cacheRead");
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"cacheWrite\":21000", "\"cacheWrite\":-1"))),
                "cacheWrite");
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"cacheWrite1h\":9000", "\"cacheWrite1h\":-1"))),
                "cacheWrite1h");
    }

    @Test
    @DisplayName("a 1-hour cache write larger than the total cache write is rejected")
    void rejectsOneHourAboveTotal() {
        assertRejected(skyZip(valid(model("m1", "1", "1").replace("\"cacheWrite1h\":9000", "\"cacheWrite1h\":21001"))),
                "exceeds cacheWrite");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json at all TOP-SECRET-FILE-CONTENT",
        "[]",
        "{\"schema\":1} trailing TOP-SECRET-FILE-CONTENT",
        "{\"schema\":1,\"schema\":1}",
        "{\"extra\":\"TOP-SECRET-FILE-CONTENT\"}",
        ""
    })
    @DisplayName("garbage, an unknown field, a duplicate key and trailing content are rejected without echoing it")
    void rejectsMalformedJson(String content) {
        assertRejected(skyZip(content), "does not match the schema");
    }

    @Test
    @DisplayName("an unknown top-level field is rejected by the strict read")
    void rejectsUnknownField() {
        assertRejected(skyZip(validDoc().replace("\"complete\":true", "\"complete\":true,\"extra\":1")),
                "does not match the schema");
    }

    @Test
    @DisplayName("a zip with no entry, two entries or the wrong entry name is rejected")
    void rejectsEntryProblems() {
        assertRejected(zipOf(new String[0], new String[0]), "no entry");
        assertRejected(new byte[] {1, 2, 3}, "no entry");
        assertRejected(zip("prompt-regression-usage-ask.json", validDoc()), "wrong name");
        assertRejected(zip("../" + CiUsageArtifactReader.entryName("sky"), validDoc()), "wrong name");
        assertRejected(zipOf(new String[] {CiUsageArtifactReader.entryName("sky"), "second.json"},
                new String[] {validDoc(), "{}"}), "more than one entry");
    }

    @Test
    @DisplayName("an entry past 64 KB uncompressed is rejected, even when it compresses to almost nothing")
    void rejectsZipBomb() {
        String bomb = validDoc() + " ".repeat(CiUsageArtifactReader.MAX_UNCOMPRESSED_BYTES * 4);
        byte[] zip = skyZip(bomb);

        assertThat(zip.length).isLessThan(2_000);
        assertRejected(zip, "uncompressed");
    }

    @Test
    @DisplayName("an entry of exactly 64 KB is still read")
    void acceptsTheLimit() throws InvalidArtifactException {
        String json = validDoc();
        String padded = json + " ".repeat(CiUsageArtifactReader.MAX_UNCOMPRESSED_BYTES - json.length());

        assertThat(reader.read(skyZip(padded), "sky", RUN_ID, ATTEMPT).models()).hasSize(1);
    }

    @Test
    @DisplayName("a corrupted entry (bad CRC) and a truncated zip are rejected as unreadable")
    void rejectsCorruptZips() {
        byte[] good = skyZip(validDoc());
        byte[] corrupt = good.clone();
        int dataStart = 30 + CiUsageArtifactReader.entryName("sky").length() + 5;
        corrupt[dataStart] ^= 0x55;
        byte[] truncated = Arrays.copyOf(good, 45 + CiUsageArtifactReader.entryName("sky").length());

        assertThatThrownBy(() -> reader.read(corrupt, "sky", RUN_ID, ATTEMPT))
                .isInstanceOf(InvalidArtifactException.class).hasMessageContaining("zip");
        assertThatThrownBy(() -> reader.read(truncated, "sky", RUN_ID, ATTEMPT))
                .isInstanceOf(InvalidArtifactException.class).hasMessageContaining("zip");
    }
}
