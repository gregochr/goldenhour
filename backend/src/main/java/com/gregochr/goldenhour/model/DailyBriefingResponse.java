package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Top-level daily briefing response served by {@code GET /api/briefing}.
 *
 * @param generatedAt        UTC timestamp when this briefing was generated
 * @param headline           one-line summary highlighting the best opportunities
 * @param days               per-day briefing data (today + tomorrow)
 * @param bestBets           Claude-generated "best bet" picks (empty if the advisory call failed)
 * @param auroraTonight      tonight's aurora summary, or {@code null} when the state machine is idle
 * @param auroraTomorrow     tomorrow night's Kp forecast summary, or {@code null} if unavailable
 * @param stale              true when this is the last-known-good briefing, not freshly generated
 * @param partialFailure     true when some (but not all) location fetches failed this run
 * @param failedLocationCount number of locations that failed to fetch weather data this run
 * @param bestBetModel       display name of the Claude model used for best-bet picks (e.g. "Opus")
 * @param hotTopics          seasonal and special-interest hot topics for the forecast window
 * @param seasonalFeatures   active seasonal feature keys (e.g. "BLUEBELL") — used by frontend
 *                           to conditionally render seasonal filter chips
 * @param bestBetStatus      explicit best-bet outcome so the UI distinguishes an honest empty
 *                           result from a failure; {@code null} on legacy payloads (frontend
 *                           then infers from {@code bestBets} length)
 * @param bestBetsWithdrawn  {@code true} when the honesty filter removed at least one pick at
 *                           serve time because it named a region the same response reports as
 *                           unevaluable. Serve-path only — never persisted, {@code null}
 *                           everywhere else, including on every stored payload.
 *
 *                           <p>It exists because a one-pick list is ambiguous and the UI resolves
 *                           the ambiguity in prose. The banner tells a reader "nothing else in
 *                           this window scored well enough to be worth a second trip — that's the
 *                           forecast, not a missing recommendation", which is true when the
 *                           advisor withheld a runner-up and false when one was withdrawn here.
 *                           {@code bestBetStatus} cannot carry this: it describes what this
 *                           cycle's <em>advisor</em> did, which is a different question. The two
 *                           are orthogonal — a withdrawal can accompany a successful advisor, and
 *                           equally a {@code FAILED} one whose stale fallback list is what got
 *                           withdrawn.
 * @param renderedEvents     the solar events the Plan tab draws, ordered and capped at
 *                           {@code PlanRenderLimits.MAX_VISIBLE_EVENTS}, with elapsed events
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
 */
public record DailyBriefingResponse(
        LocalDateTime generatedAt,
        String headline,
        List<BriefingDay> days,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<BestBet> bestBets,
        AuroraTonightSummary auroraTonight,
        AuroraTomorrowSummary auroraTomorrow,
        boolean stale,
        boolean partialFailure,
        int failedLocationCount,
        String bestBetModel,
        List<HotTopic> hotTopics,
        List<String> seasonalFeatures,
        BestBetStatus bestBetStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean bestBetsWithdrawn,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PlanRenderedEvent> renderedEvents) {

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
        bestBets = bestBets == null ? List.of() : List.copyOf(bestBets);
        hotTopics = hotTopics == null ? List.of() : List.copyOf(hotTopics);
        seasonalFeatures = seasonalFeatures == null ? List.of() : List.copyOf(seasonalFeatures);
        renderedEvents = renderedEvents == null ? null : List.copyOf(renderedEvents);
    }

    /**
     * Fourteen-component form, defaulting {@code renderedEvents} to null.
     *
     * <p>Retained because every <em>producer</em> of a response legitimately has nothing to say
     * about which events are rendered — only {@code PlanWindowProjector}, the outermost serve step,
     * does, and it publishes through {@link #withPlan}. Same caveat as the shorter forms above: a
     * copy that omits the field silently unpublishes the rendered list.
     *
     * @param generatedAt         UTC generation timestamp
     * @param headline            one-line summary
     * @param days                per-day briefing data
     * @param bestBets            best-bet picks
     * @param auroraTonight       tonight's aurora summary or null
     * @param auroraTomorrow      tomorrow's aurora summary or null
     * @param stale               last-known-good flag
     * @param partialFailure      partial-failure flag
     * @param failedLocationCount failed location count
     * @param bestBetModel        best-bet model display name
     * @param hotTopics           hot topics
     * @param seasonalFeatures    active seasonal feature keys
     * @param bestBetStatus       whether the advisor produced picks, found none, or failed
     * @param bestBetsWithdrawn   whether the honesty filter withdrew a pick at serve time
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            List<BestBet> bestBets,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            boolean partialFailure,
            int failedLocationCount,
            String bestBetModel,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures,
            BestBetStatus bestBetStatus,
            Boolean bestBetsWithdrawn) {
        this(generatedAt, headline, days, bestBets, auroraTonight, auroraTomorrow,
                stale, partialFailure, failedLocationCount, bestBetModel, hotTopics,
                seasonalFeatures, bestBetStatus, bestBetsWithdrawn, null);
    }

    /**
     * Backward-compatible constructor without an explicit best-bet status (defaults to
     * {@code null}). Lets existing call sites and persisted JSON payloads that predate the
     * status contract continue to work unchanged; callers that know the outcome use the
     * canonical constructor instead.
     *
     * @param generatedAt         UTC generation timestamp
     * @param headline            one-line summary
     * @param days                per-day briefing data
     * <p><strong>Defaults {@code bestBetStatus} to {@code null} — do not use it to rebuild an
     * existing response.</strong> Two consumers switch on that field and both fail silently when
     * it is absent: {@code BriefingService.applyBestBetFallback} returns early unless the status is
     * {@code FAILED}, so the serve-time fallback never fires; and the frontend renders its "from an
     * earlier forecast" warning on {@code bestBetStatus === 'FAILED'}, so the staleness cue never
     * appears. Nothing throws and no test that ignores the field notices. This constructor is
     * retained for the many call sites that legitimately build a response from scratch with no
     * status yet; anything copying or overlaying an existing response must pass all 13 components
     * (or use {@link #withDays}).
     *
     * @param bestBets            best-bet picks
     * @param auroraTonight       tonight's aurora summary or null
     * @param auroraTomorrow      tomorrow's aurora summary or null
     * @param stale               last-known-good flag
     * @param partialFailure      partial-failure flag
     * @param failedLocationCount failed location count
     * @param bestBetModel        best-bet model display name
     * @param hotTopics           hot topics
     * @param seasonalFeatures    active seasonal feature keys
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            List<BestBet> bestBets,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            boolean partialFailure,
            int failedLocationCount,
            String bestBetModel,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures) {
        this(generatedAt, headline, days, bestBets, auroraTonight, auroraTomorrow,
                stale, partialFailure, failedLocationCount, bestBetModel, hotTopics,
                seasonalFeatures, null, null);
    }

    /**
     * Thirteen-component form, defaulting {@code bestBetsWithdrawn} to null.
     *
     * <p>Same caveat as the twelve-component form above, for the same reason: a copy that omits
     * the flag silently un-suppresses the "no also good" note. It is retained because every
     * <em>producer</em> of a response legitimately has nothing to say about withdrawal — only
     * {@link com.gregochr.goldenhour.service.BriefingHonestyFilter}, which performs it, does.
     *
     * @param bestBetStatus whether the advisor produced picks, found none, or failed
     */
    public DailyBriefingResponse(
            LocalDateTime generatedAt,
            String headline,
            List<BriefingDay> days,
            List<BestBet> bestBets,
            AuroraTonightSummary auroraTonight,
            AuroraTomorrowSummary auroraTomorrow,
            boolean stale,
            boolean partialFailure,
            int failedLocationCount,
            String bestBetModel,
            List<HotTopic> hotTopics,
            List<String> seasonalFeatures,
            BestBetStatus bestBetStatus) {
        this(generatedAt, headline, days, bestBets, auroraTonight, auroraTomorrow,
                stale, partialFailure, failedLocationCount, bestBetModel, hotTopics,
                seasonalFeatures, bestBetStatus, null);
    }

    /**
     * Returns a copy of this response with the day hierarchy replaced. All other fields
     * (headline, best bets, aurora, hot topics, status flags) are preserved. Used by the
     * serve-time re-enrichment path to swap in regions with freshly re-derived verdicts.
     *
     * <p>It must carry <b>every</b> component, {@code bestBetsWithdrawn} included. This method is
     * how the outermost serve step ({@code PlanWindowProjector}) rebuilds the response, and the
     * honesty filter has already run by then — dropping the flag here would silently restore the
     * false "nothing else scored well enough" note it exists to suppress.
     *
     * @param newDays the replacement day hierarchy
     * @return a copy carrying {@code newDays}
     */
    public DailyBriefingResponse withDays(List<BriefingDay> newDays) {
        return new DailyBriefingResponse(generatedAt, headline, newDays, bestBets,
                auroraTonight, auroraTomorrow, stale, partialFailure, failedLocationCount,
                bestBetModel, hotTopics, seasonalFeatures, bestBetStatus, bestBetsWithdrawn,
                renderedEvents);
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
        return new DailyBriefingResponse(generatedAt, headline, newDays, bestBets,
                auroraTonight, auroraTomorrow, stale, partialFailure, failedLocationCount,
                bestBetModel, hotTopics, seasonalFeatures, bestBetStatus, bestBetsWithdrawn,
                newRenderedEvents);
    }
}
