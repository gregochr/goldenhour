package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A flat, bounded, chronological projection of the briefing's solar windows.
 *
 * <p><b>Why this exists at all, when {@code GET /api/briefing} already carries every field here.</b>
 * That payload is a tree — {@code days[] → eventSummaries[] → regions[] → slots[]} — plus hot
 * topics, best bets and two aurora summaries. A client that only wants to say "the next window is
 * Thursday's sunset, three stars, here is the sentence" has to walk the whole tree to find it, and
 * some clients cannot afford to: an iOS widget extension decodes inside a ~30 MB ceiling and is
 * woken on a battery budget. This projection is the same answer with the tree removed.
 *
 * <p><b>It derives nothing.</b> Every field below is copied from a {@link BriefingWindow} that
 * {@link com.gregochr.goldenhour.service.PlanWindowProjector} has already authored for the Plan
 * tab. That is the entire point: a second client that re-derived a verdict, a rating or a headline
 * could disagree with the web UI about the same window, and the two would be impossible to
 * reconcile from the payload. If a figure is not on {@code BriefingWindow}, it does not belong
 * here — it belongs on {@code BriefingWindow} first, where both clients can read it.
 *
 * <p><b>Two things a non-web client must be told, because the payload does not say them.</b>
 *
 * <p>⚠️ <b>Every timestamp here is UTC and carries no offset</b> — {@code "2999-03-25T18:30:00"},
 * the project-wide wire format pinned by {@code JsonDateFormatContractTest} and shared with every
 * other endpoint. It is stated rather than fixed here on purpose: a digest that alone spoke
 * offset-carrying instants would be the second timezone rule this codebase has, which is the thing
 * its tide-chart bullet forbids. The consequence for a decoder is concrete — Swift's
 * {@code JSONDecoder.dateDecodingStrategy = .iso8601} rejects an offset-free string outright, so a
 * client must decode with an explicit UTC formatter and render in {@code Europe/London}. Note also
 * that {@code date} is the UK civil date while {@code eventTime} is UTC, so a device in another
 * zone that formats {@code eventTime} locally can print a day that disagrees with {@code date};
 * trust {@code date}, which is the day the forecast is about.
 *
 * <p>⚠️ <b>There is no verdict WORD.</b> {@code verdict} is the enum, and the display vocabulary
 * ("Worth it", "Maybe", "Stand down") lives in the web client. A second client must therefore keep
 * its own copy, and the two can drift — a real cost, accepted here rather than papered over,
 * because publishing a label would put display vocabulary on a payload whose stated rule is that
 * it copies {@link BriefingWindow} and derives nothing. If a label is wanted on the wire it belongs
 * on {@code BriefingWindow} first, where both clients read it.
 *
 * @param generatedAt when the underlying briefing was built, so a client can show its age
 * @param windows     the next {@code limit} windows that have not yet passed, earliest first
 */
public record BriefingDigestResponse(
        LocalDateTime generatedAt,
        List<Window> windows) {

    /** Defensive copy of the window list. */
    public BriefingDigestResponse {
        windows = List.copyOf(windows);
    }

    /**
     * One solar window, flattened.
     *
     * <p>The last four components are the {@link BriefingWindow.Pick} this window carries, unpacked
     * rather than nested — a pick is at most one per window and its absence is expressed by nulls,
     * which spares a small client a second object to model. {@code headline} and {@code pick} are
     * null together: a Pick exists only when its headline is usable, so neither can appear without
     * the other, and their joint absence is how this payload says "no narrative for this window".
     *
     * @param date         the window's civil date
     * @param event        SUNRISE or SUNSET
     * @param eventTime    the window's own event time, UTC, as {@code BriefingWindow} states it
     * @param verdict      the served verdict for this window
     * @param bestRating   the best star rating across the window, or null when nothing is rated.
     *                     ⚠️ <b>A labelled spot signal, never a verdict</b> — the rule
     *                     {@link BriefingWindow} states and every client inherits. {@code verdict}
     *                     is the top region's <em>average</em>, so {@code STAND_DOWN} beside a 4
     *                     here is a legitimate state describing two different things, and a client
     *                     that renders this number as the window's quality will contradict the
     *                     verdict beside it. Render it with its own label ("best spot 4★")
     * @param confidence   how provisional that verdict is, or null when unknown
     * @param pick         BEST or ALSO when this window is one of the forecast's two picks. The two
     *                     are drawn only from the leading {@link
     *                     com.gregochr.goldenhour.service.PlanRenderLimits#MAX_VISIBLE_EVENTS}
     *                     windows, so a request reaching past that horizon gets tail windows that
     *                     structurally cannot carry one — absence there is not evidence of a poor
     *                     window
     * @param headline     that pick's headline; null exactly when {@code pick} is null
     * @param regionName   the picked region's name, or null when this window carries no pick
     * @param locationName the picked region's highest-rated location, nullable independently
     * @param locationId   that location's id, travelling with the name for the reason
     *                     {@link BriefingWindow.Pick} gives: every per-user contract in this project
     *                     joins on the id and carries no name at all, so a name-only payload forces
     *                     a join through the locations roster — the join a rename silently empties.
     *                     Null on a slot cached before slots carried an id; prefer the id, fall
     *                     back to the name
     */
    public record Window(
            LocalDate date,
            TargetType event,
            LocalDateTime eventTime,
            DisplayVerdict verdict,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer bestRating,
            @JsonInclude(JsonInclude.Include.NON_NULL) Confidence confidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) BriefingWindow.PickKind pick,
            @JsonInclude(JsonInclude.Include.NON_NULL) String headline,
            @JsonInclude(JsonInclude.Include.NON_NULL) String regionName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String locationName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long locationId) {
    }
}
