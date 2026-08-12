package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.EclipseCircumstances;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import com.gregochr.goldenhour.util.EclipseCalculator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects solar-eclipse hot topics from the seeded ephemeris in {@link EclipseCatalog}.
 *
 * <p>An eclipse is the purest almanac event this app carries: fixed by orbital mechanics, published
 * to the second centuries ahead, and unaffected by any forecast. The only uncertainty is cloud,
 * which the app already forecasts separately — so nothing here consults the weather, and the topic
 * fires on geometry alone.
 *
 * <h2>Every figure is computed for this roster, not quoted from a source</h2>
 *
 * <p>Published eclipse circumstances are quoted for cities — almost always London for a UK audience
 * — and they do not transfer. The 2026 eclipse peaks over London at 19:13 with 92% of the Sun's
 * diameter covered; on the Northumberland coast it is 19:06 and 91%, and in the Isles of Scilly
 * 19:16 and 96%. This strategy therefore reduces the Besselian elements at each enabled location
 * and reports what that roster actually sees. The alternative — seeding one city's numbers and
 * labelling them "by region" — would print a time nobody's shutter should follow.
 *
 * <h2>Why the topic disappears the moment it is over</h2>
 *
 * <p>Unlike a tide run or a meteor shower, an eclipse is a single event 108 minutes long. Once last
 * contact has passed there is nothing left to drive to, so the topic is withdrawn rather than left
 * on the pane pointing at an evening that has finished — the same rule, and the same
 * {@link SolarEventFreshness}, that the inversion and tide strategies use.
 */
@Component
public class EclipseHotTopicStrategy implements HotTopicStrategy {

    /**
     * Topic priority. The almanac band is 5–7 ("calendar heads-up, sorts below the act-on-it
     * topics") and the condition-driven band is 1–3. An eclipse is neither: it is calendar-fixed
     * like a supermoon, but for one evening it is as act-on-it as an inversion. 4 is the gap
     * between the two bands and says exactly that.
     */
    private static final int PRIORITY = 4;

    /** Magnitude at or above which the label reads "deep partial" rather than plain "partial". */
    private static final double DEEP_PARTIAL_MAGNITUDE = 0.80;

    /** Magnitude below which the eclipse is a sliver not worth a photographer's evening. */
    private static final double SLIGHT_MAGNITUDE = 0.40;

    /**
     * The safety warning. Non-negotiable and non-dismissible: this is the one topic in the catalogue
     * whose subject is the Sun itself, and the app is actively suggesting people point cameras at
     * it. The second clause is the load-bearing one — eclipse glasses protect the eye at the
     * viewfinder while an unfiltered lens concentrates sunlight onto the sensor, and on a mirrorless
     * body the eye is behind that sensor.
     */
    private static final String SAFETY_NOTE =
            "Certified solar filter on the lens — not only over your eye";

    /** The "where to look" cue. The design's own sentence, and true for every UK location here. */
    private static final String ECLIPSE_NOTE =
            "a clear low western horizon matters more than the last 2%";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** The photographic window an evening eclipse belongs to — it is an evening shoot. */
    private static final String SUNSET_EVENT = "SUNSET";

    /** The morning equivalent, for an eclipse that peaks before local noon. */
    private static final String SUNRISE_EVENT = "SUNRISE";

    private final LocationRepository locationRepository;
    private final SolarService solarService;
    private final SolarEventFreshness freshness;

    /**
     * Constructs an {@code EclipseHotTopicStrategy}.
     *
     * @param locationRepository the enabled-location roster the eclipse is reduced against
     * @param solarService       sunset time, for the "how long after the eclipse" relation
     * @param freshness          the shared clock test that withdraws the topic once it has finished
     */
    public EclipseHotTopicStrategy(LocationRepository locationRepository, SolarService solarService,
            SolarEventFreshness freshness) {
        this.locationRepository = locationRepository;
        this.solarService = solarService;
        this.freshness = freshness;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per catalogued eclipse falling in the window that is visible from at least
     * one enabled location and has not already finished.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<EclipseCatalog.Eclipse> eclipses = EclipseCatalog.between(fromDate, toDate);
        if (eclipses.isEmpty()) {
            return List.of();
        }
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        if (enabled.isEmpty()) {
            return List.of();
        }

        List<HotTopic> topics = new ArrayList<>();
        for (EclipseCatalog.Eclipse eclipse : eclipses) {
            buildTopic(eclipse, enabled).ifPresent(topics::add);
        }
        return topics;
    }

    /**
     * A location and what it sees, so the roster can be ranked without recomputing.
     *
     * @param location      the enabled location
     * @param circumstances what the eclipse looks like from it
     */
    private record Seen(LocationEntity location, EclipseCircumstances circumstances) { }

    private Optional<HotTopic> buildTopic(EclipseCatalog.Eclipse eclipse, List<LocationEntity> enabled) {
        List<Seen> seen = new ArrayList<>();
        for (LocationEntity location : enabled) {
            EclipseCalculator.circumstances(eclipse.elements(), location.getLat(), location.getLon())
                    .ifPresent(c -> seen.add(new Seen(location, c)));
        }
        if (seen.isEmpty()) {
            return Optional.empty();
        }

        // The deepest view on the roster speaks for the topic. Ties break on the name the repository
        // already ordered by, so the choice is stable between two serves of the same evening.
        Seen best = seen.stream()
                .max(Comparator.comparingDouble(s -> s.circumstances().magnitude()))
                .orElseThrow();
        EclipseCircumstances circumstances = best.circumstances();

        // Nothing left to drive to. Withdrawing the whole topic rather than dropping its event
        // anchor is deliberate: a null eventType would leave the pill on the pane with no time on
        // it, asserting that an eclipse is worth planning for after it has ended.
        if (!freshness.isAhead(circumstances.lastContactUtc())) {
            return Optional.empty();
        }

        List<String> regions = seen.stream()
                .map(s -> s.location().getRegion())
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();

        HotTopic topic = new HotTopic(
                "ECLIPSE",
                label(circumstances),
                detail(circumstances),
                eclipse.date(),
                PRIORITY,
                null,
                regions,
                description(best, seen),
                null);

        return Optional.of(topic
                // Set here rather than left to HotTopicEventEnricher, which would resolve SUNSET to
                // the actual sunset — 20:47, an hour and three quarters after the eclipse peaks.
                // The enricher passes through any topic that already carries an event.
                .withEvent(eventType(circumstances), londonTime(circumstances.maximumUtc()))
                .withScience(facts(best), ECLIPSE_NOTE)
                .withSafety(SAFETY_NOTE));
    }

    /**
     * The photographic window this eclipse belongs to.
     *
     * <p>Keyed on the maximum's own clock time rather than hard-coded to SUNSET: the 2026 eclipse
     * peaks in the evening, but the next entry in the catalogue may not, and an eclipse badged onto
     * the sunset window when it happens at breakfast would put it on the wrong card entirely.
     */
    private String eventType(EclipseCircumstances circumstances) {
        int hour = circumstances.maximumUtc().atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).getHour();
        return hour < 12 ? SUNRISE_EVENT : SUNSET_EVENT;
    }

    private String label(EclipseCircumstances circumstances) {
        if (circumstances.isTotal()) {
            return "Total solar eclipse";
        }
        if (circumstances.magnitude() >= DEEP_PARTIAL_MAGNITUDE) {
            return "Deep partial eclipse";
        }
        return circumstances.magnitude() >= SLIGHT_MAGNITUDE ? "Partial eclipse" : "Slight partial eclipse";
    }

    /**
     * The pill's one-line detail.
     *
     * <p>Obscuration, not magnitude — "covered" reads as area to anyone who is not an eclipse
     * chaser, and area is what obscuration measures. The low Sun is the second clause because it is
     * the photographic story: 90% of the Sun disappearing at noon is a curiosity, and the same
     * thing 13 degrees above the horizon is a picture.
     */
    private String detail(EclipseCircumstances circumstances) {
        return circumstances.obscurationPct() + "% of the sun covered, and it sits only "
                + circumstances.sunAltitudeRounded() + "° up";
    }

    /**
     * The (i) tooltip body.
     *
     * <p>Names the location the clock times belong to, which is the caveat the fact chips have no
     * room for: maximum sweeps across the UK by a quarter of an hour, so one location's contacts
     * cannot silently speak for a roster that may span it.
     */
    private String description(Seen best, List<Seen> seen) {
        int uncoveredPct = 100 - best.circumstances().obscurationPct();
        return "Brightness falls with the uncovered area, not with how dark it feels. At maximum a"
                + " sliver of photosphere is still throwing roughly " + uncoveredPct + "% of full"
                + " sunlight — thousands of times brighter than a full moon, and a light meter still"
                + " reads daylight. What changes is the quality: colour goes metallic, shadows sharpen"
                + " to knife edges, and pinholes between leaves cast crescents on the ground. The drop"
                + " only becomes obvious in the last few minutes either side of maximum."
                + " Times and figures are for " + best.location().getName() + ", the deepest view of "
                + seen.size() + " locations; maximum sweeps west across the UK by a few minutes.";
    }

    private List<HotTopicFact> facts(Seen best) {
        EclipseCircumstances c = best.circumstances();
        List<HotTopicFact> facts = new ArrayList<>();

        facts.add(HotTopicFact.directional("max",
                c.obscurationPct() + "% covered · sun only " + c.sunAltitudeRounded() + "° up",
                PromptUtils.toCardinal((int) Math.round(c.sunAzimuthDeg())),
                true));

        facts.add(HotTopicFact.metric("contacts",
                londonTime(c.firstContactUtc()) + " → " + londonTime(c.maximumUtc())
                        + " → " + londonTime(c.lastContactUtc())));

        sunsetRelation(best).ifPresent(facts::add);
        return facts;
    }

    /**
     * The "and then the sun sets" chip — marked optional, so it is the one dropped on a phone.
     *
     * <p>Two wordings rather than one, because the relation is not guaranteed. A deep partial low in
     * the west can still be in progress when the Sun sets, and printing "N minutes after last
     * contact" from a negative number would state the opposite of what happened.
     */
    private Optional<HotTopicFact> sunsetRelation(Seen best) {
        LocalDateTime sunset = solarService.sunsetUtc(
                best.location().getLat(), best.location().getLon(), best.circumstances().maximumUtc().toLocalDate());
        if (sunset == null) {
            return Optional.empty();
        }
        // Both instants are truncated to the minute BEFORE the gap is taken, because both are
        // printed to the minute. Last contact at 19:59:53 renders as "20:00"; diffing the raw
        // instants against a 20:47 sunset gives 46 minutes, and the pill would then state a gap its
        // own two clock times contradict. Round the display and derive the arithmetic from it, not
        // the other way round.
        long minutes = Duration.between(
                best.circumstances().lastContactUtc().truncatedTo(ChronoUnit.MINUTES),
                sunset.truncatedTo(ChronoUnit.MINUTES)).toMinutes();
        String relation = minutes >= 0
                ? "sets " + londonTime(sunset) + ", " + minutes + " min after last contact"
                : "sets " + londonTime(sunset) + ", while the eclipse is still running";
        return Optional.of(HotTopicFact.metric("sun", relation).asOptional());
    }

    private String londonTime(LocalDateTime utc) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
    }
}
