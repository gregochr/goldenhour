package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClaudeEvaluationStrategy}.
 *
 * <p>Tests the shared logic: Claude API call, response parsing, token extraction,
 * and dual-tier scoring. Prompt construction tests live in {@link PromptBuilderTest};
 * metrics logging tests live in {@code MetricsLoggingDecoratorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeEvaluationStrategyTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;

    private ClaudeEvaluationStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ClaudeEvaluationStrategy(
                anthropicApiClient, new PromptBuilder(), new CoastalPromptBuilder(),
                new ObjectMapper(), EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("evaluate() calls Claude and returns parsed evaluation with all scores")
    void evaluate_callsClaude_returnsParsedEvaluation() {
        AtmosphericData data = buildAtmosphericData();
        Message response = buildMessage(
                "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Promising conditions.\"}");

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
        assertThat(result.summary()).isEqualTo("Promising conditions.");
    }

    @Test
    @DisplayName("evaluate() throws when Claude returns no text content")
    void evaluate_noTextContent_throwsIllegalStateException() {
        AtmosphericData data = buildAtmosphericData();
        Message response = Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of())
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .usage(buildUsage(10, 20, 0, 0))
                .build();

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        assertThatThrownBy(() -> strategy.evaluate(data))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no text content");
    }

    @Test
    @DisplayName("evaluate() propagates non-retryable Anthropic errors")
    void evaluate_nonRetryableError_propagates() {
        AtmosphericData data = buildAtmosphericData();

        AnthropicServiceException badRequestException = mock(AnthropicServiceException.class);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenThrow(badRequestException);

        assertThatThrownBy(() -> strategy.evaluate(data))
                .isInstanceOf(AnthropicServiceException.class);
    }

    @Test
    @DisplayName("evaluateWithDetails() returns prompt, raw response, and token usage")
    void evaluateWithDetails_returnsFull() {
        AtmosphericData data = buildAtmosphericData();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Promising conditions.\"}";
        Message response = buildMessage(rawJson, 400, 60, 0, 150);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.evaluation().rating()).isEqualTo(4);
        assertThat(detail.evaluation().fierySkyPotential()).isEqualTo(70);
        assertThat(detail.evaluation().goldenHourPotential()).isEqualTo(75);
        assertThat(detail.promptSent()).contains("Durham UK");
        assertThat(detail.promptSent()).endsWith(PromptBuilder.PROMPT_SUFFIX);
        assertThat(detail.rawResponse()).isEqualTo(rawJson);
        assertThat(detail.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(detail.tokenUsage()).isNotNull();
        assertThat(detail.tokenUsage().inputTokens()).isEqualTo(400);
        assertThat(detail.tokenUsage().outputTokens()).isEqualTo(60);
        assertThat(detail.tokenUsage().cacheReadInputTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("extractTokenUsage() extracts all four token categories from SDK response")
    void extractTokenUsage_extractsAllCategories() {
        Message response = buildMessage("{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 60,"
                + " \"summary\": \"Test.\"}", 500, 80, 200, 100);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.outputTokens()).isEqualTo(80);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(200);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("parseEvaluation() handles missing rating field gracefully")
    void parseEvaluation_missingRating_returnsNullRating() {
        SunsetEvaluation result = strategy.parseEvaluation(
                "{\"fiery_sky\": 50, \"golden_hour\": 60, \"summary\": \"Moderate conditions.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(50);
        assertThat(result.goldenHourPotential()).isEqualTo(60);
    }

    @Test
    @DisplayName("parseBluebellEvaluation() parses rating, summary and headline (strict JSON)")
    void parseBluebellEvaluation_strictJson_parsesAllFields() {
        BluebellEvaluation result = strategy.parseBluebellEvaluation(
                "{\"rating\": 4, \"summary\": \"Soft even light if they're in flower.\","
                + " \"headline\": \"Bright overcast over the carpet\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Soft even light if they're in flower.");
        assertThat(result.headline()).isEqualTo("Bright overcast over the carpet");
    }

    @Test
    @DisplayName("parseBluebellEvaluation() tolerates a missing headline (optional field)")
    void parseBluebellEvaluation_noHeadline_returnsNullHeadline() {
        BluebellEvaluation result = strategy.parseBluebellEvaluation(
                "{\"rating\": 2, \"summary\": \"Too breezy under a part-leafed canopy.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.headline()).isNull();
    }

    @Test
    @DisplayName("parseBluebellEvaluation() falls back to regex when the summary has raw quotes")
    void parseBluebellEvaluation_unescapedQuotes_recoversViaRegex() {
        // An unescaped inner quote breaks strict JSON; the bounded/salvage regex still recovers
        // the rating and summary rather than dropping the slot.
        BluebellEvaluation result = strategy.parseBluebellEvaluation(
                "{\"rating\": 5, \"summary\": \"Mist and low sun \"crepuscular\" rays.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.summary()).isNotBlank();
    }

    @Test
    @DisplayName("parseBluebellEvaluation() throws when neither JSON nor regex recovers a summary")
    void parseBluebellEvaluation_unparseable_throws() {
        assertThatThrownBy(() -> strategy.parseBluebellEvaluation(
                "not json at all", new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evaluate() parses dual-tier response with basic fields")
    void evaluate_dualTierResponse_parsesBasicFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":78,"
                + "\"summary\":\"Enhanced evaluation with directional data.\","
                + "\"basic_fiery_sky\":55,\"basic_golden_hour\":60,"
                + "\"basic_summary\":\"Moderate potential with some low cloud.\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(78);
        assertThat(result.summary()).isEqualTo("Enhanced evaluation with directional data.");
        assertThat(result.basicFierySkyPotential()).isEqualTo(55);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(60);
        assertThat(result.basicSummary()).isEqualTo("Moderate potential with some low cloud.");
    }

    @Test
    @DisplayName("evaluate() returns null basic fields when not in response")
    void evaluate_noBasicFields_returnsNullBasicFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Standard evaluation.\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
    }

    @Test
    @DisplayName("evaluateWithDetails() uses surge-aware prompt when surge is non-null")
    void evaluateWithDetails_withSurge_usesSurgeAwarePrompt() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Moderate surge conditions");
        AtmosphericData data = TestAtmosphericData.defaults()
                .withSurge(surge, 5.4, 5.0);
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Surge conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).contains("STORM SURGE");
        assertThat(detail.evaluation().fierySkyPotential()).isEqualTo(70);
    }

    @Test
    @DisplayName("evaluateWithDetails() uses standard prompt when surge is null")
    void evaluateWithDetails_withoutSurge_usesStandardPrompt() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).doesNotContain("STORM SURGE");
    }

    @Test
    @DisplayName("parseEvaluation() extracts inversion fields from JSON response")
    void parseEvaluation_withInversionFields_extractsInversion() {
        String json = "{\"rating\":5,\"fiery_sky\":85,\"golden_hour\":90,"
                + "\"summary\":\"Dramatic inversion.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() returns null inversion fields when not present")
    void parseEvaluation_noInversionFields_returnsNullInversion() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Normal conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("evaluate() parses inversion fields from full Claude response")
    void evaluate_withInversionResponse_parsesInversionFields() {
        AtmosphericData data = buildAtmosphericData();
        String json = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":90,"
                + "\"summary\":\"Spectacular sea of clouds.\","
                + "\"inversion_score\":10,\"inversion_potential\":\"STRONG\"}";
        Message response = buildMessage(json);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        SunsetEvaluation result = strategy.evaluate(data);

        assertThat(result.inversionScore()).isEqualTo(10);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() extracts moderate inversion fields")
    void parseEvaluation_moderateInversion_extractsCorrectly() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket visible.\","
                + "\"inversion_score\":7,\"inversion_potential\":\"MODERATE\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(7);
        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("sanitiseInversionPotential() normalises verbose Claude responses to enum names")
    void sanitiseInversionPotential_normalisesVerboseValues() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("STRONG")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("MODERATE")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("NONE")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(null)).isNull();
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles verbose labels from Claude")
    void sanitiseInversionPotential_handlesVerboseLabels() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "Moderate Cloud Inversion Potential")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "Strong Cloud Inversion Potential")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "No inversion potential")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential(
                "not_applicable")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("parseEvaluation() sanitises verbose inversion_potential from Claude JSON")
    void parseEvaluation_verboseInversionPotential_sanitisedToEnum() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket below.\","
                + "\"inversion_score\":8,"
                + "\"inversion_potential\":\"Moderate Cloud Inversion Potential\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("parseEvaluation() extracts basic-tier fields when present")
    void parseEvaluation_withBasicTierFields_extractsBasicScores() {
        String json = "{\"rating\":4,\"fiery_sky\":75,\"golden_hour\":70,"
                + "\"summary\":\"Good conditions.\","
                + "\"basic_fiery_sky\":60,\"basic_golden_hour\":55,"
                + "\"basic_summary\":\"Decent conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isEqualTo(60);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(55);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
    }

    @Test
    @DisplayName("parseEvaluation() returns null basic-tier fields when not present")
    void parseEvaluation_noBasicTierFields_returnsNull() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Average.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when JSON has unescaped quotes in summary")
    void parseEvaluation_regexFallback_extractsScores() {
        // Invalid JSON due to unescaped quotes — triggers regex fallback
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"orange\" sky at sunset.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        // Internal unescaped quotes → bounded pattern can't match → greedy salvage keeps the
        // summary (and the rating). This case has no following field, so there is no over-capture.
        assertThat(result.summary()).contains("orange");
    }

    // ── Bug B: regex-fallback over-capture fix (bounded summary capture) ──────

    // SYNTHETIC fixture — reconstructs the over-capture trigger from the real
    // cached_evaluation artifact ("…horizon.','headline\"}", Tyne and Wear /
    // Angel of the North, observed 2026-06). Not a captured production rawText
    // (over-capture is a silent success and was never persisted pre-instrumentation).
    // Purpose: demonstrate the bounded-regex fix handles the over-capture CLASS.
    // A single-quoted headline value breaks strict JSON (sending it to the fallback) and,
    // with the OLD greedy pattern, the summary swallowed up to the last quote
    // (…horizon.","headline). The headline is single-quoted so it is unextractable — that is
    // the model's malformation; the FIX's job is to stop the summary swallowing it.
    private static final String SYNTHETIC_OVERCAPTURE_SINGLE_QUOTED_HEADLINE =
            "{\"rating\":2,\"fiery_sky\":15,\"golden_hour\":20,"
            + "\"summary\":\"Heavy blanket over the horizon.\",\"headline\":'no colour'}";

    @Test
    @DisplayName("Bug B: over-capture fixed — summary stays clean, does not swallow the next field")
    void parseEvaluation_overCapture_summaryNotPolluted() {
        SunsetEvaluation result = strategy.parseEvaluation(
                SYNTHETIC_OVERCAPTURE_SINGLE_QUOTED_HEADLINE, new ObjectMapper());

        assertThat(result.summary()).isEqualTo("Heavy blanket over the horizon.");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.summary()).doesNotContain("','");
        // Rating extracted by its independent pattern — always safe.
        assertThat(result.rating()).isEqualTo(2);
    }

    @Test
    @DisplayName("Bug B: bounded summary + a well-formed following headline both extract cleanly")
    void parseEvaluation_overCapture_summaryCleanHeadlineExtracted() {
        // Trailing comma breaks strict JSON → fallback; summary and headline are both well-formed.
        String text = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Soft pastels over the western ridge.\","
                + "\"headline\":\"Pastel skies\",}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.summary()).isEqualTo("Soft pastels over the western ridge.");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.headline()).isEqualTo("Pastel skies");
        assertThat(result.rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("Bug B boundary: bounded summary preserves escaped quotes (does not truncate early)")
    void parseEvaluation_overCapture_escapedQuotesPreserved() {
        // Valid \" escapes inside the summary; trailing comma breaks strict → fallback.
        String text = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"A so-called \\\"golden\\\" hour over the bay.\","
                + "\"headline\":\"Golden hour\",}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        // The escaped quotes did not cut the summary short, and it did not over-capture.
        assertThat(result.summary()).contains("golden");
        assertThat(result.summary()).contains("bay");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.headline()).isEqualTo("Golden hour");
        assertThat(result.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Bug B: basic_summary is also bounded against over-capture")
    void parseEvaluation_overCapture_basicSummaryNotPolluted() {
        String text = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Clear horizon.\",\"basic_summary\":\"Looks clear.\","
                + "\"headline\":\"Clear\",}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.basicSummary()).isEqualTo("Looks clear.");
        assertThat(result.basicSummary()).doesNotContain("headline");
        assertThat(result.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Bug B: happy path unchanged — well-formed JSON parses strictly, fields exact")
    void parseEvaluation_wellFormed_strictPathUnchanged() {
        String text = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":72,"
                + "\"summary\":\"Pre-frontal fire — mid cloud catches colour.\","
                + "\"headline\":\"Pre-frontal fire\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(88);
        assertThat(result.goldenHourPotential()).isEqualTo(72);
        assertThat(result.summary()).isEqualTo("Pre-frontal fire — mid cloud catches colour.");
        assertThat(result.headline()).isEqualTo("Pre-frontal fire");
    }

    @Test
    @DisplayName("parseEvaluationWithMetadata() flags usedRegexFallback=false on a strict parse")
    void parseEvaluationWithMetadata_strictParse_flagFalse() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,\"summary\":\"Calm.\"}";

        ClaudeEvaluationStrategy.ParseResult result =
                strategy.parseEvaluationWithMetadata(json, new ObjectMapper());

        assertThat(result.usedRegexFallback()).isFalse();
        assertThat(result.evaluation().rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("parseEvaluationWithMetadata() flags usedRegexFallback=true when strict parse fails")
    void parseEvaluationWithMetadata_regexFallback_flagTrue() {
        // Unescaped inner quotes break strict JSON → regex fallback is used.
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"orange\" sky.\"}";

        ClaudeEvaluationStrategy.ParseResult result =
                strategy.parseEvaluationWithMetadata(text, new ObjectMapper());

        assertThat(result.usedRegexFallback()).isTrue();
        // Rating is extracted by its own pattern, so it stays correct even on fallback.
        assertThat(result.evaluation().rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("parseEvaluation() throws when regex fallback also fails")
    void parseEvaluation_totalGarbage_throws() {
        String text = "This is not JSON and has no matching patterns.";

        assertThatThrownBy(() -> strategy.parseEvaluation(text, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles rating-only response (no basic or inversion fields)")
    void parseEvaluation_ratingOnly_minimalResponse() {
        String json = "{\"rating\":2,\"fiery_sky\":25,\"golden_hour\":30,"
                + "\"summary\":\"Cloudy with little colour.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(25);
        assertThat(result.goldenHourPotential()).isEqualTo(30);
        assertThat(result.summary()).isEqualTo("Cloudy with little colour.");
        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() without rating field returns null rating")
    void parseEvaluation_noRatingField_returnsNullRating() {
        String json = "{\"fiery_sky\":80,\"golden_hour\":75,"
                + "\"summary\":\"Great sky.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(80);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
    }

    @Test
    @DisplayName("extractTokenUsage() extracts all four token counts")
    void extractTokenUsage_extractsAllCounts() {
        Message response = buildMessage("test", 500, 200, 1000, 300);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.outputTokens()).isEqualTo(200);
        assertThat(usage.cacheCreationInputTokens()).isEqualTo(1000);
        assertThat(usage.cacheReadInputTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles case variations")
    void sanitiseInversionPotential_caseInsensitive() {
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("strong")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("Strong")).isEqualTo("STRONG");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("moderate")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("Moderate")).isEqualTo("MODERATE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("none")).isEqualTo("NONE");
        assertThat(ClaudeEvaluationStrategy.sanitiseInversionPotential("")).isEqualTo("NONE");
    }

    // ── Code-fenced JSON ──────────────────────────────────────────────────────

    @Test
    @DisplayName("parseEvaluation() strips ```json fences around response")
    void parseEvaluation_codeFencedJson_strippedAndParsed() {
        String fenced = "```json\n"
                + "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Fenced response.\"}\n"
                + "```";

        SunsetEvaluation result = strategy.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        assertThat(result.summary()).isEqualTo("Fenced response.");
    }

    @Test
    @DisplayName("parseEvaluation() strips bare ``` fences (no language tag)")
    void parseEvaluation_bareFences_strippedAndParsed() {
        String fenced = "```\n"
                + "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Bare fence.\"}\n"
                + "```";

        SunsetEvaluation result = strategy.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Bare fence.");
    }

    // ── Kitchen-sink response (all fields simultaneously) ────────────────────

    @Test
    @DisplayName("parseEvaluation() extracts all fields when response contains everything")
    void parseEvaluation_allFieldsPresent_extractsEveryField() {
        String json = "{\"rating\":5,\"fiery_sky\":92,\"golden_hour\":88,"
                + "\"summary\":\"Spectacular conditions.\","
                + "\"basic_fiery_sky\":65,\"basic_golden_hour\":60,"
                + "\"basic_summary\":\"Decent conditions.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(92);
        assertThat(result.goldenHourPotential()).isEqualTo(88);
        assertThat(result.summary()).isEqualTo("Spectacular conditions.");
        assertThat(result.basicFierySkyPotential()).isEqualTo(65);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(60);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    // ── Score boundary values ────────────────────────────────────────────────

    @Test
    @DisplayName("parseEvaluation() handles minimum score boundaries (rating=1, fiery=0, golden=0)")
    void parseEvaluation_minimumScores_parsedCorrectly() {
        String json = "{\"rating\":1,\"fiery_sky\":0,\"golden_hour\":0,"
                + "\"summary\":\"Total overcast.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(1);
        assertThat(result.fierySkyPotential()).isZero();
        assertThat(result.goldenHourPotential()).isZero();
    }

    @Test
    @DisplayName("parseEvaluation() handles maximum score boundaries (rating=5, fiery=100, golden=100)")
    void parseEvaluation_maximumScores_parsedCorrectly() {
        String json = "{\"rating\":5,\"fiery_sky\":100,\"golden_hour\":100,"
                + "\"summary\":\"Perfect conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(100);
        assertThat(result.goldenHourPotential()).isEqualTo(100);
    }

    // ── Regex fallback for extended fields ────────────────────────────────────

    @Test
    @DisplayName("regex fallback extracts basic-tier fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsBasicFields() {
        // Invalid JSON (unescaped quote) but regex can extract all fields
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"glow\" at sunset.\","
                + "\"basic_fiery_sky\":55,\"basic_golden_hour\":50,"
                + "\"basic_summary\":\"Moderate conditions.\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.basicFierySkyPotential()).isEqualTo(55);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(50);
    }

    @Test
    @DisplayName("regex fallback extracts inversion fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsInversionFields() {
        String text = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":90,"
                + "\"summary\":\"Sea of \"clouds\" below.\","
                + "\"inversion_score\":10,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = strategy.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.inversionScore()).isEqualTo(10);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    // ── Evaluate with cache-hit tokens ─────────────────────────────────────

    @Test
    @DisplayName("extractTokenUsage() handles zero cache tokens without error")
    void extractTokenUsage_zeroCacheTokens_parsesCorrectly() {
        Message response = buildMessage("{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Test.\"}", 300, 50, 0, 0);

        TokenUsage usage = strategy.extractTokenUsage(response);

        assertThat(usage.cacheCreationInputTokens()).isZero();
        assertThat(usage.cacheReadInputTokens()).isZero();
        assertThat(usage.inputTokens()).isEqualTo(300);
    }

    // ── Builder Selection (coastal vs inland) ───────────────────────────────

    @Test
    @DisplayName("evaluateWithDetails() sends coastal (surge) system prompt when data has tide")
    void evaluateWithDetails_withTide_sendsCoastalSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false, null))
                .build();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Coastal conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        MessageCreateParams params = captor.getValue();
        String systemPrompt = params.system().get().asTextBlockParams().get(0).text();

        // v2.13.2: coastal guidance is now surge-only; the tide-boost guidance is gone.
        assertThat(systemPrompt)
                .contains("COASTAL CONDITIONS GUIDANCE:")
                .doesNotContain("COASTAL TIDE GUIDANCE");
    }

    @Test
    @DisplayName("evaluateWithDetails() sends base system prompt (no coastal guidance) when data has no tide")
    void evaluateWithDetails_withoutTide_sendsBaseSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Inland conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        MessageCreateParams params = captor.getValue();
        String systemPrompt = params.system().get().asTextBlockParams().get(0).text();

        assertThat(systemPrompt).doesNotContain("COASTAL TIDE GUIDANCE:");
    }

    @Test
    @DisplayName("SSE path uses default cache TTL (not 1-hour batch TTL)")
    void evaluateWithDetails_cacheControlUsesDefaultTtl() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Test.\"}";
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(buildMessage(rawJson));

        strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());

        // SSE path should have cache control but NO explicit TTL — the API default
        // (5 min) is appropriate for single-request real-time calls. Only batch
        // paths should use TTL_1H.
        var systemBlock = captor.getValue().system().get().asTextBlockParams().get(0);
        assertThat(systemBlock.cacheControl()).isPresent();
        assertThat(systemBlock.cacheControl().get().ttl())
                .isNotEqualTo(java.util.Optional.of(CacheControlEphemeral.Ttl.TTL_1H));
    }

    @Test
    @DisplayName("evaluateWithDetails() user message omits the tide block even for coastal data "
            + "(v2.13.2: tide is scored separately by TideVisitor, not in the prompt)")
    void evaluateWithDetails_withTide_userMessageOmitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true, null))
                .build();
        String rawJson = "{\"rating\": 5, \"fiery_sky\": 85, \"golden_hour\": 90,"
                + " \"summary\": \"King tide conditions.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent())
                .doesNotContain("Tide:")
                .doesNotContain("KING TIDE");
    }

    @Test
    @DisplayName("evaluateWithDetails() user message omits tide block for inland data")
    void evaluateWithDetails_withoutTide_userMessageOmitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();
        String rawJson = "{\"rating\": 3, \"fiery_sky\": 50, \"golden_hour\": 55,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        assertThat(detail.promptSent()).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("evaluateWithDetails() coastal data with surge includes the surge block (no tide line)")
    void evaluateWithDetails_coastalWithSurge_includesSurgeNotTide() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Moderate surge");
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false, null))
                .build()
                .withSurge(surge, 4.85, 4.50);
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 72, \"golden_hour\": 68,"
                + " \"summary\": \"Surge + tide.\"}";
        Message response = buildMessage(rawJson);

        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        // v2.13.2: surge block kept (foreground/safety), tide line removed (scored separately).
        assertThat(detail.promptSent())
                .doesNotContain("Tide:")
                .contains("STORM SURGE FORECAST:")
                .contains("Risk level: MODERATE");

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get().asTextBlockParams().get(0).text();
        assertThat(systemPrompt).contains("COASTAL CONDITIONS GUIDANCE:");
    }

    @Test
    @DisplayName("evaluateWithDetails() user prompt contains fiery_sky, golden_hour, and location name")
    void evaluateWithDetails_userMessage_containsPromptKeywords() {
        AtmosphericData data = buildAtmosphericData();
        String rawJson = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
                + " \"summary\": \"Standard.\"}";
        Message response = buildMessage(rawJson);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class))).thenReturn(response);

        EvaluationDetail detail = strategy.evaluateWithDetails(data);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        String systemPrompt = captor.getValue().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemPrompt).isNotBlank();

        assertThat(detail.promptSent()).contains("Fiery Sky Potential");
        assertThat(detail.promptSent()).contains("Golden Hour Potential");
        assertThat(detail.promptSent()).contains("Durham UK");
    }

    // --- Helper methods ---

    private Message buildMessage(String text) {
        return buildMessage(text, 10, 20, 0, 0);
    }

    private Message buildMessage(String text, long inputTokens, long outputTokens,
            long cacheCreationTokens, long cacheReadTokens) {
        TextBlock textBlock = TextBlock.builder()
                .text(text)
                .citations(List.of())
                .build();
        ContentBlock contentBlock = ContentBlock.ofText(textBlock);
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of(contentBlock))
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .usage(buildUsage(inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens))
                .build();
    }

    private Usage buildUsage(long inputTokens, long outputTokens,
            long cacheCreationTokens, long cacheReadTokens) {
        return Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreation(CacheCreation.builder()
                        .ephemeral5mInputTokens(0)
                        .ephemeral1hInputTokens(0)
                        .build())
                .cacheCreationInputTokens(cacheCreationTokens)
                .cacheReadInputTokens(cacheReadTokens)
                .inferenceGeo("us")
                .serverToolUse(ServerToolUsage.builder()
                        .webSearchRequests(0)
                        .webFetchRequests(0)
                        .build())
                .serviceTier(Usage.ServiceTier.of("standard"))
                .outputTokensDetails(OutputTokensDetails.builder()
                        .thinkingTokens(0)
                        .build())
                .build();
    }

    private AtmosphericData buildAtmosphericData() {
        return TestAtmosphericData.defaults();
    }
}
