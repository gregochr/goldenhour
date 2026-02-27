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
    HAIKU,

    /** Claude Sonnet — higher accuracy, dual 0–100 score output. */
    SONNET,

    /** Claude Opus — highest accuracy, dual 0–100 score output. */
    OPUS,

    /** No Claude call — raw comfort weather data only (temperature, wind, rain). */
    WILDLIFE
}
