package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The noctilucent cloud season, as a span.
 *
 * <p><strong>The plan filed NLC under "Almanac" and the existing strategy cannot serve it.</strong>
 * {@code NlcHotTopicStrategy} looks almanac-shaped because its season bounds are fixed, but its
 * firing condition is the first statement in {@code detect}: a clear-night clarity cache built
 * during the briefing run. Out of season, before the first run of the day, and after any restart,
 * it returns nothing. Asking it for ninety days would return nothing for ninety days.
 *
 * <p>So the two halves are split, and only the almanac half lives here: <em>when the season runs</em>
 * is arithmetic over fixed calendar bounds and answerable forever;<em> whether tonight is clear
 * enough to see anything</em> is a forecast and stays where it is. This source deliberately makes no
 * claim about visibility on any given night.
 *
 * <p>The bounds come from {@code NlcClarityService.isNlcSeason}, probed day by day, rather than
 * from a copy of its {@code NLC_SEASON} constant — which is private. Probing means the two can
 * never disagree about a boundary date, at the cost of a few dozen cheap calls per rebuild.
 *
 * <p>The entry is <strong>{@link AlmanacKind#ALMANAC}</strong> because its dates are fixed. That is
 * not a claim that noctilucent cloud will appear: NLC is the product's canonical unforecastable
 * display, and the copy says so. The kind describes the certainty of the <em>span</em>, and the
 * detail carries the caveat.
 */
@Component
public class NlcSeasonAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "nlc-season";

    private static final String TITLE = "Noctilucent cloud season";

    private static final String DETAIL =
            "The weeks when the sun sits at the right depth below the northern horizon to light"
                    + " ice clouds at the edge of space. They cannot be forecast — the season only"
                    + " says when to keep looking north an hour or two after sunset.";

    private final NlcClarityService nlcClarityService;

    /**
     * Constructs an {@code NlcSeasonAlmanacSource}.
     *
     * @param nlcClarityService supplies the season predicate — the only part of it used here
     */
    public NlcSeasonAlmanacSource(NlcClarityService nlcClarityService) {
        this.nlcClarityService = nlcClarityService;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        List<AlmanacEvent> events = new ArrayList<>();
        LocalDate spanStart = null;
        LocalDate spanEnd = null;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (nlcClarityService.isNlcSeason(date)) {
                if (spanStart == null) {
                    spanStart = date;
                }
                spanEnd = date;
            } else if (spanStart != null) {
                events.add(toEvent(spanStart, spanEnd));
                spanStart = null;
            }
        }

        if (spanStart != null) {
            events.add(toEvent(spanStart, spanEnd));
        }
        return events;
    }

    /**
     * Builds the entry, flagging whether the span was cut short by the edge of the feed.
     *
     * <p>An eleven-week season seen through a ninety-day window is usually clipped at one end or
     * both, and a reader told "season: 25 May – 12 Aug" when the window simply stopped on the 12th
     * would take a rendering artefact for an astronomical fact. The flags say which ends are real.
     *
     * <p>⚠️ <strong>The flags ask the season, not the window.</strong> They were first written as
     * {@code start.isEqual(from)}, which is true both when the season was clipped <em>and</em> when
     * it genuinely opens on the feed's first day — so once a year the real season boundary was
     * reported as an artefact. The test that was supposed to guard this stubbed every day in
     * season and could not tell the two apart. Probing the day either side costs two calls and
     * distinguishes them exactly.
     */
    private AlmanacEvent toEvent(LocalDate start, LocalDate end) {
        boolean clippedAtStart = nlcClarityService.isNlcSeason(start.minusDays(1));
        boolean clippedAtEnd = nlcClarityService.isNlcSeason(end.plusDays(1));
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, TYPE, TITLE, DETAIL,
                AlmanacEvent.metaOf(
                        "startsBeforeWindow", clippedAtStart ? "true" : null,
                        "endsAfterWindow", clippedAtEnd ? "true" : null),
                List.of());
    }
}
