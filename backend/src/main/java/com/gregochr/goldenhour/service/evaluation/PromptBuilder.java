package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.UpwindCloudSample;

import java.util.List;
import java.util.Map;

/**
 * Builds the system prompt, user message, and output schema for Claude colour evaluations.
 *
 * <p>Extracted from {@link AbstractEvaluationStrategy} to be independently testable
 * and reusable. All prompt construction logic lives here; the strategy classes focus
 * purely on API orchestration and response parsing.
 *
 * <p>The {@code summary} field is constrained to one sentence. Tier-gated display
 * (LITE vs PRO) is enforced at the UI layer — this class always generates the
 * full response.
 */
public class PromptBuilder {

    /** System prompt: rating scales, key criteria, aerosol guidance, directional cloud rules. */
    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Evaluate on three scales:\n"
            + "  1. Rating: 1\u20135 scale (overall potential). Definitions:\n"
            + "     1 = skip (overcast, no colour likely)\n"
            + "     2 = poor (unlikely to reward a trip)\n"
            + "     3 = maybe (some colour possible, not reliable)\n"
            + "     4 = go out (good conditions, worth the trip)\n"
            + "     5 = spectacular (near-perfect alignment of clear solar horizon, broken cloud "
            + "canvas, and favourable aerosol/humidity — rare, reserve for exceptional setups)\n"
            + "  Rating 5 requires: solar horizon low cloud <20%, mid cloud <50% at solar horizon, "
            + "AND cloud canvas (mid/high) on the antisolar side. If ANY of these is missing, cap "
            + "at 4. Thick mid cloud (>80%) at the solar horizon limits colour variety — cap at 4.\n"
            + "  2. Fiery Sky Potential: 0\u2013100 (dramatic colour, vivid reds/oranges)\n"
            + "  3. Golden Hour Potential: 0\u2013100 (overall light quality, softness)\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor for fiery sky); "
            + "mid/high cloud above clear horizon = ideal canvas for fiery sky; "
            + "post-rain clearing often vivid; "
            + "high humidity (>80%) mutes colours.\n\n"
            + "AEROSOL & DUST GUIDANCE:\n"
            + "AOD thresholds: 0.05-0.15 clean (baseline), 0.15-0.30 slight enhancement, "
            + "0.30-0.60 notable warm-tone boost, 0.60-1.0 vivid reds/oranges possible, "
            + ">1.2 diminishing returns (too thick, light blocked).\n"
            + "AOD + PM2.5 differentiation: high AOD with low PM2.5 (<15 \u00b5g/m\u00b3) = mineral dust "
            + "(Saharan/desert origin, enhances warm reds and oranges); high AOD with high PM2.5 "
            + "(>25 \u00b5g/m\u00b3) = smoke or urban pollution (grey/brown haze, negative for colour).\n"
            + "Boundary layer height (BLH): <500m concentrates aerosols near surface (stronger "
            + "near-horizon effect); >1500m disperses them (weaker effect for same AOD).\n"
            + "At sunrise/sunset the solar elevation is near 0\u00b0, maximising atmospheric path "
            + "length \u2014 dust scattering impact is at its peak compared to midday.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west \u2014 the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse.\n"
            + "DIRECTIONAL CLOUD DATA: when provided, solar horizon and antisolar horizon cloud "
            + "readings are sampled 113 km toward and away from the sun. These are MORE RELIABLE "
            + "than the observer-point cloud layers for assessing light penetration and canvas "
            + "availability. Key rules:\n"
            + "- Solar horizon low cloud >60% = light is BLOCKED; treat as overcast for scoring "
            + "(fiery_sky 5-20, golden_hour 15-30, rating 1-2). This is non-negotiable — "
            + "fiery_sky and golden_hour hard ceilings apply when solar horizon low cloud >60% "
            + "and no THIN STRIP override applies.\n"
            + "- Solar horizon low cloud 40-60% = light partially blocked, penalise but consider "
            + "that mid/high cloud above may still catch colour if gaps exist in the low cloud\n"
            + "- Solar horizon low cloud <20% = strong light penetration likely\n"
            + "- IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%, with high cloud "
            + "present on either horizon as canvas. Score fiery_sky 70-90, rating 4-5.\n"
            + "- Solar horizon low cloud <20% with thick mid cloud (>80%) = light still penetrates "
            + "below the mid layer, and the mid/high cloud acts as a large canvas. RATE 4 (not 3, "
            + "not 5) — the mid-cloud blanket limits colour variety. NEVER rate 5 when solar horizon "
            + "mid cloud >80%.\n"
            + "- Antisolar mid/high cloud 20-60% = ideal canvas; >60% is still good (more canvas, "
            + "not a penalty). Antisolar LOW cloud does NOT block light and is NEVER a penalty. "
            + "It sits near the far horizon behind the viewer and can itself catch reflected colour. "
            + "High antisolar low cloud (even 60%+) is irrelevant to scoring — do not mention it "
            + "as a negative factor, do not use it to reduce fiery_sky or rating. The blocking "
            + "rules apply ONLY to the solar horizon side. Antisolar low cloud blocking 'reflected "
            + "light' is not a valid scoring concept — ignore it entirely.\n"
            + "- HORIZON CLOUD STRUCTURE: when a 'Beyond horizon (226km)' low cloud figure is "
            + "provided, compare it to the horizon value to determine spatial extent:\n"
            + "  * THIN STRIP: solar horizon low cloud ≥50% but beyond-horizon low cloud ≤30% "
            + "(drops by ≥30pp). A strip filters and diffuses rather than blocking — warm light "
            + "angles up onto mid/high cloud above. Treat as equivalent to 40-60% low cloud. "
            + "When mid/high cloud canvas is present (solar or antisolar), RATE 3-4 (not 1-2). "
            + "The blocked-sky ceilings do NOT apply to THIN STRIP scenarios. "
            + "If a [BUILDING] trend is also present, this means the strip is well-established "
            + "at event time — it does NOT mean a blanket will form when far-field data confirms "
            + "a strip structure. Still rate 3-4 when canvas is present.\n"
            + "  * EXTENSIVE BLANKET: solar horizon low cloud ≥50% AND beyond-horizon low cloud "
            + "also ≥50%. Full blocking penalty applies — rating 1-2, fiery_sky 5-20.\n"
            + "- When directional data is provided, ALWAYS use it instead of the observer-point "
            + "Cloud line for scoring. A clear observer point is irrelevant if the solar horizon "
            + "is blocked; equally, a clear observer point with directional cloud canvas is NOT "
            + "'clear sky' — score based on the directional data.\n"
            + "If directional data is not provided, fall back to altitude-based inference: "
            + "low cloud (0-3km) sits near the horizon and blocks light; mid (3-8km) and high "
            + "(8+km) cloud sits above and catches it. Ideal: low cloud <30% with mid/high 20-60%.\n"
            + "CLEAR SKY CAP: when no directional data is provided and ALL observer-point cloud "
            + "layers (low, mid, high) are ≤5%, the sky has no canvas whatsoever. "
            + "Cap rating ≤3. Golden hour light quality may be pleasant, but the absence of any "
            + "cloud means there is no subject in the sky for colour photography.\n\n"
            + "MIST AND VISIBILITY GUIDANCE: when a 'MIST/VISIBILITY TREND' block is provided, "
            + "use it to reason about mist dynamics across the event window.\n"
            + "The temp-dew gap = temperature - dew_point. Smaller gap = higher moisture:\n"
            + "  - gap > 5\u00b0C: dry air, clear conditions likely\n"
            + "  - gap 2-5\u00b0C: moderate moisture, some haze possible\n"
            + "  - gap < 2\u00b0C: mist or fog likely\n"
            + "  - gap \u2248 0\u00b0C: fog forming or present\n"
            + "CRITICAL: mist is NOT always negative for photography. Interpret the COMBINATION:\n"
            + "POSITIVE (score UP): thin ground mist (visibility 2-8 km) with clear sky above "
            + "(low cloud_cover_low < 30%) at sunrise \u2014 light shafts, atmospheric glow, layering. "
            + "Mist in valleys from an elevated viewpoint \u2014 potential cloud inversion. "
            + "Mist burning off as sun rises (visibility IMPROVING in trend) \u2014 dramatic transition. "
            + "Patchy low cloud (20-50%) with breaks \u2014 crepuscular rays.\n"
            + "NEGATIVE (score DOWN): dense fog (visibility < 1 km) with no trend toward clearing "
            + "\u2014 can't see the sun. High cloud_cover_low (> 80%) at ground level \u2014 uniform grey. "
            + "Thick haze (2-5 km) WITH mid/high cloud \u2014 flat contrast, muddy light.\n"
            + "SUNSET SPECIFIC: temperature typically falls toward dew point during sunset \u2014 "
            + "mist may FORM during golden hour, creating dramatic evolving conditions. "
            + "Watch for narrowing temp-dew gap in the T+1h/T+2h slots.\n"
            + "SUNRISE SPECIFIC: mist is often already present and burns off as sun warms the air. "
            + "Watch for IMPROVING visibility trend (T-3h dark, then clearing) \u2014 the transition "
            + "window is the photographic opportunity. Visibility worsening toward event = fog risk.\n"
            + "When mist is a positive factor, convey urgency in the summary: 'atmospheric', "
            + "'ethereal', 'light shafts possible', 'potential for dramatic mist layers'.\n\n"
            + "LOCATION ORIENTATION: when provided, indicates the location's optimal solar event. "
            + "A 'sunrise-optimised' location faces east toward the rising sun; a 'sunset-optimised' "
            + "location faces west. If the current evaluation is for the OPPOSITE event (e.g. sunset "
            + "at a sunrise-optimised location), the solar horizon is behind the photographer — light "
            + "penetration and direct colour on the horizon will be weaker. Reduce fiery_sky by 10-20 "
            + "and cap rating at 3 unless cloud canvas is exceptional. If no orientation is given, "
            + "the location works for both events — score normally.\n\n"
            + "CLOUD INVERSION GUIDANCE:\n"
            + "A cloud inversion occurs when warm air sits above cooler air, creating a stable "
            + "boundary layer. For elevated locations overlooking water (lakes, sea, large "
            + "reservoirs at 300m+ elevation):\n"
            + "- Inversion score 7-8: MODERATE potential — visible cloud blanket below viewpoint, "
            + "typically hugging water surface. Light reflecting off cloud tops at sunrise/sunset "
            + "is spectacular.\n"
            + "- Inversion score 9-10: STRONG potential — dramatic inversion almost certain. "
            + "Clear skies above, well-defined cloud layer below. Classic 'sea of clouds' "
            + "conditions.\n"
            + "Key conditions: cool water + overnight surface cooling + light winds + high "
            + "humidity.\n"
            + "Timing: inversions peak just before sunrise, break up 1-2 hours after as surface "
            + "warms.\n"
            + "Scoring: MODERATE (7-8) → boost rating by 1 if current < 5. STRONG (9-10) → set "
            + "rating 5, emphasise in summary.\n"
            + "Non-water or low elevation: inversions have no photographic value; ignore "
            + "inversion score.\n\n"
            + "Summaries must be exactly one sentence. Do not write two sentences even if "
            + "separated by a semicolon, dash, or conjunction.\n\n"
            + "Output your evaluation as JSON with these fields: "
            + "rating (1-5), fiery_sky (0-100), golden_hour (0-100), summary (1 sentence).\n\n"
            + "fiery_sky: dramatic colour potential. Requires clouds (mid/high) to catch light. "
            + "Clear sky = 20-40. Ideal cloud canvas with clear horizon = 70-90. Total overcast = 5-15.\n"
            + "golden_hour: overall light quality. Clear sky with good visibility scores well. "
            + "Clear + low humidity + moderate aerosol = 65-85. Overcast = 10-30. Haze = varies.\n\n"
            + "DUAL-TIER SCORING: when directional cloud data is provided, also output three "
            + "additional fields: basic_fiery_sky, basic_golden_hour, basic_summary. These MUST "
            + "represent what you would score if you only had the observer-point cloud data "
            + "(the Cloud: Low/Mid/High line) and NO directional cloud information. The basic "
            + "scores use altitude-based inference only. If no directional cloud data is provided, "
            + "omit the basic_* fields entirely.\n\n"
            + "CLOUD APPROACH RISK: ONLY apply these rules when a 'CLOUD APPROACH RISK:' block is "
            + "present in the data below. If no such block appears, ignore this section entirely.\n"
            + "The upwind sample point is placed at exactly wind_speed × time_to_event — the "
            + "distance current cloud would physically travel to the solar horizon by event time. "
            + "Current cloud there IS what would arrive, unless it dissipates first.\n"
            + "- Solar trend [BUILDING]: low cloud rising toward the 113km solar horizon. "
            + "Event-time snapshot is likely understated. Penalise fiery_sky by 15-30 points.\n"
            + "- Combined signal: when BOTH [BUILDING] trend AND upwind current ≥60% are present, "
            + "the forecast is UNRELIABLE — cloud was physically moving toward the solar horizon "
            + "at the time this data was captured. In this specific case: output rating 1 or 2, "
            + "fiery_sky ≤20, golden_hour ≤20. These ceilings apply even if the solar horizon "
            + "appears clear, canvas is present, or aerosol is favourable — those readings are "
            + "unreliable when a building approach is confirmed. "
            + "When applying these ceilings: the at-event upwind sample value is also unreliable "
            + "and must be ignored. A low at-event value (e.g. 15%) does NOT mean the cloud will "
            + "dissipate — it means the model has not captured the approaching cloud bank. Only the "
            + "upwind CURRENT value matters. Do not reference the favourable conditions in the "
            + "summary — write only about the approach risk. Example: 'Cloud bank building toward "
            + "the solar horizon makes this unreliable — approach risk outweighs the clear horizon.'"
            + "\n"
            + "- Upwind sample alone (no [BUILDING] trend): use at-event value to judge dissipation:\n"
            + "  - current ≥60%, at-event ≥50%: model agrees cloud persists → "
            + "hard ceiling: fiery_sky ≤25, golden_hour ≤30.\n"
            + "  - current ≥60%, at-event <25%: model predicts dissipation in transit → "
            + "apply moderate scepticism — reduce fiery_sky by 15-25 points, do not assume blockage.\n"
            + "  - current ≥60%, at-event 25-50%: uncertain → penalise fiery_sky by 10-20 points.\n"
            + "  - current 30-60%: softer signal — penalise fiery_sky by 5-15 points.\n\n"
            + "Do not use double-quote characters within the summary text.";

    /** Prompt suffix: requests all three metrics and a summary. */
    static final String PROMPT_SUFFIX =
            "Rate 1-5, estimate Fiery Sky Potential (0-100) and Golden Hour Potential (0-100), "
            + "then summarise in exactly one sentence.";

    /** AOD threshold above which the dust context block is included. */
    private static final double DUST_AOD_THRESHOLD = 0.3;

    /** Surface dust threshold (µg/m³) above which the dust context block is included. */
    private static final double DUST_UGM3_THRESHOLD = 50.0;

    /** Inversion score at or above which the inversion context block is included. */
    private static final double INVERSION_SCORE_THRESHOLD = 7.0;

    /**
     * Cloud inversion potential classification derived from the inversion score.
     */
    public enum InversionPotential {
        /** No meaningful inversion potential. */
        NONE("No inversion potential"),
        /** Moderate inversion — visible cloud blanket below viewpoint. */
        MODERATE("Moderate Cloud Inversion Potential"),
        /** Strong inversion — dramatic sea-of-clouds almost certain. */
        STRONG("Strong Cloud Inversion Potential");

        private final String label;

        InversionPotential(String label) {
            this.label = label;
        }

        /**
         * Returns the human-readable label for this potential level.
         *
         * @return the label string
         */
        public String label() {
            return label;
        }

        /**
         * Derives the inversion potential from a 0–10 score.
         *
         * @param score the inversion score (0–10)
         * @return the corresponding potential level
         */
        public static InversionPotential fromScore(int score) {
            if (score >= 9) {
                return STRONG;
            }
            if (score >= 7) {
                return MODERATE;
            }
            return NONE;
        }
    }

    /**
     * Returns the system prompt for Claude colour evaluations.
     *
     * @return the system prompt string
     */
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Returns the prompt suffix appended to each user message.
     *
     * @return the prompt suffix string
     */
    public String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }

    /**
     * Builds the user message from atmospheric data with optional storm surge context.
     *
     * @param data  the atmospheric forecast data
     * @param surge storm surge breakdown, or null if not a coastal tidal location
     * @param adjustedRangeM adjusted tidal range including surge, or null
     * @param astronomicalRangeM astronomical tidal range before surge, or null
     * @return formatted user message string
     */
    public String buildUserMessage(AtmosphericData data, StormSurgeBreakdown surge,
                                   Double adjustedRangeM, Double astronomicalRangeM) {
        String base = buildUserMessage(data);
        if (surge == null || !surge.isSignificant()) {
            return base;
        }
        String surgeBlock = buildSurgeBlockString(surge, adjustedRangeM, astronomicalRangeM);
        return PromptUtils.insertBeforeSuffix(base, getPromptSuffix(), surgeBlock);
    }

    private String buildSurgeBlockString(StormSurgeBreakdown surge,
                                          Double adjustedRangeM, Double astronomicalRangeM) {
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
        sb.append("- Note: Upper-bound estimate. Tide-surge interaction means actual level at HW"
                + " is typically less.\n");
        return sb.toString();
    }

    public String buildUserMessage(AtmosphericData data) {
        var cloud = data.cloud();
        var w = data.weather();
        var a = data.aerosol();
        var comfort = data.comfort();

        StringBuilder sb = new StringBuilder();
        String dewPointStr = w.dewPointCelsius() != null
                ? String.format("%.1f\u00b0C (gap %.1f\u00b0C)",
                        w.dewPointCelsius(),
                        comfort.temperatureCelsius() != null
                                ? comfort.temperatureCelsius() - w.dewPointCelsius() : Double.NaN)
                : "N/A";

        sb.append(String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Precip probability: %s%%%n"
                + "Dew point: %s%n"
                + "Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                cloud.lowCloudPercent(), cloud.midCloudPercent(), cloud.highCloudPercent(),
                w.visibilityMetres(), w.windSpeedMs(), w.windDirectionDegrees(),
                w.precipitationMm(),
                w.humidityPercent(),
                comfort.precipitationProbability() != null ? comfort.precipitationProbability() : "N/A",
                dewPointStr,
                w.weatherCode(),
                a.boundaryLayerHeightMetres(), w.shortwaveRadiationWm2(),
                a.pm25(), a.dustUgm3(), a.aerosolOpticalDepth()));

        if (data.locationOrientation() != null) {
            sb.append(String.format("%nLocation orientation: %s (this location is best suited "
                    + "for %s photography)",
                    data.locationOrientation(),
                    data.locationOrientation().replace("-optimised", "")));
        }

        // Directional cloud data — sampled 113 km toward and away from the sun
        DirectionalCloudData dc = data.directionalCloud();
        if (dc != null) {
            sb.append(String.format(
                    "%nDIRECTIONAL CLOUD (113km sample):%n"
                    + "Solar horizon (toward sun): Low %d%%, Mid %d%%, High %d%%%n"
                    + "Antisolar horizon (away from sun): Low %d%%, Mid %d%%, High %d%%",
                    dc.solarLowCloudPercent(), dc.solarMidCloudPercent(),
                    dc.solarHighCloudPercent(),
                    dc.antisolarLowCloudPercent(), dc.antisolarMidCloudPercent(),
                    dc.antisolarHighCloudPercent()));
            if (dc.solarMidCloudPercent() > 80) {
                sb.append(" [THICK MID CLOUD — rate 4, not 5]");
            }
            if (dc.farSolarLowCloudPercent() != null) {
                int near = dc.solarLowCloudPercent();
                int far = dc.farSolarLowCloudPercent();
                sb.append(String.format("%nBeyond horizon (226km, solar azimuth): Low %d%%", far));
                if (near >= 50 && (near - far) >= 30) {
                    sb.append(" [THIN STRIP — soften low-cloud penalty]");
                } else if (near >= 50 && far >= 50) {
                    sb.append(" [EXTENSIVE BLANKET — full penalty applies]");
                }
            }
        }

        // Cloud approach risk block — temporal trend and upwind sample
        boolean thinStripConfirmed = dc != null && dc.farSolarLowCloudPercent() != null
                && dc.solarLowCloudPercent() >= 50
                && (dc.solarLowCloudPercent() - dc.farSolarLowCloudPercent()) >= 30;
        CloudApproachData ca = data.cloudApproach();
        if (ca != null) {
            sb.append(String.format("%nCLOUD APPROACH RISK:"));
            SolarCloudTrend trend = ca.solarTrend();
            if (trend != null && trend.slots() != null && !trend.slots().isEmpty()) {
                sb.append(String.format("%nSolar horizon low cloud trend (113km):"));
                for (SolarCloudTrend.SolarCloudSlot slot : trend.slots()) {
                    String label = slot.hoursBeforeEvent() == 0 ? "event" : "T-" + slot.hoursBeforeEvent() + "h";
                    sb.append(String.format(" %s=%d%%", label, slot.lowCloudPercent()));
                }
                if (trend.isBuilding()) {
                    if (thinStripConfirmed) {
                        sb.append(" [BUILDING — but THIN STRIP CONFIRMED at event time:"
                                + " strip is well-established, not a developing blanket;"
                                + " THIN STRIP rules take priority — rate 3-4 with canvas present]");
                    } else {
                        sb.append(" [BUILDING]");
                    }
                }
            }
            UpwindCloudSample upwind = ca.upwindSample();
            if (upwind != null) {
                sb.append(String.format(
                        "%nUpwind sample (%dkm along %d\u00b0 %s): current=%d%%, at-event=%d%%",
                        upwind.distanceKm(), upwind.windFromBearing(),
                        toCardinal(upwind.windFromBearing()),
                        upwind.currentLowCloudPercent(), upwind.eventLowCloudPercent()));
            }
        }

        // Mist/visibility trend block — hourly series from T-3h to T+2h
        MistTrend mistTrend = data.mistTrend();
        if (mistTrend != null && !mistTrend.slots().isEmpty()) {
            sb.append(String.format("%nMIST/VISIBILITY TREND (T-3h to T+2h):"));
            for (MistTrend.MistSlot slot : mistTrend.slots()) {
                String label = slot.hoursRelativeToEvent() == 0 ? "event"
                        : (slot.hoursRelativeToEvent() < 0 ? "T" + slot.hoursRelativeToEvent() + "h"
                        : "T+" + slot.hoursRelativeToEvent() + "h");
                double gap = slot.temperatureCelsius() - slot.dewPointCelsius();
                String gapLabel = gap < 1.0 ? " [AT/NEAR DEW POINT]"
                        : gap < 2.0 ? " [NEAR DEW POINT]"
                        : "";
                sb.append(String.format(" %s: vis=%,dm temp=%.1f\u00b0C dew=%.1f\u00b0C (gap=%.1f\u00b0C)%s",
                        label,
                        slot.visibilityMetres(),
                        slot.temperatureCelsius(),
                        slot.dewPointCelsius(),
                        gap,
                        gapLabel));
            }
        }

        // Conditional dust enrichment block — only when aerosol levels are elevated
        if (isDustElevated(a)) {
            sb.append(String.format(
                    "%nSAHARAN DUST CONTEXT:%n"
                    + "AOD: %s (elevated), Surface dust: %s \u00b5g/m\u00b3%n"
                    + "Wind: %s (%d\u00b0) at %s m/s%n"
                    + "Boundary layer: %dm%n"
                    + "Elevated AOD with low solar elevation at %s maximises warm scattering potential.",
                    a.aerosolOpticalDepth(), a.dustUgm3(),
                    toCardinal(w.windDirectionDegrees()), w.windDirectionDegrees(),
                    w.windSpeedMs(),
                    a.boundaryLayerHeightMetres(),
                    data.targetType()));
        }

        // Cloud inversion forecast — elevated water-overlooking locations only
        Double inversionScore = data.inversionScore();
        if (isInversionLikely(inversionScore)) {
            InversionPotential potential = InversionPotential.fromScore(inversionScore.intValue());
            sb.append(String.format(
                    "%nCLOUD INVERSION FORECAST:%n"
                    + "Score: %d/10 (%s)%n"
                    + "Expected: %s%n"
                    + "Timing: Peak at event time, dissipates 1-2 hours after as surface warms.",
                    inversionScore.intValue(),
                    potential.label(),
                    potential == InversionPotential.STRONG
                            ? "Dramatic blanket below viewpoint; clear sky above"
                            : "Visible cloud layer below; light touching cloud tops"));
        }

        sb.append("\n").append(getPromptSuffix());
        return sb.toString();
    }

    /**
     * Builds the structured output configuration constraining Claude's response to the
     * evaluation JSON schema.
     *
     * @return the output configuration with JSON schema constraint
     */
    public OutputConfig buildOutputConfig() {
        return OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                        Map.entry("rating", Map.of("type", "integer")),
                                        Map.entry("fiery_sky", Map.of("type", "integer")),
                                        Map.entry("golden_hour", Map.of("type", "integer")),
                                        Map.entry("summary", Map.of("type", "string")),
                                        Map.entry("basic_fiery_sky", Map.of("type", "integer")),
                                        Map.entry("basic_golden_hour", Map.of("type", "integer")),
                                        Map.entry("basic_summary", Map.of("type", "string")),
                                        Map.entry("inversion_score", Map.of("type", "integer")),
                                        Map.entry("inversion_potential", Map.of(
                                                "type", "string",
                                                "enum", List.of("NONE", "MODERATE", "STRONG"))))))
                                .putAdditionalProperty("required", JsonValue.from(
                                        List.of("rating", "fiery_sky", "golden_hour", "summary")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                        .build())
                .build();
    }

    /**
     * Converts a wind direction in degrees (0-360) to a 16-point compass cardinal.
     *
     * @param degrees wind direction in degrees (meteorological convention)
     * @return compass cardinal (e.g. "N", "SW", "ENE")
     */
    static String toCardinal(int degrees) {
        return PromptUtils.toCardinal(degrees);
    }

    /**
     * Returns {@code true} if aerosol levels are elevated enough to warrant the dust context block.
     *
     * @param aerosol the aerosol data
     * @return true when AOD exceeds 0.3 or surface dust exceeds 50 µg/m³
     */
    static boolean isDustElevated(AerosolData aerosol) {
        return (aerosol.aerosolOpticalDepth() != null
                        && aerosol.aerosolOpticalDepth().doubleValue() > DUST_AOD_THRESHOLD)
                || (aerosol.dustUgm3() != null
                        && aerosol.dustUgm3().doubleValue() > DUST_UGM3_THRESHOLD);
    }

    /**
     * Returns {@code true} if the inversion score is high enough to include inversion context.
     *
     * @param inversionScore the inversion likelihood score (0–10), or null
     * @return true when score is at or above the threshold (7.0)
     */
    static boolean isInversionLikely(Double inversionScore) {
        return inversionScore != null && inversionScore >= INVERSION_SCORE_THRESHOLD;
    }
}
