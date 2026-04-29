package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationDeltaLogEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the evaluation delta log instrumentation in
 * {@link BriefingEvaluationService#writeFromBatch}.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationDeltaLogTest {

    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private ForecastService forecastService;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private JobRunService jobRunService;
    @Mock private ForecastBatchRepository batchRepository;
    @Mock private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock private EvaluationDeltaLogRepository deltaLogRepository;
    @Mock private AnthropicClient anthropicClient;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private BriefingEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                locationService, briefingService, forecastService,
                modelSelectionService, jobRunService, batchRepository,
                cachedEvaluationRepository, deltaLogRepository,
                anthropicClient, new ObjectMapper(),
                freshnessResolver, stabilitySnapshotProvider);
    }

    @SuppressWarnings("unchecked")
    private void injectCacheEntry(String key, Instant evaluatedAt,
            BriefingEvaluationResult... results) throws Exception {
        Field cacheField = BriefingEvaluationService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ConcurrentHashMap<String, Object> cache =
                (ConcurrentHashMap<String, Object>) cacheField.get(service);

        ConcurrentHashMap<String, BriefingEvaluationResult> resultMap =
                new ConcurrentHashMap<>();
        for (BriefingEvaluationResult r : results) {
            resultMap.put(r.locationName(), r);
        }

        var ctors = Class.forName(
                        BriefingEvaluationService.class.getName() + "$CachedEvaluation")
                .getDeclaredConstructors();
        ctors[0].setAccessible(true);
        Object entry = ctors[0].newInstance(resultMap, evaluatedAt);
        cache.put(key, entry);
    }

    private BriefingEvaluationResult result(String locationName, Integer rating) {
        return new BriefingEvaluationResult(locationName, rating, null, null, "summary");
    }

    @Nested
    @DisplayName("Delta log instrumentation")
    class DeltaLogInstrumentation {

        @Test
        @DisplayName("Overwrites existing entry — delta log row inserted with correct fields")
        void overwriteExistingEntryInsertsDelta() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant oldTime = Instant.now().minus(Duration.ofHours(10));
            injectCacheEntry(cacheKey, oldTime, result("Bamburgh", 3));

            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(
                    new StabilitySummaryResponse(Instant.now(), 1,
                            Map.of(ForecastStability.TRANSITIONAL, 1L),
                            List.of(new StabilitySummaryResponse.GridCellDetail(
                                    "55.60,-1.71", 55.6, -1.71,
                                    ForecastStability.TRANSITIONAL, "test", 1,
                                    List.of("Bamburgh")))));
            when(freshnessResolver.maxAgeFor(ForecastStability.TRANSITIONAL))
                    .thenReturn(Duration.ofHours(12));

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 4)));

            ArgumentCaptor<EvaluationDeltaLogEntity> captor =
                    ArgumentCaptor.forClass(EvaluationDeltaLogEntity.class);
            verify(deltaLogRepository).save(captor.capture());

            EvaluationDeltaLogEntity delta = captor.getValue();
            assertThat(delta.getCacheKey()).isEqualTo(cacheKey);
            assertThat(delta.getLocationName()).isEqualTo("Bamburgh");
            assertThat(delta.getTargetType()).isEqualTo("SUNRISE");
            assertThat(delta.getStabilityLevel()).isEqualTo("TRANSITIONAL");
            assertThat(delta.getOldRating()).isEqualTo(3);
            assertThat(delta.getNewRating()).isEqualTo(4);
            assertThat(delta.getRatingDelta().intValue()).isEqualTo(1);
            assertThat(delta.getThresholdUsedHours().intValue()).isEqualTo(12);
            assertThat(delta.getAgeHours().doubleValue()).isGreaterThan(9.9);
        }

        @Test
        @DisplayName("No prior entry — no delta log row inserted")
        void noPriorEntryNoDelta() {
            String cacheKey = "North East|2026-04-24|SUNRISE";

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 4)));

            verify(deltaLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("Delta log failure does NOT break cache write")
        void deltaLogFailureDoesNotBreakWrite() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant oldTime = Instant.now().minus(Duration.ofHours(5));
            injectCacheEntry(cacheKey, oldTime, result("Bamburgh", 3));

            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED))
                    .thenReturn(Duration.ofHours(4));
            when(deltaLogRepository.save(any())).thenThrow(
                    new RuntimeException("DB connection lost"));

            // Should not throw — the cache write succeeds despite delta logging failure
            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 5)));

            // Verify the cache was updated despite the logging failure
            assertThat(service.hasEvaluation(cacheKey)).isTrue();
        }

        @Test
        @DisplayName("Rating delta = 0 when old and new ratings are equal — delta log still written")
        void zeroDelta_stillWritten() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant oldTime = Instant.now().minus(Duration.ofHours(6));
            injectCacheEntry(cacheKey, oldTime, result("Bamburgh", 3));

            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED))
                    .thenReturn(Duration.ofHours(4));

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 3)));

            ArgumentCaptor<EvaluationDeltaLogEntity> captor =
                    ArgumentCaptor.forClass(EvaluationDeltaLogEntity.class);
            verify(deltaLogRepository).save(captor.capture());

            EvaluationDeltaLogEntity delta = captor.getValue();
            assertThat(delta.getCacheKey()).isEqualTo(cacheKey);
            assertThat(delta.getLocationName()).isEqualTo("Bamburgh");
            assertThat(delta.getOldRating()).isEqualTo(3);
            assertThat(delta.getNewRating()).isEqualTo(3);
            assertThat(delta.getRatingDelta().intValue()).isEqualTo(0);
        }

        @Test
        @DisplayName("Rating decrease (old=4, new=2) — ratingDelta is absolute value 2")
        void ratingDecrease_absoluteValueDelta() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant oldTime = Instant.now().minus(Duration.ofHours(8));
            injectCacheEntry(cacheKey, oldTime, result("Bamburgh", 4));

            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(null);
            when(freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED))
                    .thenReturn(Duration.ofHours(4));

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 2)));

            ArgumentCaptor<EvaluationDeltaLogEntity> captor =
                    ArgumentCaptor.forClass(EvaluationDeltaLogEntity.class);
            verify(deltaLogRepository).save(captor.capture());

            EvaluationDeltaLogEntity delta = captor.getValue();
            assertThat(delta.getCacheKey()).isEqualTo(cacheKey);
            assertThat(delta.getLocationName()).isEqualTo("Bamburgh");
            assertThat(delta.getOldRating()).isEqualTo(4);
            assertThat(delta.getNewRating()).isEqualTo(2);
            assertThat(delta.getRatingDelta().intValue()).isEqualTo(2);
        }

        @Test
        @DisplayName("SETTLED stability level — thresholdUsedHours matches SETTLED config value")
        void settledStability_thresholdUsedHoursMatchesConfig() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant oldTime = Instant.now().minus(Duration.ofHours(10));
            injectCacheEntry(cacheKey, oldTime, result("Bamburgh", 3));

            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(
                    new StabilitySummaryResponse(Instant.now(), 1,
                            Map.of(ForecastStability.SETTLED, 1L),
                            List.of(new StabilitySummaryResponse.GridCellDetail(
                                    "55.60,-1.71", 55.6, -1.71,
                                    ForecastStability.SETTLED, "high pressure", 3,
                                    List.of("Bamburgh")))));
            when(freshnessResolver.maxAgeFor(ForecastStability.SETTLED))
                    .thenReturn(Duration.ofHours(24));

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 5)));

            ArgumentCaptor<EvaluationDeltaLogEntity> captor =
                    ArgumentCaptor.forClass(EvaluationDeltaLogEntity.class);
            verify(deltaLogRepository).save(captor.capture());

            EvaluationDeltaLogEntity delta = captor.getValue();
            assertThat(delta.getStabilityLevel()).isEqualTo("SETTLED");
            assertThat(delta.getThresholdUsedHours().intValue()).isEqualTo(24);
            assertThat(delta.getOldRating()).isEqualTo(3);
            assertThat(delta.getNewRating()).isEqualTo(5);
            assertThat(delta.getRatingDelta().intValue()).isEqualTo(2);
        }
    }
}
