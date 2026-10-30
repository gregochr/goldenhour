package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import java.time.LocalDate;

/**
 * Test-side read of {@link BriefingEvaluationService}'s cache by its {@code region|date|type} key.
 * This was {@code BriefingEvaluationService#hasEvaluation} until nothing in {@code src/main} called
 * it; it asks the live {@code getCachedScores} the same question.
 */
final class BriefingEvaluationTestSupport {

    private BriefingEvaluationTestSupport() {
    }

    /**
     * Whether a non-empty cached evaluation exists for the given cache key.
     *
     * @param service  the service under test
     * @param cacheKey the key in the format {@code regionName|date|targetType}
     * @return true if the cache holds at least one result for this key
     */
    static boolean hasEvaluation(BriefingEvaluationService service, String cacheKey) {
        String[] parts = cacheKey.split("\\|");
        return !service.getCachedScores(parts[0], LocalDate.parse(parts[1]),
                TargetType.valueOf(parts[2])).isEmpty();
    }
}
