package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final BluebellPromptBuilder bluebellBuilder;
    private final WoodlandPromptBuilder woodlandBuilder;

    /**
     * Constructs the factory.
     *
     * @param inlandBuilder   builder for inland (non-tidal) locations
     * @param coastalBuilder  builder for coastal (tidal) locations
     * @param bluebellBuilder builder for the dedicated bluebell-conditions prompt
     * @param woodlandBuilder builder for the year-round woodland-conditions prompt
     */
    public BatchRequestFactory(@Qualifier("promptBuilder") PromptBuilder inlandBuilder,
            CoastalPromptBuilder coastalBuilder,
            BluebellPromptBuilder bluebellBuilder,
            WoodlandPromptBuilder woodlandBuilder) {
        this.inlandBuilder = inlandBuilder;
        this.coastalBuilder = coastalBuilder;
        this.bluebellBuilder = bluebellBuilder;
        this.woodlandBuilder = woodlandBuilder;
    }

    /**
     * Builds a batch request for a single forecast evaluation task.
     *
     * <p>Selects between {@link PromptBuilder} and {@link CoastalPromptBuilder} by the
     * presence of tide data, and between the base and surge-aware
     * {@link PromptBuilder#buildUserMessage} overloads by the presence of storm-surge data.
     * The system block has {@link CacheControlEphemeral} attached so the <b>~4,320-token</b>
     * system prompt is shared across all requests in a batch.
     *
     * <p>That number is load-bearing, not decorative. Haiku 4.5 — the {@code BATCH_FAR_TERM} model
     * (V92) — will not cache a prefix below <b>4,096 tokens</b>, and below it fails silently rather
     * than erroring. The forecast prompt clears that by about 5%, which
     * {@code SystemPromptCacheabilityTest} pins. (This javadoc previously claimed ~3,600 tokens,
     * which is <em>under</em> the floor and would have argued the opposite conclusion.)
     *
     * <p>The bluebell and woodland variants below carry the same {@code cache_control} block, and
     * whether it does anything <b>depends on the horizon, because it decides the model</b>.
     * {@code ForecastTaskCollector} builds those tasks with the same {@code decision.model()} as
     * the sky path, so T+0/T+1 runs them on {@code BATCH_NEAR_TERM} — Sonnet by default (V92),
     * floor <b>1,024 tokens</b> — and T+2/T+3 on Haiku's 4,096.
     *
     * <p>So: <b>inert on the far-term (Haiku) path</b>, where both prompts sit far below 4,096.
     * On the near-term path woodland (~4,770 chars, order of 1,270 tokens) clears Sonnet's floor
     * and <b>does cache</b>; bluebell (~3,847 chars, order of 1,030) sits within measurement error
     * of it and could fall either side. Neither has been measured with
     * {@code messages.count_tokens} — those are character-ratio estimates, so do not act on the
     * bluebell one without measuring.
     *
     * <p>Left as-is deliberately. Padding them past 4,096 tokens to win the far-term path would
     * cost more input than caching could recover, and the blocks are free where they do nothing.
     * <b>Do not remove them as dead weight</b> — on the near-term path at least one is live.
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
     * Builds a batch request for a single bluebell-conditions evaluation task.
     *
     * <p>Uses the standalone {@link BluebellPromptBuilder} (its own system prompt and output
     * schema), not the colour {@link PromptBuilder}. The bluebell mini-batch is submitted as a
     * homogeneous batch so its ~bluebell system prompt caches across every request, mirroring
     * the coastal/inland homogeneity of the colour buckets.
     *
     * @param customId  the Anthropic custom ID (produced via {@link CustomIdFactory#forBluebell})
     * @param model     the evaluation model to invoke
     * @param data      the atmospheric data (must carry a bluebell condition score)
     * @param maxTokens Anthropic {@code maxTokens} for this request
     * @return a fully formed batch request ready for submission
     */
    public BatchCreateParams.Request buildBluebellRequest(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens) {
        Objects.requireNonNull(customId, "customId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(data, "data");

        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(maxTokens)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(bluebellBuilder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(bluebellBuilder.buildOutputConfig())
                        .addUserMessage(bluebellBuilder.buildUserMessage(data))
                        .build())
                .build();
    }

    /**
     * Builds a batch request for a single woodland-conditions evaluation task.
     *
     * <p>Uses the standalone {@link WoodlandPromptBuilder}. Woodland tasks form their own
     * homogeneous bucket for the same reason bluebell does — the system prompt only caches
     * across requests that share it, so interleaving woodland with sky or bluebell requests in
     * one batch would cost a cache miss on every boundary.
     *
     * <p>Unlike the bluebell request this has no precondition on {@code data}: the woodland
     * builder derives its deterministic hint from the atmospheric data it is handed, so there is
     * no augmentor step a caller can forget.
     *
     * @param customId  the Anthropic custom ID (produced via {@link CustomIdFactory#forWoodland})
     * @param model     the evaluation model to invoke
     * @param data      the atmospheric data for the slot
     * @param maxTokens Anthropic {@code maxTokens} for this request
     * @return a fully formed batch request ready for submission
     */
    public BatchCreateParams.Request buildWoodlandRequest(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens) {
        Objects.requireNonNull(customId, "customId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(data, "data");

        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(maxTokens)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(woodlandBuilder.getSystemPrompt())
                                        .cacheControl(CacheControlEphemeral.builder().build())
                                        .build()))
                        .outputConfig(woodlandBuilder.buildOutputConfig())
                        .addUserMessage(woodlandBuilder.buildUserMessage(data))
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
