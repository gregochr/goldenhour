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
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.TideMetrics;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.context.annotation.Lazy;
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
 * Detects King Tide hot topics from the cached briefing triage data.
 *
 * <p>A king tide occurs when a new or full moon coincides with the moon's
 * closest approach to Earth (perigee), producing the strongest tidal forcing.
 * This happens only 5–10 times per year and warrants a high-priority alert
 * for coastal photography.
 *
 * <p>Reads from the briefing cache ({@link BriefingService#getCachedDays()})
 * so that the pill is consistent with what the heatmap grid and Best Bet show.
 * Falls back to empty when no briefing has been generated yet.
 */
@Component
public class KingTideHotTopicStrategy implements HotTopicStrategy {

    private static final String KING_TIDE_DESCRIPTION =
            "King tides occur when a new or full moon coincides with the moon's closest"
                    + " approach to Earth, producing exceptionally large tidal ranges."
                    + " Only happens 5\u201310 times per year — rare dramatic foreground at"
                    + " coastal locations.";

    private final BriefingService briefingService;
    private final LocationRepository locationRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs a {@code KingTideHotTopicStrategy}.
     *
     * @param briefingService              cached briefing data (injected lazily
     *                                     to break circular dependency)
     * @param locationRepository           repository for location lookups
     * @param forecastEvaluationRepository repository for tide alignment queries
     */
    public KingTideHotTopicStrategy(@Lazy BriefingService briefingService,
            LocationRepository locationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository) {
        this.briefingService = briefingService;
        this.locationRepository = locationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the cached briefing days for king tide candidates and picks
     * the best date: prefers dates with tide alignment; when none have
     * alignment, prefers a future date over today (more actionable).
     * Emits at most one topic. Returns empty when no briefing has been
     * cached yet.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null) {
            return List.of();
        }

        List<BriefingDay> candidates = days.stream()
                .filter(d -> !d.date().isBefore(fromDate)
                        && !d.date().isAfter(toDate))
                .sorted(Comparator.comparing(BriefingDay::date))
                .filter(d -> findKingTide(d) != null)
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        CandidateResult best = pickBestCandidate(candidates, fromDate);
        BriefingSlot.TideInfo kingTide = findKingTide(best.day());
        LocalDate date = best.day().date();
        String dayLabel = formatDayLabel(date, fromDate);
        List<LocationEntity> coastalLocations =
                locationRepository.findCoastalLocations();
        List<String> coastalRegions = extractRegionNames(coastalLocations);
        Map<TargetType, Long> alignmentCounts = best.alignmentCounts();
        int sunriseCount = alignmentCounts
                .getOrDefault(TargetType.SUNRISE, 0L).intValue();
        int sunsetCount = alignmentCounts
                .getOrDefault(TargetType.SUNSET, 0L).intValue();
        ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(
                coastalLocations, "King tide",
                kingTide.lunarPhase(), alignmentCounts);

        return List.of(new HotTopic(
                "KING_TIDE",
                "King tide",
                buildKingTideDetail(sunriseCount, sunsetCount, dayLabel),
                date,
                1,
                null,
                coastalRegions,
                KING_TIDE_DESCRIPTION,
                expandedDetail));
    }

    /**
     * Returns the first king tide info found in the given briefing day.
     *
     * @param day the briefing day to scan
     * @return first matching {@link BriefingSlot.TideInfo}, or null if none
     */
    static BriefingSlot.TideInfo findKingTide(BriefingDay day) {
        for (BriefingEventSummary event : day.eventSummaries()) {
            for (BriefingRegion region : event.regions()) {
                for (BriefingSlot slot : region.slots()) {
                    if (isKingTide(slot.tide())) {
                        return slot.tide();
                    }
                }
            }
            for (BriefingSlot slot : event.unregioned()) {
                if (isKingTide(slot.tide())) {
                    return slot.tide();
                }
            }
        }
        return null;
    }

    private static boolean isKingTide(BriefingSlot.TideInfo tide) {
        return tide != null
                && (tide.isKingTide()
                        || tide.lunarTideType() == LunarTideType.KING_TIDE);
    }

    /**
     * Result of evaluating a king tide candidate date.
     */
    private record CandidateResult(BriefingDay day,
            Map<TargetType, Long> alignmentCounts) {}

    /**
     * Picks the best king tide date from the candidates.
     *
     * <p>Prefers a date with tide alignment (sunrise or sunset). When
     * no candidate has alignment, prefers a future date over today
     * because it is more actionable (the user still has time to plan).
     *
     * @param candidates date-sorted list of days with king tides
     * @param today      the reference date (typically {@code fromDate})
     * @return the best candidate with its alignment counts
     */
    private CandidateResult pickBestCandidate(
            List<BriefingDay> candidates, LocalDate today) {
        CandidateResult bestAligned = null;
        CandidateResult bestUnaligned = null;

        for (BriefingDay candidate : candidates) {
            Map<TargetType, Long> counts = parseTideAlignmentCounts(
                    forecastEvaluationRepository, candidate.date());
            boolean hasAlignment = counts.values().stream()
                    .anyMatch(v -> v > 0);
            CandidateResult result =
                    new CandidateResult(candidate, counts);

            if (hasAlignment) {
                if (bestAligned == null) {
                    bestAligned = result;
                }
            } else {
                if (bestUnaligned == null
                        || (bestUnaligned.day.date().equals(today)
                                && !candidate.date().equals(today))) {
                    bestUnaligned = result;
                }
            }
        }

        return bestAligned != null ? bestAligned : bestUnaligned;
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

    /**
     * Builds the detail line for a king tide pill based on alignment counts.
     *
     * @param sunriseCount number of coastal locations aligned with sunrise
     * @param sunsetCount  number of coastal locations aligned with sunset
     * @param dayLabel     "today", "tomorrow", or day-of-week name
     * @return human-readable detail line
     */
    static String buildKingTideDetail(int sunriseCount, int sunsetCount,
            String dayLabel) {
        if (sunriseCount > 0 && sunsetCount > 0) {
            return String.format("Rare king tide \u2014 %s, %s %s",
                    formatCatch(sunriseCount, "sunrise"),
                    formatCatchShort(sunsetCount, "sunset"),
                    dayLabel);
        }
        if (sunriseCount > 0) {
            return String.format("Rare king tide \u2014 %s aligned with sunrise %s",
                    formatLocationCount(sunriseCount), dayLabel);
        }
        if (sunsetCount > 0) {
            return String.format("Rare king tide \u2014 %s aligned with sunset %s",
                    formatLocationCount(sunsetCount), dayLabel);
        }
        return String.format("Rare king tide %s \u2014 no sunrise or sunset"
                + " alignment, but exceptional coastal foreground", dayLabel);
    }

    static String formatCatch(int count, String event) {
        return count == 1
                ? String.format("1 location catches %s", event)
                : String.format("%d locations catch %s", count, event);
    }

    static String formatCatchShort(int count, String event) {
        return count == 1
                ? String.format("1 catches %s", event)
                : String.format("%d catch %s", count, event);
    }

    static String formatLocationCount(int count) {
        return count == 1 ? "1 location" : count + " locations";
    }
}
