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

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detects Spring Tide hot topics from the cached briefing triage data.
 *
 * <p>A spring tide occurs around new and full moons when gravitational
 * alignment produces larger-than-normal tidal ranges. Suppressed entirely
 * when any (non-expired) king tide exists in the detection window — they are
 * redundant (a king tide is a stronger spring tide).
 *
 * <p>Surveys the whole forecast window and reports <em>every</em> spring-tide day that still has
 * a non-expired solar event, mirroring {@link KingTideHotTopicStrategy}: the pill communicates the
 * full day range and highlights the best tide/solar alignment, and drops solar events that have
 * already passed via {@link SolarEventFreshness}. Reads from the briefing cache
 * ({@link BriefingService#getCachedDays()}) so the pill is consistent with the heatmap grid.
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
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code SpringTideHotTopicStrategy}.
     *
     * @param briefingService              cached briefing data (injected lazily
     *                                     to break circular dependency)
     * @param locationRepository           repository for location lookups
     * @param forecastEvaluationRepository repository for tide alignment queries
     * @param freshness                    shared filter dropping solar events already past
     */
    public SpringTideHotTopicStrategy(@Lazy BriefingService briefingService,
            LocationRepository locationRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            SolarEventFreshness freshness) {
        this.briefingService = briefingService;
        this.locationRepository = locationRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.freshness = freshness;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns empty if a non-expired king tide exists anywhere in the window (king tide trumps
     * spring). Otherwise collects every spring-not-king day that still has a solar event ahead,
     * dates the pill to the earliest, communicates the full day range, and highlights the best
     * non-expired sunrise/sunset alignment. Returns empty when no briefing is cached or no
     * spring-tide day has a non-expired event.
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

        // King tide trumps spring tide anywhere in the window (a king tide is a stronger spring).
        boolean kingTideInWindow = sorted.stream()
                .anyMatch(d -> KingTideHotTopicStrategy.findKingTide(d) != null);
        if (kingTideInWindow) {
            return List.of();
        }

        List<BriefingDay> springCandidates = sorted.stream()
                .filter(d -> findSpringTide(d) != null)
                .toList();
        if (springCandidates.isEmpty()) {
            return List.of();
        }

        List<LocationEntity> coastalLocations = locationRepository.findCoastalLocations();
        LocationEntity representative =
                coastalLocations.isEmpty() ? null : coastalLocations.get(0);

        // Drop spring-tide days whose sunrise and sunset have both already passed.
        List<BriefingDay> springDays = springCandidates.stream()
                .filter(d -> !KingTideHotTopicStrategy.nonExpiredEvents(
                        d.date(), representative, freshness).isEmpty())
                .toList();
        if (springDays.isEmpty()) {
            return List.of();
        }

        List<LocalDate> springDates = springDays.stream().map(BriefingDay::date).toList();
        LocalDate pillDate = springDates.get(0);
        BriefingSlot.TideInfo springTide = findSpringTide(springDays.get(0));

        Map<LocalDate, Map<TargetType, Long>> allAlignments = new LinkedHashMap<>();
        for (BriefingDay day : springDays) {
            allAlignments.put(day.date(),
                    KingTideHotTopicStrategy.maskExpired(
                            KingTideHotTopicStrategy.parseTideAlignmentCounts(
                                    forecastEvaluationRepository, day.date()),
                            KingTideHotTopicStrategy.nonExpiredEvents(
                                    day.date(), representative, freshness)));
        }

        KingTideHotTopicStrategy.BestAlignment best =
                KingTideHotTopicStrategy.findBestAlignment(allAlignments);
        Map<TargetType, Long> bestCounts = best != null
                ? allAlignments.get(best.date()) : Map.of();

        List<String> coastalRegions = extractRegionNames(coastalLocations);
        String dateRange = KingTideHotTopicStrategy.formatDateRange(springDates, fromDate);
        String alignmentInfo = best != null
                ? KingTideHotTopicStrategy.buildAlignmentInfo(
                        best, springDates.size() > 1, fromDate)
                : null;
        ExpandedHotTopicDetail expandedDetail =
                KingTideHotTopicStrategy.buildExpandedDetail(
                        coastalLocations, "Spring tide",
                        springTide.lunarPhase(), bestCounts);

        return List.of(new HotTopic(
                "SPRING_TIDE",
                "Spring tide",
                buildSpringTideDetail(dateRange, alignmentInfo, coastalLocations.size()),
                pillDate,
                2,
                null,
                coastalRegions,
                SPRING_TIDE_DESCRIPTION,
                expandedDetail));
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

    /**
     * Builds the detail line for a spring tide pill, mirroring the king-tide format:
     * {@code Spring tide [dateRange] · [alignmentInfo] · N coastal locations}.
     *
     * @param dateRange     day range label (e.g. "today", "today through Saturday")
     * @param alignmentInfo best-alignment segment, or {@code null} when no non-expired alignment
     * @param coastalCount  total number of coastal locations
     * @return human-readable detail line
     */
    static String buildSpringTideDetail(String dateRange, String alignmentInfo,
            int coastalCount) {
        StringBuilder sb = new StringBuilder("Spring tide ");
        sb.append(dateRange);
        if (alignmentInfo != null) {
            sb.append(" · ").append(alignmentInfo);
        } else {
            sb.append(" · no sunrise or sunset alignment, but good coastal foreground");
        }
        sb.append(" · ").append(coastalCount)
                .append(coastalCount == 1 ? " coastal location" : " coastal locations");
        return sb.toString();
    }
}
