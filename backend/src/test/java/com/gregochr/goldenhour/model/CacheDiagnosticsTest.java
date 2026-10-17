package com.gregochr.goldenhour.model;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheMissPreviousMessageNotFound;
import com.anthropic.models.messages.CacheMissReason;
import com.anthropic.models.messages.CacheMissUnavailable;
import com.anthropic.models.messages.Diagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CacheDiagnostics}: reading the SDK's {@code diagnostics} field in each of the
 * three shapes the API documents, and the compact JSON written to {@code api_call_log}.
 */
class CacheDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Diagnostics fromApiJson(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, Diagnostics.class);
    }

    @Test
    @DisplayName("a message with no diagnostics field reads as EMPTY, which is not present")
    void absentDiagnosticsIsEmpty() {
        CacheDiagnostics read = CacheDiagnostics.from(
                CacheDiagnosticsFixtures.message(Optional.empty(), "{}"));

        assertThat(read).isSameAs(CacheDiagnostics.EMPTY);
        assertThat(read.isPresent()).isFalse();
        assertThat(read.summary()).isEqualTo("none");
    }

    @Test
    @DisplayName("a null message reads as EMPTY")
    void nullMessageIsEmpty() {
        assertThat(CacheDiagnostics.from((com.anthropic.models.messages.Message) null))
                .isSameAs(CacheDiagnostics.EMPTY);
        assertThat(CacheDiagnostics.from((Optional<Diagnostics>) null)).isSameAs(CacheDiagnostics.EMPTY);
    }

    @Test
    @DisplayName("a divergence reads as a MISS with its reason and the estimate of missed tokens")
    void divergenceIsAMiss() {
        CacheDiagnostics read = CacheDiagnostics.from(
                CacheDiagnosticsFixtures.message(Optional.of(CacheDiagnosticsFixtures.MESSAGES_CHANGED), "{}"));

        assertThat(read).isEqualTo(CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ);
        assertThat(read.isPresent()).isTrue();
        assertThat(read.summary()).isEqualTo("messages_changed (1234 tokens)");
    }

    @Test
    @DisplayName("each *_changed reason is named by its API type and carries its token estimate")
    void everyChangedReasonIsNamed() {
        assertThat(CacheDiagnostics.from(Optional.of(Diagnostics.of(CacheMissReason.ofModelChanged(7L)))))
                .isEqualTo(new CacheDiagnostics(CacheDiagnostics.Status.MISS, "model_changed", 7L));
        assertThat(CacheDiagnostics.from(Optional.of(Diagnostics.of(CacheMissReason.ofSystemChanged(8L)))))
                .isEqualTo(new CacheDiagnostics(CacheDiagnostics.Status.MISS, "system_changed", 8L));
        assertThat(CacheDiagnostics.from(Optional.of(Diagnostics.of(CacheMissReason.ofToolsChanged(9L)))))
                .isEqualTo(new CacheDiagnostics(CacheDiagnostics.Status.MISS, "tools_changed", 9L));
    }

    @Test
    @DisplayName("reasons that carry no token estimate leave it absent")
    void reasonsWithoutAnEstimate() {
        CacheDiagnostics notFound = CacheDiagnostics.from(Optional.of(Diagnostics.of(
                CacheMissReason.ofPreviousMessageNotFound(CacheMissPreviousMessageNotFound.builder().build()))));
        CacheDiagnostics unavailable = CacheDiagnostics.from(Optional.of(Diagnostics.of(
                CacheMissReason.ofUnavailable(CacheMissUnavailable.builder().build()))));

        assertThat(notFound).isEqualTo(
                new CacheDiagnostics(CacheDiagnostics.Status.MISS, "previous_message_not_found", null));
        assertThat(notFound.summary()).isEqualTo("previous_message_not_found");
        assertThat(unavailable).isEqualTo(new CacheDiagnostics(CacheDiagnostics.Status.MISS, "unavailable", null));
    }

    @Test
    @DisplayName("a present diagnostics object with no reason is a comparison still pending, not a miss")
    void presentWithoutReasonIsPending() throws Exception {
        CacheDiagnostics read = CacheDiagnostics.from(Optional.of(fromApiJson("{\"cache_miss_reason\":null}")));

        assertThat(read).isEqualTo(new CacheDiagnostics(CacheDiagnostics.Status.PENDING, null, null));
        assertThat(read.isPresent()).isTrue();
        assertThat(read.summary()).isEqualTo("pending");
    }

    @Test
    @DisplayName("a reason type this SDK cannot name is kept as 'unknown', never thrown")
    void unnamedReasonTypeDoesNotThrow() throws Exception {
        Diagnostics diagnostics = fromApiJson(
                "{\"cache_miss_reason\":{\"type\":\"brand_new_reason\",\"cache_missed_input_tokens\":5}}");

        CacheDiagnostics read = CacheDiagnostics.from(Optional.of(diagnostics));

        assertThat(read.status()).isEqualTo(CacheDiagnostics.Status.MISS);
        assertThat(read.reason()).isIn("unknown", "brand_new_reason");
    }

    @Test
    @DisplayName("the JSON stored for a miss is compact and stable")
    void missJsonIsCompactAndStable() throws Exception {
        assertThat(MAPPER.writeValueAsString(CacheDiagnosticsFixtures.MESSAGES_CHANGED_READ))
                .isEqualTo("{\"status\":\"MISS\",\"reason\":\"messages_changed\",\"missedInputTokens\":1234}");
    }

    @Test
    @DisplayName("the JSON stored for a pending comparison and for a reason with no estimate omits what is absent")
    void absentFieldsAreOmittedFromTheJson() throws Exception {
        assertThat(MAPPER.writeValueAsString(new CacheDiagnostics(CacheDiagnostics.Status.PENDING, null, null)))
                .isEqualTo("{\"status\":\"PENDING\"}");
        assertThat(MAPPER.writeValueAsString(
                new CacheDiagnostics(CacheDiagnostics.Status.MISS, "unavailable", null)))
                .isEqualTo("{\"status\":\"MISS\",\"reason\":\"unavailable\"}");
    }
}
