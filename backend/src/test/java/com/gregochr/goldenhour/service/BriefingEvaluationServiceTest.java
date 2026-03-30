package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRefreshedEvent;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingEvaluationService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingEvaluationServiceTest {

    @Mock
    private LocationService locationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private JobRunService jobRunService;

    private BriefingEvaluationService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 30);
    private static final String REGION = "Northumberland";

    @BeforeEach
    void setUp() {
        service = new BriefingEvaluationService(
                locationService, briefingService, forecastService,
                modelSelectionService, jobRunService);
        // Default: all locations are colour locations
        org.mockito.Mockito.lenient().when(briefingService.isColourLocation(any())).thenReturn(true);
    }

    @Test
    @DisplayName("evaluateRegion evaluates GO/MARGINAL locations only")
    void evaluates_goAndMarginal_only() {
        LocationEntity goLoc = locationInRegion("Bamburgh", REGION);
        LocationEntity marginalLoc = locationInRegion("Dunstanburgh", REGION);
        LocationEntity standdownLoc = locationInRegion("Craster", REGION);

        when(locationService.findAllEnabled()).thenReturn(List.of(goLoc, marginalLoc, standdownLoc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        // Briefing with GO, MARGINAL, and STANDDOWN slots
        BriefingSlot goSlot = slot("Bamburgh", Verdict.GO);
        BriefingSlot marginalSlot = slot("Dunstanburgh", Verdict.MARGINAL);
        BriefingSlot standdownSlot = slot("Craster", Verdict.STANDDOWN);
        stubBriefing(List.of(goSlot, marginalSlot, standdownSlot));

        // ForecastService stubs — use any() matchers for reliability
        ForecastPreEvalResult preEval = nonTriagedResult(goLoc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good conditions"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        // Verify standdown location was never fetched — total calls should be 2 (GO + MARGINAL)
        verify(forecastService, org.mockito.Mockito.times(2))
                .fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any());

        // Verify cache populated
        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached).hasSize(2);
        assertThat(cached).containsKey("Bamburgh");
        assertThat(cached).containsKey("Dunstanburgh");
        assertThat(cached.get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Cache hit replays results without new Claude calls")
    void cacheHit_noCalls() {
        // Pre-populate cache
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        SseEmitter emitter1 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter1);

        // Second call should use cache
        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter2);

        // ForecastService should only be called once (first run)
        verify(forecastService).fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("BriefingRefreshedEvent clears cache")
    void eventClearsCache() {
        // Pre-populate cache
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isNotEmpty();

        service.onBriefingRefreshed(new BriefingRefreshedEvent(this));
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("getCachedScores returns empty map when no cache exists")
    void noCacheReturnsEmpty() {
        assertThat(service.getCachedScores("Unknown", DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Null briefing yields no evaluable locations")
    void nullBriefing_noEvaluations() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(briefingService.getCachedBriefing()).thenReturn(null);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        org.mockito.Mockito.verifyNoInteractions(forecastService);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Location without region is filtered out")
    void locationWithoutRegion_filtered() {
        LocationEntity noRegionLoc = LocationEntity.builder()
                .id(99L).name("Orphan").lat(55.0).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .region(null)
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(noRegionLoc));
        stubBriefing(List.of(slot("Orphan", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        org.mockito.Mockito.verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("Non-colour location (pure wildlife) is filtered out")
    void nonColourLocation_filtered() {
        LocationEntity wildlifeLoc = locationInRegion("Farne Islands", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(wildlifeLoc));
        when(briefingService.isColourLocation(any())).thenReturn(false);
        stubBriefing(List.of(slot("Farne Islands", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        org.mockito.Mockito.verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("No locations in region yields empty evaluation")
    void noLocationsInRegion_emptyEvaluation() {
        LocationEntity otherRegionLoc = locationInRegion("Whitby", "Yorkshire");
        when(locationService.findAllEnabled()).thenReturn(List.of(otherRegionLoc));
        stubBriefing(List.of(slot("Whitby", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        org.mockito.Mockito.verifyNoInteractions(forecastService);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Evaluation error continues to next location")
    void evaluationError_continuesProcessing() {
        LocationEntity failLoc = locationInRegion("Bamburgh", REGION);
        LocationEntity okLoc = locationInRegion("Dunstanburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(failLoc, okLoc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO), slot("Dunstanburgh", Verdict.MARGINAL)));

        // First call fails, second succeeds
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("API timeout"))
                .thenReturn(nonTriagedResult(okLoc));
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(3, 50, 45, "Decent"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        // Only the successful one should be cached
        assertThat(cached).hasSize(1);
        assertThat(cached).containsKey("Dunstanburgh");
        assertThat(cached).doesNotContainKey("Bamburgh");
    }

    @Test
    @DisplayName("Different date creates separate cache entry")
    void differentDate_separateCache() {
        LocalDate otherDate = LocalDate.of(2026, 3, 31);
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"))
                .thenReturn(evaluationEntity(2, 30, 25, "Poor"));

        // Stub briefing for both dates
        stubBriefingForDate(DATE, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter1 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter1);

        stubBriefingForDate(otherDate, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, otherDate, TargetType.SUNSET, emitter2);

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).hasSize(1);
        assertThat(service.getCachedScores(REGION, otherDate, TargetType.SUNSET)).hasSize(1);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)
                .get("Bamburgh").rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("SUNRISE targetType works independently of SUNSET")
    void sunriseTargetType_separateCache() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(5, 90, 85, "Spectacular"));

        stubBriefingForDateAndTarget(DATE, TargetType.SUNRISE, List.of(slot("Bamburgh", Verdict.GO)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNRISE, emitter);

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNRISE)).hasSize(1);
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Triaged location includes triage reason in summary")
    void triagedLocation_includesReasonInSummary() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult triagedPreEval = new ForecastPreEvalResult(
                true, "Heavy rain forecast", null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(triagedPreEval);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        BriefingEvaluationResult result =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET).get("Bamburgh");
        assertThat(result.summary()).contains("Heavy rain forecast");
        assertThat(result.fierySkyPotential()).isEqualTo(5);
        assertThat(result.goldenHourPotential()).isEqualTo(5);
    }

    @Test
    @DisplayName("clearCache is idempotent when empty")
    void clearCache_idempotentWhenEmpty() {
        service.clearCache();
        service.clearCache();
        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("clearCache removes all region entries")
    void clearCache_removesAllRegions() {
        LocationEntity loc1 = locationInRegion("Bamburgh", REGION);
        LocationEntity loc2 = locationInRegion("Whitby", "Yorkshire");
        when(locationService.findAllEnabled())
                .thenReturn(List.of(loc1))
                .thenReturn(List.of(loc2));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());

        ForecastPreEvalResult preEval = nonTriagedResult(loc1);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 70, 60, "Good"));

        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        stubBriefingForRegion("Yorkshire", List.of(slot("Whitby", Verdict.GO)));
        service.evaluateRegion("Yorkshire", DATE, TargetType.SUNSET, mock(SseEmitter.class));

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isNotEmpty();
        assertThat(service.getCachedScores("Yorkshire", DATE, TargetType.SUNSET)).isNotEmpty();

        service.clearCache();

        assertThat(service.getCachedScores(REGION, DATE, TargetType.SUNSET)).isEmpty();
        assertThat(service.getCachedScores("Yorkshire", DATE, TargetType.SUNSET)).isEmpty();
    }

    @Test
    @DisplayName("Cache hit calls emitter.complete()")
    void cacheHit_callsEmitterComplete() throws Exception {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult preEval = nonTriagedResult(loc);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(preEval);
        when(forecastService.evaluateAndPersist(any(), any()))
                .thenReturn(evaluationEntity(4, 72, 65, "Good"));

        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, mock(SseEmitter.class));

        // Cache hit path
        SseEmitter emitter2 = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter2);

        verify(emitter2).complete();
    }

    @Test
    @DisplayName("All STANDDOWN slots yield empty evaluation with no job run")
    void allStanddown_noJobRun() {
        LocationEntity loc = locationInRegion("Craster", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        stubBriefing(List.of(slot("Craster", Verdict.STANDDOWN)));

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        org.mockito.Mockito.verifyNoInteractions(forecastService);
        org.mockito.Mockito.verifyNoInteractions(modelSelectionService);
        org.mockito.Mockito.verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("Triaged location returns rating 1")
    void triagedLocationReturnsRating1() {
        LocationEntity loc = locationInRegion("Bamburgh", REGION);
        when(locationService.findAllEnabled()).thenReturn(List.of(loc));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any()))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        stubBriefing(List.of(slot("Bamburgh", Verdict.GO)));

        ForecastPreEvalResult triagedPreEval = new ForecastPreEvalResult(
                true, "Heavy rain", null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null);
        when(forecastService.fetchWeatherAndTriage(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(triagedPreEval);

        SseEmitter emitter = mock(SseEmitter.class);
        service.evaluateRegion(REGION, DATE, TargetType.SUNSET, emitter);

        Map<String, BriefingEvaluationResult> cached =
                service.getCachedScores(REGION, DATE, TargetType.SUNSET);
        assertThat(cached.get("Bamburgh").rating()).isEqualTo(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private LocationEntity locationInRegion(String name, String regionName) {
        RegionEntity region = RegionEntity.builder().id(1L).name(regionName).build();
        return LocationEntity.builder()
                .id((long) name.hashCode())
                .name(name)
                .lat(55.6)
                .lon(-1.7)
                .region(region)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .build();
    }

    private BriefingSlot slot(String locationName, Verdict verdict) {
        return new BriefingSlot(locationName,
                LocalDateTime.of(2026, 3, 30, 18, 30),
                verdict,
                new BriefingSlot.WeatherConditions(30, null, 20000, 70, 8.0, 6.0, 2, null),
                new BriefingSlot.TideInfo(null, false, null, null, false, false, null, null, null),
                List.of());
    }

    private void stubBriefing(List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, DATE, TargetType.SUNSET, slots);
    }

    private void stubBriefingForDate(LocalDate date, List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, date, TargetType.SUNSET, slots);
    }

    private void stubBriefingForDateAndTarget(LocalDate date, TargetType target,
            List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(REGION, date, target, slots);
    }

    private void stubBriefingForRegion(String regionName, List<BriefingSlot> slots) {
        stubBriefingForRegionDateTarget(regionName, DATE, TargetType.SUNSET, slots);
    }

    private void stubBriefingForRegionDateTarget(String regionName, LocalDate date,
            TargetType target, List<BriefingSlot> slots) {
        BriefingRegion region = new BriefingRegion(regionName, Verdict.GO, "Clear skies",
                List.of(), slots, 8.0, 6.0, null, null);
        BriefingEventSummary es = new BriefingEventSummary(
                target, List.of(region), List.of());
        BriefingDay day = new BriefingDay(date, List.of(es));
        DailyBriefingResponse resp = new DailyBriefingResponse(
                LocalDateTime.now(), "Headline", List.of(day), List.of(),
                null, null, false, false, 0);
        org.mockito.Mockito.lenient().when(briefingService.getCachedBriefing()).thenReturn(resp);
    }

    private ForecastPreEvalResult nonTriagedResult(LocationEntity loc) {
        return new ForecastPreEvalResult(
                false, null, null, loc, DATE, TargetType.SUNSET,
                null, null, 0, null, null, null);
    }

    private ForecastEvaluationEntity evaluationEntity(int rating, int fiery, int golden, String summary) {
        return ForecastEvaluationEntity.builder()
                .rating(rating)
                .fierySkyPotential(fiery)
                .goldenHourPotential(golden)
                .summary(summary)
                .build();
    }
}
