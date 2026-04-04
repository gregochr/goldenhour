package com.gregochr.goldenhour.entity;

/**
 * Synoptic-scale forecast stability classification for a grid cell.
 *
 * <p>Determines how many days ahead the forecast is reliable enough to
 * justify a Claude evaluation. Active weather degrades Open-Meteo cloud
 * cover skill beyond T+0, wasting API credits on evaluations that will
 * be invalidated at the next triage cycle.
 */
public enum ForecastStability {

    /**
     * Synoptically settled — high pressure dominant, low precipitation
     * probability, no frontal signals. Forecast reliable across the full
     * app window (T through T+3).
     */
    SETTLED,

    /**
     * Mixed signals — some instability or frontal approach but core
     * pattern holding. Forecast reliable T and T+1 only.
     */
    TRANSITIONAL,

    /**
     * Active weather — frontal system present or approaching, high
     * precipitation probability variance, falling pressure. Forecast
     * reliable T only.
     */
    UNSETTLED;

    /**
     * How many days ahead to evaluate for this stability level.
     * T = 0, T+1 = 1, T+2 = 2, T+3 = 3.
     * Hard cap at 3 — the app never shows beyond T+3 for stability purposes.
     *
     * @return maximum days ahead (inclusive)
     */
    public int evaluationWindowDays() {
        return switch (this) {
            case SETTLED      -> 3;
            case TRANSITIONAL -> 1;
            case UNSETTLED    -> 0;
        };
    }
}
