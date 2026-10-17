package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one place a lunar eclipse's magnitude and visible span become words, shared by
 * {@link LunarEclipseHotTopicStrategy} and {@link LunarEclipseAlmanacSource} so the two surfaces
 * cannot independently mis-render either.
 *
 * <h2>The bugs this class exists to make impossible</h2>
 *
 * <p>Codex review of PR #913 found three real defects, across three passes, all from the same root
 * cause: two classes independently deriving copy from the same underlying facts, with no shared
 * place to enforce a rule once.
 *
 * <p><b>First and second</b>, both from treating {@code umbralMagnitude()} as a bounded percentage.
 * A total eclipse's magnitude is the fraction of the Moon's <em>diameter</em> inside the umbra,
 * which runs past {@code 1.0} once the Moon is fully swallowed (2028-12-31 is 1.24785, 2029-06-26
 * is 1.8452) — an un-capped {@code round(magnitude * 100)} therefore printed impossible copy like
 * "125% in shadow" and "185% in shadow" across the Plan detail line, the fact chip and the
 * Coming-up card, since {@link LunarEclipseAlmanacSource} independently re-ran the same arithmetic
 * and propagated the same impossible figure into the 90-day feed. Separately, that same class's
 * Coming-up "why" paragraph hard-coded the design's deep-partial copy ("Earth's shadow covers all
 * but a sliver…") for every entry, which is false for the 2028-01-12 eclipse — magnitude 0.0679,
 * roughly 7% of the Moon's diameter — where "all but a sliver" claims the opposite of what
 * actually happens.
 *
 * <p><b>Third</b>, a rises-in-shadow eclipse's "in shadow" span printed the eclipse's own {@code u1}
 * contact as its start even when the representative location cannot actually see the Moon until
 * later — the real 2028-12-31 UK-centre sight rises around 15:36 while {@code u1} is 15:07, so the
 * Plan chip and the Coming-up "shadow" line both claimed nearly half an hour of unavailable
 * viewing. {@link LunarEclipseSight#visibleUmbraStart()} already exists and is already correct
 * (moonrise when the Moon rises in shadow, {@code u1} otherwise) — the end was already clipped to
 * moonset via {@link LunarEclipseSight#visibleUmbraEnd()}; {@link #shadowSpan} clips the start the
 * same way.
 *
 * <p>This class is the shared place all three routes through — both callers derive every
 * magnitude- and span-related word from it, so a future divergence would have to be introduced in
 * a single function both surfaces already call, not reintroduced independently in each.
 */
final class LunarEclipseWording {

    /** How deep an eclipse reads, coarsened into the four bands the copy branches on. */
    enum Depth {
        TOTAL,
        DEEP,
        PARTIAL,
        SLIGHT
    }

    /** Magnitude at or above which an eclipse is TOTAL — the whole disc is inside the umbra. */
    private static final double TOTAL_MAGNITUDE = 1.0;

    /** Magnitude at or above which a partial eclipse reads DEEP rather than PARTIAL. */
    private static final double DEEP_MAGNITUDE = 0.80;

    /** Magnitude at or above which a partial eclipse reads PARTIAL rather than SLIGHT. */
    private static final double PARTIAL_MAGNITUDE = 0.40;

    /** The hour of day, London local, below which an eclipse's window reads as SUNRISE. */
    private static final int SUNSET_HOUR_THRESHOLD = 12;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private LunarEclipseWording() {
    }

    /**
     * The photographic window a lunar eclipse belongs to, from the London hour of its own
     * maximum — mirroring {@code EclipseHotTopicStrategy#eventType}'s rule for the solar eclipse,
     * so a rare high-altitude eclipse at local noon (never seeded — see
     * {@link LunarEclipseCatalog}) would still bucket sensibly.
     *
     * <p>The one place this rule is decided — both {@link LunarEclipseHotTopicStrategy} (which
     * window the topic pill claims) and {@code EclipseSightAssembler} (which of a location's two
     * daily slots {@code BriefingSlot.eclipse} attaches to) call this rather than each re-deriving
     * it, so the topic and the per-location sight can never disagree about which window an eclipse
     * belongs to.
     *
     * @param eclipse the catalogued eclipse
     * @return {@code "SUNRISE"} or {@code "SUNSET"}
     */
    static String eventType(LunarEclipse eclipse) {
        int hour = eclipse.max().atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).getHour();
        return hour < SUNSET_HOUR_THRESHOLD ? "SUNRISE" : "SUNSET";
    }

    /**
     * Converts a UTC contact instant (as stored in {@link LunarEclipseCatalog}) to Europe/London
     * local time — the same clock every {@link LunarEclipseSight} field and every
     * {@code BriefingSlot} time already uses.
     *
     * @param utcInstant the instant, expressed as a UTC {@link LocalDateTime}
     * @return the same instant, London local
     */
    static LocalDateTime toLondonLocal(LocalDateTime utcInstant) {
        return utcInstant.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalDateTime();
    }

    /**
     * The depth band this eclipse's umbral magnitude falls into.
     *
     * @param eclipse the catalogued eclipse
     * @return TOTAL, DEEP, PARTIAL or SLIGHT
     */
    static Depth depthOf(LunarEclipse eclipse) {
        double magnitude = eclipse.umbralMagnitude();
        if (magnitude >= TOTAL_MAGNITUDE) {
            return Depth.TOTAL;
        }
        if (magnitude >= DEEP_MAGNITUDE) {
            return Depth.DEEP;
        }
        if (magnitude >= PARTIAL_MAGNITUDE) {
            return Depth.PARTIAL;
        }
        return Depth.SLIGHT;
    }

    /**
     * The umbral magnitude as a whole-number percentage, capped at 100 — a total eclipse's own
     * magnitude runs past {@code 1.0} (see the class javadoc), so an un-capped figure would print
     * an impossible "125% in shadow". Every caller in this codebase reads {@code "total"} instead
     * of a percentage for a TOTAL eclipse ({@link #coverageWord}, {@link #shadowClause}), so this
     * cap is a defensive backstop against a future caller that forgets to branch on
     * {@link #depthOf} first, not a value any current template actually prints.
     *
     * @param eclipse the catalogued eclipse
     * @return the coverage percentage, 0–100
     */
    static int coveragePct(LunarEclipse eclipse) {
        return Math.min(100, (int) Math.round(eclipse.umbralMagnitude() * 100));
    }

    /**
     * The short headline word for a fact chip or a Coming-up metric: {@code "total"} for a TOTAL
     * eclipse (never a percentage — there is nothing left uncovered to measure), otherwise
     * {@code "N%"} with the capped figure.
     *
     * @param eclipse the catalogued eclipse
     * @return {@code "total"} or {@code "N%"}
     */
    static String coverageWord(LunarEclipse eclipse) {
        return depthOf(eclipse) == Depth.TOTAL ? "total" : coveragePct(eclipse) + "%";
    }

    /**
     * The band-specific opening sentence shared by the topic's (i) tooltip and the Coming-up "why"
     * paragraph — the one place both surfaces state how much of the Moon is in shadow, so neither
     * can independently apply deep-partial copy ("all but a sliver") to a barely-visible eclipse,
     * or total-eclipse copy to a partial one.
     *
     * @param eclipse the catalogued eclipse
     * @return one complete sentence, band-specific, with the real figure substituted where the
     *     band needs one
     */
    static String shadowClause(LunarEclipse eclipse) {
        return switch (depthOf(eclipse)) {
            case TOTAL -> "Earth's shadow covers the whole moon, and the shadowed disc turns copper.";
            case DEEP -> "Earth's shadow covers all but a sliver of the full moon, and the shadowed"
                    + " part turns copper.";
            case PARTIAL -> "Earth's shadow covers about " + coveragePct(eclipse)
                    + "% of the moon's diameter, and the shadowed part turns copper.";
            case SLIGHT -> "Earth's shadow clips " + coveragePct(eclipse)
                    + "% of the moon's edge — a darkened bite rather than a copper disc.";
        };
    }

    /**
     * The "in shadow" span shared by the topic's fact chip/detail line and the Coming-up "shadow"
     * meta line — clipped at <b>both</b> ends to when the Moon can actually be seen
     * ({@link LunarEclipseSight#visibleUmbraStart()}/{@link LunarEclipseSight#visibleUmbraEnd()}),
     * never the eclipse's own {@code u1}/{@code u4} contacts (see the class javadoc's third bug).
     *
     * @param sight the representative location's sight of the eclipse
     * @return {@code "HH:mm → HH:mm"}, with a {@code "rises "} prefix on the start when the Moon
     *     rises already in shadow and a {@code "sets "} prefix on the end when it sets while still
     *     in shadow — mirroring each other, so an eclipse that both rises and sets in shadow reads
     *     {@code "rises HH:mm → sets HH:mm"}
     */
    static String shadowSpan(LunarEclipseSight sight) {
        String start = sight.risesInShadow()
                ? "rises " + localTime(sight.visibleUmbraStart())
                : localTime(sight.visibleUmbraStart());
        String end = sight.setsInShadow()
                ? "sets " + localTime(sight.visibleUmbraEnd())
                : localTime(sight.visibleUmbraEnd());
        return start + " → " + end;
    }

    /** Formats a time already expressed in London local time — {@link LunarEclipseSight}'s clock
     * fields carry no offset to convert. */
    private static String localTime(LocalDateTime londonLocal) {
        return londonLocal.toLocalTime().format(HH_MM);
    }
}
