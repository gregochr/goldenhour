package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.SurvivorSignals;
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
 * Detects bluebell photography hot topics from the Claude {@code BLUEBELL} component scores.
 *
 * <p>Runs only during the configured bluebell season ({@link SeasonalWindow}). For each day in the
 * requested window it scans the BLUEBELL component scores (the 1–5 Claude bluebell ratings written
 * by the nightly pipeline's dual-write) through the {@link SurvivorSignalReader} — only bluebell
 * sites carry a {@code scores().bluebell()}, so the read self-selects them. When the best rating is
 * &ge; {@value #HOT_TOPIC_THRESHOLD} a {@link HotTopic} is emitted. Makes no external API calls — a
 * purely read-only consumer of already-persisted data.
 *
 * <p><b>Survivor read model.</b> Like every survivor-signal detector, this reads through the unified
 * {@link SurvivorSignalReader} rather than a table directly. It previously read {@code forecast_score}
 * BLUEBELL rows via its own repository; routing it through the reader keeps all survivor-signal
 * detectors on one read path. The thresholds are the 0–10 condition score remapped onto the 1–5
 * Claude rubric (1=forget it … 5=drop everything), preserving the original selectivity:
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

    private final SurvivorSignalReader survivorSignalReader;
    private final SeasonalWindow bluebellSeason;
    private final SolarEventFreshness freshness;

    /**
     * Constructs a {@code BluebellHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (BLUEBELL component scores)
     * @param bluebellSeason       the configured bluebell season window
     * @param freshness            shared filter dropping sunrise/sunset events already past
     */
    public BluebellHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SeasonalWindow bluebellSeason, SolarEventFreshness freshness) {
        this.survivorSignalReader = survivorSignalReader;
        this.bluebellSeason = bluebellSeason;
        this.freshness = freshness;
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

        // Only bluebell sites carry a BLUEBELL component score, so filtering the survivor composites
        // to a non-null bluebell score self-selects them (no separate location lookup needed).
        List<SurvivorSignals> bluebellSignals = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.scores().bluebell() != null)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .toList();
        if (bluebellSignals.isEmpty()) {
            return List.of();
        }

        // Group by date. A location may have a SUNRISE and a SUNSET composite on the same day;
        // the per-day best below naturally takes the higher rating.
        Map<LocalDate, List<SurvivorSignals>> byDate = new LinkedHashMap<>();
        for (SurvivorSignals s : bluebellSignals) {
            byDate.computeIfAbsent(s.date(), d -> new ArrayList<>()).add(s);
        }

        List<HotTopic> topics = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (!bluebellSeason.isActive(date)) {
                continue;
            }
            List<SurvivorSignals> dayScores = byDate.getOrDefault(date, List.of());
            if (dayScores.isEmpty()) {
                continue;
            }

            SurvivorSignals best = dayScores.stream()
                    .max(Comparator.comparingInt(BluebellHotTopicStrategy::bluebellScore))
                    .orElse(null);
            if (best == null || best.scores().bluebell() == null
                    || best.scores().bluebell() < HOT_TOPIC_THRESHOLD) {
                continue;
            }

            int bestScore = best.scores().bluebell();
            String bestSummary = best.scores().bluebellSummary();

            List<String> topRegions = dayScores.stream()
                    .filter(s -> s.scores().bluebell() != null
                            && s.scores().bluebell() >= HOT_TOPIC_THRESHOLD)
                    .sorted(Comparator.comparingInt(
                            BluebellHotTopicStrategy::bluebellScore).reversed())
                    .map(s -> s.location() != null && s.location().getRegion() != null
                            ? s.location().getRegion().getName() : null)
                    .filter(Objects::nonNull)
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
     * @param dayScores all bluebell-scored survivor composites for the day
     * @param bestScore the highest rating across all composites
     * @return populated expanded detail
     */
    private ExpandedHotTopicDetail buildExpandedDetail(
            List<SurvivorSignals> dayScores, int bestScore) {

        Map<String, List<SurvivorSignals>> byRegion = dayScores.stream()
                .filter(s -> s.scores().bluebell() != null
                        && s.scores().bluebell() >= EXPANDED_DETAIL_THRESHOLD
                        && s.location() != null
                        && s.location().getRegion() != null)
                .collect(Collectors.groupingBy(
                        s -> s.location().getRegion().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        int scoringLocationCount = 0;
        List<RegionGroup> regionGroups = new ArrayList<>();

        for (Map.Entry<String, List<SurvivorSignals>> entry : byRegion.entrySet()) {
            List<SurvivorSignals> regionScores = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(
                            BluebellHotTopicStrategy::bluebellScore).reversed())
                    .toList();

            List<LocationEntry> locations = new ArrayList<>();
            boolean firstInRegion = true;
            for (SurvivorSignals s : regionScores) {
                int score = s.scores().bluebell();
                LocationEntity loc = s.location();
                String exposure = loc.getBluebellExposure() != null
                        ? loc.getBluebellExposure().name() : null;
                String locationType = formatExposure(exposure);
                String badge = firstInRegion ? "Best" : null;
                firstInRegion = false;

                locations.add(new LocationEntry(
                        loc.getName(), locationType, badge,
                        new BluebellLocationMetrics(score, exposure, s.scores().bluebellSummary()),
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
     * Null-safe bluebell-score extractor for sorting/maxing composites (treats absent as 0).
     *
     * @param signals the survivor composite
     * @return the bluebell score, or 0 if absent
     */
    private static int bluebellScore(SurvivorSignals signals) {
        return signals.scores().bluebell() != null ? signals.scores().bluebell() : 0;
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
