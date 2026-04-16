package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
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
 * Detects King Tide hot topics using lunar phase calculations.
 *
 * <p>A king tide occurs when a new or full moon coincides with the moon's
 * closest approach to Earth (perigee), producing the strongest tidal forcing.
 * This happens only 5–10 times per year and warrants a high-priority alert
 * for coastal photography.
 *
 * <p>Makes no external API calls — uses only the deterministic
 * {@link LunarPhaseService} calculation and the location repository.
 */
@Component
public class KingTideHotTopicStrategy implements HotTopicStrategy {

    private static final String KING_TIDE_DESCRIPTION =
            "King tides occur when a new or full moon coincides with the moon's closest"
                    + " approach to Earth, producing exceptionally large tidal ranges."
                    + " Only happens 5\u201310 times per year — rare dramatic foreground at"
                    + " coastal locations.";

    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code KingTideHotTopicStrategy}.
     *
     * @param lunarPhaseService  service for lunar tide classification
     * @param locationRepository repository for location lookups
     */
    public KingTideHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans each day in the window for a king tide. Emits at most one topic
     * (king tides last 1–2 days, so one pill is sufficient).
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            LunarTideType tideType = lunarPhaseService.classifyTide(date);
            if (tideType == LunarTideType.KING_TIDE) {
                String dayLabel = formatDayLabel(date, fromDate);
                List<String> coastalRegions = findCoastalRegions();

                return List.of(new HotTopic(
                        "KING_TIDE",
                        "King tide",
                        String.format("Rare extreme tidal range — exceptional coastal"
                                + " foreground %s", dayLabel),
                        date,
                        1,
                        null,
                        coastalRegions,
                        KING_TIDE_DESCRIPTION));
            }
        }

        return List.of();
    }

    private List<String> findCoastalRegions() {
        return locationRepository.findCoastalLocations().stream()
                .map(LocationEntity::getRegion)
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();
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
