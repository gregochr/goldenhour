package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Top-level daily briefing response served by {@code GET /api/briefing}.
 *
 * @param generatedAt        UTC timestamp when this briefing was generated
 * @param headline           one-line summary highlighting the best opportunities
 * @param days               per-day briefing data (today + tomorrow)
 * @param auroraTonight      tonight's aurora summary, or {@code null} when the state machine is idle
 * @param auroraTomorrow     tomorrow night's Kp forecast summary, or {@code null} if unavailable
 * @param stale              true when this is the last-known-good briefing, not freshly generated
 * @param hotTopics          seasonal and special-interest hot topics for the forecast window
 * @param seasonalFeatures   active seasonal feature keys (e.g. "BLUEBELL") — used by frontend
 *                           to conditionally render seasonal filter chips
 * @param renderedEvents     the solar events the Plan tab draws, ordered and bounded to the whole days
 *                           today..T+3 ({@code PlanRenderLimits.withinHorizon}), with elapsed events
 *                           already dropped against a UTC instant. Serve-path only, published by
 *                           {@code PlanWindowProjector} as the outermost step — {@code null} on
 *                           every internal path, on every stored payload, and on any payload cached
 *                           before this field existed.
 *
 *                           <p><b>Null and empty mean different things.</b> Null is "nothing
 *                           projected this response", and the client falls back to walking
 *                           {@code days} itself; empty is "the projector ran and found no live
 *                           window", and the client draws nothing. Collapsing the two would make a
 *                           deploy-window degrade indistinguishable from a genuinely empty forecast.
 * @param previousGeneratedAt the build stamp every {@code BriefingRegion.meanRatingDelta} on this
 *                           response was measured against — the newest briefing build strictly
 *                           before {@link #generatedAt}, from {@code briefing_region_snapshot}.
 *                           Serve-path only, {@code null} on every internal path, on every stored
 *                           payload, and whenever no earlier build is on record.
 *
 *                           <p><b>One basis for the whole screen.</b> Deltas are taken against
 *                           this single build rather than against each region's own latest earlier
 *                           row, so that one "since the last forecast run" line can describe every
 *                           chip truthfully. A region absent from that build gets no delta at all
 *                           rather than one reaching further back — silence, which is this
 *                           channel's documented degrade throughout.
 *
 *                           <p>It is not what the strip's age line prints. That reads
 *                           {@code generatedAt} (how long ago the last run was), which is the age
 *                           the shell already renders; this field is the delta's basis, published
 *                           so a client or an operator can tell what the movement was measured
 *                           from.
 * @param bigSeas            the big-seas days (docs/engineering/big-seas-plan.md), attached as the
 *                           last step of the serve path by {@code ServedBriefingAssembler}.
 *                           Serve-path only: null on every internal path and every stored payload,
 *                           so it never reaches {@code daily_briefing_cache}; omitted from the JSON
 *                           when null. It carries no personal data, so it is safe on the
 *                           ETag-shared payload.
 * @param allowedLocationIds the ids of the locations whose ratings this reader may see, sorted, or
 *                           {@code null} when the response is not shaped by the reader's sub-region picks
 *                           (docs/engineering/sub-regions-plan.md D6). Present — possibly empty — exactly
 *                           when {@code LitePickShaper} shaped the response, so the client never decides what
 *                           is outside the picks: a slot is gated when the server says so, and this list is
 *                           for surfaces that need to know which places the reader holds. Serve-time only,
 *                           never persisted in {@code daily_briefing_cache}
 * @param briefingVersion    a content hash of the ratings and dispositions this response was assembled from
 *                           (docs/engineering/reach-scoped-card-plan.md R2 step 5), computed once per memoised served
 *                           briefing by {@code ServedBriefingAssembler}. It is the key that lets the client tell that
 *                           {@code GET /api/user/settings/reach-verdicts} was computed from the very briefing it holds.
 *                           Shared, not personal (it depends on the briefing alone), so safe on the ETag-shared
 *                           payload. Serve-time only: null on every internal path and every stored payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyBriefingResponse(
        LocalDateTime generatedAt,
        String headline,
        List<BriefingDay> days,
        AuroraTonightSummary auroraTonight,
        AuroraTomorrowSummary auroraTomorrow,
        boolean stale,
        List<HotTopic> hotTopics,
        List<String> seasonalFeatures,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PlanRenderedEvent> renderedEvents,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime previousGeneratedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<BigSeasDay> bigSeas,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> allowedLocationIds,
        @JsonInclude(JsonInclude.Include.NON_NULL) String briefingVersion) {

    /**
     * Null-safe compact constructor — defensive copies for list fields only.
     *
     * <p>{@code renderedEvents} is the one list that stays nullable rather than being normalised to
     * empty. Absent and empty are different answers: absent means nothing projected this response
     * (every internal path, and every payload cached before the field existed), while empty means
     * the projector ran and found no live window. The client distinguishes them — it degrades to
     * its own walk of {@code days} on absent, and draws nothing on empty.
     */
    public DailyBriefingResponse {
        days = List.copyOf(days);
        hotTopics = hotTopics == null ? List.of() : List.copyOf(hotTopics);
        seasonalFeatures = seasonalFeatures == null ? List.of() : List.copyOf(seasonalFeatures);
        renderedEvents = renderedEvents == null ? null : List.copyOf(renderedEvents);
        bigSeas = bigSeas == null ? null : List.copyOf(bigSeas);
        allowedLocationIds = allowedLocationIds == null ? null : List.copyOf(allowedLocationIds);
    }

    /**
     * Nine-component form: no producer of a response knows the serve-path-only fields, so they default to null
     * ({@code previousGeneratedAt}, {@code bigSeas}, {@code allowedLocationIds}, {@code briefingVersion}). The
     * serve steps publish them through {@link #withMovement}, {@link #withBigSeas}, {@link #withPickScope} and
     * {@link #withBriefingVersion}.
     *
     * <p>Deliberately no overload shares an arity with a form that carried the retired best-bet components
     * (10 to 13), so a caller still passing those fails to compile rather than silently re-binding.
     *
     * @param generatedAt      UTC generation timestamp
     * @param headline         one-line summary
     * @param days             per-day briefing data
     * @param auroraTonight    tonight's aurora summary or null
     * @param auroraTomorrow   tomorrow's aurora summary or null
     * @param stale            last-known-good flag
     * @param hotTopics        hot topics
     * @param seasonalFeatures active seasonal feature keys
     * @param renderedEvents   the events the Plan tab draws, or null
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures,
            List<PlanRenderedEvent> renderedEvents) {
        this(generatedAt, headline, days, auroraTonight, auroraTomorrow, stale, hotTopics, seasonalFeatures,
                renderedEvents, null, null, null, null);
    }

    /**
     * Eight-component form, defaulting {@code renderedEvents} (and everything after it) to null. Used by call
     * sites that build a response from scratch; anything copying or overlaying an existing response must use a
     * wither so no component is silently dropped.
     *
     * @param generatedAt      UTC generation timestamp
     * @param headline         one-line summary
     * @param days             per-day briefing data
     * @param auroraTonight    tonight's aurora summary or null
     * @param auroraTomorrow   tomorrow's aurora summary or null
     * @param stale            last-known-good flag
     * @param hotTopics        hot topics
     * @param seasonalFeatures active seasonal feature keys
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures) {
        this(generatedAt, headline, days, auroraTonight, auroraTomorrow, stale, hotTopics, seasonalFeatures, null);
    }

    /**
     * Returns a copy of this response with the day hierarchy replaced. All other fields
     * (headline, aurora, hot topics, serve-path stamps) are preserved. Used by the
     * serve-time re-enrichment path to swap in regions with freshly re-derived verdicts.
     *
     * <p>It must carry <b>every</b> component: this method is how the outermost serve step
     * ({@code PlanWindowProjector}) rebuilds the response, so dropping one would silently unpublish it.
     *
     * @param newDays the replacement day hierarchy
     * @return a copy carrying {@code newDays}
     */
    public DailyBriefingResponse withDays(List<BriefingDay> newDays) {
        return new DailyBriefingResponse(generatedAt, headline, newDays,
                auroraTonight, auroraTomorrow, stale,
                hotTopics, seasonalFeatures,
                renderedEvents, previousGeneratedAt, bigSeas, allowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy carrying the movement-enriched day hierarchy and the build its deltas were
     * measured against.
     *
     * <p>One method rather than two withers, for {@link #withPlan}'s reason: the two are one
     * answer. Every {@code BriefingRegion.meanRatingDelta} in {@code newDays} was subtracted from
     * the build named by {@code newPreviousGeneratedAt}, and publishing them separately would let
     * a caller emit deltas over a basis they were not measured from.
     *
     * @param newDays                 the day hierarchy with deltas attached
     * @param newPreviousGeneratedAt  the build stamp they were measured against, or null when
     *                                there was no earlier build to measure against
     * @return a copy carrying both
     */
    public DailyBriefingResponse withMovement(List<BriefingDay> newDays,
            LocalDateTime newPreviousGeneratedAt) {
        return new DailyBriefingResponse(generatedAt, headline, newDays,
                auroraTonight, auroraTomorrow, stale,
                hotTopics, seasonalFeatures,
                renderedEvents, newPreviousGeneratedAt, bigSeas, allowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy carrying the projected day hierarchy and the events the Plan tab renders.
     *
     * <p>One method rather than two withers because the two are one answer: the days carry each
     * window's projection and each day's peak, and {@code renderedEvents} says which of those
     * windows are drawn. Publishing them separately would let a caller emit a horizon that does not
     * match the projection it came from — the class of divergence this whole phase removes.
     *
     * @param newDays           the projected day hierarchy
     * @param newRenderedEvents the ordered, capped list of events the Plan tab draws
     * @return a copy carrying both
     */
    public DailyBriefingResponse withPlan(List<BriefingDay> newDays,
            List<PlanRenderedEvent> newRenderedEvents) {
        return new DailyBriefingResponse(generatedAt, headline, newDays,
                auroraTonight, auroraTomorrow, stale,
                hotTopics, seasonalFeatures,
                newRenderedEvents, previousGeneratedAt, bigSeas, allowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy carrying the live serve-time overlays: tonight's and tomorrow night's aurora
     * summaries and the hot topics.
     *
     * <p>One method rather than three withers because the three are recomputed together on every
     * serve, from the same live state. Every other component is preserved.
     *
     * @param newAuroraTonight  tonight's aurora summary, or null
     * @param newAuroraTomorrow tomorrow night's aurora summary, or null
     * @param newHotTopics      the live hot topics
     * @return a copy carrying all three
     */
    public DailyBriefingResponse withLiveOverlays(AuroraTonightSummary newAuroraTonight,
            AuroraTomorrowSummary newAuroraTomorrow, List<HotTopic> newHotTopics) {
        return new DailyBriefingResponse(generatedAt, headline, days,
                newAuroraTonight, newAuroraTomorrow, stale,
                newHotTopics, seasonalFeatures,
                renderedEvents, previousGeneratedAt, bigSeas, allowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy carrying the big-seas days. Every other component is preserved.
     *
     * @param newBigSeas the big-seas days, or null for none
     * @return a copy carrying {@code newBigSeas}
     */
    public DailyBriefingResponse withBigSeas(List<BigSeasDay> newBigSeas) {
        return new DailyBriefingResponse(generatedAt, headline, days,
                auroraTonight, auroraTomorrow, stale,
                hotTopics, seasonalFeatures,
                renderedEvents, previousGeneratedAt, newBigSeas, allowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy served as shaped by the reader's sub-region picks.
     *
     * @param newHotTopics        the hot topics, shaped
     * @param newAllowedLocationIds the picked locations' ids, sorted (empty when the reader holds none)
     * @return a copy with both replaced
     */
    public DailyBriefingResponse withPickScope(List<HotTopic> newHotTopics, List<Long> newAllowedLocationIds) {
        return new DailyBriefingResponse(generatedAt, headline, days,
                auroraTonight, auroraTomorrow, stale,
                newHotTopics, seasonalFeatures,
                renderedEvents, previousGeneratedAt, bigSeas, newAllowedLocationIds, briefingVersion);
    }

    /**
     * Returns a copy carrying the content hash of the ratings it was assembled from, every other component
     * unchanged.
     *
     * @param newBriefingVersion the version, or null for none
     * @return the copy
     */
    public DailyBriefingResponse withBriefingVersion(String newBriefingVersion) {
        return new DailyBriefingResponse(generatedAt, headline, days,
                auroraTonight, auroraTomorrow, stale,
                hotTopics, seasonalFeatures,
                renderedEvents, previousGeneratedAt, bigSeas, allowedLocationIds, newBriefingVersion);
    }
}
