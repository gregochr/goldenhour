package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.TideSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Builds the system prompt, user message, and output schema for Claude colour evaluations.
 *
 * <p>Extracted from {@link AbstractEvaluationStrategy} to be independently testable
 * and reusable. All prompt construction logic lives here; the strategy classes focus
 * purely on API orchestration and response parsing.
 */
public class PromptBuilder {

    /** System prompt: rating scales, key criteria, aerosol guidance, directional cloud rules. */
    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Evaluate on three scales:\n"
            + "  1. Rating: 1\u20135 scale (overall potential)\n"
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
            + "readings are sampled 50 km toward and away from the sun. These are MORE RELIABLE "
            + "than the observer-point cloud layers for assessing light penetration and canvas "
            + "availability. Key rules:\n"
            + "- Solar horizon low cloud >60% = light is BLOCKED; treat as overcast for scoring "
            + "(fiery_sky 5-20, golden_hour 15-30, rating 1-2). This is non-negotiable.\n"
            + "- Solar horizon low cloud 40-60% = light partially blocked, penalise but consider "
            + "that mid/high cloud above may still catch colour if gaps exist in the low cloud\n"
            + "- Solar horizon low cloud <20% = strong light penetration likely\n"
            + "- Antisolar mid/high cloud 20-60% = ideal canvas for catching reflected colour\n"
            + "- When directional data contradicts observer data, ALWAYS trust the directional data. "
            + "A clear observer point is irrelevant if the solar horizon is blocked.\n"
            + "If directional data is not provided, fall back to altitude-based inference: "
            + "low cloud (0-3km) sits near the horizon and blocks light; mid (3-8km) and high "
            + "(8+km) cloud sits above and catches it. Ideal: low cloud <30% with mid/high 20-60%.\n\n"
            + "For coastal locations, tide data may be provided. When available:\n"
            + "- High tide can expose dramatic rock formations and alter water colour\n"
            + "- Low tide may reveal sand patterns and new horizon details\n"
            + "- If the tide aligns with the photographer's preference, factor this favourably\n"
            + "- If not aligned, briefly mention the tide limitation but don't heavily penalise"
            + " unless extreme\n\n"
            + "Output your evaluation as JSON with these fields: "
            + "rating (1-5), fiery_sky (0-100), golden_hour (0-100), summary (2 sentences).\n\n"
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
            + "Do not use double-quote characters within the summary text.";

    /** Prompt suffix: requests all three metrics and a summary. */
    static final String PROMPT_SUFFIX =
            "Rate 1-5, estimate Fiery Sky Potential (0-100) and Golden Hour Potential (0-100), "
            + "then explain in 1-2 sentences.";

    /** 16-point compass directions, indexed by (degrees / 22.5) rounded. */
    private static final String[] CARDINAL_DIRECTIONS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /** AOD threshold above which the dust context block is included. */
    private static final double DUST_AOD_THRESHOLD = 0.3;

    /** Surface dust threshold (µg/m³) above which the dust context block is included. */
    private static final double DUST_UGM3_THRESHOLD = 50.0;

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
     * Builds the user message from atmospheric data.
     *
     * <p>Includes observer-point weather, optional directional cloud data,
     * optional Saharan dust context, and optional tide data.
     *
     * @param data the atmospheric forecast data
     * @return formatted user message string
     */
    public String buildUserMessage(AtmosphericData data) {
        var cloud = data.cloud();
        var w = data.weather();
        var a = data.aerosol();
        var comfort = data.comfort();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Precip probability: %s%%%n"
                + "Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                cloud.lowCloudPercent(), cloud.midCloudPercent(), cloud.highCloudPercent(),
                w.visibilityMetres(), w.windSpeedMs(), w.windDirectionDegrees(),
                w.precipitationMm(),
                w.humidityPercent(),
                comfort.precipitationProbability() != null ? comfort.precipitationProbability() : "N/A",
                w.weatherCode(),
                a.boundaryLayerHeightMetres(), w.shortwaveRadiationWm2(),
                a.pm25(), a.dustUgm3(), a.aerosolOpticalDepth()));

        // Directional cloud data — sampled 50 km toward and away from the sun
        DirectionalCloudData dc = data.directionalCloud();
        if (dc != null) {
            sb.append(String.format(
                    "%nDIRECTIONAL CLOUD (50km sample):%n"
                    + "Solar horizon (toward sun): Low %d%%, Mid %d%%, High %d%%%n"
                    + "Antisolar horizon (away from sun): Low %d%%, Mid %d%%, High %d%%",
                    dc.solarLowCloudPercent(), dc.solarMidCloudPercent(),
                    dc.solarHighCloudPercent(),
                    dc.antisolarLowCloudPercent(), dc.antisolarMidCloudPercent(),
                    dc.antisolarHighCloudPercent()));
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

        // Include tide data if available (coastal location)
        TideSnapshot tide = data.tide();
        if (tide != null && tide.tideState() != null) {
            sb.append(String.format(
                    "%nTide: %s (next high: %.2fm at %s, next low: %.2fm at %s), Aligned: %s",
                    tide.tideState(),
                    tide.nextHighTideHeightMetres(),
                    tide.nextHighTideTime(),
                    tide.nextLowTideHeightMetres(),
                    tide.nextLowTideTime(),
                    tide.tideAligned()));
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
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "rating", Map.of("type", "integer"),
                                        "fiery_sky", Map.of("type", "integer"),
                                        "golden_hour", Map.of("type", "integer"),
                                        "summary", Map.of("type", "string"),
                                        "basic_fiery_sky", Map.of("type", "integer"),
                                        "basic_golden_hour", Map.of("type", "integer"),
                                        "basic_summary", Map.of("type", "string"))))
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
        int normalised = ((degrees % 360) + 360) % 360;
        int index = (int) Math.round(normalised / 22.5) % 16;
        return CARDINAL_DIRECTIONS[index];
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
}
