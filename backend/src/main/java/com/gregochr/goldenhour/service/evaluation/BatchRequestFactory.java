package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Builds Anthropic Batch API request objects for forecast evaluations.
 *
 * <p>Prior to extraction this pipeline was duplicated four times across
 * {@code ScheduledBatchEvaluationService.buildForecastRequest} and both the JFDI and
 * force-submit branches of {@code ForceSubmitBatchService}. Each site performed the same
 * four steps — select builder by tide presence, choose user-message overload by surge
 * presence, attach {@link CacheControlEphemeral} to the system block, and assemble the
 * final {@link BatchCreateParams.Request}. Centralising here removes the drift risk and
 * makes the cache-control convention a single-source decision.
 *
 * <p>The caller is responsible for producing the {@code customId} (via
 * {@link CustomIdFactory}) and for choosing {@code maxTokens} — scheduled batches use
 * the model's configured max tokens, while JFDI and force-submit historically used 512.
 */
@Component
public class BatchRequestFactory {

    private final PromptBuilder inlandBuilder;
    private final CoastalPromptBuilder coastalBuilder;

    /**
     * Constructs the factory.
     *
     * @param inlandBuilder  builder for inland (non-tidal) locations
     * @param coastalBuilder builder for coastal (tidal) locations
     */
    public BatchRequestFactory(PromptBuilder inlandBuilder, CoastalPromptBuilder coastalBuilder) {
        this.inlandBuilder = inlandBuilder;
        this.coastalBuilder = coastalBuilder;
    }

    /**
     * Builds a batch request for a single forecast evaluation task.
     *
     * <p>Selects between {@link PromptBuilder} and {@link CoastalPromptBuilder} by the
     * presence of tide data, and between the base and surge-aware
     * {@link PromptBuilder#buildUserMessage} overloads by the presence of storm-surge data.
     * The system block has {@link CacheControlEphemeral} attached so the ~3,600-token
     * system prompt is shared across all requests in a batch.
     *
     * @param customId  the Anthropic custom ID (produced via {@link CustomIdFactory})
     * @param model     the evaluation model to invoke
     * @param data      the atmospheric data for this evaluation task
     * @param maxTokens Anthropic {@code maxTokens} for this request
     * @return a fully formed batch request ready for submission
     */
    public BatchCreateParams.Request buildForecastRequest(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens) {
        Objects.requireNonNull(customId, "customId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(data, "data");

        PromptBuilder builder = selectBuilder(data);
        String userMessage = buildUserMessage(builder, data);

        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(maxTokens)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(builder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(builder.buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build())
                .build();
    }

    /**
     * Selects the prompt builder for a given task: coastal when tide data is present,
     * inland otherwise. Package-private for test visibility.
     */
    PromptBuilder selectBuilder(AtmosphericData data) {
        return data.tide() != null ? coastalBuilder : inlandBuilder;
    }

    /**
     * Chooses the user-message overload by the presence of storm-surge data.
     */
    private static String buildUserMessage(PromptBuilder builder, AtmosphericData data) {
        if (data.surge() != null) {
            return builder.buildUserMessage(data, data.surge(),
                    data.adjustedRangeMetres(), data.astronomicalRangeMetres());
        }
        return builder.buildUserMessage(data);
    }
}
