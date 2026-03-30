package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless evaluator for briefing slot verdicts, region rollups, and flag generation.
 *
 * <p>All thresholds mirror those used by {@link WeatherTriageEvaluator} in the full
 * forecast pipeline so that briefing and evaluation agree on weather condition boundaries.
 */
@Component
public class BriefingVerdictEvaluator {

    /** Low cloud percentage above which conditions are STANDDOWN. */
    static final int CLOUD_STANDDOWN = 80;

    /** Low cloud percentage above which conditions are MARGINAL. */
    static final int CLOUD_MARGINAL = 50;

    /** Precipitation in mm above which conditions are STANDDOWN. */
    static final BigDecimal PRECIP_STANDDOWN = new BigDecimal("2.0");

    /** Precipitation in mm above which conditions are MARGINAL. */
    static final BigDecimal PRECIP_MARGINAL = new BigDecimal("0.5");

    /** Visibility in metres below which conditions are STANDDOWN. */
    static final int VISIBILITY_STANDDOWN = 5000;

    /** Visibility in metres below which conditions are MARGINAL. */
    static final int VISIBILITY_MARGINAL = 10000;

    /** Humidity percentage above which conditions are MARGINAL (mist risk). */
    static final int HUMIDITY_MARGINAL = 90;

    /**
     * Core weather metrics used for verdict determination and flag generation.
     *
     * @param lowCloud   low cloud cover percentage
     * @param precip     precipitation in mm
     * @param visibility visibility in metres
     * @param humidity   relative humidity percentage
     */
    public record WeatherMetrics(int lowCloud, BigDecimal precip, int visibility, int humidity) {
    }

    /**
     * Tide context for flag generation.
     *
     * @param tideState       HIGH/MID/LOW or null for inland
     * @param tideAligned     whether tide matches the location's preference
     * @param isKingTide      whether this is a statistically exceptional tide (exceeds P95)
     * @param isSpringTide    whether this is a statistically high tide (exceeds 125% avg)
     * @param lunarTideType   astronomical tide classification, or null for inland
     * @param tidesNotAligned true when the coastal tide demotion was applied
     */
    public record TideContext(String tideState, boolean tideAligned,
            boolean isKingTide, boolean isSpringTide,
            LunarTideType lunarTideType, boolean tidesNotAligned) {
    }

    /**
     * Determines the verdict for a slot based on weather conditions.
     *
     * @param lowCloud   low cloud cover percentage
     * @param precip     precipitation in mm
     * @param visibility visibility in metres
     * @param humidity   relative humidity percentage
     * @return the verdict
     */
    public Verdict determineVerdict(int lowCloud, BigDecimal precip, int visibility, int humidity) {
        if (lowCloud > CLOUD_STANDDOWN || precip.compareTo(PRECIP_STANDDOWN) > 0
                || visibility < VISIBILITY_STANDDOWN) {
            return Verdict.STANDDOWN;
        }
        if (lowCloud > CLOUD_MARGINAL || precip.compareTo(PRECIP_MARGINAL) > 0
                || visibility < VISIBILITY_MARGINAL || humidity > HUMIDITY_MARGINAL) {
            return Verdict.MARGINAL;
        }
        return Verdict.GO;
    }

    /**
     * Builds human-readable flag strings for a slot.
     *
     * @param weather the core weather metrics
     * @param tide    the tide context (may have null tideState for inland locations)
     * @return list of flag strings
     */
    public List<String> buildFlags(WeatherMetrics weather, TideContext tide) {
        List<String> flags = new ArrayList<>();
        if (weather.lowCloud() > CLOUD_STANDDOWN) {
            flags.add("Sun blocked");
        } else if (weather.lowCloud() > CLOUD_MARGINAL) {
            flags.add("Partial cloud");
        }
        if (weather.precip().compareTo(PRECIP_STANDDOWN) > 0) {
            flags.add("Active rain");
        } else if (weather.precip().compareTo(PRECIP_MARGINAL) > 0) {
            flags.add("Light rain");
        }
        if (weather.visibility() < VISIBILITY_STANDDOWN) {
            flags.add("Poor visibility");
        } else if (weather.visibility() < VISIBILITY_MARGINAL) {
            flags.add("Reduced visibility");
        }
        if (weather.humidity() > HUMIDITY_MARGINAL) {
            flags.add("Mist risk");
        }
        String combinedLabel = combinedTideLabel(tide);
        if (combinedLabel != null) {
            flags.add(combinedLabel);
        }
        if (tide.tidesNotAligned()) {
            flags.add("Tide not aligned");
        }
        if (tide.tideAligned()) {
            flags.add("Tide aligned");
        }
        return flags;
    }

    /**
     * Rolls up individual slot verdicts to a region-level verdict using majority vote.
     *
     * @param slots the location slots
     * @return GO if majority GO, STANDDOWN if majority STANDDOWN, MARGINAL otherwise
     */
    public Verdict rollUpVerdict(List<BriefingSlot> slots) {
        if (slots.isEmpty()) {
            return Verdict.MARGINAL;
        }
        long goCount = slots.stream().filter(s -> s.verdict() == Verdict.GO).count();
        long standdownCount = slots.stream().filter(s -> s.verdict() == Verdict.STANDDOWN).count();

        if (goCount > slots.size() / 2) {
            return Verdict.GO;
        }
        if (standdownCount > slots.size() / 2) {
            return Verdict.STANDDOWN;
        }
        return Verdict.MARGINAL;
    }

    /**
     * Extracts tide highlights from slots using combined lunar + statistical labels.
     *
     * @param slots the location slots
     * @return list of notable tide events (e.g. "King Tide, Extra Extra High at Bamburgh")
     */
    public List<String> buildTideHighlights(List<BriefingSlot> slots) {
        List<String> highlights = new ArrayList<>();
        for (BriefingSlot slot : slots) {
            TideContext ctx = new TideContext(
                    slot.tide().tideState(), slot.tide().tideAligned(),
                    slot.tide().isKingTide(), slot.tide().isSpringTide(),
                    slot.tide().lunarTideType(), false);
            String label = combinedTideLabel(ctx);
            if (label != null) {
                highlights.add(label + " at " + slot.locationName());
            }
        }
        return highlights;
    }

    /**
     * Generates a combined tide label from lunar and statistical dimensions.
     *
     * <p>Examples: "King Tide, Extra Extra High", "Spring Tide", "Extra High".
     * Returns null when neither dimension is noteworthy.
     *
     * @param tide the tide context
     * @return combined label, or null if nothing notable
     */
    static String combinedTideLabel(TideContext tide) {
        // Lunar dimension
        String lunarLabel = null;
        if (tide.lunarTideType() == LunarTideType.KING_TIDE) {
            lunarLabel = "King Tide";
        } else if (tide.lunarTideType() == LunarTideType.SPRING_TIDE) {
            lunarLabel = "Spring Tide";
        }

        // Statistical dimension (renamed from "King"/"Spring" to avoid confusion)
        String statLabel = null;
        if (tide.isKingTide()) {
            statLabel = "Extra Extra High";
        } else if (tide.isSpringTide()) {
            statLabel = "Extra High";
        }

        if (lunarLabel != null && statLabel != null) {
            return lunarLabel + ", " + statLabel;
        }
        if (lunarLabel != null) {
            return lunarLabel;
        }
        return statLabel;
    }

    /**
     * Builds a one-line summary for a region.
     *
     * @param verdict        the region verdict
     * @param slots          the child slots
     * @param tideHighlights tide highlight strings
     * @return human-readable summary
     */
    public String buildRegionSummary(Verdict verdict, List<BriefingSlot> slots,
            List<String> tideHighlights) {
        long goCount = slots.stream().filter(s -> s.verdict() == Verdict.GO).count();
        int total = slots.size();

        String conditionText;
        if (verdict == Verdict.GO) {
            conditionText = "Clear at " + goCount + " of " + total + " location"
                    + (total != 1 ? "s" : "");
        } else if (verdict == Verdict.STANDDOWN) {
            long standdownCount = slots.stream()
                    .filter(s -> s.verdict() == Verdict.STANDDOWN).count();
            if (standdownCount == total) {
                conditionText = "Heavy cloud and rain across all " + total + " location"
                        + (total != 1 ? "s" : "");
            } else {
                conditionText = "Poor conditions at " + standdownCount + " of " + total
                        + " location" + (total != 1 ? "s" : "");
            }
        } else {
            conditionText = "Mixed conditions across " + total + " location"
                    + (total != 1 ? "s" : "");
        }

        if (!tideHighlights.isEmpty()) {
            return conditionText + ", " + String.join(", ", tideHighlights).toLowerCase();
        }
        return conditionText;
    }
}
