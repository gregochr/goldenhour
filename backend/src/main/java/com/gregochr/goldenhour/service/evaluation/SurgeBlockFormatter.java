package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.StormSurgeBreakdown;

/**
 * Formats the STORM SURGE FORECAST prompt block from a {@link StormSurgeBreakdown}.
 *
 * <p>Shared between {@link PromptBuilder} (non-coastal with surge data)
 * and {@link CoastalPromptBuilder} (coastal locations). Single source of
 * truth — both callers produce identical surge text.
 *
 * <p>Stateless, no Spring dependencies.
 */
public final class SurgeBlockFormatter {

    private SurgeBlockFormatter() {
        // utility class
    }

    /**
     * Formats the surge block as a prompt string. Returns an empty string
     * if the surge is null or insignificant.
     *
     * @param surge              the surge breakdown
     * @param adjustedRangeM     adjusted tidal range including surge, or null
     * @param astronomicalRangeM astronomical tidal range before surge, or null
     * @return the formatted surge block, or empty string if not applicable
     */
    public static String format(StormSurgeBreakdown surge,
                                Double adjustedRangeM, Double astronomicalRangeM) {
        if (surge == null || !surge.isSignificant()) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("STORM SURGE FORECAST:\n");
        sb.append(String.format("- Pressure effect: %+.2fm (%s pressure %.0f hPa)%n",
                surge.pressureRiseMetres(),
                surge.pressureRiseMetres() > 0 ? "low" : "high",
                surge.pressureHpa()));
        if (surge.windRiseMetres() >= 0.02) {
            double windKnots = surge.windSpeedMs() * 1.94384;
            sb.append(String.format("- Wind effect: +%.2fm (onshore wind %.0f kn)%n",
                    surge.windRiseMetres(), windKnots));
        }
        sb.append(String.format("- Total estimated surge: +%.2fm (upper bound)%n",
                surge.totalSurgeMetres()));
        if (adjustedRangeM != null && astronomicalRangeM != null) {
            sb.append(String.format(
                    "- Adjusted tidal range: up to %.1fm (predicted %.1fm + %.2fm surge)%n",
                    adjustedRangeM, astronomicalRangeM, surge.totalSurgeMetres()));
        }
        sb.append(String.format("- Risk level: %s%n", surge.riskLevel()));
        sb.append("- Note: Upper-bound estimate. Tide-surge interaction means "
                + "actual level at HW is typically less.\n");
        return sb.toString();
    }
}
