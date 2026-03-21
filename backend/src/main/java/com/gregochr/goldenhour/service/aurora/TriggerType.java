package com.gregochr.goldenhour.service.aurora;

/**
 * Distinguishes how an aurora alert was triggered — from a forecast or from real-time data.
 *
 * <p>Passed to {@link ClaudeAuroraInterpreter} so the prompt can instruct Claude to use
 * planning language (forecast-based) vs urgent action language (real-time).
 */
public enum TriggerType {

    /**
     * Alert triggered by Kp forecast for tonight's dark window.
     * Conditions have not started yet — the user needs planning time.
     */
    FORECAST_LOOKAHEAD,

    /**
     * Alert triggered by current Kp index or OVATION probability.
     * Aurora is happening now — the user should act immediately.
     */
    REALTIME
}
