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
 * <p>P1 ships every {@link AlmanacEvent} field unchanged, plus {@code enteredWindow} — the date this
 * entry first became visible in the feed, computed against the fixed
 * {@code AlmanacService.DEFAULT_DAYS} horizon regardless of what {@code days} the caller asked for
 * (see {@code docs/engineering/coming-up-plan.md} D3, so a caller passing {@code ?days=30} cannot
 * redefine another user's arrival badge). Every field a later phase adds (bits, facts, action, tide,
 * …) lands here in that phase's own commit — plan §13 is the single source of truth for the full
 * shape.
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
        LocalDate enteredWindow) {

    /**
     * Normalises the collections so a consumer never has to null-check them.
     */
    public ComingUpEntry {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(enteredWindow, "enteredWindow");
        meta = meta == null ? Map.of() : Map.copyOf(meta);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /**
     * Builds an entry from the almanac event it wraps.
     *
     * @param event         the source almanac event
     * @param enteredWindow the date this entry first entered the feed's window
     * @return a {@link ComingUpEntry} carrying every field of {@code event} plus {@code enteredWindow}
     */
    public static ComingUpEntry from(AlmanacEvent event, LocalDate enteredWindow) {
        return new ComingUpEntry(event.startDate(), event.endDate(), event.kind(), event.type(),
                event.title(), event.detail(), event.meta(), event.regions(), enteredWindow);
    }
}
