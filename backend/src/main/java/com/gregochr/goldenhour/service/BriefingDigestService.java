package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Projects the served briefing into the flat, bounded window list of {@link BriefingDigestResponse}.
 *
 * <p>Reads the same accessor the full briefing endpoint reads, so the digest cannot describe a
 * different forecast from the one the Plan tab is showing, and copies every published figure
 * straight off the {@link BriefingWindow} that {@link PlanWindowProjector} authored. Nothing here
 * computes a verdict, a rating or a headline.
 */
@Service
public class BriefingDigestService {

    /**
     * Windows returned when the caller names no limit — the Plan matrix's own six pictures.
     *
     * <p>⚠️ <b>Derived, never a literal.</b> {@link PlanRenderLimits} exists because the projector
     * once kept its own copy of this horizon and it silently diverged; writing {@code 6} here would
     * re-open exactly that. The divergence is not theoretical: {@code selectPicks} draws BEST and
     * ALSO from the rendered window set, so raising the render horizon while this stayed at six
     * would let the forecast's Best Bet fall outside a default digest — a widget whose whole
     * purpose is to show the best window, structurally unable to.
     */
    public static final int DEFAULT_LIMIT = PlanRenderLimits.MAX_VISIBLE_EVENTS;

    /**
     * The most windows one request may ask for.
     *
     * <p>Deliberately above anything the briefing can currently produce (five days × two solar
     * events = ten), so it is a guard against a caller asking for something absurd rather than a
     * horizon of its own. It must never be read as "the digest returns up to 24 windows".
     */
    public static final int MAX_LIMIT = 24;

    /**
     * The digest's timeline order: by date, then sunrise before sunset.
     *
     * <p>Deliberately <b>not</b> ordered on {@code BriefingWindow.eventTime}, which is nullable — a
     * timeless window would then need a null-ordering rule, and whichever end it were sorted to
     * would be a claim about when it happens that the payload does not make. Date and event type
     * are always present and together are a total order, so this sorts on those and publishes the
     * event time as the nullable fact it is.
     */
    private static final Comparator<BriefingDigestResponse.Window> CHRONOLOGICAL =
            Comparator.comparing(BriefingDigestResponse.Window::date)
                    .thenComparing(BriefingDigestResponse.Window::event);

    private final BriefingService briefingService;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code BriefingDigestService}.
     *
     * @param briefingService the service that serves the cached briefing
     * @param freshness       the project's single answer to "has this solar event passed"
     */
    public BriefingDigestService(BriefingService briefingService, SolarEventFreshness freshness) {
        this.briefingService = briefingService;
        this.freshness = freshness;
    }

    /**
     * Returns the next windows that have not yet passed, earliest first.
     *
     * <p>{@code limit} is clamped to [1, {@value #MAX_LIMIT}] rather than rejected: the callers this
     * exists for are unattended background refreshes, and a widget that fails to draw because it
     * asked for one window too many is a worse outcome than one that draws a shorter list.
     *
     * @param limit how many windows to return, clamped into range
     * @return the digest, or null when no briefing has been generated yet
     */
    public BriefingDigestResponse digest(int limit) {
        // ⚠️ A SMALL PAYLOAD, NOT A CHEAP REQUEST. This is the only accessor that attaches
        // `summary.window()`, so it is the only correct one here — but it is the full Plan-tab
        // assembly: live hot-topic recomputation across every strategy, the cached-evaluation
        // re-enrichment, the movement queries and the window tide rollup. The digest keeps ten
        // scalars per window and discards the rest. That is the "paid twice, one copy thrown away"
        // shape `ServedBriefingAssembler` was extracted to stop, and it matters more here than
        // elsewhere because the intended caller is an unattended background refresh — the most
        // frequent, lowest-payload reader in the system now drives the most expensive read path.
        // If that becomes a problem the fix is a windows-only assembly or a short-TTL memo of the
        // assembled payload, NOT a cheaper accessor: one that omits the window omits the answer.
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return null;
        }
        LocalDateTime now = freshness.now();
        List<BriefingDigestResponse.Window> windows = new ArrayList<>();
        for (BriefingDay day : briefing.days()) {
            if (day == null || day.date() == null) {
                continue;
            }
            for (BriefingEventSummary summary : day.eventSummaries()) {
                BriefingDigestResponse.Window window = project(day.date(), summary, now);
                if (window != null) {
                    windows.add(window);
                }
            }
        }
        windows.sort(CHRONOLOGICAL);
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        return new BriefingDigestResponse(
                briefing.generatedAt(),
                windows.size() > capped ? windows.subList(0, capped) : windows);
    }

    /**
     * Flattens one event summary, or returns null when it carries nothing a timeline can show.
     *
     * <p>Three summaries are dropped: one with no window at all (the tree carries these for days
     * the projector never reached), one that is not a solar event, and one whose moment has gone by
     * {@link PlanWindowProjector#hasPassed}. The elapsed test is that method rather than a local
     * comparison so this list and the Plan tab retire a window at the same minute.
     */
    private BriefingDigestResponse.Window project(LocalDate date, BriefingEventSummary summary,
            LocalDateTime now) {
        if (summary == null || summary.window() == null || !isSolar(summary.targetType())) {
            return null;
        }
        BriefingWindow window = summary.window();
        if (PlanWindowProjector.hasPassed(window.eventTime(), now)) {
            return null;
        }
        BriefingWindow.Pick pick = window.pick();
        return new BriefingDigestResponse.Window(
                date,
                summary.targetType(),
                window.eventTime(),
                window.verdict(),
                window.bestRating(),
                window.confidence(),
                pick == null ? null : pick.kind(),
                pick == null ? null : pick.headline(),
                pick == null ? null : pick.regionName(),
                pick == null ? null : pick.locationName(),
                pick == null ? null : pick.locationId());
    }

    /**
     * Whether an event type belongs on the digest's timeline.
     *
     * <p>A null type cannot be placed on it, and {@code HOURLY} is not a window — it is the
     * wildlife comfort row, which carries no verdict this shape could publish.
     *
     * @param type the summary's event type
     * @return true for the two solar events the digest lists
     */
    private static boolean isSolar(TargetType type) {
        return type == TargetType.SUNRISE || type == TargetType.SUNSET;
    }
}
