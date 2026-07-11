package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Detects supermoon hot topics deterministically from the lunar ephemeris.
 *
 * <p>A supermoon is a full moon that coincides with the Moon's closest approach to
 * Earth (perigee), appearing larger and brighter — moonrise over the coast or behind
 * a landmark is the classic shot. Fires when {@link LunarPhaseService#isFullMoon} is
 * true AND the Moon is within {@value #PERIGEE_WINDOW_DAYS} days of perigee. Sky-wide:
 * no per-location scoring, but the copy hints the eastern/coastal horizon where
 * moonrise is best framed. Makes no external API calls.
 */
@Component
public class SupermoonHotTopicStrategy implements HotTopicStrategy {

    private static final String SUPERMOON_DESCRIPTION =
            "A supermoon occurs when the full moon coincides with its closest approach"
                    + " to Earth, appearing larger and brighter. Moonrise over the coast"
                    + " or behind a landmark is a classic shot.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 5;

    /** Maximum days from perigee for a full moon to count as a supermoon. */
    private static final double PERIGEE_WINDOW_DAYS = 3.0;

    /** Mean Earth–Moon distance (km) — the baseline for the "larger than an average full moon". */
    private static final double MEAN_DISTANCE_KM = 384400.0;

    /** Fallback reference point (UK centre) when no coastal location is configured. */
    private static final double UK_CENTRE_LAT = 54.5;
    private static final double UK_CENTRE_LON = -2.5;

    /** Minutes after sunset within which moonrise reads as "just after sunset". */
    private static final long JUST_AFTER_SUNSET_MINUTES = 90;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SUPERMOON_NOTE = "catch it low behind a landmark";

    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;
    private final LunarCalculator lunarCalculator;
    private final MoonriseMoonsetCalculator moonriseMoonsetCalculator;
    private final SolarService solarService;

    /**
     * Constructs a {@code SupermoonHotTopicStrategy}.
     *
     * @param lunarPhaseService         deterministic lunar phase / perigee service
     * @param locationRepository        coastal-region lookups + the reference location for moonrise
     * @param lunarCalculator           lunar position (distance, azimuth, illumination) calculator
     * @param moonriseMoonsetCalculator moonrise/moonset time calculator
     * @param solarService              sunset time for the "just after sunset" relation
     */
    public SupermoonHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository, LunarCalculator lunarCalculator,
            MoonriseMoonsetCalculator moonriseMoonsetCalculator, SolarService solarService) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
        this.lunarCalculator = lunarCalculator;
        this.moonriseMoonsetCalculator = moonriseMoonsetCalculator;
        this.solarService = solarService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the window for the earliest full-moon-at-perigee day and emits a single
     * topic dated to it. Returns empty when no supermoon falls in the window.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (lunarPhaseService.isFullMoon(date)
                    && lunarPhaseService.daysFromNearestPerigee(date) <= PERIGEE_WINDOW_DAYS) {
                return List.of(buildTopic(date, fromDate));
            }
        }
        return List.of();
    }

    private HotTopic buildTopic(LocalDate date, LocalDate today) {
        List<LocationEntity> coastal = locationRepository.findCoastalLocations();
        List<String> coastalRegions = coastal.stream()
                .map(LocationEntity::getRegion)
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();

        double lat = coastal.isEmpty() ? UK_CENTRE_LAT : coastal.get(0).getLat();
        double lon = coastal.isEmpty() ? UK_CENTRE_LON : coastal.get(0).getLon();

        HotTopic topic = new HotTopic(
                "SUPERMOON",
                "Supermoon",
                "Full moon at perigee — moonrise over the coast " + formatDayLabel(date, today),
                date,
                PRIORITY,
                null,
                coastalRegions,
                SUPERMOON_DESCRIPTION,
                null);
        List<HotTopicFact> facts = buildFacts(date, lat, lon);
        return facts.isEmpty() ? topic : topic.withScience(facts, SUPERMOON_NOTE);
    }

    /**
     * Builds the supermoon fact chips from the lunar ephemeris at moonrise (or sunset when the Moon
     * does not rise): how much larger than an average full moon it appears — mean-distance based,
     * the honest "vs a normal full moon" figure — with its illumination, and the moonrise time,
     * compass bearing and relation to sunset.
     *
     * @param date the supermoon date
     * @param lat  reference latitude
     * @param lon  reference longitude
     * @return the supermoon fact chips (empty only in the degenerate no-moonrise, no-sunset case)
     */
    private List<HotTopicFact> buildFacts(LocalDate date, double lat, double lon) {
        List<HotTopicFact> facts = new ArrayList<>();
        MoonriseMoonset moonriseMoonset = moonriseMoonsetCalculator.calculate(date, lat, lon, LONDON);
        LocalDateTime sunsetLocal = solarService.sunsetUtc(lat, lon, date);
        ZonedDateTime sunset = sunsetLocal == null ? null : sunsetLocal.atZone(ZoneOffset.UTC);
        ZonedDateTime sampleAt = moonriseMoonset.moonrise().orElse(sunset);
        if (sampleAt == null) {
            return facts;
        }

        LunarPosition moon = lunarCalculator.calculate(sampleAt, lat, lon);
        long litPct = Math.round(moon.illuminationPercent());
        long pctLarger = Math.round((MEAN_DISTANCE_KM / moon.distanceKm() - 1) * 100);
        facts.add(HotTopicFact.metric("perigee", "+" + pctLarger + "% larger · " + litPct + "% lit"));

        moonriseMoonset.moonrise().ifPresent(rise -> {
            String cardinal = PromptUtils.toCardinal((int) Math.round(moon.azimuth()));
            String time = rise.withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
            facts.add(HotTopicFact.directional("rises",
                    time + ", " + sunsetRelation(sunset, rise), cardinal, false));
        });

        return facts;
    }

    private static String sunsetRelation(ZonedDateTime sunset, ZonedDateTime moonrise) {
        if (sunset == null) {
            return "after dark";
        }
        long minutes = Duration.between(sunset, moonrise).toMinutes();
        if (minutes < 0) {
            return "before sunset";
        }
        return minutes <= JUST_AFTER_SUNSET_MINUTES ? "just after sunset" : "after dark";
    }

    private String formatDayLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "today";
        }
        if (date.equals(today.plusDays(1))) {
            return "tomorrow";
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow.getDisplayName(TextStyle.FULL, Locale.UK);
    }
}
