package com.gregochr.goldenhour.entity;

import com.anthropic.models.messages.Model;

/**
 * Identifies which evaluation path produced a {@code ForecastEvaluationEntity} row.
 *
 * <p>{@code HAIKU} rows carry a 1–5 {@code rating}; {@code SONNET} rows carry
 * {@code fierySkyPotential} and {@code goldenHourPotential} (0–100 each);
 * {@code WILDLIFE} rows skip Claude entirely and carry only comfort weather fields.
 */
public enum EvaluationModel {

    /** Claude Haiku — lower cost, 1–5 rating output. */
    HAIKU("4.5", Model.CLAUDE_HAIKU_4_5_20251001),

    /** Claude Sonnet — higher accuracy, dual 0–100 score output. */
    SONNET("4.6", Model.CLAUDE_SONNET_4_6),

    /** Claude Sonnet with extended thinking — same model, thinking enabled. */
    SONNET_ET("4.6", Model.CLAUDE_SONNET_4_6),

    /**
     * Claude Sonnet 5.5 — selectable only, never a default. Thinking is left at the model's
     * adaptive default (it rejects {@code thinking: disabled}) and effort is sent as low. The enum
     * name is capped at 10 characters because every column storing a model name is VARCHAR(10) in
     * production (as of 2026-10-04): api_call_log.evaluation_model,
     * briefing_model_test_result.evaluation_model, forecast_evaluation.evaluation_model,
     * job_run.evaluation_model, model_selection.active_model, model_test_result.evaluation_model,
     * prompt_test_result.evaluation_model, prompt_test_run.evaluation_model and
     * sky_rating_eval_run.model.
     *
     * <p>A refusal from this model counts as a failed result for {@code LocationFailureService}
     * exactly like any other failed result (it can contribute to a place's auto-disable count).
     */
    SONNET_55("5.5", Model.CLAUDE_SONNET_5_5),

    /** Claude Opus — highest accuracy, dual 0–100 score output. */
    OPUS("4.6", Model.CLAUDE_OPUS_4_6),

    /** Claude Opus with extended thinking — same model, thinking enabled. */
    OPUS_ET("4.6", Model.CLAUDE_OPUS_4_6),

    /** No Claude call — raw comfort weather data only (temperature, wind, rain). */
    WILDLIFE(null, null);

    private static final int SONNET_55_MAX_TOKENS = 4096;

    private final String version;
    private final String modelId;

    /**
     * Each model id comes from the SDK's typed {@link Model} constants, so a typo is a compile error
     * and the SDK's own deprecation markers are visible at the point of use. {@code WILDLIFE} makes
     * no Claude call and so has no model.
     */
    EvaluationModel(String version, Model model) {
        this.version = version;
        this.modelId = model == null ? null : model.asString();
    }

    /**
     * Returns the model family version (e.g. "4.5", "4.6"), or null for non-Claude models.
     *
     * @return version string, or null
     */
    public String getVersion() {
        return version;
    }

    /**
     * The billing tier a model is priced at. Several models share a tier (the extended-thinking
     * variants bill at their base model's rates), and WILDLIFE makes no API call at all.
     */
    public enum PricingTier {
        /** Haiku rates. */
        HAIKU,
        /** Sonnet rates — also used by SONNET_ET. */
        SONNET,
        /** Sonnet 5.5 rates. */
        SONNET_55,
        /** Opus rates — also used by OPUS_ET. */
        OPUS,
        /** No API call, so no cost. */
        FREE
    }

    /**
     * Returns the billing tier this model is priced at — the single source of truth for the
     * model-to-rate mapping, which {@code CostCalculator} previously repeated once per rate kind.
     *
     * @return the pricing tier
     */
    public PricingTier pricingTier() {
        return switch (this) {
            case HAIKU -> PricingTier.HAIKU;
            case SONNET, SONNET_ET -> PricingTier.SONNET;
            case SONNET_55 -> PricingTier.SONNET_55;
            case OPUS, OPUS_ET -> PricingTier.OPUS;
            case WILDLIFE -> PricingTier.FREE;
        };
    }

    /**
     * Returns the Anthropic API model identifier (e.g. "claude-haiku-4-5-20251001"), or null for WILDLIFE.
     *
     * @return model identifier string, or null
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Returns {@code true} if this variant uses extended thinking (budget-based thinking mode).
     *
     * @return true for SONNET_ET and OPUS_ET
     */
    public boolean isExtendedThinking() {
        return this == SONNET_ET || this == OPUS_ET;
    }

    /**
     * Returns the recommended max output tokens for this model.
     *
     * <p>Sonnet tends to produce chain-of-thought reasoning in its output, so it needs
     * a larger token budget to complete the full JSON schema. Haiku and Opus are terser.
     * Sonnet 5.5 thinks by default and thinking tokens count against the limit, so it gets 4096.
     *
     * @return max output tokens
     */
    public int getMaxTokens() {
        if (this == SONNET_55) {
            return SONNET_55_MAX_TOKENS;
        }
        return (this == SONNET || this == SONNET_ET) ? 1024 : 512;
    }
}
