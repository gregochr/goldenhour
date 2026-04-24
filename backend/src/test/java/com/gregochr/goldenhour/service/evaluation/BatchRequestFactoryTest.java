package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
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
    private BatchRequestFactory factory;

    @BeforeEach
    void setUp() {
        inlandBuilder = new PromptBuilder();
        coastalBuilder = new CoastalPromptBuilder();
        factory = new BatchRequestFactory(inlandBuilder, coastalBuilder);
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
    void coastalRequestUsesCoastalSystemPrompt() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(coastalTide())
                .build();

        BatchCreateParams.Request request = factory.buildForecastRequest(
                "fc-1-2026-04-16-SUNRISE", EvaluationModel.SONNET, data, 1024);

        String systemText = request.params().system().get()
                .asTextBlockParams().get(0).text();
        assertThat(systemText).contains("COASTAL TIDE GUIDANCE");
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
