package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derives the best dark-sky region for tonight's aurora from the cached aurora scores, so the
 * best-bet advisor recommends a real region instead of improvising one.
 *
 * <p>Instance-scoped seam extracted from {@code BriefingBestBetAdvisor}: it reads the current
 * aurora scores from the injected {@link AuroraStateCache} and ranks the candidate regions by
 * clear-location count, then mean star rating, then darkness.
 */
public final class AuroraRegionSelector {

    /** Cloud-cover percent below which a scored aurora location counts as "clear" for ranking. */
    private static final int AURORA_CLEAR_CLOUD_PERCENT = 50;

    /** Darkness rank used when a region's aurora locations have no Bortle class (brightest/unknown). */
    private static final int AURORA_DARKNESS_UNKNOWN = 9;

    /**
     * Orders candidate aurora regions best-first: most clear locations, then highest mean star
     * rating, then darkest sky ({@code bortleClass}), then name for a deterministic tie-break.
     * Used with {@code min(...)} so the smallest under this order is the best region.
     */
    private static final Comparator<AuroraRegionSummary> AURORA_REGION_ORDER =
            Comparator.comparingInt(AuroraRegionSummary::clearCount).reversed()
                    .thenComparing(Comparator
                            .comparingDouble(AuroraRegionSummary::averageStars).reversed())
                    .thenComparingInt(AuroraRegionSummary::darknessRank)
                    .thenComparing(AuroraRegionSummary::name);

    private final AuroraStateCache auroraStateCache;

    /**
     * Constructs an {@code AuroraRegionSelector}.
     *
     * @param auroraStateCache read-only access to the current aurora alert state and cached scores
     */
    public AuroraRegionSelector(AuroraStateCache auroraStateCache) {
        this.auroraStateCache = auroraStateCache;
    }

    /**
     * Derives the best dark-sky region for tonight's aurora from the cached aurora scores,
     * so the advisor recommends a real region instead of improvising one (the observed
     * "Northumberland is typically the premier dark-sky region" gap-filling). Each cached
     * {@link AuroraForecastScore} carries its full {@link LocationEntity}, so the scored
     * locations already know their region; this groups them and ranks by clear-location count,
     * then mean star rating, then darkness ({@code bortleClass}).
     *
     * <p>Returns {@code null} when no cached score carries a region — the caller then degrades
     * to region-agnostic phrasing. There is deliberately no config-default fallback: inventing
     * a default would re-introduce improvisation in disguise.
     *
     * @return the best dark-sky region name for the aurora, or {@code null} when none is derivable
     */
    public String bestAuroraRegion() {
        List<AuroraForecastScore> scores = auroraStateCache.getCachedScores();
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        Map<String, List<AuroraForecastScore>> byRegion = new LinkedHashMap<>();
        for (AuroraForecastScore score : scores) {
            String name = regionNameOf(score);
            if (name != null) {
                byRegion.computeIfAbsent(name, k -> new ArrayList<>()).add(score);
            }
        }
        return byRegion.entrySet().stream()
                .map(e -> summariseAuroraRegion(e.getKey(), e.getValue()))
                .min(AURORA_REGION_ORDER)
                .map(AuroraRegionSummary::name)
                .orElse(null);
    }

    /**
     * Returns the region name of a scored aurora location, or {@code null} when the score,
     * its location, or its region (or the region's name) is missing/blank.
     */
    private static String regionNameOf(AuroraForecastScore score) {
        if (score == null || score.location() == null || score.location().getRegion() == null) {
            return null;
        }
        String name = score.location().getRegion().getName();
        return (name == null || name.isBlank()) ? null : name;
    }

    /**
     * Reduces a region's cached aurora scores to the figures the ranking compares: how many
     * locations are clear, the mean star rating, and the darkest Bortle class present.
     */
    private AuroraRegionSummary summariseAuroraRegion(String name, List<AuroraForecastScore> scores) {
        int clearCount = (int) scores.stream()
                .filter(s -> s.cloudPercent() < AURORA_CLEAR_CLOUD_PERCENT).count();
        double averageStars = scores.stream()
                .mapToInt(AuroraForecastScore::stars).average().orElse(0.0);
        int darkness = scores.stream()
                .map(s -> s.location().getBortleClass())
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min().orElse(AURORA_DARKNESS_UNKNOWN);
        return new AuroraRegionSummary(name, clearCount, averageStars, darkness);
    }

    /**
     * Per-region aurora figures for ranking the best dark-sky region.
     *
     * @param name         region name
     * @param clearCount   number of scored locations below the clear-sky cloud threshold
     * @param averageStars mean aurora star rating across the region's scored locations
     * @param darknessRank darkest Bortle class present (1 = darkest; higher = brighter)
     */
    private record AuroraRegionSummary(String name, int clearCount,
            double averageStars, int darknessRank) {
    }
}
