package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
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

    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code SupermoonHotTopicStrategy}.
     *
     * @param lunarPhaseService  deterministic lunar phase / perigee service
     * @param locationRepository repository for coastal-region lookups
     */
    public SupermoonHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
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
        List<String> coastalRegions = locationRepository.findCoastalLocations().stream()
                .map(LocationEntity::getRegion)
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();

        return new HotTopic(
                "SUPERMOON",
                "Supermoon",
                "Full moon at perigee — moonrise over the coast " + formatDayLabel(date, today),
                date,
                PRIORITY,
                null,
                coastalRegions,
                SUPERMOON_DESCRIPTION,
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
