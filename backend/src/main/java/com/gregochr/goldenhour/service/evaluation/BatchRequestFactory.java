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
import java.util.Set;

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
     * @param inlandBuilder    builder for inland (non-tidal) locations
     * @param coastalBuilder   builder for coastal (tidal) locations
     * @param bluebellBuilder  builder for the dedicated bluebell-conditions prompt
     * @param woodlandBuilder  builder for the year-round woodland-conditions prompt
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
     * The system block has {@link CacheControlEphemeral} attached so the <b>~4,700-token</b>
     * system prompt is shared across all requests in a batch.
     *
     * <p>That number is load-bearing, not decorative. Haiku 4.5 — the {@code BATCH_FAR_TERM} model
     * (V92) — will not cache a prefix below <b>4,096 tokens</b>, and below it fails silently rather
     * than erroring. Measured with {@code messages.count_tokens} on
     * {@code claude-haiku-4-5-20251001} (2026-10-04, {@code SystemPromptTokenCountTest}): the inland
     * system prompt is <b>4,726 tokens</b> (17,920 characters) and the coastal <b>4,779</b>
     * (18,179), clearing the floor by 630 and 683 tokens (about 15%). With the structured-output
     * config attached the count endpoint reports 5,339 and 5,392, so the margin only widens if the
     * config counts toward the cached prefix. {@code SystemPromptCacheabilityTest} is the offline
     * character guard for this.
     *
     * <p>The bluebell and woodland variants below carry the same {@code cache_control} block, and
     * whether it does anything <b>depends on the horizon, because it decides the model</b>.
     * {@code ForecastTaskCollector} builds those tasks with the same {@code decision.model()} as
     * the sky path, so T+0/T+1 runs them on {@code BATCH_NEAR_TERM} — Sonnet by default (V92),
     * floor <b>1,024 tokens</b> — and T+2/T+3 on Haiku's 4,096.
     *
     * <p>So: <b>inert on the far-term (Haiku) path</b>, where both prompts sit far below 4,096.
     * Both were measured with {@code messages.count_tokens} on 2026-10-04: woodland is
     * <b>1,190 tokens</b> (4,770 characters; 1,416 with the output config) and bluebell <b>968</b>
     * (3,847 characters; 1,194 with the output config). Against Sonnet's 1,024 floor woodland
     * clears it and <b>does cache</b>; bluebell is 56 tokens <em>under</em> it on the system text
     * alone and over it only if the output config counts toward the cached prefix, which has not
     * been established — treat bluebell's caching as unproven either way. (Counts are for Haiku's
     * tokenizer; Sonnet's can differ slightly.)
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
        return buildSkyRequest(customId, model, data, maxTokens, false).request();
    }

    /**
     * A built sky forecast batch request together with the exact user message that was put into
     * it, so a caller can record the message without ever building it a second time.
     *
     * @param request     the batch request, ready for submission
     * @param userMessage the very string passed to {@code addUserMessage} for {@code request}
     */
    public record ForecastRequest(BatchCreateParams.Request request, String userMessage) {
    }

    /**
     * Builds the same request as {@link #buildForecastRequest} and hands back the user message it
     * carries. One build, one value: the returned {@code userMessage} is the string placed in the
     * request, not a re-derivation of it.
     *
     * <p>The system block carries the default (five-minute) cache lifetime. Only the overload taking
     * warmed prefixes can produce the one-hour lifetime.
     *
     * @param customId  the Anthropic custom ID (produced via {@link CustomIdFactory})
     * @param model     the evaluation model to invoke
     * @param data      the atmospheric data for this evaluation task
     * @param maxTokens Anthropic {@code maxTokens} for this request
     * @return the request and its user message
     */
    public ForecastRequest buildForecastRequestAndPrompt(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens) {
        return buildSkyRequest(customId, model, data, maxTokens, false);
    }

    /**
     * As {@link #buildForecastRequestAndPrompt(String, EvaluationModel, AtmosphericData, int)}, but
     * the system block carries the one-hour cache lifetime exactly when this request's cache prefix
     * ({@link #cachePrefixKey}) is in {@code warmedPrefixes} - that is, the scheduled cycle's primer
     * for that prefix ended with a succeeded request. For any other request the result is identical
     * to the four-argument overload.
     *
     * @param customId        the Anthropic custom ID
     * @param model           the evaluation model to invoke
     * @param data            the atmospheric data for this evaluation task
     * @param maxTokens       Anthropic {@code maxTokens} for this request
     * @param warmedPrefixes  prefix keys a primer has warmed this cycle (may be empty)
     * @return the request and its user message
     */
    public ForecastRequest buildForecastRequestAndPrompt(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens,
            Set<String> warmedPrefixes) {
        Objects.requireNonNull(warmedPrefixes, "warmedPrefixes");
        boolean warmed = warmedPrefixes.contains(cachePrefixKey(model, data));
        return buildSkyRequest(customId, model, data, maxTokens, warmed);
    }

    /**
     * Builds the cache-primer request: identical to the request the five-argument {@link
     * #buildForecastRequestAndPrompt} builds for a warmed prefix (same builder, system block,
     * one-hour lifetime and output config), under a distinct custom id.
     *
     * @param customId  the primer custom id (see {@link CustomIdFactory#forCachePrimer})
     * @param model     the evaluation model to invoke
     * @param data      the atmospheric data of a real task sharing the prefix being primed
     * @param maxTokens Anthropic {@code maxTokens} for this request
     * @return the primer request
     */
    public BatchCreateParams.Request buildCachePrimerRequest(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens) {
        return buildSkyRequest(customId, model, data, maxTokens, true).request();
    }

    /**
     * Names the cache prefix a SKY request for this model and data carries: the model id and which
     * prompt builder (coastal or inland) supplies the system block. Computed per task, so a bucket
     * holding several models or builders yields several keys.
     *
     * @param model the evaluation model
     * @param data  the atmospheric data of the task
     * @return a stable key, equal for two tasks exactly when they share a cache prefix
     */
    public String cachePrefixKey(EvaluationModel model, AtmosphericData data) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(data, "data");
        String builderName = selectBuilder(data) == coastalBuilder ? "coastal" : "inland";
        return model.getModelId() + "|" + builderName;
    }

    private static CacheControlEphemeral skyCacheControl(boolean longLived) {
        CacheControlEphemeral.Builder control = CacheControlEphemeral.builder();
        if (longLived) {
            control.ttl(CacheControlEphemeral.Ttl.TTL_1H);
        }
        return control.build();
    }

    private ForecastRequest buildSkyRequest(
            String customId,
            EvaluationModel model,
            AtmosphericData data,
            int maxTokens,
            boolean longLivedCache) {
        Objects.requireNonNull(customId, "customId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(data, "data");

        PromptBuilder builder = selectBuilder(data);
        String userMessage = buildUserMessage(builder, data);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model.getModelId())
                        .maxTokens(maxTokens)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder()
                                        .text(builder.getSystemPrompt())
                                        .cacheControl(skyCacheControl(longLivedCache))
                                        .build()))
                        .outputConfig(builder.buildOutputConfig())
                        .addUserMessage(userMessage)
                        .build())
                .build();
        return new ForecastRequest(request, userMessage);
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
