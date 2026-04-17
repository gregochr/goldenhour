package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideMetrics;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs a {@code KingTideHotTopicStrategy}.
     *
     * @param lunarPhaseService            service for lunar tide classification
     * @param locationRepository           repository for location lookups
     * @param forecastEvaluationRepository repository for tide alignment queries
     */
    public KingTideHotTopicStrategy(LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
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
                List<LocationEntity> coastalLocations =
                        locationRepository.findCoastalLocations();
                List<String> coastalRegions = extractRegionNames(coastalLocations);
                Map<TargetType, Long> alignmentCounts =
                        parseTideAlignmentCounts(forecastEvaluationRepository, date);
                ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(
                        coastalLocations, "King tide",
                        lunarPhaseService.getMoonPhase(date), alignmentCounts);

                return List.of(new HotTopic(
                        "KING_TIDE",
                        "King tide",
                        String.format("Rare extreme tidal range — exceptional coastal"
                                + " foreground %s", dayLabel),
                        date,
                        1,
                        null,
                        coastalRegions,
                        KING_TIDE_DESCRIPTION,
                        expandedDetail));
            }
        }

        return List.of();
    }

    /**
     * Builds expanded detail with coastal locations grouped by region.
     *
     * @param coastalLocations     all enabled coastal locations
     * @param tidalClassification  "King tide" or "Spring tide"
     * @param lunarPhase           current moon phase name
     * @param alignmentCounts      tide alignment counts by target type
     * @return populated expanded detail
     */
    static ExpandedHotTopicDetail buildExpandedDetail(List<LocationEntity> coastalLocations,
            String tidalClassification, String lunarPhase,
            Map<TargetType, Long> alignmentCounts) {
        Map<String, List<LocationEntity>> byRegion = coastalLocations.stream()
                .filter(loc -> loc.getRegion() != null)
                .collect(Collectors.groupingBy(
                        loc -> loc.getRegion().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<RegionGroup> regionGroups = new ArrayList<>();
        for (Map.Entry<String, List<LocationEntity>> entry
                : byRegion.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
            List<LocationEntry> locations = entry.getValue().stream()
                    .sorted(Comparator.comparing(LocationEntity::getName))
                    .map(loc -> {
                        String tidePreference = loc.getTideType() != null
                                && !loc.getTideType().isEmpty()
                                ? loc.getTideType().iterator().next().name() : null;
                        return new LocationEntry(
                                loc.getName(), "Coastal", null, null,
                                new TideLocationMetrics(tidePreference));
                    })
                    .toList();
            regionGroups.add(new RegionGroup(
                    entry.getKey(), null, null, null, locations));
        }

        int sunriseCount = alignmentCounts
                .getOrDefault(TargetType.SUNRISE, 0L).intValue();
        int sunsetCount = alignmentCounts
                .getOrDefault(TargetType.SUNSET, 0L).intValue();

        return new ExpandedHotTopicDetail(
                regionGroups, null,
                new TideMetrics(tidalClassification, lunarPhase,
                        sunriseCount, sunsetCount));
    }

    /**
     * Parses the raw query result into a map of target type to alignment count.
     *
     * @param repository the forecast evaluation repository
     * @param date       the date to query
     * @return map of target type to count of aligned coastal locations
     */
    static Map<TargetType, Long> parseTideAlignmentCounts(
            ForecastEvaluationRepository repository, LocalDate date) {
        List<Object[]> rows = repository.countTideAlignedByTargetType(date);
        Map<TargetType, Long> counts = new java.util.EnumMap<>(TargetType.class);
        for (Object[] row : rows) {
            counts.put((TargetType) row[0], (Long) row[1]);
        }
        return counts;
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
