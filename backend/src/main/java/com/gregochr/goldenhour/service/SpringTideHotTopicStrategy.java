package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
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
 * Detects Spring Tide hot topics using lunar phase calculations.
 *
 * <p>A spring tide occurs around new and full moons when gravitational
 * alignment produces larger-than-normal tidal ranges. Emits a topic only
 * when the tide is classified as {@link LunarTideType#SPRING_TIDE} — king
 * tides (spring + perigee) are handled separately by
 * {@link KingTideHotTopicStrategy} to avoid duplication.
 *
 * <p>Makes no external API calls — uses only the deterministic
 * {@link LunarPhaseService} calculation and the location repository.
 */
@Component
public class SpringTideHotTopicStrategy implements HotTopicStrategy {

    private static final String SPRING_TIDE_DESCRIPTION =
            "Spring tides happen around each new and full moon, producing the biggest"
                    + " tidal ranges. Higher water at coastal locations means more"
                    + " dramatic foreground and wave action.";

    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code SpringTideHotTopicStrategy}.
     *
     * @param lunarPhaseService  service for lunar tide classification
     * @param locationRepository repository for location lookups
     */
    public SpringTideHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans each day in the window for a spring tide that is NOT a king tide.
     * Emits at most one topic (one pill is sufficient for a spring tide window).
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            LunarTideType tideType = lunarPhaseService.classifyTide(date);
            if (tideType == LunarTideType.SPRING_TIDE) {
                String dayLabel = formatDayLabel(date, fromDate);
                List<LocationEntity> coastalLocations =
                        locationRepository.findCoastalLocations();
                List<String> coastalRegions = extractRegionNames(coastalLocations);
                ExpandedHotTopicDetail expandedDetail =
                        KingTideHotTopicStrategy.buildExpandedDetail(
                                coastalLocations, "Spring tide",
                                lunarPhaseService.getMoonPhase(date));

                return List.of(new HotTopic(
                        "SPRING_TIDE",
                        "Spring tide",
                        String.format("Large tidal range at coastal locations %s",
                                dayLabel),
                        date,
                        2,
                        null,
                        coastalRegions,
                        SPRING_TIDE_DESCRIPTION,
                        expandedDetail));
            }
        }

        return List.of();
    }

    private List<String> extractRegionNames(List<LocationEntity> locations) {
        return locations.stream()
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
