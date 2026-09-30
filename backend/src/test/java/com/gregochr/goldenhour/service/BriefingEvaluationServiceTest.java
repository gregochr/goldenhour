package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import com.gregochr.goldenhour.service.evaluation.SupersedingDispositionService;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private SupersedingDispositionService supersedingDispositionService;

    // Must match the ObjectMapper bean AppConfig actually injects, JavaTimeModule and all.
    // A bare `new ObjectMapper()` cannot serialise the Instant that BriefingEvaluationResult now
    // carries, and persistToDb swallows JsonProcessingException with only a WARN — so a test on a
    // bare mapper does not fail loudly, it silently stops exercising the persistence path at all.
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private BriefingEvaluationService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 30);
    private static final String REGION = "Northumberland";
    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    @Nested
    @DisplayName("Region entry survives multiple batches writing the same key")
    class MultiBatchSameKey {

        private static final String KEY = REGION + "|2026-03-30|SUNRISE";

        /**
         * The defect this pins. The cache key is per REGION, but a region's slots routinely land in
         * more than one batch — coastal and inland are split per-location, and bluebell is its own
         * bucket. Three production regions mix coastal and inland enabled locations, so whichever
         * batch finished second was deleting the other half of the region on every cycle.
         */
        @Test
        @DisplayName("a second batch merging the same key keeps the first batch's locations")
        void secondBatchDoesNotDeleteTheFirst() {
            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "Coastal.")));

            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Penshaw Monument", 3, 60, 55, "Inland.")));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNRISE))
                    .containsOnlyKeys("Bamburgh", "Penshaw Monument");
        }

        @Test
        @DisplayName("the destructive write is what loses them — proving the defect was real")
        void writeFromBatchDeletesThePriorLocations() {
            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "Coastal.")));

            service.writeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Penshaw Monument", 3, 60, 55, "Inland.")));

            // Bamburgh is gone — a paid-for evaluation deleted by a batch that never carried it.
            // This is why production must never call writeFromBatch.
            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNRISE))
                    .containsOnlyKeys("Penshaw Monument");
        }

        @Test
        @DisplayName("a re-evaluated location is overwritten, not duplicated")
        void mergeOverwritesByLocationName() {
            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Penshaw Monument", 2, 30, 25, "Old.")));

            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Penshaw Monument", 4, 70, 65, "New.")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNRISE);
            assertThat(scores).containsOnlyKeys("Penshaw Monument");
            assertThat(scores.get("Penshaw Monument").rating()).isEqualTo(4);
            assertThat(scores.get("Penshaw Monument").summary()).isEqualTo("New.");
        }
    }

    @Nested
    @DisplayName("The per-location evaluatedAt is durable — the merge gate depends on it")
    class PerLocationStampIsDurable {

        private static final String KEY = REGION + "|2026-03-30|SUNRISE";

        /**
         * Why this is pinned here rather than left to the reader of the field's javadoc.
         *
         * <p>{@code EvaluationViewService.cachedWins} gates a cached rating against the latest
         * {@code forecast_evaluation} row on THIS stamp, because the region-level one is reset by
         * any write to the region and therefore made a retained entry look newer than the very
         * triage stand-down that had excluded it from re-evaluation (the ratchet, production
         * 2026-08-28). Every property that gate needs is a property of this class:
         *
         * <ul>
         *   <li>a merge must NOT restamp the locations it retained — otherwise the retained entry
         *       is handed a fresh time and the ratchet is back, one field over;</li>
         *   <li>the stamp must reach {@code results_json} and survive rehydration — otherwise the
         *       gate silently degrades to the old region-stamp behaviour after every restart, which
         *       is exactly the kind of failure that stays invisible for weeks.</li>
         * </ul>
         */
        @Test
        @DisplayName("a merge keeps the RETAINED location's own earlier stamp")
        void mergeDoesNotRestampRetainedLocations() {
            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "Overnight batch.")));
            Instant firstWrite = service.getCachedScores(REGION, DATE, TargetType.SUNRISE)
                    .get("Bamburgh").evaluatedAt();
            assertThat(firstWrite).isNotNull();

            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Penshaw Monument", 2, 20, 18, "Afternoon.")));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNRISE);
            // Bamburgh was not in the second batch, so it must still carry the instant it was
            // actually scored at. Restamping it here would hand the gate the afternoon's time for a
            // rating written overnight — precisely the substitution the region stamp was making.
            assertThat(scores.get("Bamburgh").evaluatedAt()).isEqualTo(firstWrite);
            assertThat(scores.get("Penshaw Monument").evaluatedAt()).isNotNull();
        }

        @Test
        @DisplayName("the stamp reaches results_json and survives a restart")
        void stampSurvivesPersistenceAndRehydration() throws Exception {
            service.mergeFromBatch(KEY, List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "Overnight batch.")));
            Instant written = service.getCachedScores(REGION, DATE, TargetType.SUNRISE)
                    .get("Bamburgh").evaluatedAt();

            ArgumentCaptor<CachedEvaluationEntity> captor =
                    ArgumentCaptor.forClass(CachedEvaluationEntity.class);
            verify(cachedEvaluationRepository).save(captor.capture());
            String persistedJson = captor.getValue().getResultsJson();

            // Rehydrate a FRESH service from that exact JSON — the restart. The date must be one
            // rehydrateCacheOnStartup will load, and it derives "today" from the wall clock, so the
            // key is rebuilt around it rather than around this class's fixed DATE.
            LocalDate today = LocalDate.now(UK_ZONE);
            CachedEvaluationEntity row = new CachedEvaluationEntity();
            row.setCacheKey(REGION + "|" + today + "|SUNRISE");
            row.setEvaluationDate(today);
            row.setTargetType("SUNRISE");
            row.setResultsJson(persistedJson);
            row.setEvaluatedAt(written);
            row.setUpdatedAt(Instant.now());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(today))
                    .thenReturn(List.of(row));

            BriefingEvaluationService restarted = new BriefingEvaluationService(
                    cachedEvaluationRepository, deltaLogRepository,
                    objectMapper, freshnessResolver, stabilitySnapshotProvider,
                    supersedingDispositionService);
            restarted.rehydrateCacheOnStartup();

            assertThat(restarted.getCachedScores(REGION, today, TargetType.SUNRISE)
                    .get("Bamburgh").evaluatedAt()).isEqualTo(written);
        }
    }

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                cachedEvaluationRepository, deltaLogRepository,
                objectMapper, freshnessResolver, stabilitySnapshotProvider,
                supersedingDispositionService);
        // Default: no existing DB cache entries
        org.mockito.Mockito.lenient()
                .when(cachedEvaluationRepository.findByCacheKey(any()))
                .thenReturn(java.util.Optional.empty());
        // Default: supersedingDispositionService is left UNSTUBBED here — Mockito's default answer
        // for a Set-returning method is an empty set, which is exactly "nothing superseded", so
        // every pre-existing test (and any new test that does not deliberately opt into the
        // superseded-result scenario) behaves exactly as it did before this check existed.
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

    @Test
    @DisplayName("getCachedEvaluatedAt returns empty when no cache entry exists")
    void getCachedEvaluatedAt_emptyWhenAbsent() {
        assertThat(service.getCachedEvaluatedAt(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("getCachedEvaluatedAt returns the write instant after writeFromBatch")
    void getCachedEvaluatedAt_presentAfterWrite() {
        LocalDate date = LocalDate.of(2026, 4, 7);
        Instant before = Instant.now();
        service.writeFromBatch("North East|2026-04-07|SUNRISE", List.of(
                new BriefingEvaluationResult("Durham", 4, 72, 65, "Good conditions")));

        Optional<Instant> evaluatedAt =
                service.getCachedEvaluatedAt("North East", date, TargetType.SUNRISE);
        assertThat(evaluatedAt).isPresent();
        // The stamp is the moment the batch results were written, not a downstream request time.
        assertThat(evaluatedAt.get()).isAfterOrEqualTo(before);
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
                            "Raking fell light")),
                    Map.of("Rannerdale", BluebellExposure.OPEN_FELL));

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
                            "Soft canopy light")),
                    Map.of("Bluebell Wood", BluebellExposure.WOODLAND));

            Map<String, BriefingEvaluationResult> scores =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET);
            BriefingEvaluationResult merged = scores.get("Bluebell Wood");
            assertThat(merged.rating()).isEqualTo(4);
            assertThat(merged.fierySkyPotential()).isNull();
            assertThat(merged.summary()).contains("Bright still light");
        }

        @Test
        @DisplayName("WOODLAND exposure with a stale prior sky entry does NOT recombine — the "
                + "location's actual exposure decides, never the shape of the cache")
        void woodlandExposure_withStalePriorSkyEntry_doesNotRecombine() {
            // A bluebell location whose exposure is WOODLAND but which is NOT isWoodlandOnly() —
            // it also carries a sky-eligible LocationType (e.g. LANDSCAPE), so it is still
            // sky-scored off-season. A stale, months-old pre-season sky entry is still sitting in
            // the cache when bluebell season starts and the first bluebell mini-batch lands.
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Emsworthy Mire", 2, 30, 25,
                            "Stale pre-season sky forecast")));

            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Emsworthy Mire", 5, null, null,
                            "Bright still light if they are in flower", null, null,
                            "Soft canopy light")),
                    Map.of("Emsworthy Mire", BluebellExposure.WOODLAND));

            BriefingEvaluationResult merged = service.getCachedScores(
                    REGION, DATE, TargetType.SUNSET).get("Emsworthy Mire");
            // The bluebell rating stands alone: WOODLAND is never averaged with sky, regardless
            // of what a prior cache entry happens to look like (regression guard — this used to
            // be inferred from `existing.fierySkyPotential() != null` instead of the location's
            // own exposure, and would have averaged here: round(avg(2, 5)) = 4, wrongly).
            assertThat(merged.rating()).isEqualTo(5);
            assertThat(merged.fierySkyPotential()).isNull();
            assertThat(merged.summary()).contains("Bright still light");
        }

        @Test
        @DisplayName("missing/null exposure defaults to not-WOODLAND (averages), matching "
                + "RatingCombiner.selectRatingPeers's own default")
        void missingExposure_defaultsToAveraging() {
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 3, 60, 55, "sky")));

            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 5, null, null, "bluebell",
                            null, null, null)),
                    Map.of());

            // round(avg(3, 5)) = 4 — averaged, as OPEN_FELL would be.
            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)
                    .get("Rannerdale").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("OPEN_FELL merge preserves the region's other sky locations")
        void openFell_preservesOtherSkyLocations() {
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 2, 40, 35, "sky"),
                    new BriefingEvaluationResult("Buttermere", 4, 75, 70, "great sky")));

            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 4, null, null, "bluebell",
                            null, null, null)),
                    Map.of("Rannerdale", BluebellExposure.OPEN_FELL));

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
            assertThat(service.recombineBluebell(sky, bluebell, BluebellExposure.OPEN_FELL)
                    .rating()).isEqualTo(5);
            // A null prior (woodland) returns the bluebell unchanged.
            assertThat(service.recombineBluebell(null, bluebell, BluebellExposure.OPEN_FELL))
                    .isSameAs(bluebell);
        }

        @Test
        @DisplayName("recombineBluebell never averages a WOODLAND exposure, even with a "
                + "present prior sky entry")
        void recombineBluebell_woodlandNeverAverages() {
            BriefingEvaluationResult sky =
                    new BriefingEvaluationResult("X", 4, 70, 65, "sky");
            BriefingEvaluationResult bluebell =
                    new BriefingEvaluationResult("X", 5, null, null, "bb", null, null, null);
            assertThat(service.recombineBluebell(sky, bluebell, BluebellExposure.WOODLAND))
                    .isSameAs(bluebell);
        }

        @Test
        @DisplayName("recombineBluebell never forwards the prior sky entry's skyRating — the "
                + "blend's own arithmetic can no longer be explained by it")
        void recombineBluebell_neverForwardsSkyRating() {
            // A coastal OPEN_FELL site: `existing` is itself avg(SKY, TIDE) from the sky+tide
            // combine, and `buildBluebellResult`'s own combine also carries the tide context, so
            // this blend's `averaged` is avg(avg(SKY,TIDE), avg(TIDE,BLUEBELL)) — TIDE enters
            // twice. Forwarding `existing.skyRating()` (SKY alone) here would put a "sky N★" clause
            // beside a star this figure no longer accounts for, so it must be null, not carried.
            BriefingEvaluationResult skyWithSkyRating = new BriefingEvaluationResult(
                    "X", 3, 70, 65, "sky", null, null, null, null, 4);
            BriefingEvaluationResult bluebell =
                    new BriefingEvaluationResult("X", 5, null, null, "bb", null, null, null);
            assertThat(service.recombineBluebell(skyWithSkyRating, bluebell, BluebellExposure.OPEN_FELL)
                    .skyRating()).isNull();
        }

        // ── forced provenance survives recombination (round 10, P1-A) ──

        @Test
        @DisplayName("forced sky + forced bluebell, same cycle → combined result is forced")
        void recombineBluebell_forcedSkyAndForcedBluebell_combinedIsForced() {
            // A same-cycle OPEN_FELL pair: both tasks share ForecastTaskCollector's one loop-local
            // `forced` boolean, so both sides carry true here.
            BriefingEvaluationResult forcedSky =
                    new BriefingEvaluationResult("X", 4, 70, 65, "sky").withForced(true);
            BriefingEvaluationResult forcedBluebell =
                    new BriefingEvaluationResult("X", 5, null, null, "bb", null, null, null)
                            .withForced(true);
            assertThat(service.recombineBluebell(forcedSky, forcedBluebell, BluebellExposure.OPEN_FELL)
                    .forced()).isTrue();
        }

        @Test
        @DisplayName("forced bluebell + ordinary sky (bluebell-then-sky arrival order at the "
                + "combiner) → combined result is forced")
        void recombineBluebell_forcedBluebellOrdinarySky_combinedIsForced() {
            BriefingEvaluationResult ordinarySky =
                    new BriefingEvaluationResult("X", 3, 60, 55, "sky");
            BriefingEvaluationResult forcedBluebell =
                    new BriefingEvaluationResult("X", 5, null, null, "bb", null, null, null)
                            .withForced(true);
            assertThat(service.recombineBluebell(ordinarySky, forcedBluebell, BluebellExposure.OPEN_FELL)
                    .forced()).isTrue();
        }

        @Test
        @DisplayName("ordinary sky + ordinary bluebell → combined result is not forced")
        void recombineBluebell_ordinaryBoth_combinedIsNotForced() {
            BriefingEvaluationResult ordinarySky =
                    new BriefingEvaluationResult("X", 3, 60, 55, "sky");
            BriefingEvaluationResult ordinaryBluebell =
                    new BriefingEvaluationResult("X", 4, null, null, "bb", null, null, null);
            assertThat(service.recombineBluebell(ordinarySky, ordinaryBluebell, BluebellExposure.OPEN_FELL)
                    .forced()).isFalse();
        }

        @Test
        @DisplayName("forced sky from cycle N + ordinary bluebell arriving in cycle N+1 → NOT "
                + "combined (a genuine cross-cycle mismatch) — the bluebell stands alone, unforced")
        void recombineBluebell_forcedSkyCycleN_ordinaryBluebellCycleNPlus1_exemptionEnds() {
            // Round 12: cross-cycle is now told apart by submittedAt, not by argument position —
            // this test predates that mechanism and originally carried no timestamps at all (both
            // null read as "same cycle, unknown" and combined via OR, which happened to still
            // clear the exemption here only by coincidence — a sibling case broke exactly this
            // way, see BriefingEvaluationResultTest). Real, differing instants now encode what the
            // test's name always claimed: existing = the cache's prior sky entry, forced when it
            // was written in an earlier cycle; bluebell = the just-arrived, ordinary result from a
            // LATER, genuinely different cycle. A cross-cycle bluebell is not combined with the
            // older sky entry at all (see recombineBluebell's own javadoc) — it stands alone,
            // unforced, which is the same end result the exemption-ending story described, reached
            // by the correct mechanism.
            Instant cycleN = Instant.parse("2026-03-30T01:05:00Z");
            Instant cycleNPlus1 = Instant.parse("2026-03-30T14:04:00Z");
            BriefingEvaluationResult forcedSkyFromEarlierCycle =
                    new BriefingEvaluationResult("X", 3, 60, 55, "sky").withForced(true)
                            .withSubmittedAt(cycleN);
            BriefingEvaluationResult ordinaryBluebellThisCycle =
                    new BriefingEvaluationResult("X", 4, null, null, "bb", null, null, null)
                            .withSubmittedAt(cycleNPlus1);
            assertThat(service.recombineBluebell(
                    forcedSkyFromEarlierCycle, ordinaryBluebellThisCycle, BluebellExposure.OPEN_FELL)
                    .forced()).isFalse();
        }

        @Test
        @DisplayName("ordinary sky from cycle N + forced bluebell arriving in cycle N+1 → NOT "
                + "combined (a genuine cross-cycle mismatch) — the bluebell stands alone, forced")
        void recombineBluebell_ordinarySkyCycleN_forcedBluebellCycleNPlus1_combinedIsForced() {
            // Real, differing instants — see the sibling test's comment above for why.
            Instant cycleN = Instant.parse("2026-03-30T01:05:00Z");
            Instant cycleNPlus1 = Instant.parse("2026-03-30T14:04:00Z");
            BriefingEvaluationResult ordinarySkyFromEarlierCycle =
                    new BriefingEvaluationResult("X", 3, 60, 55, "sky").withSubmittedAt(cycleN);
            BriefingEvaluationResult forcedBluebellThisCycle =
                    new BriefingEvaluationResult("X", 4, null, null, "bb", null, null, null)
                            .withForced(true).withSubmittedAt(cycleNPlus1);
            assertThat(service.recombineBluebell(
                    ordinarySkyFromEarlierCycle, forcedBluebellThisCycle, BluebellExposure.OPEN_FELL)
                    .forced()).isTrue();
        }

        @Test
        @DisplayName("OPEN_FELL keeps the SKY entry's write time — it is mostly the sky entry")
        void openFell_keepsThePriorSkyWriteTime() {
            // The composite returns the prior sky entry's prose, potentials and headline with only
            // the rating blended, so stamping it with this merge's clock would let hours-old sky
            // narrative outrank a stand-down written in between. `EvaluationViewService` gates on
            // this field, so the lie would be load-bearing: the triage ratchet, entered through the
            // bluebell door. WOODLAND is genuinely new content and is stamped now — the test below.
            service.writeFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 3, 60, 55, "Overnight sky.")));
            Instant skyWrite = service.getCachedScores(REGION, DATE, TargetType.SUNSET)
                    .get("Rannerdale").evaluatedAt();

            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Rannerdale", 5, null, null, "bluebell",
                            null, null, null)),
                    Map.of("Rannerdale", BluebellExposure.OPEN_FELL));

            BriefingEvaluationResult merged = service.getCachedScores(
                    REGION, DATE, TargetType.SUNSET).get("Rannerdale");
            assertThat(merged.rating()).isEqualTo(4);
            assertThat(merged.evaluatedAt()).isEqualTo(skyWrite);
        }

        @Test
        @DisplayName("WOODLAND is new content, so it IS stamped with this write")
        void woodland_isStampedWithThisWrite() {
            service.mergeBluebellFromBatch(cacheKey, List.of(
                    new BriefingEvaluationResult("Bluebell Wood", 4, null, null, "bluebell",
                            null, null, null)),
                    Map.of("Bluebell Wood", BluebellExposure.WOODLAND));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)
                    .get("Bluebell Wood").evaluatedAt()).isNotNull();
        }
    }

    // ── submission order decides writes and combination, never arrival order (round 12) ──

    @Nested
    @DisplayName("every cached_evaluation write compares SUBMISSION order, never ARRIVAL order")
    class SubmissionOrderStaleness {

        private final String cacheKey = REGION + "|" + DATE + "|SUNSET";

        // Nightly ~01:05, intraday ~14:04 — the two real cycles this class's own javadoc names.
        private static final Instant NIGHTLY = Instant.parse("2026-03-30T01:05:00Z");
        private static final Instant INTRADAY = Instant.parse("2026-03-30T14:04:00Z");

        private static BriefingEvaluationResult sky(String location, int rating, boolean forced,
                Instant submittedAt) {
            BriefingEvaluationResult r = new BriefingEvaluationResult(
                    location, rating, 70, 65, "sky-" + rating).withSubmittedAt(submittedAt);
            return forced ? r.withForced(true) : r;
        }

        private static BriefingEvaluationResult bluebell(String location, int rating,
                boolean forced, Instant submittedAt) {
            BriefingEvaluationResult r = new BriefingEvaluationResult(
                    location, rating, null, null, "bb-" + rating, null, null, null)
                    .withSubmittedAt(submittedAt);
            return forced ? r.withForced(true) : r;
        }

        @Test
        @DisplayName("Codex's case: forced bluebell submitted at the nightly cycle, ordinary sky "
                + "submitted at the later intraday cycle, sky arrives first, bluebell arrives "
                + "second — the stored result is the sky result alone, NOT forced, and the region "
                + "gets no exemption — must fail against 9761f365")
        void forcedBluebellFromOlderCycle_rejectedAsStaleAfterNewerSkyArrives() {
            service.mergeFromBatch(cacheKey, List.of(sky("X", 3, false, INTRADAY)));
            service.mergeBluebellFromBatch(cacheKey,
                    List.of(bluebell("X", 5, true, NIGHTLY)),
                    Map.of("X", BluebellExposure.OPEN_FELL));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(3);
            assertThat(stored.forced()).isFalse();
            assertThat(stored.summary()).isEqualTo("sky-3");
        }

        @Test
        @DisplayName("the mirror: ordinary bluebell submitted at the nightly cycle, forced sky "
                + "submitted at the intraday cycle, sky arrives first — the stored result is the "
                + "forced sky result; the late bluebell is rejected as stale")
        void ordinaryBluebellFromOlderCycle_rejectedAsStaleAfterForcedSkyArrives() {
            service.mergeFromBatch(cacheKey, List.of(sky("X", 4, true, INTRADAY)));
            service.mergeBluebellFromBatch(cacheKey,
                    List.of(bluebell("X", 2, false, NIGHTLY)),
                    Map.of("X", BluebellExposure.OPEN_FELL));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.forced()).isTrue();
            assertThat(stored.summary()).isEqualTo("sky-4");
        }

        @Test
        @DisplayName("same-cycle pair (equal submittedAt): combined, forced because the BLUEBELL "
                + "half is, and the combined result carries the shared cycle's instant")
        void sameCycle_forcedBluebell_combinedAndForced() {
            BriefingEvaluationResult combined = service.recombineBluebell(
                    sky("X", 3, false, NIGHTLY), bluebell("X", 5, true, NIGHTLY),
                    BluebellExposure.OPEN_FELL);
            assertThat(combined.rating()).isEqualTo(4); // avg(3, 5) = 4
            assertThat(combined.forced()).isTrue();
            assertThat(combined.submittedAt()).isEqualTo(NIGHTLY);
        }

        @Test
        @DisplayName("same-cycle pair (equal submittedAt): combined, forced because the SKY half "
                + "is — either half forces the combination, matching the mirror case above")
        void sameCycle_forcedSky_combinedAndForced() {
            BriefingEvaluationResult combined = service.recombineBluebell(
                    sky("X", 3, true, NIGHTLY), bluebell("X", 5, false, NIGHTLY),
                    BluebellExposure.OPEN_FELL);
            assertThat(combined.rating()).isEqualTo(4);
            assertThat(combined.forced()).isTrue();
            assertThat(combined.submittedAt()).isEqualTo(NIGHTLY);
        }

        @Test
        @DisplayName("newer-but-different-cycle bluebell does not combine with an older sky entry "
                + "— it stands alone, exactly the pre-existing race, until its own cycle's sky "
                + "arrives")
        void differentCycle_newerBluebell_standsAloneNotCombined() {
            BriefingEvaluationResult combined = service.recombineBluebell(
                    sky("X", 3, true, NIGHTLY), bluebell("X", 5, false, INTRADAY),
                    BluebellExposure.OPEN_FELL);
            // Not stale (INTRADAY > NIGHTLY) so it IS written — but not combined: no averaging,
            // no sky narrative, bluebell stands exactly as it arrived.
            assertThat(combined.rating()).isEqualTo(5);
            assertThat(combined.summary()).isEqualTo("bb-5");
            assertThat(combined.forced()).isFalse();
        }

        @Test
        @DisplayName("the wider defect, pinned generally: a late ORDINARY sky result from an "
                + "older cycle arriving after a newer sky result is rejected — the newer RATING "
                + "is kept, not merely the newer forced mark")
        void lateOlderOrdinarySkyResult_rejectedAfterNewerSkyResult_ratingProtected() {
            service.mergeFromBatch(cacheKey, List.of(sky("X", 4, false, INTRADAY)));
            // A delayed nightly-cycle batch, carrying a worse rating, finally completes.
            service.mergeFromBatch(cacheKey, List.of(sky("X", 1, false, NIGHTLY)));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.summary()).isEqualTo("sky-4");
        }

        @Test
        @DisplayName("the same for woodland: a late older-cycle woodland result is rejected after "
                + "a newer one has already landed")
        void lateOlderWoodlandResult_rejectedAfterNewerWoodlandResult() {
            BriefingEvaluationResult newerWoodland = new BriefingEvaluationResult(
                    "Bluebell Wood", 4, null, null, "newer", null, null, null)
                    .withSubmittedAt(INTRADAY);
            BriefingEvaluationResult olderWoodland = new BriefingEvaluationResult(
                    "Bluebell Wood", 1, null, null, "older", null, null, null)
                    .withSubmittedAt(NIGHTLY);

            service.mergeWoodlandFromBatch(cacheKey, List.of(newerWoodland));
            service.mergeWoodlandFromBatch(cacheKey, List.of(olderWoodland));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("Bluebell Wood");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.summary()).isEqualTo("newer");
        }

        @Test
        @DisplayName("the same for a bluebell-only (WOODLAND-exposure) site: a late older-cycle "
                + "bluebell result is rejected after a newer one has already landed")
        void lateOlderBluebellOnlyResult_rejectedAfterNewerBluebellOnlyResult() {
            service.mergeBluebellFromBatch(cacheKey,
                    List.of(bluebell("Bluebell Wood", 4, false, INTRADAY)),
                    Map.of("Bluebell Wood", BluebellExposure.WOODLAND));
            service.mergeBluebellFromBatch(cacheKey,
                    List.of(bluebell("Bluebell Wood", 1, false, NIGHTLY)),
                    Map.of("Bluebell Wood", BluebellExposure.WOODLAND));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("Bluebell Wood");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.summary()).isEqualTo("bb-4");
        }

        @Test
        @DisplayName("a synchronous result written after an older batch was submitted but before "
                + "it completed, then the batch completes late — the sync result wins, because "
                + "its SUBMISSION instant (when the admin pressed the button) is newer, whatever "
                + "order the two calls actually landed in")
        void syncResultNewerThanPendingBatch_winsWhenBatchLateArrives() {
            // The batch was SUBMITTED first (NIGHTLY) but is slow; an admin's hand-started sync
            // evaluation is submitted and completes immediately at INTRADAY, well before the
            // slow batch's own result eventually arrives.
            BriefingEvaluationResult syncResult = sky("X", 5, false, INTRADAY);
            service.mergeFromBatch(cacheKey, List.of(syncResult));

            // The slow batch, submitted BEFORE the sync call, only now completes and arrives.
            service.mergeFromBatch(cacheKey, List.of(sky("X", 2, false, NIGHTLY)));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(5);
            assertThat(stored.summary()).isEqualTo("sky-5");
        }

        @Test
        @DisplayName("legacy stored row with no submission instant: the incoming result always "
                + "wins, whatever its own submittedAt")
        void legacyStoredRowWithNoSubmittedAt_incomingWins() {
            BriefingEvaluationResult legacyStored =
                    new BriefingEvaluationResult("X", 2, 40, 35, "legacy");
            service.mergeFromBatch(cacheKey, List.of(legacyStored));

            service.mergeFromBatch(cacheKey, List.of(sky("X", 4, false, NIGHTLY)));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.summary()).isEqualTo("sky-4");
        }

        @Test
        @DisplayName("incoming result with no submission instant: treated as today's behaviour — "
                + "it still wins over an older stored result with a known instant")
        void incomingWithNoSubmittedAt_stillWinsOverStoredWithKnownInstant() {
            service.mergeFromBatch(cacheKey, List.of(sky("X", 2, false, NIGHTLY)));

            // A fixture or a not-yet-migrated writer producing a result with no submittedAt at
            // all — treated as "unknown, no comparison possible", the pre-round-12 behaviour.
            BriefingEvaluationResult noInstant =
                    new BriefingEvaluationResult("X", 4, 70, 65, "no-instant");
            service.mergeFromBatch(cacheKey, List.of(noInstant));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.summary()).isEqualTo("no-instant");
        }
    }

    // ── a result superseded by a later DECISION reaches no sink (round 14 wiring) ──
    //
    // The supersession ALGORITHM (the discriminator, the disposition allow-list, the anchor-run
    // shape, query counts) is tested in full in SupersedingDispositionServiceTest — that class owns
    // the logic. These tests only prove BriefingEvaluationService consults it correctly: whatever
    // supersededLocations() reports, mergeFromBatch/mergeWoodlandFromBatch/mergeBluebellFromBatch
    // must skip exactly those locations and write everything else normally.

    @Nested
    @DisplayName("a result the SupersedingDispositionService reports as superseded reaches no sink")
    class SupersededByLaterRun {

        private final String cacheKey = REGION + "|" + DATE + "|SUNSET";
        private static final Instant SUBMITTED_AT = Instant.parse("2026-03-30T01:05:00Z");

        private static BriefingEvaluationResult sky(String location, int rating, boolean forced,
                Instant submittedAt) {
            BriefingEvaluationResult r = new BriefingEvaluationResult(
                    location, rating, 70, 65, "sky-" + rating).withSubmittedAt(submittedAt);
            return forced ? r.withForced(true) : r;
        }

        @Test
        @DisplayName("reported superseded: not written; the cache never carries the slot at all")
        void reportedSuperseded_notWritten() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of("X"));

            service.mergeFromBatch(cacheKey, List.of(sky("X", 3, false, SUBMITTED_AT)));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).doesNotContainKey("X");
        }

        @Test
        @DisplayName("reported superseded and forced=true: still rejected — a superseded result "
                + "buys the region no exemption, because nothing about it is ever written")
        void reportedSupersededForcedResult_rejectedAndGrantsNoExemption() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of("X"));

            service.mergeFromBatch(cacheKey, List.of(sky("X", 5, true, SUBMITTED_AT)));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).doesNotContainKey("X");
        }

        @Test
        @DisplayName("not reported superseded: written normally, forced mark preserved")
        void notReportedSuperseded_writtenNormally() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of());

            service.mergeFromBatch(cacheKey, List.of(sky("X", 4, true, SUBMITTED_AT)));

            BriefingEvaluationResult stored =
                    service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("X");
            assertThat(stored).isNotNull();
            assertThat(stored.rating()).isEqualTo(4);
            assertThat(stored.forced()).isTrue();
        }

        @Test
        @DisplayName("mergeFromBatch passes each result's own (locationName, submittedAt) pair and "
                + "the merge call's date/eventType through to the service unchanged")
        void passesLocatedSubmissionsThrough() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of());

            service.mergeFromBatch(cacheKey, List.of(sky("X", 3, false, SUBMITTED_AT)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SupersedingDispositionService.LocatedSubmission>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(supersedingDispositionService)
                    .supersededLocations(captor.capture(), eq(DATE), eq(TargetType.SUNSET));
            assertThat(captor.getValue()).containsExactly(
                    new SupersedingDispositionService.LocatedSubmission("X", SUBMITTED_AT));
        }

        @Test
        @DisplayName("a location reported superseded in a WOODLAND merge call is not written either")
        void woodlandMerge_reportedSuperseded_notWritten() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of("Bluebell Wood"));
            BriefingEvaluationResult woodland = new BriefingEvaluationResult(
                    "Bluebell Wood", 4, null, null, "wood", null, null, null)
                    .withSubmittedAt(SUBMITTED_AT);

            service.mergeWoodlandFromBatch(cacheKey, List.of(woodland));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET))
                    .doesNotContainKey("Bluebell Wood");
        }

        @Test
        @DisplayName("a location reported superseded in a BLUEBELL merge call is not written, and "
                + "recombineBluebell is never reached for it")
        void bluebellMerge_reportedSuperseded_notWritten() {
            when(supersedingDispositionService.supersededLocations(any(), eq(DATE), eq(TargetType.SUNSET)))
                    .thenReturn(Set.of("X"));
            BriefingEvaluationResult bluebell = new BriefingEvaluationResult(
                    "X", 5, null, null, "bb-5", null, null, null).withSubmittedAt(SUBMITTED_AT);

            service.mergeBluebellFromBatch(cacheKey, List.of(bluebell),
                    Map.of("X", BluebellExposure.WOODLAND));

            assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).doesNotContainKey("X");
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

    // ── skyRating (tide gate lift, 2026-09-18, docs/engineering/tide-window-plan.md §6 Q1) ────

    @Test
    @DisplayName("writeFromBatch: skyRating round-trips through the JSON alongside the combined rating")
    void writeFromBatch_skyRating_roundTrips() throws Exception {
        BriefingEvaluationResult withSky = new BriefingEvaluationResult(
                "Bamburgh", 3, 62, 58, "Tide sits well off the light", null, null, null, null, 4);
        String cacheKey = REGION + "|" + DATE + "|SUNRISE";

        service.writeFromBatch(cacheKey, List.of(withSky));

        ArgumentCaptor<CachedEvaluationEntity> captor =
                ArgumentCaptor.forClass(CachedEvaluationEntity.class);
        verify(cachedEvaluationRepository).save(captor.capture());
        List<BriefingEvaluationResult> roundTripped = objectMapper.readValue(
                captor.getValue().getResultsJson(),
                new TypeReference<List<BriefingEvaluationResult>>() { });

        assertThat(roundTripped).hasSize(1);
        assertThat(roundTripped.getFirst().rating()).isEqualTo(3);
        assertThat(roundTripped.getFirst().skyRating()).isEqualTo(4);
    }

    @Test
    @DisplayName("A pre-skyRating cache row (no such key in the JSON at all) deserialises to "
            + "skyRating = null — fail-soft, never a fabricated figure")
    void legacyJsonWithNoSkyRatingKey_deserialisesToNull() throws Exception {
        // The literal shape a row written before the tide gate lift takes: every field the
        // record already had, none of the fields added since.
        String legacyJson = "[{\"locationName\":\"Bamburgh\",\"rating\":4,"
                + "\"fierySkyPotential\":70,\"goldenHourPotential\":65,\"summary\":\"Clear skies\"}]";

        List<BriefingEvaluationResult> parsed = objectMapper.readValue(
                legacyJson, new TypeReference<List<BriefingEvaluationResult>>() { });

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().rating()).isEqualTo(4);
        assertThat(parsed.getFirst().skyRating()).isNull();
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
