package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingEvaluationService} cache freshness logic.
 */
@ExtendWith(MockitoExtension.class)
class BriefingEvaluationServiceCacheFreshnessTest {

    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private ForecastService forecastService;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private JobRunService jobRunService;
    @Mock private ForecastBatchRepository batchRepository;
    @Mock private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock private AnthropicClient anthropicClient;

    private BriefingEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                locationService, briefingService, forecastService,
                modelSelectionService, jobRunService, batchRepository,
                cachedEvaluationRepository, anthropicClient, new ObjectMapper());
    }

    /**
     * Injects an entry directly into the in-memory cache via reflection.
     */
    @SuppressWarnings("unchecked")
    private void injectCacheEntry(String key, Instant evaluatedAt,
            BriefingEvaluationResult... results) throws Exception {
        Field cacheField = BriefingEvaluationService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ConcurrentHashMap<String, Object> cache =
                (ConcurrentHashMap<String, Object>) cacheField.get(service);

        // Build the CachedEvaluation record via its constructor
        ConcurrentHashMap<String, BriefingEvaluationResult> resultMap =
                new ConcurrentHashMap<>();
        for (BriefingEvaluationResult r : results) {
            resultMap.put(r.locationName(), r);
        }

        // CachedEvaluation is a package-private record inside BriefingEvaluationService
        var ctor = Class.forName(
                        BriefingEvaluationService.class.getName() + "$CachedEvaluation")
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object cachedEval = ctor.newInstance(resultMap, evaluatedAt);

        cache.put(key, cachedEval);
    }

    private BriefingEvaluationResult sampleResult(String locationName) {
        return new BriefingEvaluationResult(locationName, 4, 75, 60, "Nice sunset");
    }

    @Nested
    @DisplayName("hasFreshEvaluation")
    class HasFreshEvaluation {

        @Test
        @DisplayName("returns true when entry is within freshness threshold")
        void freshEntry() throws Exception {
            String key = "North East|2026-04-24|SUNRISE";
            injectCacheEntry(key, Instant.now().minus(Duration.ofHours(6)),
                    sampleResult("Bamburgh"));

            assertThat(service.hasFreshEvaluation(key, Duration.ofHours(18))).isTrue();
        }

        @Test
        @DisplayName("returns false when entry is older than freshness threshold")
        void staleEntry() throws Exception {
            String key = "North East|2026-04-24|SUNRISE";
            injectCacheEntry(key, Instant.now().minus(Duration.ofHours(24)),
                    sampleResult("Bamburgh"));

            assertThat(service.hasFreshEvaluation(key, Duration.ofHours(18))).isFalse();
        }

        @Test
        @DisplayName("returns false when entry is absent")
        void absentEntry() {
            assertThat(service.hasFreshEvaluation(
                    "Missing|2026-04-24|SUNRISE", Duration.ofHours(18))).isFalse();
        }

        @Test
        @DisplayName("returns false when entry has empty results")
        void emptyResults() throws Exception {
            String key = "North East|2026-04-24|SUNRISE";
            injectCacheEntry(key, Instant.now().minus(Duration.ofHours(2)));

            assertThat(service.hasFreshEvaluation(key, Duration.ofHours(18))).isFalse();
        }
    }

    @Nested
    @DisplayName("hasEvaluation (unchanged — presence-only)")
    class HasEvaluation {

        @Test
        @DisplayName("returns true regardless of age when results exist")
        void oldEntryStillPresent() throws Exception {
            String key = "North East|2026-04-24|SUNRISE";
            injectCacheEntry(key, Instant.now().minus(Duration.ofHours(48)),
                    sampleResult("Bamburgh"));

            assertThat(service.hasEvaluation(key)).isTrue();
        }

        @Test
        @DisplayName("returns false when absent")
        void absentEntry() {
            assertThat(service.hasEvaluation("Missing|2026-04-24|SUNRISE")).isFalse();
        }
    }
}
