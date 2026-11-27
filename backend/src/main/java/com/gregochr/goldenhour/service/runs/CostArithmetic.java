package com.gregochr.goldenhour.service.runs;

/**
 * The two pieces of cost arithmetic the app's Cost trends and the CI spend block share, so both are worked out one
 * way. Nothing here knows about run types or suites.
 */
final class CostArithmetic {

    private static final double PERCENT = 100.0;

    private CostArithmetic() {
    }

    /**
     * The whole-percent change of a figure on a base.
     *
     * @param now  the figure
     * @param base the base
     * @return the signed change, or null when the base is zero or negative
     */
    static Integer percent(double now, double base) {
        return base <= 0 ? null : (int) Math.round((now - base) / base * PERCENT);
    }

    /**
     * The cost per run now as a multiple of the cost per run before.
     *
     * @param lastGbp   pounds in the last period
     * @param lastRuns  runs in the last period
     * @param priorGbp  pounds in the prior period
     * @param priorRuns runs in the prior period
     * @return the multiple, or null when either period has no runs or the prior period spent nothing
     */
    static Double perRunMultiple(double lastGbp, int lastRuns, double priorGbp, int priorRuns) {
        if (priorRuns == 0 || lastRuns == 0 || priorGbp <= 0) {
            return null;
        }
        return (lastGbp / lastRuns) / (priorGbp / priorRuns);
    }
}
