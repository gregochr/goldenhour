package com.gregochr.goldenhour.entity;

/**
 * Distinguishes re-run modes for model comparison tests.
 *
 * <p>{@code FRESH_DATA} re-runs fetch new weather data from Open-Meteo (same locations,
 * different atmospheric conditions). {@code SAME_DATA} re-runs replay the exact atmospheric
 * data from a previous run to test Claude's determinism.
 */
public enum RerunType {

    /** Same locations, fresh weather data from Open-Meteo. */
    FRESH_DATA,

    /** Same locations, identical weather data replayed from a previous run. */
    SAME_DATA
}
