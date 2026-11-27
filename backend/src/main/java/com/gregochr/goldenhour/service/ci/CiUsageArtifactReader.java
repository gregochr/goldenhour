package com.gregochr.goldenhour.service.ci;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads and validates a downloaded CI usage artifact (docs/engineering/ci-spend-plan.md, C2, "Untrusted input").
 *
 * <p>The zip is read in memory with {@link ZipInputStream} through a bounded read that fails past
 * {@value #MAX_UNCOMPRESSED_BYTES} bytes uncompressed ({@code entry.getSize()} is never trusted, nothing is written
 * to disk). It must hold exactly one entry named {@code prompt-regression-usage-<suite>.json}. The JSON is read
 * strictly (no unknown fields, no duplicate keys, no trailing tokens, no string-to-number coercion) into records and
 * then checked: {@code schema == 1}, the suite and the API's run id and attempt match, at most
 * {@value #MAX_MODELS} models with ids matching {@code [a-z0-9.-]{1,64}}, every token count in
 * {@code 0..}{@value #MAX_TOKENS}, and {@code calls} and {@code erroredCalls} in {@code 0..}{@value #MAX_CALLS}.
 *
 * <p>Every failure is an {@link InvalidArtifactException} with a fixed reason; no file content is ever echoed.
 */
@Component
public class CiUsageArtifactReader {

    /** The most uncompressed bytes the single entry may hold. */
    public static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024;

    /** The most models one artifact may list. */
    static final int MAX_MODELS = 10;

    /** The upper bound of every token count. */
    static final long MAX_TOKENS = 100_000_000L;

    /** The upper bound of {@code calls} and {@code erroredCalls}. */
    static final int MAX_CALLS = 5000;

    /** The upper bound of {@code cases} and {@code failed}. */
    static final int MAX_CASES = 100_000;

    private static final int SCHEMA = 1;
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9.-]{1,64}");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * One model's validated token counts.
     *
     * @param model        the requested model id
     * @param calls        calls recorded
     * @param erroredCalls calls that threw
     * @param input        standard input tokens
     * @param output       output tokens
     * @param cacheRead    cache-read tokens
     * @param cacheWrite   cache-write tokens (either lifetime)
     * @param cacheWrite1h the 1-hour subset of {@code cacheWrite}
     */
    public record ModelUsage(String model, int calls, int erroredCalls, long input, long output, long cacheRead,
            long cacheWrite, long cacheWrite1h) {
    }

    /**
     * The validated content of an artifact. Identity is deliberately absent: it comes from the GitHub API.
     *
     * @param cases  test cases run, or null when the file gave none
     * @param failed test cases failed or errored, or null when the file gave none
     * @param models the per-model usage (possibly empty)
     */
    public record Usage(Integer cases, Integer failed, List<ModelUsage> models) {
    }

    /** The wire shape of the artifact; every field is declared so that an extra one fails the strict read. */
    record Doc(Integer schema, String suite, Long runId, Integer runAttempt, String headSha, Integer pr,
            String startedAt, String finishedAt, Boolean complete, Integer cases, Integer failed,
            List<ModelDoc> models) {
    }

    /** The wire shape of one model entry. */
    record ModelDoc(String model, Integer calls, Integer erroredCalls, Long input, Long output, Long cacheRead,
            Long cacheWrite, Long cacheWrite1h) {
    }

    /**
     * The entry name an artifact for a suite must hold.
     *
     * @param suite {@code sky} or {@code ask}
     * @return the exact entry name
     */
    public static String entryName(String suite) {
        return "prompt-regression-usage-" + suite + ".json";
    }

    /**
     * Reads and validates one artifact.
     *
     * @param zip           the downloaded zip bytes (already size-capped by the downloader)
     * @param suite         the suite the artifact must describe
     * @param apiRunId      the workflow run id from the GitHub API
     * @param apiRunAttempt the attempt number from the GitHub API
     * @return the validated content
     * @throws InvalidArtifactException when any rule fails
     */
    public Usage read(byte[] zip, String suite, long apiRunId, int apiRunAttempt) throws InvalidArtifactException {
        byte[] json = singleEntry(zip, entryName(suite));
        Doc doc;
        try {
            doc = mapper.readValue(json, Doc.class);
        } catch (IOException e) {
            throw new InvalidArtifactException("the JSON does not match the schema");
        }
        return validate(doc, suite, apiRunId, apiRunAttempt);
    }

    private byte[] singleEntry(byte[] zip, String expectedName) throws InvalidArtifactException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                throw new InvalidArtifactException("the zip holds no entry");
            }
            if (!expectedName.equals(entry.getName())) {
                throw new InvalidArtifactException("the zip entry has the wrong name");
            }
            byte[] bytes = in.readNBytes(MAX_UNCOMPRESSED_BYTES + 1);
            if (bytes.length > MAX_UNCOMPRESSED_BYTES) {
                throw new InvalidArtifactException("the entry exceeds " + MAX_UNCOMPRESSED_BYTES
                        + " bytes uncompressed");
            }
            if (in.getNextEntry() != null) {
                throw new InvalidArtifactException("the zip holds more than one entry");
            }
            return bytes;
        } catch (ZipException e) {
            throw new InvalidArtifactException("the zip is malformed");
        } catch (IOException e) {
            throw new InvalidArtifactException("the zip could not be read");
        }
    }

    private Usage validate(Doc doc, String suite, long apiRunId, int apiRunAttempt)
            throws InvalidArtifactException {
        if (doc.schema() == null || doc.schema() != SCHEMA) {
            throw new InvalidArtifactException("unsupported schema");
        }
        if (!suite.equals(doc.suite())) {
            throw new InvalidArtifactException("the suite does not match");
        }
        if (doc.runId() == null || doc.runId() != apiRunId) {
            throw new InvalidArtifactException("the run id does not match the API's");
        }
        if (doc.runAttempt() == null || doc.runAttempt() != apiRunAttempt) {
            throw new InvalidArtifactException("the run attempt does not match the API's");
        }
        requireRange(doc.cases(), MAX_CASES, "cases");
        requireRange(doc.failed(), MAX_CASES, "failed");
        if (doc.models() == null) {
            throw new InvalidArtifactException("models is missing");
        }
        if (doc.models().size() > MAX_MODELS) {
            throw new InvalidArtifactException("more than " + MAX_MODELS + " models");
        }
        Set<String> seen = new HashSet<>();
        List<ModelUsage> models = new ArrayList<>();
        for (ModelDoc m : doc.models()) {
            models.add(validateModel(m, seen));
        }
        return new Usage(doc.cases(), doc.failed(), List.copyOf(models));
    }

    private ModelUsage validateModel(ModelDoc m, Set<String> seen) throws InvalidArtifactException {
        if (m == null || m.model() == null || !MODEL_ID.matcher(m.model()).matches()) {
            throw new InvalidArtifactException("a model id is missing or malformed");
        }
        if (!seen.add(m.model())) {
            throw new InvalidArtifactException("a model is listed twice");
        }
        int calls = requiredRange(m.calls(), MAX_CALLS, "calls");
        int errored = requiredRange(m.erroredCalls(), MAX_CALLS, "erroredCalls");
        long cacheWrite = requiredTokens(m.cacheWrite(), "cacheWrite");
        long cacheWrite1h = requiredTokens(m.cacheWrite1h(), "cacheWrite1h");
        if (cacheWrite1h > cacheWrite) {
            throw new InvalidArtifactException("cacheWrite1h exceeds cacheWrite");
        }
        return new ModelUsage(m.model(), calls, errored,
                requiredTokens(m.input(), "input"), requiredTokens(m.output(), "output"),
                requiredTokens(m.cacheRead(), "cacheRead"), cacheWrite, cacheWrite1h);
    }

    private static void requireRange(Integer value, int max, String field) throws InvalidArtifactException {
        if (value != null && (value < 0 || value > max)) {
            throw new InvalidArtifactException(field + " is out of range");
        }
    }

    private static int requiredRange(Integer value, int max, String field) throws InvalidArtifactException {
        if (value == null || value < 0 || value > max) {
            throw new InvalidArtifactException(field + " is missing or out of range");
        }
        return value;
    }

    private static long requiredTokens(Long value, String field) throws InvalidArtifactException {
        if (value == null || value < 0 || value > MAX_TOKENS) {
            throw new InvalidArtifactException(field + " is missing or out of range");
        }
        return value;
    }
}
