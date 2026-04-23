package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;

import java.time.LocalDate;
import java.util.List;

/**
 * Computes Claude rating statistics across a set of locations in a briefing region.
 *
 * <p>Unifies the averaging logic used by {@code BriefingService.enrichWithCachedScores()}
 * (for region-level {@code displayVerdict} + {@code scoredLocationCount}) and
 * {@code BriefingBestBetAdvisor.appendClaudeScores()} (for the Best Bet prompt).
 *
 * <p>Defensively skips null or out-of-range ratings by delegating to
 * {@link RatingValidator} — which is the single source of truth for the
 * {@code [1, 5]} bound and the {@code [RATING GUARDRAIL]} WARN log.
 */
public final class BriefingRatingStats {

    private BriefingRatingStats() {
    }

    /**
     * A single location's rating contribution to the region rollup.
     *
     * @param locationName the location (used only for diagnostic logging)
     * @param rating       the 1-5 Claude rating, or null if the location is unscored
     */
    public record Entry(String locationName, Integer rating) {
    }

    /**
     * Aggregated rating statistics across the valid scored locations in a region.
     *
     * @param count          how many ratings contributed
     * @param highRated      count of ratings 4-5
     * @param mediumRated    count of ratings == 3
     * @param averageRating  arithmetic mean of valid ratings, rounded to 1dp;
     *                       {@code 0.0} when {@link #isEmpty()} is true
     */
    public record Stats(int count, long highRated, long mediumRated, double averageRating) {

        public static Stats empty() {
            return new Stats(0, 0L, 0L, 0.0);
        }

        public boolean isEmpty() {
            return count == 0;
        }
    }

    /**
     * Computes aggregate stats for the given region/date/event context.
     *
     * <p>Null ratings are skipped silently (location simply not scored yet).
     * Out-of-range ratings are skipped and logged at WARN with full context so
     * data-integrity issues upstream can be tracked.
     *
     * @param entries      one entry per location being considered
     * @param regionName   region display name (for WARN context)
     * @param date         forecast date (for WARN context)
     * @param targetType   sunrise/sunset (for WARN context)
     * @return stats across the entries whose ratings are non-null and in {@code [1, 5]}
     */
    public static Stats compute(List<Entry> entries, String regionName,
            LocalDate date, TargetType targetType) {
        int count = 0;
        long high = 0L;
        long medium = 0L;
        long sum = 0L;
        for (Entry entry : entries) {
            Integer rating = RatingValidator.validateRating(
                    entry.rating(), regionName, date, targetType, entry.locationName(), null);
            if (rating == null) {
                continue;
            }
            count++;
            sum += rating;
            if (rating >= 4) {
                high++;
            } else if (rating == 3) {
                medium++;
            }
        }
        if (count == 0) {
            return Stats.empty();
        }
        double avg = (double) sum / count;
        double rounded = Math.round(avg * 10.0) / 10.0;
        return new Stats(count, high, medium, rounded);
    }

    /**
     * Resolves the region-level {@link DisplayVerdict} from the rating stats and
     * the triage fallback verdict.
     *
     * <p>When at least one scored location contributed, the average rating drives
     * the verdict: {@code >= 3.5} → WORTH_IT, {@code >= 2.5} → MAYBE, else
     * STAND_DOWN. When no location is scored, the triage {@code verdict} is
     * mapped through {@link DisplayVerdict#resolve(Integer, Verdict)}.
     *
     * @param stats           aggregate stats (may be empty)
     * @param triageFallback  triage verdict to use when stats are empty; may be null
     * @return the region display verdict (never null)
     */
    public static DisplayVerdict resolveRegionDisplayVerdict(Stats stats, Verdict triageFallback) {
        if (stats.isEmpty()) {
            return DisplayVerdict.resolve(null, triageFallback);
        }
        if (stats.averageRating() >= 3.5) {
            return DisplayVerdict.WORTH_IT;
        }
        if (stats.averageRating() >= 2.5) {
            return DisplayVerdict.MAYBE;
        }
        return DisplayVerdict.STAND_DOWN;
    }
}
