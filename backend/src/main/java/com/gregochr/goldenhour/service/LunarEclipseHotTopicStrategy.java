package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
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
import java.util.Objects;
import java.util.Optional;

/**
 * Detects lunar-eclipse hot topics from the seeded ephemeris in {@link LunarEclipseCatalog}.
 *
 * <p>Mirrors {@link EclipseHotTopicStrategy} structurally, but a lunar eclipse's depth is the same
 * for every observer who can see the Moon at all (the whole Earth stands between the Moon and the
 * Sun, not a narrow shadow cone on the ground — see {@link LunarEclipseSight}), so what varies by
 * location is only geometry: whether the Moon clears the eligibility altitude, for how long, and
 * whether it rises or sets while still eclipsed. {@link LunarEclipseCalculator} supplies that
 * geometry; this class turns it into the pill a photographer plans an evening (or, more often
 * here, a pre-dawn start) around.
 *
 * <h2>Why the chip label never names the kind</h2>
 *
 * <p>Unlike the solar strategy, whose pill label switches between "Total", "Deep partial" and
 * "Partial" (the depth genuinely differs by location, so the label is a claim about the roster's
 * best view), a lunar eclipse's magnitude is a single catalogue fact true everywhere it is visible
 * at all. The Plan-tab label is therefore the constant {@code "Lunar eclipse"} on every surface
 * (plan {@code lunar-eclipse-plan.md} §4 #2); the kind still reaches the reader, in the Coming-up
 * feed's title ({@link LunarEclipseAlmanacSource}) — this pill's own {@link #detail} states
 * percentage in shadow and geometry, never the kind.
 *
 * <h2>Why the topic disappears the moment it is over</h2>
 *
 * <p>The same rule {@link EclipseHotTopicStrategy} and the tide strategies use: once the umbral
 * phase has ended ({@code u4}) there is nothing left to get up for, so the topic withdraws via
 * {@link SolarEventFreshness} rather than pointing at a night that has finished.
 */
@Component
public class LunarEclipseHotTopicStrategy implements HotTopicStrategy {

    /**
     * Topic priority — identical to {@link EclipseHotTopicStrategy}'s. 4 sits between the
     * condition-driven band (1–3) and the calendar band (5–7): a lunar eclipse is calendar-fixed
     * like a supermoon, but for one night it is as act-on-it as an inversion.
     */
    private static final int PRIORITY = 4;

    /** The Plan-tab label, constant regardless of kind or depth — see the class javadoc. */
    private static final String LABEL = "Lunar eclipse";

    /**
     * The exposure cue. Rides {@link HotTopic#note()}, never {@link HotTopic#safetyNote()}: unlike
     * the solar eclipse, this is not a safety instruction — nothing about photographing an
     * eclipsed Moon is hazardous — so it must not carry the un-gated, non-dismissible treatment the
     * warning field exists for (plan §4 #5).
     */
    private static final String EXPOSURE_NOTE =
            "No filter needed — bracket, the shadow is ~10 stops under the lit edge";

    /**
     * The popup's (i) tip body — the design's own "WHY IT TURNS COPPER, NOT BLACK" paragraph,
     * verbatim, with the magnitude figure substituted per eclipse.
     */
    private static final String COPPER_EXPLANATION =
            "Earth's atmosphere bends a little sunlight into its own shadow and filters out the blue"
                    + " on the way, so the shadowed moon glows the colour of every sunrise and sunset"
                    + " on Earth at once. %d%% is a fraction of the moon's diameter in shadow, not its"
                    + " area. The lit sliver on the lower-left edge will still be much the brightest"
                    + " thing in the frame.";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter NEXT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);

    private static final String SUNRISE_EVENT = "SUNRISE";
    private static final String SUNSET_EVENT = "SUNSET";

    private final LocationRepository locationRepository;
    private final SolarService solarService;
    private final SolarEventFreshness freshness;
    private final LunarEclipseCalculator calculator;

    /**
     * Constructs a {@code LunarEclipseHotTopicStrategy}.
     *
     * @param locationRepository the enabled-location roster the eclipse is reduced against
     * @param solarService       kept for structural parity with {@link EclipseHotTopicStrategy};
     *                           not yet consulted by this class's own logic — the topic's clock
     *                           anchor is the eclipse's own maximum, never a sunrise or sunset
     * @param freshness          the shared clock test that withdraws the topic once it has finished
     * @param calculator         per-location geometry (altitude, bearing, moonrise/moonset,
     *                           eligibility) for one catalogued eclipse
     */
    public LunarEclipseHotTopicStrategy(LocationRepository locationRepository, SolarService solarService,
            SolarEventFreshness freshness, LunarEclipseCalculator calculator) {
        this.locationRepository = locationRepository;
        this.solarService = solarService;
        this.freshness = freshness;
        this.calculator = calculator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per catalogued lunar eclipse falling in the window that at least one
     * enabled location can actually see (the eligibility rule in {@link LunarEclipseCalculator}) and
     * that has not already finished.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<LunarEclipse> eclipses = LunarEclipseCatalog.between(fromDate, toDate);
        if (eclipses.isEmpty()) {
            return List.of();
        }
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        if (enabled.isEmpty()) {
            return List.of();
        }

        List<HotTopic> topics = new ArrayList<>();
        for (LunarEclipse eclipse : eclipses) {
            buildTopic(eclipse, enabled).ifPresent(topics::add);
        }
        return topics;
    }

    /**
     * A location and what it sees, so the roster can be ranked without recomputing.
     *
     * @param location the enabled location
     * @param sight    what it sees of the eclipse
     */
    private record Seen(LocationEntity location, LunarEclipseSight sight) { }

    private Optional<HotTopic> buildTopic(LunarEclipse eclipse, List<LocationEntity> enabled) {
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

        // Nothing left to get up for. u4 is the same instant for every observer, so unlike the
        // solar strategy this does not depend on which location is representative.
        if (!freshness.isAhead(eclipse.u4())) {
            return Optional.empty();
        }

        // The deepest VIEW on the roster speaks for the topic — longest visible umbral span, not
        // magnitude (which is identical everywhere). Ties break on the name the repository already
        // ordered by: Stream.max keeps the first-encountered element unless a later one is
        // strictly greater, so an alphabetically earlier tie wins without a second comparator.
        Seen best = seen.stream()
                .max(Comparator.comparing(s ->
                        Duration.between(s.sight().visibleUmbraStart(), s.sight().visibleUmbraEnd())))
                .orElseThrow();

        List<String> regions = seen.stream()
                .map(s -> s.location().getRegion())
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();
        List<String> visibleNames = seen.stream().map(s -> s.location().getName()).toList();

        HotTopic topic = new HotTopic(
                "LUNAR_ECLIPSE",
                LABEL,
                detail(eclipse, best.sight()),
                eclipse.date(),
                PRIORITY,
                null,
                regions,
                description(eclipse),
                null);

        return Optional.of(topic
                .withEvent(eventType(eclipse), londonTime(eclipse.max()))
                .withScience(facts(eclipse, best.sight(), seen.size(), enabled.size()), EXPOSURE_NOTE)
                .withRarity(rarityNote(eclipse))
                .withLocations(visibleNames));
    }

    /**
     * The photographic window this eclipse belongs to, from the London hour of its own maximum —
     * exactly {@link EclipseHotTopicStrategy#eventType}'s rule, so a rare high-altitude eclipse at
     * local noon (never seeded — see {@link LunarEclipseCatalog}) would still bucket sensibly.
     */
    private String eventType(LunarEclipse eclipse) {
        int hour = eclipse.max().atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).getHour();
        return hour < 12 ? SUNRISE_EVENT : SUNSET_EVENT;
    }

    /**
     * The pill's one-line detail: percentage in shadow, the clock time of maximum, the Moon's
     * altitude and bearing, and — where it says something the fact chips do not — whether and when
     * the Moon sets while still eclipsed.
     */
    private String detail(LunarEclipse eclipse, LunarEclipseSight sight) {
        String base = percentInShadow(eclipse) + "% in shadow at " + londonTime(eclipse.max())
                + ", moon " + sight.moonAltAtMax() + "° above " + sight.moonAzCardinal();
        if (sight.setsInShadow()) {
            return base + ", sets " + localTime(sight.moonset()) + " still in shadow";
        }
        if (sight.moonset() != null) {
            // Re-attach the zone before differencing: the sight's fields are bare London local
            // LocalDateTimes (no offset), and Duration.between on two of those would misreport by
            // an hour across a BST transition night. Zoning both first makes the difference a real
            // wall-clock elapsed duration regardless (no seeded entry spans a transition today, but
            // this is the correct computation either way).
            Duration visible = Duration.between(
                    sight.visibleUmbraStart().atZone(LONDON), sight.visibleUmbraEnd().atZone(LONDON));
            return base + ", " + visible.toHours() + "h " + visible.toMinutesPart()
                    + "m in shadow before it sets";
        }
        return base;
    }

    /**
     * The (i) tooltip body — the design's own science paragraph, with the magnitude substituted so
     * a partial and a total eclipse each state their own figure.
     */
    private String description(LunarEclipse eclipse) {
        return String.format(Locale.UK, COPPER_EXPLANATION, percentInShadow(eclipse));
    }

    private List<HotTopicFact> facts(LunarEclipse eclipse, LunarEclipseSight sight, int visibleCount,
            int rosterSize) {
        List<HotTopicFact> facts = new ArrayList<>();

        facts.add(HotTopicFact.directional("max",
                percentInShadow(eclipse) + "% in shadow · moon " + sight.moonAltAtMax() + "° up",
                sight.moonAzCardinal(),
                true));

        String shadowEnd = sight.setsInShadow()
                ? "sets " + localTime(sight.moonset())
                : londonTime(eclipse.u4());
        facts.add(HotTopicFact.metric("in shadow", londonTime(eclipse.u1()) + " → " + shadowEnd));

        facts.add(HotTopicFact.metric("seen from", visibleCount + " of " + rosterSize + " sites"));
        return facts;
    }

    /**
     * The quiet recurrence line, or null for the catalogue's last entry (which has nothing to
     * point forward to — see {@link LunarEclipseCatalog}'s class javadoc on
     * {@code nextComparable}).
     *
     * <p>Unlike the solar strategy's return-period gate, every non-final catalogued entry earns
     * this line: "comparable" already collapsed to "the next catalogued UK-visible eclipse" when
     * the catalogue was built, so there is no separate rarity threshold to re-apply here.
     */
    private String rarityNote(LunarEclipse eclipse) {
        if (eclipse.nextComparable() == null) {
            return null;
        }
        String kindWord = "total".equals(eclipse.nextComparableKind()) ? "a total eclipse" : "a partial eclipse";
        return "next from the UK: " + kindWord + ", " + NEXT_DATE_FORMAT.format(eclipse.nextComparable());
    }

    /** The umbral magnitude as a whole-number percentage, rounded to the nearest point. */
    private static int percentInShadow(LunarEclipse eclipse) {
        return (int) Math.round(eclipse.umbralMagnitude() * 100);
    }

    private String londonTime(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
    }

    /** Formats a time already expressed in London local time — {@link LunarEclipseSight}'s clock
     * fields carry no offset to convert. */
    private String localTime(LocalDateTime londonLocal) {
        return londonLocal.toLocalTime().format(HH_MM);
    }
}
