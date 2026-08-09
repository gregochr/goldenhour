package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.TokenUsage;
import org.springframework.stereotype.Service;

/**
 * Calculates operational costs for external API calls.
 *
 * <p>All calculations return costs in micro-dollars (1 dollar = 1,000,000 µ$), derived from
 * actual token usage for Anthropic calls and flat per-call rates for other services.
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
            case OPEN_METEO_FORECAST, OPEN_METEO_AIR_QUALITY, OPEN_METEO_MARINE ->
                    costProperties.getOpenMeteoMicroDollars();
            case ANTHROPIC -> 0;
            case LIGHT_POLLUTION -> 0L;
        };
    }

    /**
     * Prices a metered call from the credit count the provider reported.
     *
     * <p>Only WorldTides is credit-metered; every other non-Anthropic service is flat or free, so
     * they fall through to {@link #calculateFlatCostMicroDollars}. A null credit count also falls
     * through, which is deliberate — a call whose credits are unknown is not a free call, and
     * returning zero would quietly under-count every error path.
     *
     * @param service the external service
     * @param credits credits the provider reported billing, or null if it did not report any
     * @return cost in micro-dollars
     */
    public long calculateMeteredCostMicroDollars(ServiceName service, Integer credits) {
        if (service != ServiceName.WORLD_TIDES || credits == null || credits <= 0) {
            return calculateFlatCostMicroDollars(service);
        }
        return credits * costProperties.getWorldTidesMicroDollarsPerCredit();
    }

    private long microDollarsForTokens(long tokens, double usdPerMTok) {
        return Math.round((double) tokens * usdPerMTok);
    }

    private double getInputRate(EvaluationModel model) {
        return switch (model.pricingTier()) {
            case HAIKU -> costProperties.getHaikuInputUsdPerMtok();
            case SONNET -> costProperties.getSonnetInputUsdPerMtok();
            case OPUS -> costProperties.getOpusInputUsdPerMtok();
            case FREE -> 0.0;
        };
    }

    private double getOutputRate(EvaluationModel model) {
        return switch (model.pricingTier()) {
            case HAIKU -> costProperties.getHaikuOutputUsdPerMtok();
            case SONNET -> costProperties.getSonnetOutputUsdPerMtok();
            case OPUS -> costProperties.getOpusOutputUsdPerMtok();
            case FREE -> 0.0;
        };
    }

    private double getCacheWriteRate(EvaluationModel model) {
        return switch (model.pricingTier()) {
            case HAIKU -> costProperties.getHaikuCacheWriteUsdPerMtok();
            case SONNET -> costProperties.getSonnetCacheWriteUsdPerMtok();
            case OPUS -> costProperties.getOpusCacheWriteUsdPerMtok();
            case FREE -> 0.0;
        };
    }

    private double getCacheReadRate(EvaluationModel model) {
        return switch (model.pricingTier()) {
            case HAIKU -> costProperties.getHaikuCacheReadUsdPerMtok();
            case SONNET -> costProperties.getSonnetCacheReadUsdPerMtok();
            case OPUS -> costProperties.getOpusCacheReadUsdPerMtok();
            case FREE -> 0.0;
        };
    }
}
