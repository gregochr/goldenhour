package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 *                            was the join the retired Close-to-home block made, and renaming a
 *                            location silently emptied that block for every user in radius. The FK has been
 *                            available since V47; the briefing DTO simply never carried it.
 * @param locationName        human-readable location name
 * @param solarEventTime      UTC time of the sunrise or sunset
 * @param verdict             GO, MARGINAL, or STANDDOWN
 * @param weather             weather conditions at the observer point
 * @param tide                tide data for coastal locations (all nulls/false for inland)
 * @param flags               human-readable flag strings (e.g. "Sun blocked", "King tide")
 * @param claudeRating        cached Claude 1-5 star rating (the served, combined figure — sky
 *                            averaged with tide and any other applicable component), or null if
 *                            not evaluated
 * @param skyRating           the sky visitor's own component score alone — Claude's rating of the
 *                            light with no tide (or other foreground) contribution averaged in —
 *                            or null when not evaluated or not yet carried by the cache entry
 *                            this slot was enriched from. Added with the tide gate lift
 *                            (2026-09-18, {@code docs/engineering/tide-window-plan.md} §6 Q1): once
 *                            a mismatched tide could pull {@link #claudeRating} down from a strong
 *                            sky, the map tab's tide-fit block needed a way to say so — "wrong
 *                            water, not wrong light" — without a reader mistaking the dimmed
 *                            combined star for the sky's own verdict. Equal to {@link
 *                            #claudeRating} for an inland slot (the sky is the only component), so
 *                            a reader of this field alone cannot tell "inland, agreeing" from
 *                            "coastal, agreeing" — a distinction no surface needs, since the two
 *                            render identically. Never present without {@link #claudeRating}: the
 *                            sky component cannot score without the sky evaluation the combined
 *                            rating is itself built from
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
 * @param eclipse             the moon's geometry and the dawn/dusk race data for a lunar eclipse
 *                            whose own window (date + {@code targetType}) this slot falls on, or
 *                            null every other night — see {@link EclipseSight}. Deliberately
 *                            <b>not</b> {@code @JsonUnwrapped}: unlike {@link TideInfo}, whose flat
 *                            fields are the map tab's own long-established wire shape, an eclipse
 *                            sight is a self-contained fact the popup mounts as one nested object
 *                            (the plan's own reasoning against flattening eleven more keys onto an
 *                            already-large slot — {@code docs/engineering/lunar-eclipse-plan.md}
 *                            §2.5). Nullable and {@code NON_NULL}-serialised for the same
 *                            {@code daily_briefing_cache} reason as {@link #claudeHeadline}: a
 *                            payload written before this field existed deserialises with a null
 *                            here, never a fabricated one
 * @param neighbourOutlier    {@code TRUE} when the neighbour guard marked this slot as rated far
 *                            above every nearby place that sees the same sky (see
 *                            {@code NeighbourOutlierGuard}), otherwise null — never {@code FALSE}
 *                            when the guard set it, so an unflagged payload serialises exactly as
 *                            it did before the field existed. <b>Serve-time only:</b> set by
 *                            {@code ServedBriefingAssembler.assembleForPlan} after enrichment and
 *                            never at build time, so {@code daily_briefing_cache} never carries it.
 *                            The slot keeps its star; the flag only withholds its NAME as a single
 *                            best place
 * @param gated               {@code TRUE} when this slot is outside the reader's sub-region picks and its
 *                            rating fields have been removed at the HTTP edge by {@code LitePickShaper}
 *                            (docs/engineering/sub-regions-plan.md D6), otherwise null — never
 *                            {@code FALSE}, so an unshaped payload serialises exactly as it did before the
 *                            field existed. A gated slot is <b>not unrated</b>: it has a rating the reader
 *                            may not see, and no surface may count it as unrated or as an insufficient
 *                            sample. <b>Serve-time only:</b> set by the shaper, never persisted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BriefingSlot(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long locationId,
        String locationName,
        LocalDateTime solarEventTime,
        Verdict verdict,
        @JsonUnwrapped WeatherConditions weather,
        @JsonUnwrapped TideInfo tide,
        List<String> flags,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer claudeRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer skyRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer fierySkyPotential,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer goldenHourPotential,
        @JsonInclude(JsonInclude.Include.NON_NULL) String claudeSummary,
        DisplayVerdict displayVerdict,
        @JsonInclude(JsonInclude.Include.NON_NULL) String claudeHeadline,
        boolean canopy,
        @JsonInclude(JsonInclude.Include.NON_NULL) EclipseSight eclipse,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean neighbourOutlier,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean gated) {

    /**
     * Canonical constructor: takes an immutable copy of {@code flags}, so a caller's later changes
     * to the list it passed cannot alter this slot. A null {@code flags} is rejected with a
     * {@link NullPointerException}.
     */
    public BriefingSlot {
        flags = List.copyOf(flags);
    }

    /**
     * Legacy 18-argument constructor, retained so every call site that predates {@link #gated} keeps
     * compiling unchanged. Defaults {@link #gated} to null, the "absent, not fabricated" convention.
     *
     * <p><b>Not used by the withers</b> — they thread {@link #gated} through from {@code this}.
     *
     * @param locationId          database id of the location, or null
     * @param locationName        human-readable location name
     * @param solarEventTime      UTC time of the sunrise or sunset
     * @param verdict             GO, MARGINAL, or STANDDOWN
     * @param weather             weather conditions at the observer point
     * @param tide                tide data for coastal locations
     * @param flags               human-readable flag strings
     * @param claudeRating        cached Claude 1-5 star rating, or null
     * @param skyRating           the sky visitor's own component score, or null
     * @param fierySkyPotential   cached Claude fiery sky score 0-100, or null
     * @param goldenHourPotential cached Claude golden hour score 0-100, or null
     * @param claudeSummary       cached Claude prose summary, or null
     * @param displayVerdict      unified colour/label signal
     * @param claudeHeadline      4-9 word Claude-authored card header, or null
     * @param canopy              true when this slot came from the woodland evaluator
     * @param eclipse             the lunar eclipse sight, or null
     * @param neighbourOutlier    the neighbour guard's outlier mark, or null
     */
    public BriefingSlot(Long locationId, String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, TideInfo tide, List<String> flags,
            Integer claudeRating, Integer skyRating,
            Integer fierySkyPotential, Integer goldenHourPotential, String claudeSummary,
            DisplayVerdict displayVerdict, String claudeHeadline, boolean canopy,
            EclipseSight eclipse, Boolean neighbourOutlier) {
        this(locationId, locationName, solarEventTime, verdict, weather, tide, flags,
                claudeRating, skyRating, fierySkyPotential, goldenHourPotential,
                claudeSummary, displayVerdict, claudeHeadline, canopy, eclipse, neighbourOutlier, null);
    }

    /**
     * Legacy 17-argument constructor, retained so every call site that predates
     * {@link #neighbourOutlier} keeps compiling unchanged. Defaults {@link #neighbourOutlier} to
     * null, the "absent, not fabricated" convention for a field added after this record's first
     * release.
     *
     * <p><b>Not used by the withers</b> — they must thread {@link #neighbourOutlier} through from
     * {@code this}, or a flagged slot would silently lose its flag (the same "positional rebuild
     * drops a field" defect {@link BriefingEventSummary#withWindow}'s own javadoc warns about).
     * They call the 18-argument canonical form directly instead.
     *
     * @param locationId          database id of the location, or null
     * @param locationName        human-readable location name
     * @param solarEventTime      UTC time of the sunrise or sunset
     * @param verdict             GO, MARGINAL, or STANDDOWN
     * @param weather             weather conditions at the observer point
     * @param tide                tide data for coastal locations
     * @param flags               human-readable flag strings
     * @param claudeRating        cached Claude 1-5 star rating, or null
     * @param skyRating           the sky visitor's own component score, or null
     * @param fierySkyPotential   cached Claude fiery sky score 0-100, or null
     * @param goldenHourPotential cached Claude golden hour score 0-100, or null
     * @param claudeSummary       cached Claude prose summary, or null
     * @param displayVerdict      unified colour/label signal
     * @param claudeHeadline      4-9 word Claude-authored card header, or null
     * @param canopy              true when this slot came from the woodland evaluator
     * @param eclipse             the lunar eclipse sight, or null
     */
    public BriefingSlot(Long locationId, String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, TideInfo tide, List<String> flags,
            Integer claudeRating, Integer skyRating,
            Integer fierySkyPotential, Integer goldenHourPotential, String claudeSummary,
            DisplayVerdict displayVerdict, String claudeHeadline, boolean canopy,
            EclipseSight eclipse) {
        this(locationId, locationName, solarEventTime, verdict, weather, tide, flags,
                claudeRating, skyRating, fierySkyPotential, goldenHourPotential,
                claudeSummary, displayVerdict, claudeHeadline, canopy, eclipse, null);
    }

    /**
     * Legacy 16-argument constructor, retained so every existing production and test call site
     * that predates {@link #eclipse} — the many convenience constructors in this class, and every
     * direct canonical-constructor call in the test tree — keeps compiling unchanged. Defaults
     * {@link #eclipse} (and {@link #neighbourOutlier}) to null, the "absent, not fabricated"
     * convention for a field added after this record's first release.
     *
     * <p><b>Not used by the withers</b> — they must thread every later component through from
     * {@code this}. They call the canonical form directly instead.
     *
     * @param locationId          database id of the location, or null
     * @param locationName        human-readable location name
     * @param solarEventTime      UTC time of the sunrise or sunset
     * @param verdict             GO, MARGINAL, or STANDDOWN
     * @param weather             weather conditions at the observer point
     * @param tide                tide data for coastal locations
     * @param flags               human-readable flag strings
     * @param claudeRating        cached Claude 1-5 star rating, or null
     * @param skyRating           the sky visitor's own component score, or null
     * @param fierySkyPotential   cached Claude fiery sky score 0-100, or null
     * @param goldenHourPotential cached Claude golden hour score 0-100, or null
     * @param claudeSummary       cached Claude prose summary, or null
     * @param displayVerdict      unified colour/label signal
     * @param claudeHeadline      4-9 word Claude-authored card header, or null
     * @param canopy              true when this slot came from the woodland evaluator
     */
    public BriefingSlot(Long locationId, String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, TideInfo tide, List<String> flags,
            Integer claudeRating, Integer skyRating,
            Integer fierySkyPotential, Integer goldenHourPotential, String claudeSummary,
            DisplayVerdict displayVerdict, String claudeHeadline, boolean canopy) {
        this(locationId, locationName, solarEventTime, verdict, weather, tide, flags,
                claudeRating, skyRating, fierySkyPotential, goldenHourPotential,
                claudeSummary, displayVerdict, claudeHeadline, canopy, null, null);
    }

    /**
     * Whether this slot could carry a Claude rating — the coverage denominator's membership test.
     *
     * <p>One predicate, two callers ({@code BriefingHonestyFilter}'s zero-coverage defence and
     * {@code BriefingRegionEvaluationRollup.rosterOf}'s confidence roster), because each already
     * claimed to match the other by hand. A slot is out when it never expected a rating: an
     * unscored canopy slot (deliberately excluded from the sky batch — but an in-season bluebell
     * wood IS scored, by its own prompt, so a rated one stays in).
     *
     * @return true when a rating is or could be present
     */
    public boolean couldCarryRating() {
        return claudeRating != null || !canopy;
    }

    /**
     * Returns a copy of this slot carrying the given tide data, every other field unchanged.
     *
     * <p>Its one production caller is the serve-time strip in {@code ServedBriefingAssembler}, which
     * passes {@code null} so the unwrapped tide keys leave the API payload (the per-location tide now
     * travels on {@code BriefingWindow.tideFacts}). A {@code null} here serialises none of the
     * {@code @JsonUnwrapped} keys; an empty {@link TideInfo} would serialise {@code tideAligned:false}
     * on every slot.
     *
     * @param newTide the tide data, or null to carry none
     * @return a new slot, every other field unchanged
     */
    public BriefingSlot withTide(TideInfo newTide) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, newTide,
                flags, claudeRating, skyRating, fierySkyPotential,
                goldenHourPotential, claudeSummary, displayVerdict, claudeHeadline, canopy,
                eclipse, neighbourOutlier, gated);
    }

    /**
     * Returns a copy of this slot carrying the given lunar eclipse sight, every other field
     * unchanged.
     *
     * <p>A wither rather than a constructor argument at every call site, because
     * {@code EclipseSightAssembler} decides this AFTER the slot already exists,
     * at {@code BriefingSlotBuilder}'s own build-time seam.
     *
     * @param eclipse the sight, or null to clear it (every night that is not this eclipse's own
     *                window)
     * @return a new slot, every other field unchanged
     */
    public BriefingSlot withEclipse(EclipseSight eclipse) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, tide,
                flags, claudeRating, skyRating, fierySkyPotential,
                goldenHourPotential, claudeSummary, displayVerdict, claudeHeadline, canopy,
                eclipse, neighbourOutlier, gated);
    }

    /**
     * Returns a copy of this slot as a LITE reader may see it: the Fiery Sky and Golden Hour
     * potentials and the per-location written why ({@link #claudeSummary} and
     * {@link #claudeHeadline}) are null, every other component unchanged.
     *
     * <p>Built on the canonical constructor on purpose, <b>not</b> on {@link #withClaudeScores},
     * which recomputes {@link #displayVerdict} from the rating and would overwrite a triage,
     * neighbour-guard or sample-gate verdict. The star rating, the sky component and the verdict
     * are free for every role.
     *
     * <p>The slot's {@link TideInfo} is also narrowed to {@link TideInfo#forLite()}: the
     * per-location tide match, the heights and the tide against the light are Pro, as they are on the
     * window's per-location facts.
     *
     * @return a new slot with the potentials, the written why and the Pro tide fields removed
     */
    public BriefingSlot forLite() {
        TideInfo liteTide = tide == null ? null : tide.forLite();
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, liteTide,
                flags, claudeRating, skyRating, null, null, null,
                displayVerdict, null, canopy, eclipse, neighbourOutlier, gated);
    }

    /**
     * Returns this slot as a reader whose picks do not cover it may see it (sub-regions-plan §4.2): the
     * verdict, both stars, the display verdict, the flags, the neighbour mark and
     * every other score or written why are removed, and {@link #gated} is set. The location, the event
     * time, the weather and the tide facts stay. Rebuilt through the canonical constructor so a component
     * added later is carried, not defaulted away.
     *
     * @return a gated copy
     */
    public BriefingSlot forGated() {
        return new BriefingSlot(locationId, locationName, solarEventTime, null, weather, tide,
                List.of(), null, null, null, null, null,
                null, null, canopy, eclipse, null, Boolean.TRUE);
    }

    /**
     * Returns a copy of this slot carrying the given flags, every other field unchanged.
     *
     * @param newFlags the replacement flag strings
     * @return a new slot, every other field unchanged
     */
    public BriefingSlot withFlags(List<String> newFlags) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, tide,
                newFlags, claudeRating, skyRating, fierySkyPotential,
                goldenHourPotential, claudeSummary, displayVerdict, claudeHeadline, canopy,
                eclipse, neighbourOutlier, gated);
    }

    /**
     * Returns a copy of this slot carrying the given neighbour-outlier mark, every other field
     * unchanged. The value is stored verbatim; the guard passes {@code TRUE} or null, never
     * {@code FALSE}.
     *
     * @param neighbourOutlier {@code TRUE} to mark the slot, or null to carry no mark
     * @return a new slot, every other field unchanged
     */
    public BriefingSlot withNeighbourOutlier(Boolean neighbourOutlier) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, tide,
                flags, claudeRating, skyRating, fierySkyPotential,
                goldenHourPotential, claudeSummary, displayVerdict, claudeHeadline, canopy,
                eclipse, neighbourOutlier, gated);
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
     */
    public BriefingSlot(String locationName, LocalDateTime solarEventTime, Verdict verdict,
            WeatherConditions weather, TideInfo tide, List<String> flags) {
        this(null, locationName, solarEventTime, verdict, weather, tide, flags);
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
     */
    public BriefingSlot(Long locationId, String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, TideInfo tide, List<String> flags) {
        this(locationId, locationName, solarEventTime, verdict, weather, tide, flags,
                null, null, null, null, null,
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
     * @return a canopy-marked slot carrying no tide facts
     */
    public static BriefingSlot canopySlot(String locationName, LocalDateTime solarEventTime,
            Verdict verdict, WeatherConditions weather, List<String> flags) {
        return canopySlot(null, locationName, solarEventTime, verdict, weather, flags);
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
     * @return a canopy-marked slot carrying no tide facts
     */
    public static BriefingSlot canopySlot(Long locationId, String locationName,
            LocalDateTime solarEventTime, Verdict verdict, WeatherConditions weather,
            List<String> flags) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather,
                TideInfo.NONE, flags, null, null, null, null, null,
                DisplayVerdict.resolve(null, verdict), null, true, null, null);
    }

    /**
     * Returns a copy of this slot with Claude evaluation scores populated (no headline, sky
     * component unknown). The {@code displayVerdict} is recomputed from the new rating.
     *
     * @param rating      Claude 1-5 star rating
     * @param fierySky    fiery sky potential 0-100
     * @param goldenHour  golden hour potential 0-100
     * @param summary     Claude prose summary
     * @return new slot with Claude fields set, headline and {@link #skyRating} left null
     */
    public BriefingSlot withClaudeScores(Integer rating, Integer fierySky,
            Integer goldenHour, String summary) {
        return withClaudeScores(rating, null, fierySky, goldenHour, summary, null);
    }

    /**
     * Returns a copy of this slot with Claude evaluation scores and the synthesised headline
     * populated, sky component unknown. The {@code displayVerdict} is recomputed from the new
     * rating.
     *
     * @param rating      Claude 1-5 star rating
     * @param fierySky    fiery sky potential 0-100
     * @param goldenHour  golden hour potential 0-100
     * @param summary     Claude prose summary
     * @param headline    4-9 word Claude-authored card header, or null when omitted
     * @return new slot with Claude fields and headline set, {@link #skyRating} left null
     */
    public BriefingSlot withClaudeScores(Integer rating, Integer fierySky,
            Integer goldenHour, String summary, String headline) {
        return withClaudeScores(rating, null, fierySky, goldenHour, summary, headline);
    }

    /**
     * Returns a copy of this slot with Claude evaluation scores, the sky-only component score,
     * and the synthesised headline populated. The {@code displayVerdict} is recomputed from the
     * new (combined) rating — never from {@code skyRating}, which is a display-only figure that
     * plays no part in the verdict or the region roll-up.
     *
     * @param rating      Claude 1-5 star rating — the served, combined figure
     * @param skyRating   the sky visitor's own component score, or null when unknown
     * @param fierySky    fiery sky potential 0-100
     * @param goldenHour  golden hour potential 0-100
     * @param summary     Claude prose summary
     * @param headline    4-9 word Claude-authored card header, or null when omitted
     * @return new slot with Claude fields, sky component and headline set
     */
    public BriefingSlot withClaudeScores(Integer rating, Integer skyRating, Integer fierySky,
            Integer goldenHour, String summary, String headline) {
        return new BriefingSlot(locationId, locationName, solarEventTime, verdict, weather, tide,
                flags, rating, skyRating, fierySky, goldenHour, summary,
                DisplayVerdict.resolve(rating, verdict), headline, canopy,
                eclipse, neighbourOutlier, gated);
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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
     * @param tideLevel                 the water level at this slot's own solar event, 0.0 at the
     *                                  series' lowest sampled water to 1.0 at its highest — the
     *                                  same normalisation {@code BriefingWindowTide.windowLevel}
     *                                  uses, from the same {@code TideCurveCalculator} cosine. A
     *                                  <em>display</em> fact only: it never decides {@link
     *                                  #tideAligned}, which stays time-proximity based (CLAUDE.md's
     *                                  two-tide-axes rule). Null for inland or when no extremes are
     *                                  stored
     * @param tideDirection             {@code "RISING"} or {@code "FALLING"} at the light, null
     *                                  alongside {@link #tideLevel}
     * @param tideHeight                the interpolated height at the light, already formatted,
     *                                  e.g. {@code "2.6 m"}; null alongside {@link #tideLevel}
     * @param tideShortfall             {@code "HIGHER"} when the state sits below every water the
     *                                  location wants, {@code "LOWER"} when it sits above every
     *                                  water wanted, or null — on an aligned slot, or when the
     *                                  wanted set straddles the state (a {@code HIGH}/{@code LOW}
     *                                  location reading MID) and no single direction applies. Never
     *                                  derived on the client from {@link #tideState} and a
     *                                  location's wanted set — the server owns this formula
     * @param tideFitPhrase             the map tab's tide-fit block body, already formatted by
     *                                  {@code TideWording.tideFitPhrase} — one form when {@link
     *                                  #tideAligned}, a different one naming the wanted water
     *                                  otherwise; null alongside {@link #tideLevel}
     * @param tideAlignmentQuality      how well-centred the water is in the wanted state at the
     *                                  light, on the same time axis {@link #tideAligned} is
     *                                  decided on — 0.0 at the edge of the tight alignment window,
     *                                  1.0 dead centre. {@code null} unless {@link #tideAligned}.
     *                                  Never level- or height-derived (CLAUDE.md's two-tide-axes
     *                                  rule); the run ranking's own tiebreak, not shown on the card
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TideInfo(
            String tideState,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean tideAligned,
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
            @JsonInclude(JsonInclude.Include.NON_NULL) String nearestSolarOffsetPhrase,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tideLevel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideDirection,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideHeight,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideShortfall,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tideFitPhrase,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tideAlignmentQuality) {

        /** Tide info for inland locations with no tide data. */
        public static final TideInfo NONE =
                new TideInfo(null, false, null, null, false, false, null, null, null);

        /**
         * Legacy 9-field constructor, retained so the many existing call sites (mostly tests)
         * that predate the map-tab tide-alignment fields keep compiling unchanged. Defaults every
         * field it predates to null — "unknown", not "not aligned" — the same convention
         * {@link #NONE} already uses.
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
         * Legacy 13-field constructor, retained so the many existing call sites (mostly tests)
         * that predate the tide-fit fields (level, direction, height, shortfall, fit phrase) keep
         * compiling unchanged. Defaults the five new fields to null, the same convention the
         * 9-field constructor already uses for the fields it predates.
         *
         * @param tideState                 HIGH, MID, LOW, or null for inland
         * @param tideAligned               true if tide matches location preference
         * @param nearestHighTideTime       UTC time of nearest high tide, or null
         * @param nearestHighTideHeight     height of nearest high tide in metres, or null
         * @param heightAboveP95            true if the nearest high tide exceeds P95
         * @param heightAboveSpringThreshold true if the nearest high tide exceeds 125% avg
         * @param lunarTideType             astronomical tide classification, or null for inland
         * @param lunarPhase                human-readable moon phase name, or null for inland
         * @param moonAtPerigee             true if the moon is near perigee, or null for inland
         * @param nearestSolarOffsetMinutes signed minutes from the light to the nearest extreme
         * @param nearestExtremeKind        {@code "HW"} or {@code "LW"}, or null
         * @param tideOnTheLight            true when that extreme lands inside the tight window
         * @param nearestSolarOffsetPhrase  the same fact already formatted, or null
         */
        public TideInfo(String tideState, boolean tideAligned, LocalDateTime nearestHighTideTime,
                BigDecimal nearestHighTideHeight, boolean heightAboveP95,
                boolean heightAboveSpringThreshold, LunarTideType lunarTideType,
                String lunarPhase, Boolean moonAtPerigee, Integer nearestSolarOffsetMinutes,
                String nearestExtremeKind, Boolean tideOnTheLight,
                String nearestSolarOffsetPhrase) {
            this(tideState, tideAligned, nearestHighTideTime, nearestHighTideHeight,
                    heightAboveP95, heightAboveSpringThreshold, lunarTideType, lunarPhase,
                    moonAtPerigee, nearestSolarOffsetMinutes, nearestExtremeKind, tideOnTheLight,
                    nearestSolarOffsetPhrase, null, null, null, null, null, null);
        }

        /**
         * Returns a copy of this tide block as a LITE reader may see it, mirroring
         * {@link LocationTideFact#forLite()}: the per-location match ({@code tideAligned},
         * {@code tideAlignmentQuality}, {@code tideShortfall}, {@code tideFitPhrase}) and every
         * height ({@code tideHeight}, {@code tideLevel}, {@code nearestHighTideHeight}) are null.
         * The tide against the light is Pro too (tide-light-pro-plan.md): {@code
         * nearestSolarOffsetMinutes}, {@code nearestExtremeKind}, {@code tideOnTheLight} and {@code
         * nearestSolarOffsetPhrase} are null. The state, the nearest high's time, the king/spring
         * flags and the lunar facts stay: they back the free spring and king hot topics. Built on
         * the canonical constructor so a component added later cannot be defaulted away silently.
         *
         * @return a copy with the Pro tide fields null
         */
        public TideInfo forLite() {
            return new TideInfo(tideState, null, nearestHighTideTime, null, heightAboveP95,
                    heightAboveSpringThreshold, lunarTideType, lunarPhase, moonAtPerigee,
                    null, null, null, null, null, tideDirection, null, null, null, null);
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

    /**
     * One location's view of a lunar eclipse falling on this slot's own window — the dawn/dusk
     * race data (plan {@code lunar-eclipse-plan.md} §2.5).
     *
     * <p>{@link #umbraStart}/{@link #umbraEnd} are the eclipse's own {@code u1}/{@code u4} —
     * <b>unclipped</b> by this location's moonset or moonrise — so the popup's race geometry can
     * derive its own umbra band ({@code umbraStart → min(umbraEnd, moonset)}) and, on a night the
     * Moon sets mid-eclipse, its own hatch band ({@code moonset → umbraEnd}) without this record
     * doing that clipping first and destroying the information the hatch needs. Every other moon
     * field ({@link #moonAltAtMax}, {@link #moonAzAtMax}, {@link #moonAzCardinal},
     * {@link #moonset}, {@link #moonrise}, {@link #setsInShadow}, {@link #risesInShadow}) is this
     * location's own geometry, straight from {@code LunarEclipseCalculator.sight}.
     *
     * @param type            {@code "LUNAR_ECLIPSE"} — a constant today, carried so a client
     *                        keying on {@code badge.type} the way it already does for the topic
     *                        chip can key on this field identically, and so a future second
     *                        eclipse-sight producer would not need a new discriminator
     * @param moonAltAtMax    the Moon's altitude above the astronomical horizon at greatest
     *                        eclipse, degrees, rounded to the nearest whole degree; negative when
     *                        below the horizon
     * @param moonAzAtMax     the Moon's azimuth at greatest eclipse, degrees clockwise from true
     *                        north, rounded to the nearest whole degree
     * @param moonAzCardinal  {@link #moonAzAtMax} as a 16-point compass cardinal (e.g. "WSW")
     * @param maximum         greatest eclipse, London local time
     * @param umbraStart      the eclipse's own {@code u1} (umbral phase begins), London local time
     *                        — see the class javadoc above on why this is not clipped to moonrise
     * @param umbraEnd        the eclipse's own {@code u4} (umbral phase ends), London local time —
     *                        not clipped to moonset, for the same reason
     * @param moonset         the moonset paired with {@link #moonrise}, London local time, or null
     *                        on the practical non-occurrence {@link LunarEclipseSight} documents
     * @param moonrise        the moonrise that began the Moon's current visible arc, London local
     *                        time, or null on the same practical basis
     * @param setsInShadow    true when {@link #moonset} falls inside {@code [umbraStart, umbraEnd]}
     * @param risesInShadow   true when {@link #moonrise} falls inside {@code [umbraStart, umbraEnd]}
     * @param race            {@code "DAWN"} when this is a SUNRISE eclipse and the sky is still
     *                        brightening while the Moon remains in shadow ({@link #umbraEnd} falls
     *                        after nautical dawn minus an hour); {@code "DUSK"} for the SUNSET
     *                        mirror ({@link #umbraStart} falls before nautical dusk plus an hour);
     *                        null when neither applies (a high, leisurely eclipse with no race
     *                        against the light) — the popup shows facts only in that case
     * @param stops           the four {@link LightStop}s the race's gradient is built from — see
     *                        {@link LightStop}; always four entries when this record exists, keyed
     *                        for a SUNRISE window ({@code NAUTICAL_DAWN}, {@code CIVIL_DAWN},
     *                        {@code SUNRISE}, {@code GOLDEN_MORNING_END}) or a SUNSET one
     *                        ({@code GOLDEN_EVENING_START}, {@code SUNSET}, {@code CIVIL_DUSK},
     *                        {@code NAUTICAL_DUSK}), never both — {@link #race} may still be null
     *                        even when stops are present, on the leisurely-eclipse case above
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EclipseSight(
            String type,
            int moonAltAtMax,
            int moonAzAtMax,
            String moonAzCardinal,
            LocalDateTime maximum,
            LocalDateTime umbraStart,
            LocalDateTime umbraEnd,
            LocalDateTime moonset,
            LocalDateTime moonrise,
            boolean setsInShadow,
            boolean risesInShadow,
            String race,
            List<LightStop> stops) {

        /**
         * Canonical constructor: takes an immutable copy of {@code stops}, and reads a null list as
         * empty so a payload without gradient stops still yields a usable sight.
         */
        public EclipseSight {
            stops = stops == null ? List.of() : List.copyOf(stops);
        }
    }

    /**
     * One point on the dawn/dusk race's light gradient.
     *
     * @param key  a key from the frontend's {@code MastheadLight.RULE_COLOURS} map (e.g.
     *             {@code "NAUTICAL_DAWN"}, {@code "SUNSET"}) — never invented here, so the popup's
     *             race can feed this list straight into the same gradient builder the masthead's
     *             own light rule already uses, with no client-side re-mapping
     * @param time the instant this light stage begins here, on this date, London local time
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LightStop(String key, LocalDateTime time) {
    }
}
