package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects equinox-alignment hot topics deterministically from the solar ephemeris.
 *
 * <p>Around the spring and autumn equinoxes the sun rises almost exactly due east and
 * sets almost exactly due west, briefly aligning with east–west valleys, rivers and
 * roads. Fires within {@value #EQUINOX_WINDOW_DAYS} days of either equinox when at least
 * one enabled location's sunrise azimuth is within {@value #AZIMUTH_TOLERANCE_DEG}° of due
 * east (or sunset within tolerance of due west). It is a "plan for this" heads-up that
 * fires regardless of cloud. Makes no external API calls.
 */
@Component
public class EquinoxHotTopicStrategy implements HotTopicStrategy {

    private static final String EQUINOX_DESCRIPTION =
            "At the spring and autumn equinoxes the sun rises almost exactly due east"
                    + " and sets almost exactly due west — a rare solar alignment with"
                    + " valleys, rivers and roads.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 6;

    /** Days either side of an equinox within which the topic may fire. */
    private static final int EQUINOX_WINDOW_DAYS = 3;

    /** Azimuth tolerance (degrees) for "due east"/"due west" alignment. */
    private static final int AZIMUTH_TOLERANCE_DEG = 3;

    /** Compass azimuth of due east. */
    private static final int DUE_EAST_DEG = 90;

    /** Compass azimuth of due west. */
    private static final int DUE_WEST_DEG = 270;

    /** Calendar day of the (approximate) March equinox. */
    private static final int MARCH_EQUINOX_DAY = 20;

    /** Calendar day of the (approximate) September equinox. */
    private static final int SEPTEMBER_EQUINOX_DAY = 22;

    private final SolarService solarService;
    private final LocationRepository locationRepository;

    /**
     * Constructs an {@code EquinoxHotTopicStrategy}.
     *
     * @param solarService       solar event / azimuth service
     * @param locationRepository repository for enabled-location lookups
     */
    public EquinoxHotTopicStrategy(SolarService solarService,
            LocationRepository locationRepository) {
        this.solarService = solarService;
        this.locationRepository = locationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the window for the earliest near-equinox day whose sunrise/sunset azimuth
     * aligns with due east/west for at least one enabled location, and emits a single
     * topic dated to it. Returns empty otherwise.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();
        if (enabled.isEmpty()) {
            return List.of();
        }

        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (!isNearEquinox(date)) {
                continue;
            }
            List<String> alignedRegions = alignedRegions(enabled, date);
            if (!alignedRegions.isEmpty()) {
                return List.of(buildTopic(date, fromDate, alignedRegions));
            }
        }
        return List.of();
    }

    /**
     * Returns true if the date is within {@value #EQUINOX_WINDOW_DAYS} days of either equinox.
     *
     * @param date the calendar date
     * @return true if near an equinox
     */
    static boolean isNearEquinox(LocalDate date) {
        LocalDate march = LocalDate.of(date.getYear(), 3, MARCH_EQUINOX_DAY);
        LocalDate september = LocalDate.of(date.getYear(), 9, SEPTEMBER_EQUINOX_DAY);
        return Math.abs(ChronoUnit.DAYS.between(march, date)) <= EQUINOX_WINDOW_DAYS
                || Math.abs(ChronoUnit.DAYS.between(september, date)) <= EQUINOX_WINDOW_DAYS;
    }

    private List<String> alignedRegions(List<LocationEntity> enabled, LocalDate date) {
        Set<String> regions = new LinkedHashSet<>();
        for (LocationEntity loc : enabled) {
            if (isAligned(loc, date) && loc.getRegion() != null) {
                regions.add(loc.getRegion().getName());
            }
        }
        return new ArrayList<>(regions);
    }

    private boolean isAligned(LocationEntity loc, LocalDate date) {
        try {
            int sunrise = solarService.sunriseAzimuthDeg(loc.getLat(), loc.getLon(), date);
            int sunset = solarService.sunsetAzimuthDeg(loc.getLat(), loc.getLon(), date);
            return Math.abs(sunrise - DUE_EAST_DEG) <= AZIMUTH_TOLERANCE_DEG
                    || Math.abs(sunset - DUE_WEST_DEG) <= AZIMUTH_TOLERANCE_DEG;
        } catch (RuntimeException ex) {
            // Graceful — skip locations where the azimuth calculation fails (e.g. polar edge case)
            return false;
        }
    }

    private HotTopic buildTopic(LocalDate date, LocalDate today, List<String> regions) {
        return new HotTopic(
                "EQUINOX",
                "Equinox alignment",
                "Sun rises due east — aligned-horizon shots " + formatDayLabel(date, today),
                date,
                PRIORITY,
                null,
                regions,
                EQUINOX_DESCRIPTION,
                null);
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
