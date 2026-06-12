package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.ResultContext;
import com.gregochr.goldenhour.service.pipeline.PipelineRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real-path integration test for the Pass 2 {@code forecast_score} dual-write.
 *
 * <p>Drives the production seam end-to-end against a real Postgres schema (Flyway-built, so the
 * unique constraint and FKs are the production ones): a canned batch response flows through the
 * REAL {@link ForecastResultHandler#parseBatchResponse} → real parser → real
 * {@code RatingCombiner} → real {@code ForecastScoreWriter} → {@code forecast_score}. Only the
 * tide-context augmentor is mocked (an upstream collaborator, like the canned JSON — NOT the
 * dual-write seam under test); the parser, combiner, writer, repository, transaction boundary,
 * and schema are all real. This is the test the disposition bug taught us to write: the seam is
 * exercised, not mocked.
 *
 * <p><b>Triage produces no rows — structurally, not by a test here.</b> A triaged candidate is
 * filtered upstream (it is never submitted to Claude), so it never yields a response and never
 * reaches {@code parseBatchResponse}/{@code buildResult}. There is no code path by which a triaged
 * candidate can reach this seam, so it can write no row.
 *
 * <p>Requires Docker (testcontainers Postgres) — skipped automatically where Docker is absent.
 */
class ForecastScoreDualWriteIntegrationTest extends IntegrationTestBase {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
    private static final TargetType SUNSET = TargetType.SUNSET;

    @Autowired
    private ForecastResultHandler forecastResultHandler;
    @Autowired
    private ForecastScoreRepository forecastScoreRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private RegionRepository regionRepository;
    @Autowired
    private PipelineRunRepository pipelineRunRepository;
    @Autowired
    private PipelineRunService pipelineRunService;

    /**
     * The tide-context augmentor is mocked so coastal scenarios can inject a known tide state
     * without seeding tide extremes. It is an upstream collaborator of the seam, not the seam:
     * inland scenarios never consult it (the location has no tide type).
     */
    @MockitoBean
    private ForecastDataAugmentor forecastDataAugmentor;

    @AfterEach
    void clearData() {
        // forecast_score is a child of pipeline_run + locations — delete it first.
        forecastScoreRepository.deleteAll();
        pipelineRunRepository.deleteAll();
        locationRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("inland scored evaluation lands SKY + FIERY_SKY + GOLDEN_HOUR with the run's "
            + "provenance and correct scores/key")
    void inlandScoredEvaluation_landsThreeComponentRows() {
        LocationEntity location = seedInlandLocation("Keswick", 54.60, -3.13);
        PipelineRunEntity run = pipelineRunService.startRun(CycleType.NIGHTLY);

        parseForecast(location, run.getId(), evalJson(3, 72, 68, "Clearing western sky"));

        ForecastScoreEntity sky = component(ForecastType.SKY, location);
        ForecastScoreEntity fiery = component(ForecastType.FIERY_SKY, location);
        ForecastScoreEntity golden = component(ForecastType.GOLDEN_HOUR, location);

        assertThat(sky.getScore()).isEqualTo(3);
        assertThat(sky.getSummary()).isEqualTo("Clearing western sky");
        assertThat(fiery.getScore()).isEqualTo(72);
        assertThat(fiery.getSummary()).isNull();
        assertThat(golden.getScore()).isEqualTo(68);
        // No TIDAL row for an inland location.
        assertThat(forecastScoreRepository
                .findComponent(ForecastType.TIDAL, location.getId(), DATE, SUNSET)).isEmpty();
        // Provenance + key on every row.
        assertThat(forecastScoreRepository.findAll()).allSatisfy(row -> {
            assertThat(row.getEvaluationDate()).isEqualTo(DATE);
            assertThat(row.getEventType()).isEqualTo(SUNSET);
            assertThat(row.getLocation().getId()).isEqualTo(location.getId());
            assertThat(row.getPipelineRunId()).isEqualTo(run.getId());
            assertThat(row.getEvaluatedAt()).isNotNull();
        });
        assertThat(forecastScoreRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("upsert: re-evaluating the same (type, location, date, event) keeps one row, "
            + "latest score wins")
    void reEvaluation_upsertsSameKey() {
        LocationEntity location = seedInlandLocation("Keswick", 54.60, -3.13);

        parseForecast(location, null, evalJson(2, 40, 45, "First pass"));
        parseForecast(location, null, evalJson(5, 90, 88, "Intraday re-run"));

        // Still exactly one row per type, carrying the latest evaluation's scores.
        assertThat(forecastScoreRepository.count()).isEqualTo(3);
        assertThat(component(ForecastType.SKY, location).getScore()).isEqualTo(5);
        assertThat(component(ForecastType.SKY, location).getSummary()).isEqualTo("Intraday re-run");
        assertThat(component(ForecastType.FIERY_SKY, location).getScore()).isEqualTo(90);
        assertThat(component(ForecastType.GOLDEN_HOUR, location).getScore()).isEqualTo(88);
    }

    @Test
    @DisplayName("coastal scored evaluation also lands a TIDAL row carrying its state clause")
    void coastalScoredEvaluation_landsTidalRow() {
        LocationEntity location = seedCoastalLocation("Berwick-Upon-Tweed", 55.77, -1.99);
        when(forecastDataAugmentor.deriveTideContext(any(LocationEntity.class), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.of(alignedSpringTide()));

        parseForecast(location, null, evalJson(3, 72, 68, "Clearing western sky over the harbour"));

        ForecastScoreEntity tidal = component(ForecastType.TIDAL, location);
        assertThat(tidal.getScore()).isEqualTo(5);
        assertThat(tidal.getSummary()).contains("Spring tide");
        // SKY + TIDAL + FIERY_SKY + GOLDEN_HOUR.
        assertThat(forecastScoreRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("sync/admin path: a null pipeline run id is written as NULL provenance")
    void nullPipelineRunId_writesNullProvenance() {
        LocationEntity location = seedInlandLocation("Keswick", 54.60, -3.13);

        parseForecast(location, null, evalJson(4, 60, 55, "Settled high pressure"));

        assertThat(forecastScoreRepository.findAll())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getPipelineRunId()).isNull());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Optional<BatchSuccess> parseForecast(LocationEntity location, Long pipelineRunId,
            String rawJson) {
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                "fc-" + location.getId() + "-2026-06-21-SUNSET", rawJson,
                new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        ForecastIdentity identity = new ForecastIdentity(location.getId(), DATE, SUNSET);
        // jobRunId null → no api_call_log FK dependency; pipelineRunId threaded as forecast_score provenance.
        ResultContext context = ResultContext.forBatch(
                null, "msgbatch_dualwrite", pipelineRunId, BatchTriggerSource.SCHEDULED);
        return forecastResultHandler.parseBatchResponse(location, identity, outcome, context);
    }

    private static String evalJson(int rating, int fierySky, int goldenHour, String summary) {
        return String.format(
                "{\"rating\":%d,\"fiery_sky\":%d,\"golden_hour\":%d,\"summary\":\"%s\"}",
                rating, fierySky, goldenHour, summary);
    }

    private ForecastScoreEntity component(ForecastType type, LocationEntity location) {
        return forecastScoreRepository.findComponent(type, location.getId(), DATE, SUNSET)
                .orElseThrow(() -> new AssertionError("expected a " + type + " row"));
    }

    private static TideContext alignedSpringTide() {
        TideSnapshot snapshot = new TideSnapshot(
                TideState.HIGH, null, null, null, null,
                true, null, null, LunarTideType.SPRING_TIDE, null, null, null);
        return new TideContext(snapshot, true);
    }

    private LocationEntity seedInlandLocation(String name, double lat, double lon) {
        return locationRepository.save(baseLocation(name, lat, lon).build());
    }

    private LocationEntity seedCoastalLocation(String name, double lat, double lon) {
        return locationRepository.save(baseLocation(name, lat, lon)
                .tideType(Set.of(TideType.HIGH))
                .build());
    }

    private LocationEntity.LocationEntityBuilder baseLocation(String name, double lat, double lon) {
        RegionEntity region = regionRepository.save(RegionEntity.builder()
                .name("Test Region " + name)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        return LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now());
    }
}
