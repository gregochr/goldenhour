package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Request shape and response handling for {@link EvaluationModel#SONNET_55}, pinned against the
 * models it must not disturb: effort low, 4096 tokens and no {@code thinking} parameter for
 * Sonnet 5.5, and a request identical to the prompt builder's own for every other model.
 */
@ExtendWith(MockitoExtension.class)
class ModelRequestShapeTest {

    private static final String JSON = "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75,"
            + " \"summary\": \"Promising conditions.\"}";

    @Mock
    private AnthropicApiClient anthropicApiClient;

    private PromptBuilder inlandBuilder;
    private BluebellPromptBuilder bluebellBuilder;
    private WoodlandPromptBuilder woodlandBuilder;
    private BatchRequestFactory factory;

    @BeforeEach
    void setUp() {
        inlandBuilder = new PromptBuilder();
        bluebellBuilder = new BluebellPromptBuilder();
        woodlandBuilder = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator());
        factory = new BatchRequestFactory(inlandBuilder, new CoastalPromptBuilder(),
                bluebellBuilder, woodlandBuilder);
    }

    // ── Batch request shape ──

    @Test
    @DisplayName("sky batch request for Sonnet 5.5: id, effort low with the format kept, no thinking, 4096")
    void skyBatch_sonnet55() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions()).build();

        BatchCreateParams.Request.Params params = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET_55, data,
                EvaluationModel.SONNET_55.getMaxTokens()).params();

        assertThat(params.model().asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(params.maxTokens()).isEqualTo(4096L);
        assertThat(params.thinking()).isEmpty();
        assertThat(params.temperature()).isEmpty();
        assertThat(params.toolChoice()).isEmpty();
        OutputConfig config = params.outputConfig().orElseThrow();
        assertThat(config.effort()).contains(OutputConfig.Effort.LOW);
        assertThat(config.format()).isEqualTo(inlandBuilder.buildOutputConfig().format());
    }

    @Test
    @DisplayName("bluebell batch request for Sonnet 5.5 carries effort low and the bluebell format")
    void bluebellBatch_sonnet55() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions()).build();

        BatchCreateParams.Request.Params params = factory.buildBluebellRequest(
                "bb-1-2026-04-16-SUNRISE", EvaluationModel.SONNET_55, data, 4096).params();

        OutputConfig config = params.outputConfig().orElseThrow();
        assertThat(config.effort()).contains(OutputConfig.Effort.LOW);
        assertThat(config.format()).isEqualTo(bluebellBuilder.buildOutputConfig().format());
        assertThat(params.thinking()).isEmpty();
    }

    @Test
    @DisplayName("woodland batch request for Sonnet 5.5 carries effort low and the woodland format")
    void woodlandBatch_sonnet55() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions()).build();

        BatchCreateParams.Request.Params params = factory.buildWoodlandRequest(
                "wd-1-2026-11-16-SUNRISE", EvaluationModel.SONNET_55, data, 4096).params();

        OutputConfig config = params.outputConfig().orElseThrow();
        assertThat(config.effort()).contains(OutputConfig.Effort.LOW);
        assertThat(config.format()).isEqualTo(woodlandBuilder.buildOutputConfig().format());
        assertThat(params.thinking()).isEmpty();
    }

    @Test
    @DisplayName("cache primer request for Sonnet 5.5 carries the same output config as the real request")
    void primerBatch_matchesRealRequestOutputConfig() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions()).build();

        OutputConfig primer = factory.buildCachePrimerRequest(
                "primer-0", EvaluationModel.SONNET_55, data, 4096).params().outputConfig().orElseThrow();
        OutputConfig real = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET_55, data, 4096)
                .params().outputConfig().orElseThrow();

        assertThat(primer).isEqualTo(real);
    }

    @Test
    @DisplayName("every other model's batch request carries the prompt builder's output config untouched")
    void batch_otherModels_unchanged() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions()).build();
        for (EvaluationModel model : List.of(EvaluationModel.HAIKU, EvaluationModel.SONNET,
                EvaluationModel.SONNET_ET, EvaluationModel.OPUS, EvaluationModel.OPUS_ET)) {
            BatchCreateParams.Request.Params sky = factory.buildForecastRequest(
                    "fc-1-2026-04-16-SUNRISE", model, data, model.getMaxTokens()).params();
            assertThat(sky.outputConfig()).contains(inlandBuilder.buildOutputConfig());
            assertThat(sky.outputConfig().orElseThrow().effort()).isEmpty();
            assertThat(sky.maxTokens()).isEqualTo((long) model.getMaxTokens());
            assertThat(sky.thinking()).isEmpty();

            assertThat(factory.buildBluebellRequest("bb-1-2026-04-16-SUNRISE", model, data, 512)
                    .params().outputConfig()).contains(bluebellBuilder.buildOutputConfig());
            assertThat(factory.buildWoodlandRequest("wd-1-2026-11-16-SUNRISE", model, data, 512)
                    .params().outputConfig()).contains(woodlandBuilder.buildOutputConfig());
        }
    }

    // ── Synchronous strategy ──

    @Test
    @DisplayName("strategy request for Sonnet 5.5: effort low, 4096 tokens, no thinking, temperature or tool choice")
    void strategy_sonnet55_requestShape() {
        ClaudeEvaluationStrategy strategy = strategy(EvaluationModel.SONNET_55);
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(message(List.of(text(JSON)), StopReason.END_TURN));

        strategy.evaluate(TestAtmosphericData.defaults());

        MessageCreateParams params = captured();
        assertThat(params.model().asString()).isEqualTo("claude-sonnet-5-5");
        assertThat(params.maxTokens()).isEqualTo(4096L);
        assertThat(params.thinking()).isEmpty();
        assertThat(params.temperature()).isEmpty();
        assertThat(params.toolChoice()).isEmpty();
        OutputConfig config = params.outputConfig().orElseThrow();
        assertThat(config.effort()).contains(OutputConfig.Effort.LOW);
        assertThat(config.format()).isEqualTo(inlandBuilder.buildOutputConfig().format());
    }

    @Test
    @DisplayName("strategy request for Haiku and Sonnet is exactly the prompt builder's output config")
    void strategy_otherModels_unchanged() {
        for (EvaluationModel model : List.of(EvaluationModel.HAIKU, EvaluationModel.SONNET,
                EvaluationModel.OPUS)) {
            AnthropicApiClient client = org.mockito.Mockito.mock(AnthropicApiClient.class);
            when(client.createMessage(any(MessageCreateParams.class)))
                    .thenReturn(message(List.of(text(JSON)), StopReason.END_TURN));
            new ClaudeEvaluationStrategy(client, inlandBuilder, new CoastalPromptBuilder(),
                    new ObjectMapper(), model, new SunsetEvaluationParser())
                    .evaluate(TestAtmosphericData.defaults());

            ArgumentCaptor<MessageCreateParams> captor =
                    ArgumentCaptor.forClass(MessageCreateParams.class);
            verify(client).createMessage(captor.capture());
            assertThat(captor.getValue().outputConfig()).contains(inlandBuilder.buildOutputConfig());
            assertThat(captor.getValue().maxTokens()).isEqualTo((long) model.getMaxTokens());
            assertThat(captor.getValue().thinking()).isEmpty();
        }
    }

    // ── Response handling ──

    @Test
    @DisplayName("a thinking block ahead of the text block does not break parsing")
    void response_thinkingBlockFirst_parsesTextBlock() {
        ThinkingBlock thinking = ThinkingBlock.builder()
                .thinking("Weighing the cloud cover first.")
                .signature("sig")
                .build();
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(message(List.of(ContentBlock.ofThinking(thinking), text(JSON)),
                        StopReason.END_TURN));

        SunsetEvaluation result = strategy(EvaluationModel.SONNET_55)
                .evaluate(TestAtmosphericData.defaults());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Promising conditions.");
    }

    @Test
    @DisplayName("a refusal fails the evaluation with ClaudeRefusalException")
    void response_refusal_throwsRefusal() {
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(message(List.of(), StopReason.REFUSAL));

        assertThatThrownBy(() -> strategy(EvaluationModel.SONNET_55)
                .evaluate(TestAtmosphericData.defaults()))
                .isInstanceOf(ClaudeRefusalException.class);
    }

    @Test
    @DisplayName("a thinking-only reply with no text block fails as unreadable, not as an empty success")
    void response_noTextBlock_throwsUnreadable() {
        ThinkingBlock thinking = ThinkingBlock.builder().thinking("hmm").signature("sig").build();
        when(anthropicApiClient.createMessage(any(MessageCreateParams.class)))
                .thenReturn(message(List.of(ContentBlock.ofThinking(thinking)), StopReason.END_TURN));

        assertThatThrownBy(() -> strategy(EvaluationModel.SONNET_55)
                .evaluate(TestAtmosphericData.defaults()))
                .isInstanceOf(ClaudeReplyUnreadableException.class);
    }

    // ── ModelRequestSupport ──

    @Test
    @DisplayName("withEffort returns the same instance for every model but Sonnet 5.5")
    void support_withEffort_sameInstanceForOthers() {
        OutputConfig base = inlandBuilder.buildOutputConfig();
        for (EvaluationModel model : EvaluationModel.values()) {
            if (model == EvaluationModel.SONNET_55) {
                assertThat(ModelRequestSupport.withEffort(base, model)).isNotSameAs(base);
            } else {
                assertThat(ModelRequestSupport.withEffort(base, model)).isSameAs(base);
            }
        }
    }

    @Test
    @DisplayName("maxTokens adds a 4096 thinking allowance to the answer budget for Sonnet 5.5 only")
    void support_maxTokens() {
        assertThat(ModelRequestSupport.maxTokens(EvaluationModel.SONNET_55, 256)).isEqualTo(4352);
        assertThat(ModelRequestSupport.maxTokens(EvaluationModel.SONNET_55, 9000)).isEqualTo(13096);
        assertThat(ModelRequestSupport.maxTokens(EvaluationModel.HAIKU, 256)).isEqualTo(256);
        assertThat(ModelRequestSupport.maxTokens(EvaluationModel.OPUS_ET, 256)).isEqualTo(256);
    }

    @Test
    @DisplayName("tune adds an effort-only output config for Sonnet 5.5 and nothing for Haiku")
    void support_tune() {
        MessageCreateParams sonnet = ModelRequestSupport.tune(base(EvaluationModel.SONNET_55),
                EvaluationModel.SONNET_55).build();
        MessageCreateParams haiku = ModelRequestSupport.tune(base(EvaluationModel.HAIKU),
                EvaluationModel.HAIKU).build();

        assertThat(sonnet.outputConfig().orElseThrow().effort()).contains(OutputConfig.Effort.LOW);
        assertThat(sonnet.outputConfig().orElseThrow().format()).isEmpty();
        assertThat(haiku.outputConfig()).isEmpty();
    }

    @Test
    @DisplayName("checkRefusal throws only for stop_reason=refusal")
    void support_checkRefusal() {
        ModelRequestSupport.checkRefusal(message(List.of(text("ok")), StopReason.END_TURN));
        assertThatThrownBy(() -> ModelRequestSupport.checkRefusal(
                message(List.of(), StopReason.REFUSAL)))
                .isInstanceOf(ClaudeRefusalException.class);
    }

    // ── helpers ──

    private ClaudeEvaluationStrategy strategy(EvaluationModel model) {
        return new ClaudeEvaluationStrategy(anthropicApiClient, inlandBuilder,
                new CoastalPromptBuilder(), new ObjectMapper(), model,
                new SunsetEvaluationParser());
    }

    private MessageCreateParams captured() {
        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        return captor.getValue();
    }

    private static MessageCreateParams.Builder base(EvaluationModel model) {
        return MessageCreateParams.builder()
                .model(model.getModelId())
                .maxTokens(256)
                .addUserMessage("hello");
    }

    private static ContentBlock text(String value) {
        return ContentBlock.ofText(TextBlock.builder().text(value).citations(List.of()).build());
    }

    private static Message message(List<ContentBlock> content, StopReason stopReason) {
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-5-5"))
                .content(content)
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .diagnostics(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(0)
                                .build())
                        .cacheCreationInputTokens(0)
                        .cacheReadInputTokens(0)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder()
                                .webSearchRequests(0)
                                .webFetchRequests(0)
                                .build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
                        .outputTokensDetails(OutputTokensDetails.builder()
                                .thinkingTokens(0)
                                .build())
                        .build())
                .build();
    }

    private static BluebellConditionScore woodlandConditions() {
        return new BluebellConditionScore(
                7, true, true, true, false, false, true,
                BluebellExposure.WOODLAND, "Bright still overcast under the canopy.");
    }
}
