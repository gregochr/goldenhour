package com.gregochr.goldenhour.model;

import java.util.Map;

/**
 * What one Run Config tab costs, measured from the last {@code windowDays} days of
 * {@code api_call_log} and, for models that have not run, estimated.
 *
 * @param windowDays  the length of the window the figures are measured over
 * @param runsPerDay  runs (job runs for Briefing and Aurora, pipeline cycles for the batch tabs) per
 *                    day, averaged over the window; zero when nothing was logged
 * @param callsPerRun successful calls per run
 * @param measured    per model that ran, keyed by {@code EvaluationModel} name
 * @param estimates   per selectable model that did not run, priced on the tab's average token mix
 */
public record RunCostProfile(
        int windowDays,
        double runsPerDay,
        double callsPerRun,
        Map<String, ModelCost> measured,
        Map<String, ModelCost> estimates) {

    /**
     * Profile of a tab with no calls in the window.
     *
     * @param windowDays the window length
     * @return a profile with zero runs and no figures
     */
    public static RunCostProfile empty(int windowDays) {
        return new RunCostProfile(windowDays, 0, 0, Map.of(), Map.of());
    }

    /**
     * Cost of one model on one tab.
     *
     * @param calls      successful calls a measured figure rests on; zero for an estimate
     * @param usdPerCall dollars per successful call
     * @param usdPerDay  dollars per day, averaged over the window
     */
    public record ModelCost(long calls, double usdPerCall, double usdPerDay) {
    }

    /**
     * A model's configured token prices, in USD per million tokens.
     *
     * @param inputUsdPerMtok  input rate
     * @param outputUsdPerMtok output rate
     */
    public record TokenRates(double inputUsdPerMtok, double outputUsdPerMtok) {
    }
}
