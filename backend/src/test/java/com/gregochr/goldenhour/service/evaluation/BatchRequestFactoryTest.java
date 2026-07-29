package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BatchRequestFactoryTest {

    private PromptBuilder inlandBuilder;
    private CoastalPromptBuilder coastalBuilder;
    private BluebellPromptBuilder bluebellBuilder;
    private WoodlandPromptBuilder woodlandBuilder;
    private BatchRequestFactory factory;

    @BeforeEach
    void setUp() {
        inlandBuilder = new PromptBuilder();
        coastalBuilder = new CoastalPromptBuilder();
        bluebellBuilder = new BluebellPromptBuilder();
        woodlandBuilder = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator());
        factory = new BatchRequestFactory(inlandBuilder, coastalBuilder, bluebellBuilder,
                woodlandBuilder);
    }

    @Test
    void inlandTaskSelectsInlandBuilder() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        PromptBuilder selected = factory.selectBuilder(data);

        assertThat(selected).isSameAs(inlandBuilder);
    }

    @Test
    void coastalTaskSelectsCoastalBuilder() {
        AtmosphericData data = TestAtmosphericData.builder().tide(coastalTide()).build();

        PromptBuilder selected = factory.selectBuilder(data);

        assertThat(selected).isSameAs(coastalBuilder);
    }

    @Test
    void builtRequestCarriesCustomIdAndModel() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-42-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        assertThat(request.customId()).isEqualTo("fc-42-2026-04-16-SUNRISE");
        assertThat(request.params().model().asString())
                .isEqualTo(EvaluationModel.SONNET.getModelId());
    }

    @Test
    void builtRequestUsesCallerSuppliedMaxTokens() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.HAIKU, data, 512);

        assertThat(request.params().maxTokens()).isEqualTo(512L);
    }

    @Test
    void systemBlockCarriesEphemeralCacheControl() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        assertThat(request.params().system()).isPresent();
        assertThat(request.params().system().get().asTextBlockParams()).hasSize(1);
        var textBlock = request.params().system().get().asTextBlockParams().get(0);
        assertThat(textBlock.cacheControl()).isPresent();
    }

    @Test
    void inlandRequestUsesInlandSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        String systemText = request.params().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemText)
                .doesNotContain("COASTAL TIDE GUIDANCE");
    }

    @Test
    void bluebellRequestUsesBluebellSystemPromptAndCustomId() {
        AtmosphericData data = TestAtmosphericData.builder()
                .bluebellConditionScore(woodlandConditions())
                .build();

        BatchCreateParams.Request request = factory.buildBluebellRequest(
                "bb-42-2026-04-16-SUNRISE", EvaluationModel.HAIKU, data, 512);

        assertThat(request.customId()).isEqualTo("bb-42-2026-04-16-SUNRISE");
        assertThat(request.params().maxTokens()).isEqualTo(512L);
        String systemText = request.params().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemText).isEqualTo(bluebellBuilder.getSystemPrompt());
        // The bluebell system block is cached the same way the colour ones are.
        assertThat(request.params().system().get().asTextBlockParams().get(0).cacheControl())
                .isPresent();
    }

    private static BluebellConditionScore woodlandConditions() {
        return new BluebellConditionScore(
                7, true, true, true, false, false, true,
                BluebellExposure.WOODLAND, "Bright still overcast under the canopy.");
    }

    @Test
    void coastalRequestUsesCoastalSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(coastalTide())
                .build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        String systemText = request.params().system().get()
                .asTextBlockParams().get(0).text();
        // v2.13.2: coastal guidance is surge-only; tide is scored separately by TideVisitor.
        assertThat(systemText)
                .contains("COASTAL CONDITIONS GUIDANCE")
                .doesNotContain("COASTAL TIDE GUIDANCE");
    }

    @Test
    void surgePresentTriggersSurgeAwareUserMessage() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.30, 0.20, 0.50, 985.0, 18.0, 270.0, 0.95,
                TideRiskLevel.HIGH, "Strong onshore wind plus 985 hPa low");
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(coastalTide())
                .surge(surge)
                .adjustedRangeMetres(5.20)
                .astronomicalRangeMetres(4.70)
                .build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        // The surge-aware overload inserts a STORM SURGE FORECAST block; the base
        // overload would not. Asserting against block content guarantees the branch
        // chose the surge-aware path.
        String userMessage = request.params().messages().get(0).content().asString();
        assertThat(userMessage).contains("STORM SURGE FORECAST");
    }

    @Test
    void surgeAbsentDoesNotInsertSurgeBlock() {
        AtmosphericData data = TestAtmosphericData.builder().tide(coastalTide()).build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        String userMessage = request.params().messages().get(0).content().asString();
        assertThat(userMessage).doesNotContain("STORM SURGE FORECAST");
    }

    @Test
    void cacheControlHasNoTtlOverride() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        var cacheControl = request.params().system().get()
                .asTextBlockParams().get(0).cacheControl();
        assertThat(cacheControl).isPresent();
        // Default Anthropic ephemeral TTL (~5 min) — never override here. If a future
        // change adds .ttl(...) the call must be deliberate.
        assertThat(cacheControl.get().ttl()).isEmpty();
    }

    @Test
    void woodlandRequestUsesWoodlandSystemPromptByteForByte() {
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildWoodlandRequest(
                "wd-1-2026-11-16-SUNRISE", EvaluationModel.HAIKU, data, 1024);

        String system = request.params().system().get().asTextBlockParams().get(0).text();
        assertThat(system).isEqualTo(woodlandBuilder.getSystemPrompt());
    }

    @Test
    void woodlandSystemBlockCarriesEphemeralCacheControl() {
        // Without cache_control the woodland bucket pays full input price on every request in the
        // batch. The bucket exists to be cached; this is the flag that makes it so.
        AtmosphericData data = TestAtmosphericData.builder().build();

        BatchCreateParams.Request request = factory.buildWoodlandRequest(
                "wd-1-2026-11-16-SUNRISE", EvaluationModel.HAIKU, data, 1024);

        var textBlock = request.params().system().get().asTextBlockParams().get(0);
        assertThat(textBlock.cacheControl()).isPresent();
    }

    @Test
    void woodlandSystemPromptIsIdenticalAcrossRequests() {
        // The cache prefix is shared only if it is byte-identical. Two woodland requests built
        // from different atmospheric data must still carry the same system text — anything
        // location-specific leaking into the system block would give every request its own
        // prefix and silently cost full price on all of them.
        AtmosphericData a = TestAtmosphericData.builder().build();
        AtmosphericData b = TestAtmosphericData.builder().tide(coastalTide()).build();

        String first = factory.buildWoodlandRequest("wd-1-2026-11-16-SUNRISE",
                EvaluationModel.HAIKU, a, 1024)
                .params().system().get().asTextBlockParams().get(0).text();
        String second = factory.buildWoodlandRequest("wd-2-2026-12-01-SUNSET",
                EvaluationModel.HAIKU, b, 1024)
                .params().system().get().asTextBlockParams().get(0).text();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void woodlandBluebellAndSkySystemPromptsAreAllDistinct() {
        // Three distinct prefixes means three buckets that must stay homogeneous. If any two were
        // equal the bucketing would be pointless; if any two were ACCIDENTALLY equal the two
        // subjects would be scored by one rubric.
        assertThat(woodlandBuilder.getSystemPrompt())
                .isNotEqualTo(bluebellBuilder.getSystemPrompt())
                .isNotEqualTo(inlandBuilder.getSystemPrompt());
    }

    @Test
    void systemTextEqualsBuilderGetSystemPromptByteForByte() {
        AtmosphericData inland = TestAtmosphericData.builder().build();
        AtmosphericData coastal = TestAtmosphericData.builder().tide(coastalTide()).build();

        BatchCreateParams.Request inlandReq = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, inland, 1024);
        BatchCreateParams.Request coastalReq = factory.buildForecastRequest(
                "fc-2-2026-04-16-SUNRISE", EvaluationModel.SONNET, coastal, 1024);

        String inlandSystem = inlandReq.params().system().get()
                .asTextBlockParams().get(0).text();
        String coastalSystem = coastalReq.params().system().get()
                .asTextBlockParams().get(0).text();

        // Byte-for-byte equality preserves the cache prefix — any deviation between the
        // factory's output and the builder's getSystemPrompt() would bust the cache.
        assertThat(inlandSystem).isEqualTo(inlandBuilder.getSystemPrompt());
        assertThat(coastalSystem).isEqualTo(coastalBuilder.getSystemPrompt());
    }

    @Test
    void nullArgumentsAreRejected() {
        AtmosphericData data = TestAtmosphericData.builder().build();
        assertThatNullPointerException().isThrownBy(() -> factory.buildForecastRequest(
                null, EvaluationModel.SONNET, data, 1024));
        assertThatNullPointerException().isThrownBy(() -> factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", null, data, 1024));
        assertThatNullPointerException().isThrownBy(() -> factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, null, 1024));
    }

    private static TideSnapshot coastalTide() {
        return new TideSnapshot(
                TideState.MID,
                LocalDateTime.of(2026, 6, 21, 19, 30),
                new BigDecimal("4.20"),
                LocalDateTime.of(2026, 6, 21, 13, 15),
                new BigDecimal("1.10"),
                false,
                LocalDateTime.of(2026, 6, 21, 19, 30),
                LocalDateTime.of(2026, 6, 21, 13, 15),
                LunarTideType.REGULAR_TIDE,
                "First Quarter",
                false,
                TideStatisticalSize.EXTRA_HIGH);
    }
}
