package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideStatisticalSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One location x one solar event assessment in the daily briefing.
 *
 * @param locationId          database id of the location, or {@code null}.
 *
 *                            <p>Nullable on purpose. This record is serialised into
 *                            {@code daily_briefing_cache}, so every payload written before this
 *                            field existed deserialises with a null id — and those entries stay
 *                            served until their key ages out. Consumers must therefore treat the
 *                            id as an <em>upgrade</em> on the name, not a replacement: prefer it
 *                            when present, fall back to {@code locationName} when it is not.
 *
 *                            <p>It exists because matching slots to the locations roster by NAME
 *                            is the join Close to home currently makes, and renaming a location
 *                            silently empties that block for every user in radius. The FK has been
 *                            available since V47; the briefing DTO simply never carried it.
 * @param locationName        human-readable location name
 * @param solarEventTime      UTC time of the sunrise or sunset
 * @param verdict             GO, MARGINAL, or STANDDOWN
 * @param weather             weather conditions at the observer point
 * @param tide                tide data for coastal locations (all nulls/false for inland)
 * @param flags               human-readable flag strings (e.g. "Sun blocked", "King tide")
 * @param standdownReason     primary reason for STANDDOWN verdict, null for GO/MARGINAL
 * @param claudeRating        cached Claude 1-5 star rating, or null if not evaluated
 * @param fierySkyPotential   cached Claude fiery sky score 0-100, or null
 * @param goldenHourPotential cached Claude golden hour score 0-100, or null
 * @param claudeSummary       cached Claude prose summary, or null
 * @param displayVerdict      unified colour/label signal; never null, recomputed whenever
 *                            {@code claudeRating} changes (see {@link #withClaudeScores})
 * @param canopy              {@code true} when this slot's verdict was produced by
 *                            {@code WoodlandVerdictEvaluator} rather than the sky chain, i.e. the
 *                            location is under a canopy. The two evaluators have <b>opposite
 *                            polarity</b> — a woodland GO means heavy cloud and mist — so a
 *                            {@link Verdict} alone is ambiguous and every consumer that aggregates
 *                            or explains slots needs to know which question was answered. A canopy
 *                            slot carries no <em>sky</em> rating, but it can and does carry a
 *                            rating: the woodland and bluebell mini-batches score these sites with
 *                            their own prompt. So a non-null rating here is not evidence the slot
 *                            was judged as sky, and anything reducing slots to one number must
 *                            filter on this flag rather than on whether a rating is present.
 * @param claudeHeadline      4-9 word Claude-authored card header (Gate 2 redesign), or null
 *                            when no Claude evaluation has been performed or the result
 *                            pre-dates the headline field
 */
public record BriefingSlot(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long locationId,
        String locationName,
        LocalDateTime solarEventTime,
        Verdict verdict,
        @JsonUnwrapped WeatherConditions weather,
        @JsonUnwrapped TideInfo tide,
        List<String> flags,
        String standdownReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer claudeRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer fierySkyPotential,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer goldenHourPotential,
        @JsonInclude(JsonInclude.Include.NON_NULL) String claudeSummary,
        DisplayVerdict displayVerdict,
        @JsonInclude(JsonInclude.Include.NON_NULL) String claudeHeadline,
        boolean canopy) {

    public BriefingSlot {
        flags = List.copyOf(flags);
    }

    /**
     * Convenience constructor that defaults all Claude evaluation fields to {@code null}
     * and derives {@code displayVerdict} from {@code verdict} alone.
     *
     * @param locationName    human-readable location name
     * @param solarEventTime  UTC time of the sunrise or sunset
     * @param verdict         GO, MARGINAL, or STANDDOWN
     * @param weather         weather conditions at the observer point
     * @param tide            tide data for coastal locations
     * @param flags           human-readable flag strings
     * @param standdownReason primary reason for STANDDOWN verdict, null for GO/MARGINAL
     */
    public BriefingSlot(String locationName, LocalDateTime solarEventTime, Verdict verdict,
            WeatherConditions weather, TideInfo tide, List<String> flags,
            String standdownReason) {
        this(null, locationName, solarEventTime, verdict, weather, tide, flags, standdownReason);
    }

    /**
     * Convenience constructor carrying the location id — the form production should use.
     *
     * <p>The id-less overload above is retained for tests and for any caller that genuinely has
     * only a name. Production has exactly two slot producers, both in {@code BriefingSlotBuilder}
     * and both holding the {@code LocationEntity}, and
     * {@code BriefingSlotBuilderLocationIdTest} pins that they use this form — the constructor
     * cannot enforce it, so a test does.
     *
     * @param locationId      database id of the location
     * @param locationName    human-readable location name
     * @param solarEventTime  UTC time of the sunrise or sunset
     * @param verdict         GO, MARGINAL, or STANDDOWN
     * @param weather         weather conditions at the observer point
     * @param tide            tide data for coastal locations
     * @param flags           human-readable flag strings
     * @param standdownReason primary reason for STANDDOWN verdict, null for GO/MARGINAL
     */
    public BriefingSlot(Long locationId, String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, TideInfo tide, List<String> flags,
            String standdownReason) {
        this(locationId, locationName, solarEventTime, verdict, weather, tide, flags,
                standdownReason, null, null, null, null,
                DisplayVerdict.resolve(null, verdict), null, false);
    }

    /**
     * Builds a slot whose verdict came from the woodland evaluator.
     *
     * <p>A named factory rather than a boolean at a call site, so the one place that produces
     * canopy slots cannot silently stop marking them — which would restore the ambiguity this
     * flag exists to remove.
     *
     * @param locationName    human-readable location name
     * @param solarEventTime  UTC time of the solar event
     * @param verdict         the woodland verdict (opposite polarity to a sky verdict)
     * @param weather         weather conditions at the observer point
     * @param flags           woodland-worded flag strings
     * @param standdownReason primary reason for STANDDOWN, null otherwise
     * @return a canopy-marked slot carrying no tide facts
     */
    public static BriefingSlot canopySlot(String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, List<String> flags,
            String standdownReason) {
        return canopySlot(null, locationName, solarEventTime, verdict, weather, flags,
                standdownReason);
    }

    /**
     * The slots that vote on a region's verdict: its non-canopy slots, or all of them when the
     * region has nothing else.
     *
     * <p><b>Why a wood does not vote.</b> A woodland verdict is computed on inverted polarity — a
     * canopy GO means heavy cloud and mist, which is the opposite of what GO means for a sky slot —
     * so averaging the two averages two opposite meanings of the same word. A single overcast wood
     * could otherwise carry a region to "Worth it" while the summary beneath it read "Clear at N of
     * M locations" and counted it.
     *
     * <p><b>Why the fallback.</b> A region with nothing but canopy slots falls back to them rather
     * than having no verdict at all — silence there would be a worse answer than an inverted-polarity
     * one, because the region genuinely has a forecast.
     *
     * <p><b>Why it lives here.</b> The rule had four independent copies — the hierarchy builder's
     * {@code votingSlots}, the confidence roster's {@code voting} count, the enrichment pass's
     * rating rollup and the Plan projector's region ranking — and one of them was missing, which is
     * how a rated wood came to lift a window's badge a band above every rating its card rendered.
     * Two prompt builders joined them afterwards ({@code BriefingGlossService} and
     * {@code BriefingRollupBuilder}), each averaging over this list while counting over the full
     * one. Call it; do not re-derive it — an inline copy is how the gap opened last time, and the
     * all-canopy fallback below is the clause a copy tends to drop.
     * Woods are not lost by any of them: they keep their own slot, verdict and flags in the
     * drill-down. They simply do not vote on a question they are not answering.
     *
     * @param slots a region's slots for one date and event; null or empty yields an empty list
     * @return the voting slots, never null
     */
    public static List<BriefingSlot> votingSlots(List<BriefingSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<BriefingSlot> sky = slots.stream().filter(slot -> !slot.canopy()).toList();
        return sky.isEmpty() ? List.copyOf(slots) : sky;
    }

    /**
     * Builds a canopy slot carrying the location id — the form production should use.
     *
     * @param locationId      database id of the location
     * @param locationName    human-readable location name
     * @param solarEventTime  UTC time of the solar event
     * @param verdict         the woodland verdict (opposite polarity to a sky verdict)
     * @param weather         weather conditions at the observer point
     * @param flags           woodland-worded flag strings
     * @param standdownReason primary reason for STANDDOWN, null otherwise
     * @return a canopy-marked slot carrying no tide facts
     */
    public static BriefingSlot canopySlot(Long locationId, String locationName,
            LocalDateTime solarEventTime, Verdict verdict, WeatherConditions weather,
            List<String> flags, String standdownReason) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather,
                TideInfo.NONE, flags, standdownReason, null, null, null, null,
                DisplayVerdict.resolve(null, verdict), null, true);
    }

    /**
     * Returns a copy of this slot with Claude evaluation scores populated (no headline).
     * The {@code displayVerdict} is recomputed from the new rating.
     *
     * @param rating      Claude 1-5 star rating
     * @param fierySky    fiery sky potential 0-100
     * @param goldenHour  golden hour potential 0-100
     * @param summary     Claude prose summary
     * @return new slot with Claude fields set, headline left null
     */
    public BriefingSlot withClaudeScores(Integer rating, Integer fierySky,
            Integer goldenHour, String summary) {
        return withClaudeScores(rating, fierySky, goldenHour, summary, null);
    }

    /**
     * Returns a copy of this slot with Claude evaluation scores and the synthesised headline
     * populated. The {@code displayVerdict} is recomputed from the new rating.
     *
     * @param rating      Claude 1-5 star rating
     * @param fierySky    fiery sky potential 0-100
     * @param goldenHour  golden hour potential 0-100
     * @param summary     Claude prose summary
     * @param headline    4-9 word Claude-authored card header, or null when omitted
     * @return new slot with Claude fields and headline set
     */
    public BriefingSlot withClaudeScores(Integer rating, Integer fierySky,
            Integer goldenHour, String summary, String headline) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, tide,
                flags, standdownReason, rating, fierySky, goldenHour, summary,
                DisplayVerdict.resolve(rating, verdict), headline, canopy);
    }

    /**
     * Weather conditions at the observer point for a briefing slot.
     *
     * @param lowCloudPercent             low cloud cover (%)
     * @param precipitationMm             precipitation in mm
     * @param visibilityMetres            visibility in metres
     * @param humidityPercent             relative humidity (%)
     * @param temperatureCelsius          temperature in degrees Celsius
     * @param apparentTemperatureCelsius  feels-like temperature in degrees Celsius
     * @param weatherCode                 WMO weather code, or null if unavailable
     * @param windSpeedMs                 wind speed in m/s
     * @param midCloudPercent             mid-level cloud cover (%)
     * @param highCloudPercent            high-level cloud cover (%)
     */
    public record WeatherConditions(
            int lowCloudPercent,
            BigDecimal precipitationMm,
            int visibilityMetres,
            int humidityPercent,
            Double temperatureCelsius,
            Double apparentTemperatureCelsius,
            Integer weatherCode,
            BigDecimal windSpeedMs,
            int midCloudPercent,
            int highCloudPercent) {
    }

    /**
     * Tide data for coastal locations within a briefing slot.
     *
     * @param tideState           HIGH, MID, LOW, or null for inland
     * @param tideAligned         true if tide matches location preference
     * @param nearestHighTideTime UTC time of nearest high tide, or null
     * @param nearestHighTideHeight height of nearest high tide in metres, or null
     * @param heightAboveP95          true if the nearest high tide exceeds P95 (statistical)
     * @param heightAboveSpringThreshold        true if the nearest high tide exceeds 125% avg (statistical)
     * @param lunarTideType       astronomical tide classification, or null for inland
     * @param lunarPhase          human-readable moon phase name, or null for inland
     * @param moonAtPerigee       true if the moon is near perigee, or null for inland
     * @param nearestSolarOffsetMinutes signed minutes from this slot's solar event to whichever
     *                                  tide extreme — high or low, type-blind — lands nearest it;
     *                                  positive when the water comes after the sun. Null for
     *                                  inland locations or when no extreme was found nearby.
     *                                  Deliberately not derived from {@code tideAligned}, which
     *                                  tests this location's configured {@code TideType}
     *                                  preference, a different question
     * @param nearestExtremeKind        {@code "HW"} or {@code "LW"} for whichever extreme
     *                                  {@code nearestSolarOffsetMinutes} names, or null
     * @param tideOnTheLight            true when that nearest extreme falls inside
     *                                  {@code TideFactDeriver}'s dynamic tight alignment window for
     *                                  this location, date and event — the map tab's tide-alignment
     *                                  glyph and label-budget tiebreaker. Null alongside the two
     *                                  fields above
     * @param nearestSolarOffsetPhrase  the same fact already formatted, e.g.
     *                                  {@code "HW 19:52 · 36m before sunset"}, built from the
     *                                  shared {@code TideWording} vocabulary so no client ever
     *                                  formats a tide clock time itself; null alongside the three
     *                                  fields above
     */
    public record TideInfo(
            String tideState,
            boolean tideAligned,
            LocalDateTime nearestHighTideTime,
            BigDecimal nearestHighTideHeight,
            boolean heightAboveP95,
            boolean heightAboveSpringThreshold,
            LunarTideType lunarTideType,
            String lunarPhase,
            Boolean moonAtPerigee,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer nearestSolarOffsetMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String nearestExtremeKind,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean tideOnTheLight,
            @JsonInclude(JsonInclude.Include.NON_NULL) String nearestSolarOffsetPhrase) {

        /** Tide info for inland locations with no tide data. */
        public static final TideInfo NONE =
                new TideInfo(null, false, null, null, false, false, null, null, null);

        /**
         * Legacy 9-field constructor, retained so the many existing call sites (mostly tests)
         * that predate the map-tab tide-alignment fields keep compiling unchanged. Defaults the
         * four new fields to null — "unknown", not "not aligned" — the same convention
         * {@link #NONE} already uses for the fields it predates.
         *
         * @param tideState           HIGH, MID, LOW, or null for inland
         * @param tideAligned         true if tide matches location preference
         * @param nearestHighTideTime UTC time of nearest high tide, or null
         * @param nearestHighTideHeight height of nearest high tide in metres, or null
         * @param heightAboveP95          true if the nearest high tide exceeds P95 (statistical)
         * @param heightAboveSpringThreshold  true if the nearest high tide exceeds 125% avg
         * @param lunarTideType       astronomical tide classification, or null for inland
         * @param lunarPhase          human-readable moon phase name, or null for inland
         * @param moonAtPerigee       true if the moon is near perigee, or null for inland
         */
        public TideInfo(String tideState, boolean tideAligned, LocalDateTime nearestHighTideTime,
                BigDecimal nearestHighTideHeight, boolean heightAboveP95,
                boolean heightAboveSpringThreshold, LunarTideType lunarTideType,
                String lunarPhase, Boolean moonAtPerigee) {
            this(tideState, tideAligned, nearestHighTideTime, nearestHighTideHeight,
                    heightAboveP95, heightAboveSpringThreshold, lunarTideType, lunarPhase,
                    moonAtPerigee, null, null, null, null);
        }

        /**
         * Derives the statistical size classification from the existing boolean flags.
         *
         * @return {@link TideStatisticalSize#EXTRA_EXTRA_HIGH} if {@code heightAboveP95},
         *         {@link TideStatisticalSize#EXTRA_HIGH} if {@code heightAboveSpringThreshold},
         *         or {@code null} for regular-sized tides
         */
        public TideStatisticalSize statisticalSize() {
            if (heightAboveP95) {
                return TideStatisticalSize.EXTRA_EXTRA_HIGH;
            }
            if (heightAboveSpringThreshold) {
                return TideStatisticalSize.EXTRA_HIGH;
            }
            return null;
        }
    }
}
