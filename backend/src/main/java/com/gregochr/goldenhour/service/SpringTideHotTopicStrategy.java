package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Detects Spring Tide hot topics from the cached briefing triage data.
 *
 * <p>A spring tide occurs around new and full moons when gravitational
 * alignment produces larger-than-normal tidal ranges. Emits a topic only
 * when the tide is classified as a spring tide but NOT a king tide — king
 * tides are handled separately by {@link KingTideHotTopicStrategy} to
 * avoid duplication.
 *
 * <p>Reads from the briefing cache ({@link BriefingService#getCachedDays()})
 * so that the pill is consistent with what the heatmap grid and Best Bet show.
 * Falls back to empty when no briefing has been generated yet.
 */
@Component
public class SpringTideHotTopicStrategy implements HotTopicStrategy {

    private static final String SPRING_TIDE_DESCRIPTION =
            "Spring tides happen around each new and full moon, producing the biggest"
                    + " tidal ranges. Higher water at coastal locations means more"
                    + " dramatic foreground and wave action.";

    private final BriefingService briefingService;
    private final LocationRepository locationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs a {@code SpringTideHotTopicStrategy}.
     *
     * @param briefingService              cached briefing data (injected lazily
     *                                     to break circular dependency)
     * @param locationRepository           repository for location lookups
     * @param forecastEvaluationRepository repository for tide alignment queries
     */
    public SpringTideHotTopicStrategy(@Lazy BriefingService briefingService,
            LocationRepository locationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository) {
        this.briefingService = briefingService;
        this.locationRepository = locationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the cached briefing days for any slot whose tide data indicates
     * a spring tide (but NOT a king tide). Emits at most one topic. Returns
     * empty when no briefing has been cached yet.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null) {
            return List.of();
        }

        List<BriefingDay> sorted = days.stream()
                .filter(d -> !d.date().isBefore(fromDate) && !d.date().isAfter(toDate))
                .sorted(Comparator.comparing(BriefingDay::date))
                .toList();

        for (BriefingDay day : sorted) {
            BriefingSlot.TideInfo springTide = findSpringTide(day);
            if (springTide != null) {
                LocalDate date = day.date();
                String dayLabel = formatDayLabel(date, fromDate);
                List<LocationEntity> coastalLocations =
                        locationRepository.findCoastalLocations();
                List<String> coastalRegions = extractRegionNames(coastalLocations);
                Map<TargetType, Long> alignmentCounts =
                        KingTideHotTopicStrategy.parseTideAlignmentCounts(
                                forecastEvaluationRepository, date);
                int sunriseCount = alignmentCounts
                        .getOrDefault(TargetType.SUNRISE, 0L).intValue();
                int sunsetCount = alignmentCounts
                        .getOrDefault(TargetType.SUNSET, 0L).intValue();
                ExpandedHotTopicDetail expandedDetail =
                        KingTideHotTopicStrategy.buildExpandedDetail(
                                coastalLocations, "Spring tide",
                                springTide.lunarPhase(), alignmentCounts);

                return List.of(new HotTopic(
                        "SPRING_TIDE",
                        "Spring tide",
                        buildSpringTideDetail(sunriseCount, sunsetCount,
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

    /**
     * Returns the first spring-but-not-king tide info found in the given day.
     *
     * @param day the briefing day to scan
     * @return first matching {@link BriefingSlot.TideInfo}, or null if none
     */
    static BriefingSlot.TideInfo findSpringTide(BriefingDay day) {
        for (BriefingEventSummary event : day.eventSummaries()) {
            for (BriefingRegion region : event.regions()) {
                for (BriefingSlot slot : region.slots()) {
                    if (isSpringNotKing(slot.tide())) {
                        return slot.tide();
                    }
                }
            }
            for (BriefingSlot slot : event.unregioned()) {
                if (isSpringNotKing(slot.tide())) {
                    return slot.tide();
                }
            }
        }
        return null;
    }

    private static boolean isSpringNotKing(BriefingSlot.TideInfo tide) {
        if (tide == null) {
            return false;
        }
        boolean isKing = tide.isKingTide()
                || tide.lunarTideType() == LunarTideType.KING_TIDE;
        if (isKing) {
            return false;
        }
        return tide.isSpringTide()
                || tide.lunarTideType() == LunarTideType.SPRING_TIDE;
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

    /**
     * Builds the detail line for a spring tide pill based on alignment counts.
     *
     * @param sunriseCount number of coastal locations aligned with sunrise
     * @param sunsetCount  number of coastal locations aligned with sunset
     * @param dayLabel     "today", "tomorrow", or day-of-week name
     * @return human-readable detail line
     */
    static String buildSpringTideDetail(int sunriseCount, int sunsetCount,
            String dayLabel) {
        if (sunriseCount > 0 && sunsetCount > 0) {
            return String.format("Spring tide \u2014 %s, %s %s",
                    KingTideHotTopicStrategy.formatCatch(sunriseCount, "sunrise"),
                    KingTideHotTopicStrategy.formatCatchShort(sunsetCount, "sunset"),
                    dayLabel);
        }
        if (sunriseCount > 0) {
            return String.format("Spring tide \u2014 %s aligned with sunrise %s",
                    KingTideHotTopicStrategy.formatLocationCount(sunriseCount),
                    dayLabel);
        }
        if (sunsetCount > 0) {
            return String.format("Spring tide \u2014 %s aligned with sunset %s",
                    KingTideHotTopicStrategy.formatLocationCount(sunsetCount),
                    dayLabel);
        }
        return String.format("Spring tide %s \u2014 no sunrise or sunset"
                + " alignment, but good coastal foreground", dayLabel);
    }
}
