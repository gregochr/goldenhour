package com.gregochr.goldenhour.integration;

import com.github.tomakehurst.wiremock.client.MappingBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * Stub builders for Anthropic Batch API endpoints, matching the JSON shapes
 * the Java SDK 2.18.0 expects to deserialise.
 *
 * <p>Three endpoints are stubbed:
 * <ul>
 *   <li>{@code POST /v1/messages/batches} — batch creation, returns
 *       {@code MessageBatch} with {@code processing_status=in_progress}</li>
 *   <li>{@code GET /v1/messages/batches/{id}} — batch retrieval, returns
 *       {@code MessageBatch} with the supplied processing_status and request_counts</li>
 *   <li>{@code GET /v1/messages/batches/{id}/results} — JSONL stream of
 *       {@code MessageBatchIndividualResponse} records</li>
 * </ul>
 *
 * <p>Field names follow Anthropic's published API (snake_case). The SDK's
 * Jackson deserialiser is configured to ignore unknown fields, so additional
 * fields in the responses are harmless; missing required fields will surface
 * as deserialisation errors at test time, which is the desired feedback loop.
 *
 * <p>Use the {@link BatchResultFixture} record + factory methods to build
 * succeeded and errored entries; the helper takes care of wiring the JSONL
 * stream body and the snake_case field names.
 */
public final class AnthropicWireMockFixtures {

    // Timestamps are computed relative to "now" rather than hardcoded so the
    // fixtures cannot become time-bombs. Production polling treats a batch as
    // EXPIRED when expires_at is in the past (BatchPollingService:126), so a
    // hardcoded expires_at silently breaks any CI run that happens to fire
    // after that wallclock instant. Relative ordering: created_at < ended_at
    // < now < expires_at.
    private static String createdAt() {
        return Instant.now().minus(Duration.ofMinutes(30)).toString();
    }

    private static String expiresAt() {
        return Instant.now().plus(Duration.ofHours(24)).toString();
    }

    private static String endedAt() {
        return Instant.now().minus(Duration.ofMinutes(15)).toString();
    }

    private AnthropicWireMockFixtures() {
        // utility — not instantiable
    }

    /**
     * Stubs {@code POST /v1/messages/batches} to return a freshly-created batch
     * with {@code processing_status=in_progress} and zero request counts.
     *
     * @param batchId the batch ID the stub will return; callers typically generate
     *                this via {@code "msgbatch_" + UUID.randomUUID()}
     * @return a WireMock mapping builder ready for {@code WireMockExtension.stubFor()}
     */
    public static MappingBuilder stubBatchCreate(String batchId) {
        return post(urlPathEqualTo("/v1/messages/batches"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageBatchJson(batchId, "in_progress",
                                new RequestCounts(0, 0, 0, 0, 0), null)));
    }

    /**
     * Stubs {@code GET /v1/messages/batches/{batchId}} to return a batch in the
     * supplied processing state with the supplied request counts.
     *
     * @param batchId          the batch ID this stub responds for
     * @param processingStatus one of {@code in_progress}, {@code canceling},
     *                         {@code ended}; case-sensitive (lowercase) per Anthropic API
     * @param counts           request counts to embed in the response
     * @return a WireMock mapping builder
     */
    public static MappingBuilder stubBatchRetrieve(String batchId,
            String processingStatus, RequestCounts counts) {
        String endedAt = "ended".equals(processingStatus) ? endedAt() : null;
        String resultsUrl = "ended".equals(processingStatus)
                ? "https://api.anthropic.com/v1/messages/batches/" + batchId + "/results"
                : null;
        return get(urlPathEqualTo("/v1/messages/batches/" + batchId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageBatchJson(batchId, processingStatus, counts,
                                resultsUrl, endedAt)));
    }

    /**
     * Stubs {@code GET /v1/messages/batches/{batchId}/results} to return a JSONL
     * stream of {@link BatchResultFixture} entries.
     *
     * <p>The path has a trailing-slash-tolerant matcher because the SDK has
     * historically toggled trailing slashes on this endpoint between minor
     * versions; matching either form makes the stub robust to that.
     *
     * @param batchId the batch ID the stub responds for
     * @param results the per-request results to stream as JSONL
     * @return a WireMock mapping builder
     */
    public static MappingBuilder stubBatchResults(String batchId,
            List<BatchResultFixture> results) {
        String body = results.stream()
                .map(BatchResultFixture::toJsonLine)
                .collect(Collectors.joining("\n"));
        return get(urlPathMatching("/v1/messages/batches/" + batchId + "/results/?"))
                .withHeader("Accept", equalTo("application/x-jsonl"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-jsonl")
                        .withBody(body));
    }

    /**
     * Builds a {@link BatchResultFixture} representing a successful Claude
     * evaluation with the supplied scoring fields.
     *
     * <p>The summary defaults to a deterministic string for asserting against;
     * tests that want a specific summary should use the longer
     * {@link #succeededWithEvaluation(String, int, int, int, String, String)} overload.
     *
     * @param customId   the request's custom ID (e.g. {@code fc-42-2026-04-26-SUNRISE})
     * @param rating     1-5 rating
     * @param fierySky   0-100 fiery sky score
     * @param goldenHour 0-100 golden hour score
     * @return a BatchResultFixture with succeeded result and the supplied scores
     */
    public static BatchResultFixture succeededWithEvaluation(String customId,
            int rating, int fierySky, int goldenHour) {
        return succeededWithEvaluation(customId, rating, fierySky, goldenHour,
                "Stubbed evaluation summary for tests.",
                "claude-haiku-4-5-20251001");
    }

    /**
     * Builds a {@link BatchResultFixture} representing a successful Claude
     * evaluation with a caller-supplied summary and model.
     *
     * @param customId   the request's custom ID
     * @param rating     1-5 rating
     * @param fierySky   0-100 fiery sky score
     * @param goldenHour 0-100 golden hour score
     * @param summary    the assistant's one-sentence summary text
     * @param modelId    the model ID to embed (e.g. {@code claude-haiku-4-5-20251001})
     * @return a BatchResultFixture with succeeded result and the supplied fields
     */
    public static BatchResultFixture succeededWithEvaluation(String customId,
            int rating, int fierySky, int goldenHour, String summary, String modelId) {
        String evaluationJson = String.format(
                "{\"rating\":%d,\"fiery_sky\":%d,\"golden_hour\":%d,\"summary\":\"%s\"}",
                rating, fierySky, goldenHour, summary.replace("\"", "\\\""));
        return new BatchResultFixture(customId, ResultKind.SUCCEEDED, modelId,
                evaluationJson, null, null,
                /* inputTokens */ 1500L,
                /* outputTokens */ 50L,
                /* cacheReadInputTokens */ 0L,
                /* cacheCreationInputTokens */ 1400L);
    }

    /**
     * Builds an errored fixture with Anthropic's standard {@code overloaded_error}.
     *
     * @param customId the request's custom ID
     * @return a BatchResultFixture representing a 529-style overload error
     */
    public static BatchResultFixture erroredOverloaded(String customId) {
        return new BatchResultFixture(customId, ResultKind.ERRORED, null, null,
                "overloaded_error", "Service temporarily overloaded",
                null, null, null, null);
    }

    /**
     * Builds an errored fixture with a caller-supplied error type and message.
     *
     * @param customId     the request's custom ID
     * @param errorType    Anthropic error type (e.g. {@code invalid_request_error})
     * @param errorMessage human-readable error text
     * @return a BatchResultFixture representing the supplied error
     */
    public static BatchResultFixture erroredApi(String customId,
            String errorType, String errorMessage) {
        return new BatchResultFixture(customId, ResultKind.ERRORED, null, null,
                errorType, errorMessage, null, null, null, null);
    }

    private static String messageBatchJson(String batchId, String processingStatus,
            RequestCounts counts, String resultsUrl) {
        return messageBatchJson(batchId, processingStatus, counts, resultsUrl, null);
    }

    private static String messageBatchJson(String batchId, String processingStatus,
            RequestCounts counts, String resultsUrl, String endedAt) {
        StringBuilder sb = new StringBuilder()
                .append("{")
                .append("\"id\":\"").append(batchId).append("\",")
                .append("\"type\":\"message_batch\",")
                .append("\"processing_status\":\"").append(processingStatus).append("\",")
                .append("\"request_counts\":{")
                .append("\"processing\":").append(counts.processing()).append(",")
                .append("\"succeeded\":").append(counts.succeeded()).append(",")
                .append("\"errored\":").append(counts.errored()).append(",")
                .append("\"canceled\":").append(counts.canceled()).append(",")
                .append("\"expired\":").append(counts.expired())
                .append("},")
                .append("\"created_at\":\"").append(createdAt()).append("\",")
                .append("\"expires_at\":\"").append(expiresAt()).append("\",")
                .append("\"archived_at\":null,")
                .append("\"cancel_initiated_at\":null,")
                .append("\"ended_at\":");
        if (endedAt == null) {
            sb.append("null,");
        } else {
            sb.append("\"").append(endedAt).append("\",");
        }
        sb.append("\"results_url\":");
        if (resultsUrl == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(resultsUrl).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Discriminator for {@link BatchResultFixture#kind()}: success or error.
     */
    public enum ResultKind {
        /** The request returned a Claude message. */
        SUCCEEDED,
        /** The request errored before producing a message. */
        ERRORED
    }

    /**
     * Anthropic's per-batch counter object, matching the {@code request_counts}
     * field on {@code MessageBatch}.
     *
     * @param processing requests still being processed
     * @param succeeded  requests that succeeded
     * @param errored    requests that errored
     * @param canceled   requests that were canceled
     * @param expired    requests that expired before processing completed
     */
    public record RequestCounts(int processing, int succeeded, int errored,
            int canceled, int expired) {
    }

    /**
     * Test fixture representing one entry in a streamed batch result.
     *
     * <p>Use {@link AnthropicWireMockFixtures#succeededWithEvaluation} or
     * {@link AnthropicWireMockFixtures#erroredOverloaded} to construct;
     * direct constructor use is discouraged.
     *
     * @param customId                  custom ID echoed back from the original request
     * @param kind                      SUCCEEDED or ERRORED
     * @param modelId                   model id (success only, null for errors)
     * @param assistantTextJson         the JSON the assistant returned in its text content
     *                                  block (success only, null for errors)
     * @param errorType                 Anthropic error type (error only, null for successes)
     * @param errorMessage              Anthropic error message (error only, null for successes)
     * @param inputTokens               token counts (null for errors)
     * @param outputTokens              token counts (null for errors)
     * @param cacheReadInputTokens      token counts (null for errors)
     * @param cacheCreationInputTokens  token counts (null for errors)
     */
    public record BatchResultFixture(
            String customId,
            ResultKind kind,
            String modelId,
            String assistantTextJson,
            String errorType,
            String errorMessage,
            Long inputTokens,
            Long outputTokens,
            Long cacheReadInputTokens,
            Long cacheCreationInputTokens) {

        /**
         * Renders this fixture as one line of the streaming results body.
         *
         * @return one JSON line in the JSONL stream
         */
        String toJsonLine() {
            if (kind == ResultKind.SUCCEEDED) {
                String escapedText = assistantTextJson.replace("\\", "\\\\").replace("\"", "\\\"");
                return "{"
                        + "\"custom_id\":\"" + customId + "\","
                        + "\"result\":{"
                        + "\"type\":\"succeeded\","
                        + "\"message\":{"
                        + "\"id\":\"msg_test_" + customId + "\","
                        + "\"type\":\"message\","
                        + "\"role\":\"assistant\","
                        + "\"model\":\"" + modelId + "\","
                        + "\"content\":[{"
                        + "\"type\":\"text\","
                        + "\"text\":\"" + escapedText + "\""
                        + "}],"
                        + "\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null,"
                        + "\"usage\":{"
                        + "\"input_tokens\":" + inputTokens + ","
                        + "\"output_tokens\":" + outputTokens + ","
                        + "\"cache_read_input_tokens\":" + cacheReadInputTokens + ","
                        + "\"cache_creation_input_tokens\":" + cacheCreationInputTokens
                        + "}"
                        + "}"
                        + "}"
                        + "}";
            }
            // Anthropic's batch errored payload is doubly nested: an outer
            // error-response envelope ({type:"error", error:{...}}) wrapping
            // the actual typed error ({type:"overloaded_error", message:"..."}).
            // The SDK's MessageBatchErroredResult.error is an ErrorResponse,
            // whose own .error() field is the inner Error object. Production
            // code reads that inner type via the SDK's getter chain.
            return "{"
                    + "\"custom_id\":\"" + customId + "\","
                    + "\"result\":{"
                    + "\"type\":\"errored\","
                    + "\"error\":{"
                    + "\"type\":\"error\","
                    + "\"error\":{"
                    + "\"type\":\"" + errorType + "\","
                    + "\"message\":\"" + errorMessage.replace("\"", "\\\"") + "\""
                    + "}"
                    + "}"
                    + "}"
                    + "}";
        }
    }
}
