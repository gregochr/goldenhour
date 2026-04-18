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
     * <p>Scans the cached briefing days for king tide candidates. When
     * king tides span multiple consecutive dates the pill communicates
     * the full window (e.g. "today and tomorrow") and highlights which
     * specific event has the best tide alignment. The pill date is set
     * to the first day of the window. Emits at most one topic. Returns
     * empty when no briefing has been cached yet.
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

        List<LocalDate> kingTideDates = candidates.stream()
                .map(BriefingDay::date).toList();
        LocalDate pillDate = kingTideDates.get(0);
        BriefingSlot.TideInfo kingTide =
                findKingTide(candidates.get(0));

        Map<LocalDate, Map<TargetType, Long>> allAlignments =
                new LinkedHashMap<>();
        for (BriefingDay candidate : candidates) {
            allAlignments.put(candidate.date(),
                    parseTideAlignmentCounts(
                            forecastEvaluationRepository,
                            candidate.date()));
        }

        BestAlignment bestAlignment =
                findBestAlignment(allAlignments);
        Map<TargetType, Long> bestCounts = bestAlignment != null
                ? allAlignments.get(bestAlignment.date())
                : Map.of();

        List<LocationEntity> coastalLocations =
                locationRepository.findCoastalLocations();
        List<String> coastalRegions =
                extractRegionNames(coastalLocations);
        String dateRange =
                formatDateRange(kingTideDates, fromDate);
        String alignmentInfo = bestAlignment != null
                ? buildAlignmentInfo(
                        bestAlignment, kingTideDates.size() > 1,
                        fromDate)
                : "no tide alignments \u2014 but exceptional coastal foreground";
        ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(
                coastalLocations, "King tide",
                kingTide.lunarPhase(), bestCounts);

        return List.of(new HotTopic(
                "KING_TIDE",
                "King tide",
                buildKingTideDetail(dateRange, alignmentInfo,
                        coastalLocations.size()),
                pillDate,
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
     * Best alignment result across all king tide dates in the window.
     *
     * @param date  the date of the best alignment
     * @param event the event type with the most aligned locations
     * @param count the number of aligned coastal locations
     */
    record BestAlignment(LocalDate date, TargetType event,
            long count) {}

    /**
     * Finds the single best tide alignment across all candidate dates.
     *
     * <p>Iterates every (date, event) pair and returns the one with the
     * highest aligned-location count. Ties are broken by date order
     * (earliest first), then enum declaration order (sunrise before
     * sunset).
     *
     * @param allAlignments alignment counts keyed by date
     * @return the best alignment, or {@code null} when no date has any
     */
    static BestAlignment findBestAlignment(
            Map<LocalDate, Map<TargetType, Long>> allAlignments) {
        BestAlignment best = null;
        for (var entry : allAlignments.entrySet()) {
            for (var countEntry : entry.getValue().entrySet()) {
                long count = countEntry.getValue();
                if (count > 0
                        && (best == null
                                || count > best.count())) {
                    best = new BestAlignment(entry.getKey(),
                            countEntry.getKey(), count);
                }
            }
        }
        return best;
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

    private static String formatDayLabel(LocalDate date,
            LocalDate today) {
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
     * Formats a date range label from a list of king tide dates.
     *
     * <p>Examples: "today", "today and tomorrow",
     * "today through Saturday".
     *
     * @param dates sorted list of king tide dates
     * @param today the reference date for relative labels
     * @return human-readable date range
     */
    static String formatDateRange(List<LocalDate> dates,
            LocalDate today) {
        if (dates.size() == 1) {
            return formatDayLabel(dates.get(0), today);
        }
        String first = formatDayLabel(dates.get(0), today);
        String last = formatDayLabel(
                dates.get(dates.size() - 1), today);
        if (dates.size() == 2) {
            return first + " and " + last;
        }
        return first + " through " + last;
    }

    /**
     * Builds the alignment info segment for the detail line.
     *
     * @param best     best alignment result, or {@code null} if none
     * @param multiDay whether the window spans multiple days
     * @param today    the reference date for relative day labels
     * @return alignment info string, or {@code null} when no alignment
     */
    static String buildAlignmentInfo(BestAlignment best,
            boolean multiDay, LocalDate today) {
        if (best == null) {
            return null;
        }
        String event = best.event() == TargetType.SUNRISE
                ? "sunrise" : "sunset";
        String countPrefix = best.count() == 1
                ? "1 tide aligned with "
                : best.count() + " tides aligned with ";
        if (multiDay) {
            return countPrefix
                    + formatDayLabel(best.date(), today)
                    + " " + event;
        }
        return countPrefix + event;
    }

    /**
     * Builds the detail line for a king tide pill.
     *
     * <p>Format: {@code King tide [dateRange] · [alignmentInfo]
     * · N coastal locations}
     *
     * @param dateRange     date range label
     * @param alignmentInfo alignment info or {@code null}
     * @param coastalCount  total number of coastal locations
     * @return human-readable detail line
     */
    static String buildKingTideDetail(String dateRange,
            String alignmentInfo, int coastalCount) {
        StringBuilder sb = new StringBuilder("King tide ");
        sb.append(dateRange);
        if (alignmentInfo != null) {
            sb.append(" \u00b7 ").append(alignmentInfo);
        }
        sb.append(" \u00b7 ").append(coastalCount)
                .append(coastalCount == 1
                        ? " coastal location"
                        : " coastal locations");
        return sb.toString();
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
