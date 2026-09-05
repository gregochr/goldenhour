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
     * @param bestRating   the best star rating across the window, or null when nothing is rated
     * @param confidence   how provisional that verdict is, or null when unknown
     * @param pick         BEST or ALSO when this window is one of the forecast's two picks
     * @param headline     that pick's headline; null exactly when {@code pick} is null
     * @param regionName   the picked region's name, or null when this window carries no pick
     * @param locationName the picked region's highest-rated location, nullable independently
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
            @JsonInclude(JsonInclude.Include.NON_NULL) String locationName) {
    }
}
