package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForceSubmitBatchService}.
 *
 * <p>Post Pass 3.3.2 the service routes its JFDI work through
 * {@link EvaluationService#submit}; the engine owns request building, custom-id
 * generation, prompt-builder selection, and {@code ForecastBatchEntity}
 * persistence. Those concerns are tested in the engine-level test suites
 * ({@code EvaluationServiceImplTest}, {@code BatchRequestFactoryTest},
 * {@code BatchSubmissionServiceTest}). This class therefore focuses on the
 * service's own responsibilities: location filtering, data assembly via
 * {@code ForecastService.fetchWeatherAndTriage}, error / partial-failure
 * handling and return shape.
 */
@ExtendWith(MockitoExtension.class)
class ForceSubmitBatchServiceTest {

    @Mock
    private LocationService locationService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private EvaluationService evaluationService;

    private ForceSubmitBatchService service;

    @BeforeEach
    void setUp() {
        service = new ForceSubmitBatchService(
                locationService, forecastService, modelSelectionService, evaluationService, CLOCK);
    }

    /** Production's clock, so the existing cases keep exercising the real calendar. */
    private static final Clock CLOCK = Clock.systemUTC();

    // ── Record conditions for every place (Phase 1, owner decision 2026-09-30) ────────────────
    // The write moved to a single seam inside ForecastService.fetchWeatherAndTriage (a Codex P1
    // finding: the first cut of this phase put a write directly in this service's two entry
    // points and in ForecastTaskCollector, while collectRegionFilteredBatches and the
    // synchronous engine's runTriagePhase got none). forecastService is mocked in this test
    // class, so fetchWeatherAndTriage's real body — which now does the write — never runs here;
    // the seam itself is pinned in ForecastServiceTest, and this class keeps only the tests
    // below verifying submitJfdiBatch calls fetchWeatherAndTriage with the right
    // candidates, which is what makes the seam reachable from this service at all.

    // ── submitJfdiBatch: engine routing ───────────────────────────────────

    @Test
    @DisplayName("submitJfdiBatch builds tasks for each location × date × event "
            + "with WriteTarget.BRIEFING_CACHE via JFDI trigger")
    void submitJfdiBatch_validRegion_submitsThroughEngine() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.now(), TargetType.SUNSET,
                LocalDateTime.now(), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), any())).thenReturn(preEval);
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.JFDI)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_jfdi", 8));

        BatchSubmitResult result = service.submitJfdiBatch(List.of(7L));

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_jfdi");
        assertThat(result.requestCount()).isEqualTo(8);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.JFDI));
        // 1 location × 4 dates (T+0..T+3) × 2 events (SUNRISE+SUNSET) = 8 tasks
        assertThat(taskCaptor.getValue()).hasSize(8);
        assertThat(taskCaptor.getValue())
                .allMatch(t -> t.model() == EvaluationModel.HAIKU);
    }

    /**
     * The JFDI range is anchored on the UK civil date, because {@code ForecastService} measures the
     * horizon of every date it is handed on that calendar — and persists it. Pinned at the one
     * instant per day where the two disagree.
     */
    @Test
    @DisplayName("submitJfdiBatch asks for UK T+0..T+3, so no date it sends can be measured as past")
    void submitJfdiBatch_anchorsRangeOnTheUkCivilDate() {
        // 00:30 on 12 August in Europe/London (BST); still 23:30 on the 11th in UTC.
        //
        // Deliberately a year in the future, and this is load-bearing rather than arbitrary. The
        // sibling fixtures in ForecastWindowAnchorTest pin
        // 2026-08-11T23:30:00Z, and the mutation they defend against — ForecastHorizon's zone
        // constant — still flows through the injected clock, so any instant detects it. The
        // mutation *this* case defends against is different: reverting to LocalDate.now(UTC)
        // ignores the clock entirely and reads the real system date. A fixture whose UK date
        // happens to equal the real UTC date therefore agrees with the broken code by coincidence
        // — which is exactly what happened when this was first written against 2026-08-11 and
        // verified on 2026-08-12. A far-future instant cannot coincide with any real clock.
        Clock lateBstEvening = Clock.fixed(Instant.parse("2027-08-11T23:30:00Z"), ZoneOffset.UTC);
        LocalDate ukToday = LocalDate.of(2027, 8, 12);
        LocalDate ukYesterday = LocalDate.of(2027, 8, 11);

        ForceSubmitBatchService pinned = new ForceSubmitBatchService(
                locationService, forecastService, modelSelectionService, evaluationService, lateBstEvening);

        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);

        AtmosphericData data = mock(AtmosphericData.class);
        // Every argument matched exactly, per the file's own precedent in
        // forceSubmit_passesCorrectArgumentsToForecastService. tideAlignmentEnabled in particular
        // has to be eq(false): JFDI exists to bypass the gates, and matching it with any(Boolean)
        // would let a flip to true — which tide-triages every SEASCAPE location out of a
        // diagnostic batch — pass unnoticed.
        when(forecastService.fetchWeatherAndTriage(eq(loc), any(LocalDate.class),
                any(TargetType.class), eq(loc.getTideType()), eq(EvaluationModel.HAIKU), isNull()))
                .thenAnswer(inv -> new ForecastPreEvalResult(
                        false, null, data, loc, inv.getArgument(1), inv.getArgument(2),
                        LocalDateTime.now(), 270, 0,
                        EvaluationModel.HAIKU, loc.getTideType(), "key", null));
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.JFDI)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_jfdi", 8));

        pinned.submitJfdiBatch(List.of(7L));

        List<LocalDate> ukRange = List.of(ukToday, ukToday.plusDays(1),
                ukToday.plusDays(2), ukToday.plusDays(3));

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(forecastService, times(8)).fetchWeatherAndTriage(
                eq(loc), dateCaptor.capture(), any(TargetType.class), eq(loc.getTideType()),
                eq(EvaluationModel.HAIKU), isNull());

        // Sink 1 — forecast_evaluation. fetchWeatherAndTriage derives daysAhead from this date
        // and, on the triage branch, persists it.
        assertThat(dateCaptor.getAllValues()).containsOnly(ukRange.toArray(new LocalDate[0]));

        // Sink 2 — cached_evaluation, which is the rating the UI displays. The submitted tasks
        // carry the date independently of the captor above, and this is the sink the first draft
        // of this test missed entirely: the stub echoes the requested date back into the preEval,
        // so an implementation that built tasks from preEval.date() rather than from the loop
        // variable would satisfy every assertion above while this one still has to hold.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.JFDI));
        assertThat(taskCaptor.getValue()).extracting(EvaluationTask.Forecast::date)
                .containsOnly(ukRange.toArray(new LocalDate[0]));
        assertThat(taskCaptor.getValue()).extracting(EvaluationTask.Forecast::date)
                .doesNotContain(ukYesterday);

        // The property that actually broke, stated in the consumer's own terms rather than as a
        // date list: ForecastService derives daysAhead from these dates and persists it, along
        // with a confidence band derived from it. On the UTC anchor the first date measured as
        // T-1 — and ConfidenceDeriver.fromHorizon(-1) returns HIGH, so a day already over was
        // recorded as the most reliable kind of forecast.
        assertThat(dateCaptor.getAllValues())
                .allSatisfy(d -> assertThat(ForecastHorizon.daysAhead(d, lateBstEvening))
                        .isNotNegative());
    }

    @Test
    @DisplayName("submitJfdiBatch returns null when no eligible locations")
    void submitJfdiBatch_noEligibleLocations_returnsNull() {
        when(locationService.findAllEnabled()).thenReturn(List.of());

        BatchSubmitResult result = service.submitJfdiBatch(List.of(7L));

        assertThat(result).isNull();
        verifyNoInteractions(evaluationService);
    }

    @Test
    @DisplayName("submitJfdiBatch returns null when all data assembly fails")
    void submitJfdiBatch_allDataFails_returnsNull() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity loc = buildLocation(10L, "Bamburgh Castle", region);
        loc.setLocationType(Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE));
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo down"));

        BatchSubmitResult result = service.submitJfdiBatch(null);

        assertThat(result).isNull();
        verify(evaluationService, never()).submit(anyList(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    // ── Sky subjects only: neither route has a lane for a hide, a wood or a bluebell wood ──────

    private LocationEntity buildTypedLocation(Long id, String name, RegionEntity region,
            com.gregochr.goldenhour.entity.LocationType type) {
        LocationEntity loc = buildLocation(id, name, region);
        loc.setLocationType(Set.of(type));
        return loc;
    }

    private ForecastPreEvalResult preEvalFor(LocationEntity loc, AtmosphericData data) {
        return new ForecastPreEvalResult(
                false, null, data, loc,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 19, 30), 270, 1,
                EvaluationModel.HAIKU, loc.getTideType(), "key", null);
    }

    @Test
    @DisplayName("submitJfdiBatch still skips a hide and builds tasks for the sky location only")
    void submitJfdiBatch_regionWithSkyLocationAndHide_skipsTheHide() {
        RegionEntity region = buildRegion(7L, "Northumberland");
        LocationEntity sky = buildTypedLocation(10L, "Bamburgh Castle", region,
                com.gregochr.goldenhour.entity.LocationType.LANDSCAPE);
        LocationEntity hide = buildTypedLocation(11L, "Reserve Hide", region,
                com.gregochr.goldenhour.entity.LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide, sky));
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM))
                .thenReturn(EvaluationModel.HAIKU);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), any()))
                .thenReturn(preEvalFor(sky, mock(AtmosphericData.class)));
        when(evaluationService.submit(anyList(), eq(BatchTriggerSource.JFDI)))
                .thenReturn(new EvaluationHandle(null, "msgbatch_jfdi_sky", 8));

        service.submitJfdiBatch(List.of(7L));

        verify(forecastService, never()).fetchWeatherAndTriage(
                eq(hide), any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationTask.Forecast>> taskCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(evaluationService).submit(taskCaptor.capture(), eq(BatchTriggerSource.JFDI));
        assertThat(taskCaptor.getValue()).hasSize(8);
        assertThat(taskCaptor.getValue()).allMatch(t -> t.location() == sky);
    }

    private RegionEntity buildRegion(Long id, String name) {
        RegionEntity region = new RegionEntity();
        region.setId(id);
        region.setName(name);
        return region;
    }

    private LocationEntity buildLocation(Long id, String name, RegionEntity region) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(55.0)
                .lon(-1.5)
                .region(region)
                .enabled(true)
                .tideType(Set.of())
                .build();
    }
}
