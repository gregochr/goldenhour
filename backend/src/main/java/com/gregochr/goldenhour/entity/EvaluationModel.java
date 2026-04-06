package com.gregochr.goldenhour.entity;

/**
 * Identifies which evaluation path produced a {@code ForecastEvaluationEntity} row.
 *
 * <p>{@code HAIKU} rows carry a 1–5 {@code rating}; {@code SONNET} rows carry
 * {@code fierySkyPotential} and {@code goldenHourPotential} (0–100 each);
 * {@code WILDLIFE} rows skip Claude entirely and carry only comfort weather fields.
 */
public enum EvaluationModel {

    /** Claude Haiku — lower cost, 1–5 rating output. */
    HAIKU("4.5", "claude-haiku-4-5"),

    /** Claude Sonnet — higher accuracy, dual 0–100 score output. */
    SONNET("4.6", "claude-sonnet-4-6"),

    /** Claude Opus — highest accuracy, dual 0–100 score output. */
    OPUS("4.6", "claude-opus-4-6"),

    /** No Claude call — raw comfort weather data only (temperature, wind, rain). */
    WILDLIFE(null, null);

    private final String version;
    private final String modelId;

    EvaluationModel(String version, String modelId) {
        this.version = version;
        this.modelId = modelId;
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
     * Returns the Anthropic API model identifier (e.g. "claude-haiku-4-5"), or null for WILDLIFE.
     *
     * @return model identifier string, or null
     */
    public String getModelId() {
        return modelId;
    }
}
