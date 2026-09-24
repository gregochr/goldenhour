package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every catalogued lunar eclipse in the feed's ninety-day range.
 *
 * <p>Exists separately from {@link LunarEclipseHotTopicStrategy} for the reason
 * {@link EclipseAlmanacSource} does: the hot-topic path is bounded by a four-day forecast window,
 * and this feed answers for ninety days. The two share the catalogue and the calculator, so they
 * cannot disagree about what an eclipse looks like from here — only about how far ahead they can
 * see it (pinned by {@code LunarEclipseAlmanacSourceTest}'s agreement checks, the same way
 * {@code EclipseAlmanacSourceTest} pins the solar pair).
 *
 * <p><b>Every meta value is a finished string</b> — the feed's stated rule that no number is
 * parsed, compared or re-formatted on the client applies here exactly as it does to the solar
 * entry. The {@code since} line in particular is composed from the catalogue's own chronology
 * (the eclipse immediately before this one, in date order) rather than any authored copy: the L0
 * catalogue's own javadoc records that a design draft assumed the wrong "most recent" eclipse, and
 * a derived sentence says whatever the catalogue says, not what a draft assumed.
 */
@Component
public class LunarEclipseAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "lunar-eclipse";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter NEXT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);
    private static final DateTimeFormatter SINCE_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK);

    private final LocationRepository locationRepository;
    private final LunarEclipseCalculator calculator;

    /**
     * Constructs a {@code LunarEclipseAlmanacSource}.
     *
     * @param locationRepository the enabled-location roster the eclipse is reduced against
     * @param calculator         per-location geometry and eligibility for one catalogued eclipse
     */
    public LunarEclipseAlmanacSource(LocationRepository locationRepository, LunarEclipseCalculator calculator) {
        this.locationRepository = locationRepository;
        this.calculator = calculator;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        List<LunarEclipse> eclipses = LunarEclipseCatalog.between(from, to);
        if (eclipses.isEmpty()) {
            return List.of();
        }
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();

        List<AlmanacEvent> events = new ArrayList<>();
        for (LunarEclipse eclipse : eclipses) {
            toEvent(eclipse, enabled).ifPresent(events::add);
        }
        return events;
    }

    /** A location and what it sees, so the roster can be ranked without recomputing. */
    private record Seen(LocationEntity location, LunarEclipseSight sight) { }

    /**
     * One feed entry, or empty when no configured location can actually see this eclipse.
     *
     * <p>Degrades the way the interface's contract requires: an eclipse nobody can see is absent
     * rather than present with hedged figures, and an eclipse with no roster to reduce against
     * keeps its dates and its catalogue-only facts ({@code next}, {@code since}) rather than
     * borrowing a location's.
     */
    private Optional<AlmanacEvent> toEvent(LunarEclipse eclipse, List<LocationEntity> enabled) {
        if (enabled.isEmpty()) {
            return Optional.of(new AlmanacEvent(
                    eclipse.date(), eclipse.date(), AlmanacKind.ALMANAC, TYPE,
                    title(eclipse), datesOnlyDetail(),
                    AlmanacEvent.metaOf("next", nextLine(eclipse), "since", sinceLine(eclipse)),
                    List.of()));
        }

        List<Seen> seen = new ArrayList<>();
        for (LocationEntity location : enabled) {
            LunarEclipseSight sight = calculator.sight(eclipse, location.getLat(), location.getLon());
            if (sight.visible()) {
                seen.add(new Seen(location, sight));
            }
        }
        if (seen.isEmpty()) {
            return Optional.empty();
        }

        Seen b = seen.stream()
                .max(Comparator.comparing(s ->
                        Duration.between(s.sight().visibleUmbraStart(), s.sight().visibleUmbraEnd())))
                .orElseThrow();
        int visibleCount = seen.size();
        LunarEclipseSight sight = b.sight();
        // TOTAL reads "total", never a percentage — a total eclipse's own magnitude runs past 1.0
        // (LunarEclipseWording's own javadoc), so an un-capped percentage here is exactly the
        // impossible "125% in shadow" Codex review of PR #913 found.
        String magnitudeMeta = LunarEclipseWording.depthOf(eclipse) == LunarEclipseWording.Depth.TOTAL
                ? "total"
                : LunarEclipseWording.coveragePct(eclipse) + "% in shadow";
        return Optional.of(new AlmanacEvent(
                eclipse.date(), eclipse.date(), AlmanacKind.ALMANAC, TYPE,
                title(eclipse), detail(eclipse, sight),
                AlmanacEvent.metaOf(
                        "magnitude", magnitudeMeta,
                        "maximum", londonTime(eclipse.max()) + " · moon " + sight.moonAzCardinal()
                                + " " + sight.moonAzAtMax() + "°, " + sight.moonAltAtMax() + "° up",
                        "shadow", "in shadow " + shadowLine(eclipse, sight),
                        "seen", "seen from " + visibleCount + " of " + enabled.size() + " sites",
                        "next", nextLine(eclipse),
                        "since", sinceLine(eclipse),
                        "location", b.location().getName()),
                List.of()));
    }

    private static String title(LunarEclipse eclipse) {
        return switch (LunarEclipseWording.depthOf(eclipse)) {
            case TOTAL -> "Total lunar eclipse";
            case DEEP -> "Deep partial lunar eclipse";
            case PARTIAL -> "Partial lunar eclipse";
            case SLIGHT -> "Slight partial lunar eclipse";
        };
    }

    private static String datesOnlyDetail() {
        // Unlike the solar eclipse, the fraction in shadow is a catalogue fact true everywhere the
        // Moon is visible at all (the class javadoc), so it is never what a location adds.
        return "A lunar eclipse falls on this date. How long you can watch it, and whether the moon"
                + " rises or sets mid-eclipse, depends on where you stand — add a location to see"
                + " the figures.";
    }

    /**
     * The "why" paragraph — the design's own §1 shape (with every figure substituted from this
     * eclipse's own catalogue and geometry rather than the worked example's), opening with
     * {@link LunarEclipseWording#shadowClause} rather than a hard-coded deep-partial sentence: the
     * design's "all but a sliver" copy is only true for a DEEP eclipse, and printing it for the
     * 2028-01-12 eclipse (magnitude 0.0679, ~7% of the Moon's diameter) was Codex review's second
     * finding against PR #913. {@code ComingUpAssembler.markFirstOfType} copies this into the
     * Coming-up card's {@code prose} for the first occurrence of {@code lunar-eclipse} in the
     * window — the same generic mechanism every other almanac type's prose already goes through,
     * so nothing lunar-specific is needed on the assembler side for this field.
     */
    private static String detail(LunarEclipse eclipse, LunarEclipseSight sight) {
        return LunarEclipseWording.shadowClause(eclipse) + " Maximum is at " + londonTime(eclipse.max())
                + " with the moon " + sight.moonAltAtMax() + "° above the " + sight.moonAzCardinal()
                + " horizon" + closingClause(sight) + " A low, clear horizon is worth more than a dark site.";
    }

    private static String closingClause(LunarEclipseSight sight) {
        if (sight.setsInShadow()) {
            return ", and it sets at " + localTime(sight.moonset()) + " still in shadow.";
        }
        if (sight.moonset() != null) {
            // Re-attach the zone before differencing — see LunarEclipseHotTopicStrategy#detail's
            // identical comment: the sight's fields are bare London local LocalDateTimes, and a
            // Duration taken directly would misreport across a BST transition night.
            Duration visible = Duration.between(
                    sight.visibleUmbraStart().atZone(LONDON), sight.visibleUmbraEnd().atZone(LONDON));
            return ", staying in shadow for " + visible.toHours() + "h " + visible.toMinutesPart()
                    + "m before it sets.";
        }
        return ".";
    }

    private static String shadowLine(LunarEclipse eclipse, LunarEclipseSight sight) {
        String end = sight.setsInShadow() ? "sets " + localTime(sight.moonset()) : londonTime(eclipse.u4());
        return londonTime(eclipse.u1()) + " → " + end;
    }

    /**
     * The recurrence sentence, or null for the catalogue's last entry — see
     * {@link LunarEclipseHotTopicStrategy#rarityNote} for why every other entry earns one.
     */
    private static String nextLine(LunarEclipse eclipse) {
        if (eclipse.nextComparable() == null) {
            return null;
        }
        String kindWord = "total".equals(eclipse.nextComparableKind()) ? "a total eclipse" : "a partial eclipse";
        return "Next from the UK: " + kindWord + ", " + NEXT_DATE_FORMAT.format(eclipse.nextComparable());
    }

    /**
     * "The first visible from here since <Month YYYY>" needs a prior date to name — the catalogued
     * eclipse immediately before this one in date order, or null for the catalogue's earliest entry
     * (which has nothing earlier to point to; the two 2025 entries exist only so a later eclipse can
     * derive this line — {@link LunarEclipseCatalog}'s class javadoc).
     */
    private static String sinceLine(LunarEclipse eclipse) {
        LocalDate previous = null;
        for (LunarEclipse candidate : LunarEclipseCatalog.all()) {
            if (candidate.date().isBefore(eclipse.date())) {
                previous = candidate.date();
            } else {
                break;
            }
        }
        return previous == null ? null : SINCE_MONTH_FORMAT.format(previous);
    }

    private static String londonTime(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
    }

    /** Formats a time already expressed in London local time — {@link LunarEclipseSight}'s clock
     * fields carry no offset to convert. */
    private static String localTime(LocalDateTime londonLocal) {
        return londonLocal.toLocalTime().format(HH_MM);
    }
}
