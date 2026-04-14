package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
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
            String regionSuffix = topRegions.isEmpty()
                    ? "" : " — " + String.join(", ", topRegions);
            String detail = (bestSummary != null ? bestSummary + " " : "") + dayLabel + regionSuffix;

            int priority = bestScore >= 8 ? 1 : 3;

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
                            + " open-fell bluebell photography."));
        }

        return topics;
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
