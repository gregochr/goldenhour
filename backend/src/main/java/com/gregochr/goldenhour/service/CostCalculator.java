package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.TokenUsage;
import org.springframework.stereotype.Service;

/**
 * Calculates operational costs for external API calls.
 *
 * <p>Token-based calculations return costs in micro-dollars (1 dollar = 1,000,000 µ$).
 * Legacy flat-rate methods are retained for backward compatibility.
 */
@Service
public class CostCalculator {

    private final CostProperties costProperties;

    /**
     * Constructs a {@code CostCalculator}.
     *
     * @param costProperties cost configuration from {@code application.yml}
     */
    public CostCalculator(CostProperties costProperties) {
        this.costProperties = costProperties;
    }

    /**
     * Calculates cost from actual token usage in micro-dollars.
     *
     * @param model   the Anthropic model used
     * @param usage   token counts from the API response
     * @param isBatch whether this was a batch API call (50% discount)
     * @return cost in micro-dollars
     */
    public long calculateCostMicroDollars(EvaluationModel model, TokenUsage usage, boolean isBatch) {
        if (usage == null || model == null) {
            return 0;
        }
        long cost = microDollarsForTokens(usage.inputTokens(), getInputRate(model))
                + microDollarsForTokens(usage.outputTokens(), getOutputRate(model))
                + microDollarsForTokens(usage.cacheCreationInputTokens(), getCacheWriteRate(model))
                + microDollarsForTokens(usage.cacheReadInputTokens(), getCacheReadRate(model));
        return isBatch ? cost / 2 : cost;
    }

    /**
     * Calculates cost from actual token usage in micro-dollars (non-batch).
     *
     * @param model the Anthropic model used
     * @param usage token counts from the API response
     * @return cost in micro-dollars
     */
    public long calculateCostMicroDollars(EvaluationModel model, TokenUsage usage) {
        return calculateCostMicroDollars(model, usage, false);
    }

    /**
     * Calculates flat cost for non-Anthropic services in micro-dollars.
     *
     * @param service the external service
     * @return cost in micro-dollars
     */
    public long calculateFlatCostMicroDollars(ServiceName service) {
        return switch (service) {
            case WORLD_TIDES -> costProperties.getWorldTidesMicroDollars();
            case OPEN_METEO_FORECAST, OPEN_METEO_AIR_QUALITY -> costProperties.getOpenMeteoMicroDollars();
            case ANTHROPIC -> 0;
            case LIGHT_POLLUTION -> 0L;
        };
    }

    // --- Legacy flat-rate methods (deprecated) ---

    /**
     * Calculates the cost of a single API call in pence (legacy flat rate).
     *
     * @param service the external service
     * @param model   the evaluation model for Anthropic calls, or null
     * @return cost in pence
     * @deprecated Use {@link #calculateCostMicroDollars(EvaluationModel, TokenUsage)} for Anthropic
     *             or {@link #calculateFlatCostMicroDollars(ServiceName)} for other services.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public int calculateCost(ServiceName service, EvaluationModel model) {
        return switch (service) {
            case ANTHROPIC -> {
                if (model == null) {
                    yield costProperties.getAnthropicSonnetPence();
                }
                yield switch (model) {
                        case HAIKU -> costProperties.getAnthropicHaikuPence();
                        case OPUS, OPUS_ET -> costProperties.getAnthropicOpusPence();
                        default -> costProperties.getAnthropicSonnetPence();
                    };
            }
            case WORLD_TIDES -> costProperties.getWorldTidesPence();
            case OPEN_METEO_FORECAST, OPEN_METEO_AIR_QUALITY -> costProperties.getOpenMeteoPence();
            case LIGHT_POLLUTION -> 0;
        };
    }

    /**
     * Calculates the cost of a single API call without an evaluation model (non-Anthropic).
     *
     * @param service the external service
     * @return cost in pence
     * @deprecated Use {@link #calculateFlatCostMicroDollars(ServiceName)} instead.
     */
    @Deprecated
    public int calculateCost(ServiceName service) {
        return calculateCost(service, null);
    }

    private long microDollarsForTokens(long tokens, double usdPerMTok) {
        return Math.round((double) tokens * usdPerMTok);
    }

    private double getInputRate(EvaluationModel model) {
        return switch (model) {
            case HAIKU -> costProperties.getHaikuInputUsdPerMtok();
            case SONNET, SONNET_ET -> costProperties.getSonnetInputUsdPerMtok();
            case OPUS, OPUS_ET -> costProperties.getOpusInputUsdPerMtok();
            default -> costProperties.getSonnetInputUsdPerMtok();
        };
    }

    private double getOutputRate(EvaluationModel model) {
        return switch (model) {
            case HAIKU -> costProperties.getHaikuOutputUsdPerMtok();
            case SONNET, SONNET_ET -> costProperties.getSonnetOutputUsdPerMtok();
            case OPUS, OPUS_ET -> costProperties.getOpusOutputUsdPerMtok();
            default -> costProperties.getSonnetOutputUsdPerMtok();
        };
    }

    private double getCacheWriteRate(EvaluationModel model) {
        return switch (model) {
            case HAIKU -> costProperties.getHaikuCacheWriteUsdPerMtok();
            case SONNET, SONNET_ET -> costProperties.getSonnetCacheWriteUsdPerMtok();
            case OPUS, OPUS_ET -> costProperties.getOpusCacheWriteUsdPerMtok();
            default -> costProperties.getSonnetCacheWriteUsdPerMtok();
        };
    }

    private double getCacheReadRate(EvaluationModel model) {
        return switch (model) {
            case HAIKU -> costProperties.getHaikuCacheReadUsdPerMtok();
            case SONNET, SONNET_ET -> costProperties.getSonnetCacheReadUsdPerMtok();
            case OPUS, OPUS_ET -> costProperties.getOpusCacheReadUsdPerMtok();
            default -> costProperties.getSonnetCacheReadUsdPerMtok();
        };
    }
}
