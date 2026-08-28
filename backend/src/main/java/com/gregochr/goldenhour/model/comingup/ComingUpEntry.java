package com.gregochr.goldenhour.model.comingup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One "Coming up" chronology entry.
 *
 * <p>P1 shipped every {@link AlmanacEvent} field unchanged plus {@code enteredWindow}. P2
 * ({@code service/comingup/ComingUpAssembler}) grows every entry to the full shape plan §13
 * describes: the surprise score ({@code bits}), server-ordered {@code facts}, the single
 * {@code action}, and the type-specific extras ({@code tide}, {@code coincidence}). Plan §13 is the
 * single source of truth for the shape — a field not there does not exist.
 *
 * @param startDate     first day of the span, inclusive — never null
 * @param endDate       last day of the span, inclusive; never before {@code startDate}
 * @param kind          whether the date is fixed by ephemeris or driven by a forecast
 * @param type          stable machine-readable discriminator, e.g. {@code spring-tide}
 * @param title         the short human label
 * @param detail        one sentence of plain-language context
 * @param meta          derived facts, absent when they could not be derived
 * @param regions       region names the entry applies to; empty means sky-wide rather than unknown
 * @param enteredWindow the date this entry first entered the feed's 90-day window — a lower bound
 *                      rather than an exact arrival for a run near the window's far edge or an
 *                      entry that appears for a reason other than the sliding window edge; see D3
 * @param id            {@code ${type}:${startDate}:${endDate}} — deterministic, unique by
 *                      construction
 * @param family        {@code coastal|aurora|air|night-sky|sun-moon|dust|eclipse} — maps to a
 *                      {@code --color-topic-*} token (D6)
 * @param kindTag       the card's display tag: {@code Almanac} or {@code Forecast · peak} — not the
 *                      strip's cadence vocabulary
 * @param superlative   nullable, computed falsifiable-proof against the assembled window, e.g.
 *                      {@code "biggest until November"}
 * @param metric        nullable headline metric string, e.g. {@code "8/10"}, {@code "~20/hr"}
 * @param prose         nullable — feature cards only ("say the definition once": the first
 *                      occurrence of a type in the window)
 * @param facts         ordered fact rows; no HTML crosses the wire
 * @param threshold     nullable — the bar this occurrence cleared, required on any entry that will
 *                      carry a standing condition (coastal tides at first ship)
 * @param scoreNote     nullable server-authored sentence explaining a high-band score, read by both
 *                      the card and P5's since-line
 * @param action        exactly one destination into the rest of the app
 * @param bits          the surprise score; non-null for every {@link AlmanacKind#ALMANAC} entry,
 *                      nullable on a {@link AlmanacKind#FORECAST} entry until scored
 * @param interim       true unless {@code bits} rests on a mature, exact empirical magnitude —
 *                      every type but a tide run backed by ≥60 stored run peaks stays interim for
 *                      its whole life. This is the entry-level carrier plan D4's "never badge from
 *                      a bucketed magnitude" rule needs: a client badge reader must exclude interim
 *                      entries from clearing a band, however high {@code bits} reads, rather than
 *                      distorting the printed "rarity + magnitude = bits" sum to enforce it
 * @param tide          nullable — tide-run entries only; sparkline data (P3b)
 * @param coincidence   nullable/empty — set when this entry absorbed another topic under D10's max
 *                      rule; each line is the topic that did <b>not</b> carry {@code bits}
 * @param joinNote      nullable — server-authored joining sentence, present iff {@code coincidence}
 *                      is non-empty
 */
public record ComingUpEntry(
        LocalDate startDate,
        LocalDate endDate,
        AlmanacKind kind,
        String type,
        String title,
        String detail,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> meta,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> regions,
        LocalDate enteredWindow,
        String id,
        String family,
        String kindTag,
        String superlative,
        String metric,
        String prose,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ComingUpFact> facts,
        String threshold,
        String scoreNote,
        ComingUpAction action,
        Double bits,
        boolean interim,
        ComingUpTide tide,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ComingUpCoincidenceLine> coincidence,
        String joinNote) {

    /**
     * Normalises the collections so a consumer never has to null-check them, and requires the
     * fields every entry must carry regardless of phase.
     */
    public ComingUpEntry {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(enteredWindow, "enteredWindow");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(kindTag, "kindTag");
        meta = meta == null ? Map.of() : Map.copyOf(meta);
        regions = regions == null ? List.of() : List.copyOf(regions);
        facts = facts == null ? List.of() : List.copyOf(facts);
        coincidence = coincidence == null ? List.of() : List.copyOf(coincidence);
    }

    /**
     * Builds a P1-shaped entry from the almanac event it wraps: every {@link AlmanacEvent} field,
     * plus {@code enteredWindow}, with every P2 field left at its degraded default. Kept for
     * fixtures and callers that only need the P1 shape — {@code ComingUpAssembler} is the only
     * production path that builds the full P2 shape, via its own construction.
     *
     * @param event         the source almanac event
     * @param enteredWindow the date this entry first entered the feed's window
     * @return a {@link ComingUpEntry} carrying every field of {@code event}, {@code enteredWindow},
     *         and safe placeholder values for every field P2 added
     */
    public static ComingUpEntry from(AlmanacEvent event, LocalDate enteredWindow) {
        String id = event.type() + ":" + event.startDate() + ":" + event.endDate();
        return new ComingUpEntry(event.startDate(), event.endDate(), event.kind(), event.type(),
                event.title(), event.detail(), event.meta(), event.regions(), enteredWindow,
                id, "night-sky", "Almanac", null, null, null, List.of(), null, null,
                new ComingUpAction("See the plan for " + event.startDate() + " →", "plan",
                        event.startDate()),
                null, true, null, List.of(), null);
    }
}
