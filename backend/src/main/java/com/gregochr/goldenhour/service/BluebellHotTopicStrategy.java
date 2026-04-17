package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
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
import java.util.stream.Collectors;

/**
 * Detects bluebell photography hot topics from stored forecast evaluations.
 *
 * <p>Runs only during bluebell season ({@link SeasonalWindow#BLUEBELL}). For each day
 * in the requested window, scans stored {@code bluebell_score} values across all
 * enabled bluebell locations. When the best score is &ge; 6 ("good"), a
 * {@link HotTopic} is emitted. Makes no external API calls — purely read-only
 * consumer of already-persisted data.
 */
@Component
public class BluebellHotTopicStrategy implements HotTopicStrategy {

    /** Minimum bluebell score to surface as a hot topic. */
    private static final int HOT_TOPIC_THRESHOLD = 6;

    /** Minimum bluebell score for a location to appear in expanded detail. */
    private static final int EXPANDED_DETAIL_THRESHOLD = 5;

    /** Maximum number of region names to include in the detail string. */
    private static final int MAX_REGIONS = 2;

    private final LocationRepository locationRepository;
    private final ForecastEvaluationRepository evaluationRepository;

    /**
     * Constructs a {@code BluebellHotTopicStrategy}.
     *
     * @param locationRepository   repository for location lookups
     * @param evaluationRepository repository for forecast evaluation lookups
     */
    public BluebellHotTopicStrategy(LocationRepository locationRepository,
            ForecastEvaluationRepository evaluationRepository) {
        this.locationRepository = locationRepository;
        this.evaluationRepository = evaluationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an empty list immediately if today is outside bluebell season or if
     * no bluebell locations are configured. Otherwise emits one {@link HotTopic} per
     * day where bluebell conditions score &ge; 6 across any enabled bluebell location.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        if (!SeasonalWindow.BLUEBELL.isActive(fromDate)) {
            return List.of();
        }

        List<LocationEntity> bluebellLocations = locationRepository.findBluebellLocations();
        if (bluebellLocations.isEmpty()) {
            return List.of();
        }

        List<Long> locationIds = bluebellLocations.stream().map(LocationEntity::getId).toList();
        List<ForecastEvaluationEntity> evaluations =
                evaluationRepository.findBluebellEvaluations(locationIds, fromDate, toDate);

        // Group evaluations by date, picking the highest bluebell score per location per date
        // Map: date → list of (entity)
        Map<LocalDate, List<ForecastEvaluationEntity>> byDate = new LinkedHashMap<>();
        for (ForecastEvaluationEntity e : evaluations) {
            byDate.computeIfAbsent(e.getTargetDate(), d -> new ArrayList<>()).add(e);
        }

        List<HotTopic> topics = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (!SeasonalWindow.BLUEBELL.isActive(date)) {
                continue;
            }
            List<ForecastEvaluationEntity> dayEvals = byDate.getOrDefault(date, List.of());
            if (dayEvals.isEmpty()) {
                continue;
            }

            // Find highest bluebell score for this day
            ForecastEvaluationEntity best = dayEvals.stream()
                    .max(Comparator.comparingInt(e -> e.getBluebellScore() != null
                            ? e.getBluebellScore() : 0))
                    .orElse(null);
            if (best == null || best.getBluebellScore() == null
                    || best.getBluebellScore() < HOT_TOPIC_THRESHOLD) {
                continue;
            }

            int bestScore = best.getBluebellScore();
            String bestSummary = best.getBluebellSummary();

            // Collect top regions from good-scoring evaluations (score >= threshold)
            List<String> topRegions = dayEvals.stream()
                    .filter(e -> e.getBluebellScore() != null
                            && e.getBluebellScore() >= HOT_TOPIC_THRESHOLD)
                    .sorted(Comparator.comparingInt(
                            (ForecastEvaluationEntity e) -> e.getBluebellScore() != null
                                    ? e.getBluebellScore() : 0).reversed())
                    .map(e -> e.getLocation() != null && e.getLocation().getRegion() != null
                            ? e.getLocation().getRegion().getName() : null)
                    .filter(r -> r != null)
                    .distinct()
                    .limit(MAX_REGIONS)
                    .toList();

            String dayLabel = formatDayLabel(date, fromDate);
            String detail = (bestSummary != null ? bestSummary + " " : "") + dayLabel;

            int priority = bestScore >= 8 ? 1 : 3;

            ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(dayEvals, bestScore);

            topics.add(new HotTopic(
                    "BLUEBELL",
                    "Bluebell conditions",
                    detail,
                    date,
                    priority,
                    "BLUEBELL",
                    topRegions,
                    "Bluebell season runs mid-April to mid-May. We score mist, wind, light and"
                            + " recent rain to find the best mornings for woodland and"
                            + " open-fell bluebell photography.",
                    expandedDetail));
        }

        return topics;
    }

    /**
     * Builds the expanded detail for a BLUEBELL hot topic from all evaluations for a single day.
     *
     * <p>Groups locations by region, sorted by score descending within each region.
     * Locations with score &ge; 5 are included (broader than the topic threshold of 6).
     *
     * @param dayEvals  all evaluations for the day
     * @param bestScore the highest score across all evaluations
     * @return populated expanded detail
     */
    private ExpandedHotTopicDetail buildExpandedDetail(
            List<ForecastEvaluationEntity> dayEvals, int bestScore) {

        // Filter evaluations with score >= EXPANDED_DETAIL_THRESHOLD and group by region
        Map<String, List<ForecastEvaluationEntity>> byRegion = dayEvals.stream()
                .filter(e -> e.getBluebellScore() != null
                        && e.getBluebellScore() >= EXPANDED_DETAIL_THRESHOLD
                        && e.getLocation() != null
                        && e.getLocation().getRegion() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getLocation().getRegion().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        int scoringLocationCount = 0;
        List<RegionGroup> regionGroups = new ArrayList<>();

        for (Map.Entry<String, List<ForecastEvaluationEntity>> entry : byRegion.entrySet()) {
            List<ForecastEvaluationEntity> regionEvals = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(
                            (ForecastEvaluationEntity e) -> e.getBluebellScore() != null
                                    ? e.getBluebellScore() : 0).reversed())
                    .toList();

            List<LocationEntry> locations = new ArrayList<>();
            boolean firstInRegion = true;
            for (ForecastEvaluationEntity e : regionEvals) {
                int score = e.getBluebellScore();
                LocationEntity loc = e.getLocation();
                String exposure = loc.getBluebellExposure() != null
                        ? loc.getBluebellExposure().name() : null;
                String locationType = formatExposure(exposure);
                String badge = firstInRegion ? "Best" : null;
                firstInRegion = false;

                locations.add(new LocationEntry(
                        loc.getName(), locationType, badge,
                        new BluebellLocationMetrics(score, exposure, e.getBluebellSummary()),
                        null));
                scoringLocationCount++;
            }

            regionGroups.add(new RegionGroup(entry.getKey(), null, null, null, locations));
        }

        String qualityLabel = deriveQualityLabel(bestScore);

        return new ExpandedHotTopicDetail(
                regionGroups,
                new BluebellMetrics(bestScore, qualityLabel, scoringLocationCount),
                null);
    }

    /**
     * Returns a human-readable quality label from a bluebell score.
     *
     * @param score bluebell score (0–10)
     * @return quality label
     */
    static String deriveQualityLabel(int score) {
        if (score >= 9) {
            return "Excellent";
        }
        if (score >= 7) {
            return "Good";
        }
        return "Fair";
    }

    /**
     * Formats a bluebell exposure enum value as a display label.
     *
     * @param exposure enum name, e.g. "WOODLAND" or "OPEN_FELL"
     * @return display label, e.g. "Woodland" or "Open fell"
     */
    private String formatExposure(String exposure) {
        if (exposure == null) {
            return null;
        }
        return switch (exposure) {
            case "WOODLAND" -> "Woodland";
            case "OPEN_FELL" -> "Open fell";
            default -> exposure;
        };
    }

    /**
     * Returns a human-readable day label relative to {@code today}.
     *
     * @param date  the day to label
     * @param today the reference date
     * @return "today", "tomorrow", or the full day name (e.g. "Wednesday")
     */
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
