package com.gregochr.goldenhour.model;

/**
 * Per-location state in the forecast run finite state machine.
 *
 * <p>Normal flow: PENDING → FETCHING_WEATHER → FETCHING_CLOUD → FETCHING_TIDES → EVALUATING → COMPLETE.
 * Any phase can transition to FAILED. Tasks excluded before triage — a deselected slot, or an event
 * that has already passed — go directly to SKIPPED; sentinel sampling reaches it later, after triage.
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

    /** Excluded before triage (deselected slot, event already passed), or stopped by sentinel sampling. */
    SKIPPED,

    /** Weather data fetched but Claude skipped due to heuristic triage. */
    TRIAGED
}
