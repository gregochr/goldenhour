package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
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
 * Detects bluebell photography hot topics from the Claude {@code BLUEBELL} component scores.
 *
 * <p>Runs only during the configured bluebell season ({@link SeasonalWindow}). For each day in the
 * requested window it scans the {@code forecast_score} {@link ForecastType#BLUEBELL} rows (the 1–5
 * Claude bluebell ratings written by the nightly pipeline's dual-write) across all enabled bluebell
 * locations. When the best rating is &ge; {@value #HOT_TOPIC_THRESHOLD} a {@link HotTopic} is
 * emitted. Makes no external API calls — a purely read-only consumer of already-persisted data.
 *
 * <p><b>Pass 3 C4 re-point.</b> This previously read {@code forecast_evaluation.bluebell_score}
 * (the deterministic 0–10 condition score, only ever written by the rare admin sync path). It now
 * reads the normalised {@code forecast_score} BLUEBELL rows, so the bluebell hot topic fires from
 * the nightly batch pipeline for the first time. The thresholds are the 0–10 ones remapped onto the
 * 1–5 Claude rubric (1=forget it … 5=drop everything), preserving the original selectivity:
 * <ul>
 *   <li>{@code HOT_TOPIC_THRESHOLD} 6/10 → 3/5 (a workable bluebell morning worth surfacing)</li>
 *   <li>{@code EXPANDED_DETAIL_THRESHOLD} 5/10 → 2/5 (broader context in the expanded card)</li>
 *   <li>high priority 8/10 → 4/5; quality label 9/10 → 5 (Excellent), 7/10 → 4 (Good)</li>
 * </ul>
 */
@Component
public class BluebellHotTopicStrategy implements HotTopicStrategy {

    /** Minimum bluebell rating (1–5) to surface as a hot topic — 6/10 remapped. */
    private static final int HOT_TOPIC_THRESHOLD = 3;

    /** Minimum bluebell rating (1–5) for a location to appear in expanded detail — 5/10 remapped. */
    private static final int EXPANDED_DETAIL_THRESHOLD = 2;

    /** Rating (1–5) at or above which the hot topic is high priority — 8/10 remapped. */
    private static final int HIGH_PRIORITY_THRESHOLD = 4;

    /** Maximum number of region names to include in the detail string. */
    private static final int MAX_REGIONS = 2;

    private final LocationRepository locationRepository;
    private final ForecastScoreRepository forecastScoreRepository;
    private final SeasonalWindow bluebellSeason;

    /**
     * Constructs a {@code BluebellHotTopicStrategy}.
     *
     * @param locationRepository      repository for location lookups
     * @param forecastScoreRepository repository for the normalised BLUEBELL component rows
     * @param bluebellSeason          the configured bluebell season window
     */
    public BluebellHotTopicStrategy(LocationRepository locationRepository,
            ForecastScoreRepository forecastScoreRepository,
            SeasonalWindow bluebellSeason) {
        this.locationRepository = locationRepository;
        this.forecastScoreRepository = forecastScoreRepository;
        this.bluebellSeason = bluebellSeason;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an empty list immediately if today is outside bluebell season or if
     * no bluebell locations are configured. Otherwise emits one {@link HotTopic} per
     * day where the best Claude bluebell rating is &ge; {@value #HOT_TOPIC_THRESHOLD}
     * across any enabled bluebell location.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        if (!bluebellSeason.isActive(fromDate)) {
            return List.of();
        }

        List<LocationEntity> bluebellLocations = locationRepository.findBluebellLocations();
        if (bluebellLocations.isEmpty()) {
            return List.of();
        }

        List<Long> locationIds = bluebellLocations.stream().map(LocationEntity::getId).toList();
        List<ForecastScoreEntity> scores = forecastScoreRepository.findComponentsForLocations(
                ForecastType.BLUEBELL.getId(), locationIds, fromDate, toDate);

        // Group BLUEBELL rows by date. A location may have a SUNRISE and a SUNSET row on the same
        // day; the per-day best below naturally takes the higher rating.
        Map<LocalDate, List<ForecastScoreEntity>> byDate = new LinkedHashMap<>();
        for (ForecastScoreEntity s : scores) {
            byDate.computeIfAbsent(s.getEvaluationDate(), d -> new ArrayList<>()).add(s);
        }

        List<HotTopic> topics = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (!bluebellSeason.isActive(date)) {
                continue;
            }
            List<ForecastScoreEntity> dayScores = byDate.getOrDefault(date, List.of());
            if (dayScores.isEmpty()) {
                continue;
            }

            ForecastScoreEntity best = dayScores.stream()
                    .max(Comparator.comparingInt(e -> e.getScore() != null ? e.getScore() : 0))
                    .orElse(null);
            if (best == null || best.getScore() == null
                    || best.getScore() < HOT_TOPIC_THRESHOLD) {
                continue;
            }

            int bestScore = best.getScore();
            String bestSummary = best.getSummary();

            List<String> topRegions = dayScores.stream()
                    .filter(e -> e.getScore() != null && e.getScore() >= HOT_TOPIC_THRESHOLD)
                    .sorted(Comparator.comparingInt(
                            (ForecastScoreEntity e) -> e.getScore() != null
                                    ? e.getScore() : 0).reversed())
                    .map(e -> e.getLocation() != null && e.getLocation().getRegion() != null
                            ? e.getLocation().getRegion().getName() : null)
                    .filter(r -> r != null)
                    .distinct()
                    .limit(MAX_REGIONS)
                    .toList();

            String dayLabel = formatDayLabel(date, fromDate);
            String detail = (bestSummary != null ? bestSummary + " " : "") + dayLabel;

            int priority = bestScore >= HIGH_PRIORITY_THRESHOLD ? 1 : 3;

            ExpandedHotTopicDetail expandedDetail = buildExpandedDetail(dayScores, bestScore);

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
                            + " open-fell bluebell photography — assuming the flowers are in bloom.",
                    expandedDetail));
        }

        return topics;
    }

    /**
     * Builds the expanded detail for a BLUEBELL hot topic from all BLUEBELL rows for a single day.
     *
     * <p>Groups locations by region, sorted by rating descending within each region.
     * Locations with rating &ge; {@value #EXPANDED_DETAIL_THRESHOLD} are included (broader than the
     * topic threshold).
     *
     * @param dayScores all BLUEBELL rows for the day
     * @param bestScore the highest rating across all rows
     * @return populated expanded detail
     */
    private ExpandedHotTopicDetail buildExpandedDetail(
            List<ForecastScoreEntity> dayScores, int bestScore) {

        Map<String, List<ForecastScoreEntity>> byRegion = dayScores.stream()
                .filter(e -> e.getScore() != null
                        && e.getScore() >= EXPANDED_DETAIL_THRESHOLD
                        && e.getLocation() != null
                        && e.getLocation().getRegion() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getLocation().getRegion().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        int scoringLocationCount = 0;
        List<RegionGroup> regionGroups = new ArrayList<>();

        for (Map.Entry<String, List<ForecastScoreEntity>> entry : byRegion.entrySet()) {
            List<ForecastScoreEntity> regionScores = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(
                            (ForecastScoreEntity e) -> e.getScore() != null
                                    ? e.getScore() : 0).reversed())
                    .toList();

            List<LocationEntry> locations = new ArrayList<>();
            boolean firstInRegion = true;
            for (ForecastScoreEntity e : regionScores) {
                int score = e.getScore();
                LocationEntity loc = e.getLocation();
                String exposure = loc.getBluebellExposure() != null
                        ? loc.getBluebellExposure().name() : null;
                String locationType = formatExposure(exposure);
                String badge = firstInRegion ? "Best" : null;
                firstInRegion = false;

                locations.add(new LocationEntry(
                        loc.getName(), locationType, badge,
                        new BluebellLocationMetrics(score, exposure, e.getSummary()),
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
     * Returns a human-readable quality label from a bluebell rating (1–5).
     *
     * @param score bluebell rating (1–5)
     * @return quality label
     */
    static String deriveQualityLabel(int score) {
        if (score >= 5) {
            return "Excellent";
        }
        if (score >= 4) {
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
