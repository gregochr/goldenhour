package com.gregochr.goldenhour.service;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;

/**
 * Every record type reachable from {@code DailyBriefingResponse}, with its components, as reviewed for
 * per-location ratings and prose when sub-region enforcement was built (sub-regions plan S3). The types that
 * carry a rating are handled by {@code LitePickShaper} and proved by {@code LitePickSentinelTest}; the rest were
 * read and found to hold facts, prose about a region or a night, or nothing per-location. A new type or
 * component anywhere in the tree fails the sentinel test's walker until it is reviewed and added here.
 */
final class ReviewedTypes {

    private ReviewedTypes() {
    }

    /**
     * The reviewed table.
     *
     * @return simple type name (nested types as {@code Outer$Inner}) to its component names
     */
    static Map<String, Set<String>> table() {
        return Map.ofEntries(
        entry("DailyBriefingResponse", Set.of(
                "allowedLocationIds", "auroraTomorrow", "auroraTonight", "bestBetModel", "bestBetStatus",
                "bestBets", "bestBetsWithdrawn", "bigSeas", "days", "failedLocationCount", "generatedAt",
                "headline", "hotTopics", "partialFailure", "previousGeneratedAt", "renderedEvents",
                "seasonalFeatures", "stale")),
        entry("BriefingDay", Set.of(
                "date", "eventSummaries", "peak")),
        entry("BriefingEventSummary", Set.of(
                "regions", "solarEventTime", "targetType", "unregioned", "window")),
        entry("BriefingRegion", Set.of(
                "bestRating", "confidence", "coverageReason", "displayVerdict", "forcedSample", "glossDetail",
                "glossHeadline", "lightlyEvaluated", "meanRating", "meanRatingDelta",
                "regionApparentTemperatureCelsius", "regionName", "regionTemperatureCelsius",
                "regionWeatherCode", "regionWindSpeedMs", "sampleSufficient", "scoredLocationCount", "slots",
                "summary", "tideHighlights", "verdict", "verdictLabel")),
        entry("BriefingSlot", Set.of(
                "canopy", "claudeHeadline", "claudeRating", "claudeSummary", "displayVerdict", "eclipse",
                "fierySkyPotential", "flags", "gated", "goldenHourPotential", "locationId", "locationName",
                "neighbourOutlier", "skyRating", "solarEventTime", "standdownReason", "tide", "verdict",
                "weather")),
        entry("BriefingSlot$WeatherConditions", Set.of(
                "apparentTemperatureCelsius", "highCloudPercent", "humidityPercent", "lowCloudPercent",
                "midCloudPercent", "precipitationMm", "temperatureCelsius", "visibilityMetres", "weatherCode",
                "windSpeedMs")),
        entry("BriefingSlot$TideInfo", Set.of(
                "heightAboveP95", "heightAboveSpringThreshold", "lunarPhase", "lunarTideType", "moonAtPerigee",
                "nearestExtremeKind", "nearestHighTideHeight", "nearestHighTideTime",
                "nearestSolarOffsetMinutes", "nearestSolarOffsetPhrase", "tideAligned", "tideAlignmentQuality",
                "tideDirection", "tideFitPhrase", "tideHeight", "tideLevel", "tideOnTheLight", "tideShortfall",
                "tideState")),
        entry("BriefingSlot$EclipseSight", Set.of(
                "maximum", "moonAltAtMax", "moonAzAtMax", "moonAzCardinal", "moonrise", "moonset", "race",
                "risesInShadow", "setsInShadow", "stops", "type", "umbraEnd", "umbraStart")),
        entry("BriefingSlot$LightStop", Set.of(
                "key", "time")),
        entry("BriefingWindow", Set.of(
                "badges", "bestRating", "confidence", "eventTime", "pick", "tide", "tideFacts",
                "topRarityRank", "verdict")),
        entry("BriefingWindow$Pick", Set.of(
                "averageRating", "detail", "headline", "kind", "locationId", "locationName", "regionName")),
        entry("BriefingWindow$Badge", Set.of(
                "detail", "eventTime", "facts", "label", "note", "rarityNote", "rarityRank", "safetyNote",
                "type")),
        entry("HotTopicFact", Set.of(
                "dir", "emphasis", "key", "optional", "value")),
        entry("BriefingWindowTide", Set.of(
                "curve", "direction", "extremes", "heightAtWindow", "locationName", "nearestOffset",
                "nearestTime", "nearestType", "range", "rangeAnomaly", "seas", "state", "sunrisePosition",
                "sunsetPosition", "windowLevel", "windowPosition")),
        entry("BriefingWindowTide$Extreme", Set.of(
                "kind", "position", "time")),
        entry("LocationTideFact", Set.of(
                "locationId", "locationName", "nearestSolarOffsetPhrase", "tideAligned",
                "tideAlignmentQuality", "tideDirection", "tideFitPhrase", "tideHeight", "tideLevel",
                "tideOnTheLight", "tideShortfall", "tideState")),
        entry("BriefingDayPeak", Set.of(
                "events", "regions", "verdict")),
        entry("BriefingDayPeak$PeakRegion", Set.of(
                "displayVerdict", "regionName", "targetType")),
        entry("BestBet", Set.of(
                "confidence", "dayName", "detail", "differsBy", "event", "eventTime", "eventType", "headline",
                "nearestDriveMinutes", "rank", "region", "relationship")),
        entry("AuroraTonightSummary", Set.of(
                "alertLevel", "clearLocationCount", "kp", "moonAboveHorizon", "moonIlluminationPct",
                "moonPhase", "moonRiseTime", "moonSetTime", "regions", "solarWindSpeedKmPerSec",
                "totalDarkSkyCount", "windowQuality")),
        entry("AuroraRegionSummary", Set.of(
                "bestBortleClass", "clearLocationCount", "glossDetail", "glossHeadline", "locations",
                "regionName", "regionTemperatureCelsius", "regionWeatherCode", "regionWindSpeedMs",
                "totalDarkSkyLocations", "verdict")),
        entry("AuroraLocationSlot", Set.of(
                "bortleClass", "clear", "cloudPercent", "locationName", "temperatureCelsius", "weatherCode",
                "windSpeedMs")),
        entry("AuroraTomorrowSummary", Set.of(
                "alertLevel", "label", "moonIlluminationPct", "moonPhase", "peakKp", "regions")),
        entry("HotTopic", Set.of(
                "date", "description", "detail", "eveningWindow", "eventTime", "eventType", "expandedDetail",
                "facts", "filterAction", "glance", "label", "liteDetail", "locationNames", "morningWindow", "note",
                "priority", "rarityNote", "regions", "safetyNote", "surgeRun", "tideRun", "type")),
        entry("ExpandedHotTopicDetail", Set.of(
                "bluebellMetrics", "regionGroups", "tideMetrics")),
        entry("ExpandedHotTopicDetail$RegionGroup", Set.of(
                "glossDetail", "glossHeadline", "locations", "regionName", "verdict")),
        entry("ExpandedHotTopicDetail$LocationEntry", Set.of(
                "badge", "bluebellLocationMetrics", "locationName", "locationType", "tideLocationMetrics")),
        entry("ExpandedHotTopicDetail$BluebellLocationMetrics", Set.of(
                "exposure", "score", "summary")),
        entry("ExpandedHotTopicDetail$TideLocationMetrics", Set.of(
                "tidePreference")),
        entry("ExpandedHotTopicDetail$BluebellMetrics", Set.of(
                "bestScore", "qualityLabel", "scoringLocationCount")),
        entry("ExpandedHotTopicDetail$TideMetrics", Set.of(
                "lunarPhase", "sunriseAlignedCount", "sunsetAlignedCount", "tidalClassification")),
        entry("NlcWindow", Set.of(
                "azimuth", "end", "start")),
        entry("TideRunDay", Set.of(
                "aligned", "alignedEvent", "alignmentPhrase", "bestAligned", "dayCount", "dayLabel",
                "dayNumber", "highWater", "highWaterAnomaly", "highWaterRank", "locationName", "peak",
                "phrase", "range", "rangeAnomaly", "roster", "runLabel", "seas", "sunrise", "sunset", "tides",
                "verdict")),
        entry("TideRunDay$Extreme", Set.of(
                "time", "type")),
        entry("TideRunDay$RosterAlignment", Set.of(
                "measured", "sunriseAligned", "sunsetAligned")),
        entry("SurgeRunDay", Set.of(
                "aligned", "axisLabels", "datumNote", "highWaterTime", "locationName", "peak", "peakTime",
                "phrase", "sunrise", "sunset", "surgeMetres", "verdict")),
        entry("GlanceLine", Set.of(
                "badge", "shortValue", "value", "weather")),
        entry("PlanRenderedEvent", Set.of(
                "date", "targetType")),
        entry("BigSeasDay", Set.of(
                "alignedEvent", "date", "daylightEnd", "daylightHighWater", "daylightStart", "gapMinutes",
                "gapSide", "hwTime", "otherWindows", "structures", "waveDirectionDeg", "waveHeightM",
                "wavePeriodS", "windowEnd", "windowStart")),
        entry("BigSeasStructure", Set.of(
                "aligned", "facesDeg", "hwTime", "label", "locationId", "structure", "waveDirectionDeg",
                "waveHeightM", "wavePeriodS")));
    }
}
