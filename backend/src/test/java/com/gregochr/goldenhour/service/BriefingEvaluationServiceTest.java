package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the batch-only surviving surface of {@link BriefingEvaluationService}.
 *
 * <p>The legacy SSE evaluation path ({@code evaluateRegion}, {@code evaluateSingleLocation},
 * the cancel-outstanding-batches side-effect) was deleted in Pass 3.3.3 along with its
 * tests; cache freshness gates live in {@link BriefingEvaluationServiceCacheFreshnessTest}
 * and delta-log assertions in {@link EvaluationDeltaLogTest}.
 *
 * <p>What this file covers:
 * <ul>
 *   <li>{@code writeFromBatch} — in-memory cache + DB upsert, replacement semantics,
 *       multi-result serialisation, evaluatedAt preservation across upsert</li>
 *   <li>{@code hasEvaluation} / {@code getCachedScores} — read-side after a batch write</li>
 *   <li>{@code clearCache} — idempotency, in-memory + DB clearing, count return</li>
 *   <li>{@code rehydrateCacheOnStartup} — today-or-future filter, rating clamp, corrupt-row
 *       resilience, multi-entry round-trip</li>
 *   <li>{@code onBriefingRefreshed} — cache retention semantics</li>
 *   <li>DB persistence failure must not break the in-memory write path</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BriefingEvaluationServiceTest {

    @Mock private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock private EvaluationDeltaLogRepository deltaLogRepository;
    @Mock private FreshnessResolver freshnessResolver;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BriefingEvaluationService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 30);
    private static final String REGION = "Northumberland";
    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                cachedEvaluationRepository, deltaLogRepository,
                objectMapper, freshnessResolver, stabilitySnapshotProvider);
        // Default: no existing DB cache entries
        org.mockito.Mockito.lenient()
                .when(cachedEvaluationRepository.findByCacheKey(any()))
                .thenReturn(java.util.Optional.empty());
    }

    // ── getCachedScores / hasEvaluation ────────────────────────────────────────

    @Test
    @DisplayName("getCachedScores returns empty map when no cache exists")
    void noCacheReturnsEmpty() {
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("hasEvaluation returns false when cache is empty")
    void hasEvaluation_returnsFalseWhenNotCached() {
        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isFalse();
    }

    @Test
    @DisplayName("hasEvaluation returns false when writeFromBatch was called with empty list")
    void hasEvaluation_returnsFalseWhenEmptyResults() {
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of());

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isFalse();
    }

    @Test
    @DisplayName("hasEvaluation returns true after writeFromBatch with at least one result")
    void hasEvaluation_returnsTrueAfterWriteFromBatch() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(result));

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();
    }

    @Test
    @DisplayName("writeFromBatch results are returned by getCachedScores")
    void writeFromBatch_populatesCacheAccessibleByGetCachedScores() {
        LocalDate date = LocalDate.of(2026, 4, 7);
        BriefingEvaluationResult durham =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        BriefingEvaluationResult sunderland =
                new BriefingEvaluationResult("Sunderland", 3, 45, 40, "Marginal");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(durham, sunderland));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores("North East", date, TargetType.SUNRISE);
        assertThat(scores).hasSize(2);
        assertThat(scores.get("Durham").rating()).isEqualTo(4);
        assertThat(scores.get("Durham").fierySkyPotential()).isEqualTo(72);
        assertThat(scores.get("Sunderland").rating()).isEqualTo(3);
    }

    // ── writeFromBatch — replace semantics ─────────────────────────────────────

    @Test
    @DisplayName("writeFromBatch overwrites existing cache entry for same key")
    void writeFromBatch_overwritesExistingEntry() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";

        BriefingEvaluationResult first =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "First run");
        service.writeFromBatch(cacheKey, List.of(first));

        BriefingEvaluationResult second =
                new BriefingEvaluationResult("Dunstanburgh", 3, 50, 45, "Second run");
        service.writeFromBatch(cacheKey, List.of(second));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(scores).hasSize(1);
        assertThat(scores).containsKey("Dunstanburgh");
        assertThat(scores).doesNotContainKey("Bamburgh");
    }

    // ── mergeFromBatch — retry recovery (must NOT clobber the region) ──────────

    @Nested
    @DisplayName("mergeFromBatch (RETRY_FAILED recovery)")
    class MergeFromBatch {

        private final String cacheKey = REGION + "|" + DATE + "|SUNSET";

        @Test
        @DisplayName("merges the recovered location into the existing in-memory entry "
                + "without dropping the originally-successful locations")
        void mergePreservesPriorInMemoryResults() {
            // Precursor batch wrote 3 of 4 locations (Craster failed and is absent).
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "good"),
                    new BriefingEvaluationResult("Dunstanburgh", 3, 50, 45, "marginal"),
                    new BriefingEvaluationResult("Seahouses", 2, 30, 25, "poor")));

            // Retry batch recovers ONLY Craster.
            service.mergeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Craster", 5, 88, 80, "recovered")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            assertThat(scores).hasSize(4);
            assertThat(scores).containsKeys("Bamburgh", "Dunstanburgh", "Seahouses", "Craster");
            assertThat(scores.get("Craster").rating()).isEqualTo(5);
            // The three originally-successful locations survive — the data-loss guard.
            assertThat(scores.get("Bamburgh").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("persisted results_json after merge contains prior AND recovered locations")
        void mergePersistsCombinedSet() {
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "good")));

            service.mergeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Craster", 5, 88, 80, "recovered")));

            ArgumentCaptor<CachedEvaluationEntity> captor =
                    ArgumentCaptor.forClass(CachedEvaluationEntity.class);
            // save() is called once by the precursor write and once by the merge; the
            // last value is the merged set.
            verify(cachedEvaluationRepository, org.mockito.Mockito.atLeastOnce())
                    .save(captor.capture());
            CachedEvaluationEntity saved = captor.getValue();
            assertThat(saved.getResultsJson()).contains("Bamburgh").contains("Craster");
        }

        @Test
        @DisplayName("with no in-memory prior (post-restart) merges onto the persisted "
                + "results_json so a retry cannot shrink the region")
        void mergeFallsBackToDbPriorWhenInMemoryAbsent() throws Exception {
            // Fresh service with an empty in-memory cache, but the DB row from the
            // precursor write survives a restart.
            String json = objectMapper.writeValueAsString(List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "good"),
                    new BriefingEvaluationResult("Seahouses", 2, 30, 25, "poor")));
            CachedEvaluationEntity priorRow = new CachedEvaluationEntity();
            priorRow.setCacheKey(cacheKey);
            priorRow.setResultsJson(json);
            when(cachedEvaluationRepository.findByCacheKey(cacheKey))
                    .thenReturn(java.util.Optional.of(priorRow));

            service.mergeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Craster", 5, 88, 80, "recovered")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            assertThat(scores).hasSize(3);
            assertThat(scores).containsKeys("Bamburgh", "Seahouses", "Craster");
        }

        @Test
        @DisplayName("with no prior at all (whole region failed) writes just the recovered set")
        void mergeWithNoPriorWritesRecoveredOnly() {
            service.mergeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Craster", 5, 88, 80, "recovered")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            assertThat(scores).hasSize(1);
            assertThat(scores).containsKey("Craster");
        }
    }

    // ── mergeBluebellFromBatch — open-fell recombination (C3b) ─────────────────

    @Nested
    @DisplayName("mergeBluebellFromBatch (open-fell merge-join)")
    class MergeBluebellFromBatch {

        private final String cacheKey = REGION + "|" + DATE + "|SUNSET";

        @Test
        @DisplayName("OPEN_FELL: a prior sky entry exists → rating averages, sky narrative kept")
        void openFell_averagesRatingKeepsSkyNarrative() {
            // Sky batch wrote the open-fell location with a 3★ sky rating.
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 3, 60, 55, "Broken cloud catches "
                            + "the last light over the fell")));

            // Bluebell mini-batch result for the same location: 5★ bluebell, no sky scores.
            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 5, null, null,
                            "Golden light rakes the slope if they are in flower", null, null,
                            "Raking fell light")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            BriefingEvaluationResult merged = scores.get("Rannerdale");
            // round(avg(3, 5)) = 4.
            assertThat(merged.rating()).isEqualTo(4);
            // The sky narrative is retained for the served card.
            assertThat(merged.fierySkyPotential()).isEqualTo(60);
            assertThat(merged.goldenHourPotential()).isEqualTo(55);
            assertThat(merged.summary()).contains("Broken cloud");
        }

        @Test
        @DisplayName("WOODLAND: no prior sky entry → the bluebell result stands alone")
        void woodland_standsAlone() {
            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Bluebell Wood", 4, null, null,
                            "Bright still light if they are in flower", null, null,
                            "Soft canopy light")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            BriefingEvaluationResult merged = scores.get("Bluebell Wood");
            assertThat(merged.rating()).isEqualTo(4);
            assertThat(merged.fierySkyPotential()).isNull();
            assertThat(merged.summary()).contains("Bright still light");
        }

        @Test
        @DisplayName("OPEN_FELL merge preserves the region's other sky locations")
        void openFell_preservesOtherSkyLocations() {
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 2, 40, 35, "sky"),
                    new BriefingEvaluationResult("Buttermere", 4, 75, 70, "great sky")));

            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 4, null, null, "bluebell",
                            null, null, null)));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            assertThat(scores).hasSize(2);
            assertThat(scores.get("Buttermere").rating()).isEqualTo(4);
            // round(avg(2, 4)) = 3.
            assertThat(scores.get("Rannerdale").rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("recombineBluebell rounds the averaged rating half-up")
        void recombineBluebell_roundsHalfUp() {
            BriefingEvaluationResult sky =
                    new BriefingEvaluationResult("X", 4, 70, 65, "sky");
            BriefingEvaluationResult bluebell =
                    new BriefingEvaluationResult("X", 5, null, null, "bb", null, null, null);
            // avg(4, 5) = 4.5 → 5.
            assertThat(service.recombineBluebell(sky, bluebell).rating()).isEqualTo(5);
            // A null prior (woodland) returns the bluebell unchanged.
            assertThat(service.recombineBluebell(null, bluebell)).isSameAs(bluebell);
        }
    }

    // ── writeFromBatch — DB persistence ────────────────────────────────────────

    @Test
    @DisplayName("writeFromBatch persists results to DB via cachedEvaluationRepository")
    void writeFromBatch_persistsToDb() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");
        String cacheKey = REGION + "|" + DATE + "|SUNSET";

        service.writeFromBatch(cacheKey, List.of(result));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());

        CachedEvaluationEntity saved = captor.getValue();
        assertThat(saved.getCacheKey()).isEqualTo(cacheKey);
        assertThat(saved.getRegionName()).isEqualTo(REGION);
        assertThat(saved.getEvaluationDate()).isEqualTo(DATE);
        assertThat(saved.getTargetType()).isEqualTo("SUNSET");
        assertThat(saved.getSource()).isEqualTo("BATCH");
        assertThat(saved.getResultsJson()).contains("Bamburgh");
    }

    @Test
    @DisplayName("writeFromBatch updates existing DB row on same cache key")
    void writeFromBatch_updatesExistingDbRow() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        CachedEvaluationEntity existing = new CachedEvaluationEntity();
        existing.setId(42L);
        existing.setCacheKey(cacheKey);
        existing.setEvaluatedAt(Instant.now().minusSeconds(3600));
        when(cachedEvaluationRepository.findByCacheKey(cacheKey))
                .thenReturn(java.util.Optional.of(existing));

        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Excellent");
        service.writeFromBatch(cacheKey, List.of(result));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getSource()).isEqualTo("BATCH");
    }

    @Test
    @DisplayName("writeFromBatch: multiple results all appear in DB JSON and round-trip correctly")
    void writeFromBatch_multipleResults_allSerialised() throws Exception {
        BriefingEvaluationResult durham =
                new BriefingEvaluationResult("Durham", 3, 55, 48, "Fair");
        BriefingEvaluationResult sunderland =
                new BriefingEvaluationResult("Sunderland", 5, 92, 88, "Excellent");
        String cacheKey = "North East|2026-04-07|SUNRISE";

        service.writeFromBatch(cacheKey, List.of(durham, sunderland));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());

        // Round-trip: deserialise the JSON and verify all fields
        String json = captor.getValue().getResultsJson();
        List<BriefingEvaluationResult> roundTripped = objectMapper.readValue(
                json, new TypeReference<List<BriefingEvaluationResult>>() { });
        assertThat(roundTripped).hasSize(2);
        assertThat(roundTripped).extracting(BriefingEvaluationResult::locationName)
                .containsExactlyInAnyOrder("Durham", "Sunderland");

        // Verify parsed key parts
        assertThat(captor.getValue().getRegionName()).isEqualTo("North East");
        assertThat(captor.getValue().getEvaluationDate())
                .isEqualTo(LocalDate.of(2026, 4, 7));
        assertThat(captor.getValue().getTargetType()).isEqualTo("SUNRISE");
    }

    @Test
    @DisplayName("writeFromBatch upsert preserves original evaluatedAt, updates updatedAt")
    void writeFromBatch_upsert_preservesOriginalEvaluatedAt() {
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        Instant originalEvaluatedAt = Instant.parse("2026-03-30T06:00:00Z");
        CachedEvaluationEntity existing = new CachedEvaluationEntity();
        existing.setId(42L);
        existing.setCacheKey(cacheKey);
        existing.setEvaluatedAt(originalEvaluatedAt);
        existing.setUpdatedAt(Instant.parse("2026-03-30T06:00:00Z"));
        when(cachedEvaluationRepository.findByCacheKey(cacheKey))
                .thenReturn(java.util.Optional.of(existing));

        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Excellent");
        service.writeFromBatch(cacheKey, List.of(result));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());
        CachedEvaluationEntity saved = captor.getValue();

        // evaluatedAt should be the original — not overwritten
        assertThat(saved.getEvaluatedAt()).isEqualTo(originalEvaluatedAt);
        // updatedAt should be newer than the original
        assertThat(saved.getUpdatedAt()).isAfter(originalEvaluatedAt);
    }

    @Test
    @DisplayName("DB persistence failure does not break in-memory cache write")
    void persistToDb_failureDoesNotBreakInMemory() {
        when(cachedEvaluationRepository.save(any())).thenThrow(
                new RuntimeException("DB down"));

        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");
        String cacheKey = REGION + "|" + DATE + "|SUNSET";
        service.writeFromBatch(cacheKey, List.of(result));

        // In-memory cache should still work despite DB failure
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET))
                .containsKey("Bamburgh");
    }

    // ── onBriefingRefreshed — cache retention ──────────────────────────────────

    @Test
    @DisplayName("writeFromBatch cache entry is retained after onBriefingRefreshed")
    void writeFromBatch_retainedAfterBriefingRefresh() {
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good");
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(result));
        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));

        assertThat(service.hasEvaluation("North East|2026-04-07|SUNRISE")).isTrue();
    }

    @Test
    @DisplayName("batch scores are retrievable with correct values after briefing refresh")
    void writeFromBatch_scoresIntactAfterBriefingRefresh() {
        String cacheKey = "North East|" + DATE + "|SUNRISE";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions");
        service.writeFromBatch(cacheKey, List.of(result));

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores("North East", DATE, TargetType.SUNRISE);
        assertThat(scores).containsKey("Durham");
        BriefingEvaluationResult preserved = scores.get("Durham");
        assertThat(preserved.rating()).isEqualTo(4);
        assertThat(preserved.fierySkyPotential()).isEqualTo(72);
        assertThat(preserved.goldenHourPotential()).isEqualTo(65);
        assertThat(preserved.summary()).isEqualTo("Good conditions");
    }

    // ── clearCache ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearCache is idempotent when empty")
    void clearCache_idempotentWhenEmpty() {
        assertThat(service.clearCache()).isZero();
        assertThat(service.clearCache()).isZero();
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("clearCache removes batch-written entries")
    void clearCache_removesBatchWrittenEntries() {
        String cacheKey = "North East|" + DATE + "|SUNRISE";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good");
        service.writeFromBatch(cacheKey, List.of(result));
        assertThat(service.hasEvaluation(cacheKey)).isTrue();

        assertThat(service.clearCache()).isEqualTo(1);

        assertThat(service.hasEvaluation(cacheKey)).isFalse();
        assertThat(service.getCachedScores("North East", DATE, TargetType.SUNRISE)).isEmpty();
    }

    @Test
    @DisplayName("clearCache deletes all DB rows")
    void clearCache_deletesDbRows() {
        when(cachedEvaluationRepository.count()).thenReturn(3L);

        service.clearCache();

        verify(cachedEvaluationRepository).deleteAll();
    }

    @Test
    @DisplayName("clearCache returns correct count when entries exist in both stores")
    void clearCache_returnsCorrectCount() {
        // Pre-populate in-memory cache with 2 entries
        service.writeFromBatch(REGION + "|" + DATE + "|SUNSET",
                List.of(new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good")));
        service.writeFromBatch("Yorkshire|" + DATE + "|SUNRISE",
                List.of(new BriefingEvaluationResult("Whitby", 3, 55, 48, "Fair")));
        when(cachedEvaluationRepository.count()).thenReturn(2L);

        int cleared = service.clearCache();

        assertThat(cleared).isEqualTo(2);
        // Both stores cleared
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
        assertThat(service.getCachedScores("Yorkshire", DATE, TargetType.SUNRISE)).isEmpty();
        verify(cachedEvaluationRepository).deleteAll();
    }

    // ── rehydrateCacheOnStartup ────────────────────────────────────────────────

    @Test
    @DisplayName("rehydrateCacheOnStartup loads entries for today and future into in-memory cache")
    void rehydrate_loadsTodayAndFuture() throws Exception {
        LocalDate today = LocalDate.now(UK_ZONE);
        String cacheKey = REGION + "|" + today + "|SUNSET";
        BriefingEvaluationResult result =
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good");

        CachedEvaluationEntity entity = new CachedEvaluationEntity();
        entity.setCacheKey(cacheKey);
        entity.setResultsJson(objectMapper.writeValueAsString(List.of(result)));
        entity.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entity));

        service.rehydrateCacheOnStartup();

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, today, TargetType.SUNSET);
        assertThat(scores).containsKey("Bamburgh");
        assertThat(scores.get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup nulls out-of-range ratings from persisted JSON")
    void rehydrate_clampsOutOfRangeRatings() throws Exception {
        LocalDate today = LocalDate.now(UK_ZONE);
        String cacheKey = REGION + "|" + today + "|SUNSET";

        // Simulates a cached_evaluation row written before the guardrail existed,
        // carrying a schema-non-compliant Sonnet rating of 491.
        BriefingEvaluationResult bad =
                new BriefingEvaluationResult("Almscliffe Crag", 491, 72, 65, "Out-of-range");
        BriefingEvaluationResult good =
                new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good");

        CachedEvaluationEntity entity = new CachedEvaluationEntity();
        entity.setCacheKey(cacheKey);
        entity.setRegionName(REGION);
        entity.setEvaluationDate(today);
        entity.setTargetType("SUNSET");
        entity.setResultsJson(objectMapper.writeValueAsString(List.of(bad, good)));
        entity.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entity));

        service.rehydrateCacheOnStartup();

        Map<String, BriefingEvaluationResult> scores =
                service.getCachedScores(REGION, today, TargetType.SUNSET);
        assertThat(scores).containsKeys("Almscliffe Crag", "Bamburgh");
        assertThat(scores.get("Almscliffe Crag").rating()).isNull();       // 491 → null
        assertThat(scores.get("Almscliffe Crag").fierySkyPotential()).isEqualTo(72);
        assertThat(scores.get("Bamburgh").rating()).isEqualTo(4);          // untouched
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup skips entries with corrupt JSON")
    void rehydrate_skipsCorruptJson() {
        LocalDate today = LocalDate.now(UK_ZONE);

        CachedEvaluationEntity entity = new CachedEvaluationEntity();
        entity.setCacheKey(REGION + "|" + today + "|SUNSET");
        entity.setResultsJson("NOT VALID JSON {{{{");
        entity.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entity));

        service.rehydrateCacheOnStartup();

        assertThat(service.getCachedScores(REGION, today, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup loads multiple entries with distinct evaluatedAt")
    void rehydrate_multipleEntries_allLoaded() throws Exception {
        LocalDate today = LocalDate.now(UK_ZONE);
        LocalDate tomorrow = today.plusDays(1);

        Instant sunsetTime = Instant.parse("2026-03-30T18:30:00Z");
        Instant sunriseTime = Instant.parse("2026-03-31T06:00:00Z");

        CachedEvaluationEntity entry1 = new CachedEvaluationEntity();
        entry1.setCacheKey(REGION + "|" + today + "|SUNSET");
        entry1.setResultsJson(objectMapper.writeValueAsString(List.of(
                new BriefingEvaluationResult("Bamburgh", 4, 72, 65, "Good"))));
        entry1.setEvaluatedAt(sunsetTime);

        CachedEvaluationEntity entry2 = new CachedEvaluationEntity();
        entry2.setCacheKey(REGION + "|" + tomorrow + "|SUNRISE");
        entry2.setResultsJson(objectMapper.writeValueAsString(List.of(
                new BriefingEvaluationResult("Craster", 3, 55, 48, "Fair"))));
        entry2.setEvaluatedAt(sunriseTime);

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(entry1, entry2));

        service.rehydrateCacheOnStartup();

        // Both entries loaded with their respective contents
        Map<String, BriefingEvaluationResult> sunsetScores =
                service.getCachedScores(REGION, today, TargetType.SUNSET);
        assertThat(sunsetScores).hasSize(1);
        assertThat(sunsetScores.get("Bamburgh").rating()).isEqualTo(4);
        assertThat(sunsetScores.get("Bamburgh").fierySkyPotential()).isEqualTo(72);
        assertThat(sunsetScores.get("Bamburgh").goldenHourPotential()).isEqualTo(65);

        Map<String, BriefingEvaluationResult> sunriseScores =
                service.getCachedScores(REGION, tomorrow, TargetType.SUNRISE);
        assertThat(sunriseScores).hasSize(1);
        assertThat(sunriseScores.get("Craster").rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup with no DB entries leaves cache empty")
    void rehydrate_noEntries_cacheStaysEmpty() {
        LocalDate today = LocalDate.now(UK_ZONE);
        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of());

        service.rehydrateCacheOnStartup();

        assertThat(service.getCachedScores(REGION, today, TargetType.SUNSET)).isEmpty();
        assertThat(service.hasEvaluation(REGION + "|" + today + "|SUNSET")).isFalse();
    }

    @Test
    @DisplayName("rehydrateCacheOnStartup: corrupt entry does not prevent loading valid entries")
    void rehydrate_corruptEntry_doesNotBlockOthers() throws Exception {
        LocalDate today = LocalDate.now(UK_ZONE);

        CachedEvaluationEntity corrupt = new CachedEvaluationEntity();
        corrupt.setCacheKey(REGION + "|" + today + "|SUNSET");
        corrupt.setResultsJson("{corrupt");
        corrupt.setEvaluatedAt(Instant.now());

        CachedEvaluationEntity valid = new CachedEvaluationEntity();
        valid.setCacheKey(REGION + "|" + today + "|SUNRISE");
        valid.setResultsJson(objectMapper.writeValueAsString(List.of(
                new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Excellent"))));
        valid.setEvaluatedAt(Instant.now());

        when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                .thenReturn(List.of(corrupt, valid));

        service.rehydrateCacheOnStartup();

        // Corrupt entry skipped
        assertThat(service.getCachedScores(REGION, today, TargetType.SUNSET)).isEmpty();
        // Valid entry loaded
        assertThat(service.getCachedScores(REGION, today, TargetType.SUNRISE)).hasSize(1);
        assertThat(service.getCachedScores(REGION, today, TargetType.SUNRISE)
                .get("Bamburgh").rating()).isEqualTo(5);
    }

    // ── cache-health heartbeat + delete audit ───────────────────────────────────

    @Nested
    @DisplayName("Cache-health heartbeat and delete audit logging")
    class CacheHealthAndAudit {

        private static final Instant T_EARLY = Instant.parse("2026-06-05T14:11:00Z");
        private static final Instant T_LATE = Instant.parse("2026-06-06T14:08:00Z");

        private ListAppender<ILoggingEvent> appender;
        private Logger serviceLogger;

        @BeforeEach
        void attachAppender() {
            serviceLogger = (Logger) LoggerFactory.getLogger(BriefingEvaluationService.class);
            appender = new ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            serviceLogger.detachAppender(appender);
        }

        private boolean warnContains(String fragment) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .anyMatch(e -> e.getFormattedMessage().contains(fragment));
        }

        @Test
        @DisplayName("WARNs when maxEvaluatedAt moves backwards between heartbeats")
        void heartbeat_backwardsTimestamp_warns() {
            when(cachedEvaluationRepository.count()).thenReturn(100L, 100L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_LATE, T_EARLY);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(100L, 100L);

            service.recordCacheHealthHeartbeat(); // baseline at T_LATE
            service.recordCacheHealthHeartbeat(); // now at T_EARLY → backwards

            assertThat(warnContains("went BACKWARDS")).isTrue();
        }

        @Test
        @DisplayName("WARNs when row count drops beyond tolerance with no admin clear")
        void heartbeat_rowCountDrops_warns() {
            when(cachedEvaluationRepository.count()).thenReturn(100L, 50L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_LATE, T_LATE);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(100L, 50L);

            service.recordCacheHealthHeartbeat();
            service.recordCacheHealthHeartbeat();

            assertThat(warnContains("went BACKWARDS")).isTrue();
        }

        @Test
        @DisplayName("does NOT warn on normal forward movement")
        void heartbeat_forwardMovement_noWarn() {
            when(cachedEvaluationRepository.count()).thenReturn(100L, 110L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_EARLY, T_LATE);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(100L, 110L);

            service.recordCacheHealthHeartbeat();
            service.recordCacheHealthHeartbeat();

            assertThat(warnContains("went BACKWARDS")).isFalse();
        }

        @Test
        @DisplayName("does NOT warn for a drop within tolerance (boundary)")
        void heartbeat_dropWithinTolerance_noWarn() {
            // ROW_COUNT_DROP_TOLERANCE = 1, so a drop of exactly 1 must not warn.
            when(cachedEvaluationRepository.count()).thenReturn(100L, 99L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_LATE, T_LATE);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(100L, 99L);

            service.recordCacheHealthHeartbeat();
            service.recordCacheHealthHeartbeat();

            assertThat(warnContains("went BACKWARDS")).isFalse();
        }

        @Test
        @DisplayName("first heartbeat establishes a baseline without warning")
        void heartbeat_firstCall_noWarn() {
            when(cachedEvaluationRepository.count()).thenReturn(42L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_LATE);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(42L);

            service.recordCacheHealthHeartbeat();

            assertThat(warnContains("went BACKWARDS")).isFalse();
        }

        @Test
        @DisplayName("never throws into the caller when the repository fails")
        void heartbeat_repositoryThrows_swallowed() {
            when(cachedEvaluationRepository.count())
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> service.recordCacheHealthHeartbeat())
                    .doesNotThrowAnyException();
            assertThat(warnContains("heartbeat failed")).isTrue();
        }

        @Test
        @DisplayName("an admin clear resets the baseline so the next heartbeat does not warn")
        void clearCache_resetsBaseline_suppressesDropWarn() {
            // clearCache reads count() once for its audit line; the heartbeat reads it after.
            when(cachedEvaluationRepository.count()).thenReturn(100L, 5L);
            when(cachedEvaluationRepository.findMaxEvaluatedAt()).thenReturn(T_LATE);
            when(cachedEvaluationRepository.countDistinctCacheKeys()).thenReturn(5L);

            service.clearCache();                  // baseline reset to (0, null)
            service.recordCacheHealthHeartbeat();  // 5 rows vs baseline 0 → growth, no warn

            assertThat(warnContains("went BACKWARDS")).isFalse();
        }

        @Test
        @DisplayName("clearCache logs a WARN with the row count before deleting")
        void clearCache_logsAuditWarnWithCount() {
            when(cachedEvaluationRepository.count()).thenReturn(7L);

            service.clearCache();

            verify(cachedEvaluationRepository).deleteAll();
            assertThat(warnContains("cached_evaluation DELETE")).isTrue();
            assertThat(warnContains("removing 7 rows")).isTrue();
        }
    }
}
