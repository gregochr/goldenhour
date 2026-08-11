package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.EvaluationDeltaLogEntity;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
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

    @Mock private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock private EvaluationDeltaLogRepository deltaLogRepository;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private BriefingEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                cachedEvaluationRepository, deltaLogRepository,
                // JavaTimeModule to match AppConfig's bean — BriefingEvaluationResult now carries
                // an Instant, and persistToDb only WARNs on a serialisation failure, so a bare
                // mapper would quietly skip the persistence path instead of failing.
                new ObjectMapper().registerModule(new JavaTimeModule()),
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

    @Nested
    @DisplayName("age_hours is measured per location, not per cache key")
    class AgeBasis {

        /** Stubs a SETTLED snapshot + threshold for Bamburgh. */
        private void settledBamburgh() {
            when(stabilitySnapshotProvider.getLatestStabilitySummary()).thenReturn(
                    new StabilitySummaryResponse(Instant.now(), 1,
                            Map.of(ForecastStability.SETTLED, 1L),
                            List.of(new StabilitySummaryResponse.GridCellDetail(
                                    "55.60,-1.71", 55.6, -1.71,
                                    ForecastStability.SETTLED, "test", 3,
                                    List.of("Bamburgh")))));
            when(freshnessResolver.maxAgeFor(ForecastStability.SETTLED))
                    .thenReturn(Duration.ofHours(36));
        }

        private EvaluationDeltaLogEntity captureDelta() {
            ArgumentCaptor<EvaluationDeltaLogEntity> captor =
                    ArgumentCaptor.forClass(EvaluationDeltaLogEntity.class);
            verify(deltaLogRepository).save(captor.capture());
            return captor.getValue();
        }

        /**
         * The artefact this change exists to remove.
         *
         * <p>A region's slots span several batches, so by the time the second batch of a cycle
         * lands, the cache-KEY stamp has already been reset by the first — minutes ago. The
         * location's rating, though, is genuinely a day old. Measuring against the cache key
         * logged a real 24h rating movement at an age of ~0h, and those rows are what made the
         * sub-12h buckets of this table unreadable.
         */
        @Test
        @DisplayName("REGRESSION: a 24h-old location in a region written 1 minute ago logs 24h, "
                + "not 0h")
        void ageFollowsTheLocationNotTheRegion() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant regionTouchedJustNow = Instant.now().minus(Duration.ofMinutes(1));
            Instant locationScoredYesterday = Instant.now().minus(Duration.ofHours(24));
            injectCacheEntry(cacheKey, regionTouchedJustNow,
                    result("Bamburgh", 5).withEvaluatedAt(locationScoredYesterday));
            settledBamburgh();

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 1)));

            EvaluationDeltaLogEntity delta = captureDelta();
            assertThat(delta.getAgeHours().doubleValue()).isBetween(23.9, 24.1);
            assertThat(delta.getAgeBasis()).isEqualTo("LOCATION");
            assertThat(delta.getOldEvaluatedAt()).isEqualTo(locationScoredYesterday);
        }

        @Test
        @DisplayName("Legacy prior with no per-location stamp falls back to the region stamp and "
                + "says so")
        void fallsBackToRegionStampAndLabelsIt() throws Exception {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            Instant regionStamp = Instant.now().minus(Duration.ofHours(10));
            // result(...) uses the pre-evaluatedAt constructor, so this is exactly the shape a
            // row cached before this change deserialises to.
            injectCacheEntry(cacheKey, regionStamp, result("Bamburgh", 3));
            settledBamburgh();

            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 4)));

            EvaluationDeltaLogEntity delta = captureDelta();
            assertThat(delta.getAgeHours().doubleValue()).isBetween(9.9, 10.1);
            assertThat(delta.getAgeBasis()).isEqualTo("CACHE_KEY");
            assertThat(delta.getOldEvaluatedAt()).isEqualTo(regionStamp);
        }

        @Test
        @DisplayName("The write path stamps results, so the next write measures against it")
        void writePathStampsSoSubsequentWriteUsesLocationBasis() {
            String cacheKey = "North East|2026-04-24|SUNRISE";
            settledBamburgh();

            // First write seeds the cache; no prior, so no delta row.
            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 3)));
            // Second write must find the stamp the first one left behind.
            service.writeFromBatch(cacheKey, List.of(result("Bamburgh", 4)));

            EvaluationDeltaLogEntity delta = captureDelta();
            assertThat(delta.getAgeBasis()).isEqualTo("LOCATION");
            assertThat(delta.getAgeHours().doubleValue()).isLessThan(0.1);
        }
    }
}
