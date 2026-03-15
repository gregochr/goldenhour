package com.gregochr.goldenhour.model;

/**
 * Per-location state in the forecast run finite state machine.
 *
 * <p>Normal flow: PENDING → FETCHING_WEATHER → FETCHING_CLOUD → FETCHING_TIDES → EVALUATING → COMPLETE.
 * Any phase can transition to FAILED. Tasks skipped by optimisation strategies go directly to SKIPPED.
 */
public enum LocationTaskState {

    /** Task registered but not yet started. */
    PENDING,

    /** Fetching base weather data from Open-Meteo. */
    FETCHING_WEATHER,

    /** Fetching directional cloud and cloud approach data. */
    FETCHING_CLOUD,

    /** Fetching tide data from WorldTides. */
    FETCHING_TIDES,

    /** Calling Claude for evaluation. */
    EVALUATING,

    /** Successfully completed with persisted result. */
    COMPLETE,

    /** Failed at one of the processing phases. */
    FAILED,

    /** Skipped by an optimisation strategy. */
    SKIPPED
}
