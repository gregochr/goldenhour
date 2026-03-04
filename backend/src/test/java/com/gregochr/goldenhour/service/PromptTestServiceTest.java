package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PromptTestResultRepository;
import com.gregochr.goldenhour.repository.PromptTestRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptTestService}.
 */
@ExtendWith(MockitoExtension.class)
class PromptTestServiceTest {

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private PromptTestRunRepository testRunRepository;
    @Mock
    private PromptTestResultRepository testResultRepository;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private ForecastService forecastService;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private SolarService solarService;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private GitInfoService gitInfoService;

    private PromptTestService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        service = new PromptTestService(locationRepository, testRunRepository,
                testResultRepository, openMeteoService, forecastService,
                evaluationService, solarService, costCalculator, exchangeRateService,
                gitInfoService);
    }

    private LocationEntity location(Long id, String name, Set<LocationType> types) {
        return LocationEntity.builder()
                .id(id).name(name).lat(54.77).lon(-1.57)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(types)
                .tideType(new HashSet<>())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AtmosphericData sampleAtmosphericData() {
        return new AtmosphericData("Test Location",
                LocalDateTime.of(2026, 3, 1, 17, 30), TargetType.SUNSET,
                20, 40, 30, 15000,
                new BigDecimal("3.5"), 270, new BigDecimal("0.0"),
                65, 2, 1200,
                new BigDecimal("350.0"), new BigDecimal("5.0"),
                new BigDecimal("2.0"), new BigDecimal("0.15"),
                8.0, 6.0, 10,
                null, null, null, null, null, null);
    }

    private EvaluationDetail sampleDetail() {
        return new EvaluationDetail(
                new SunsetEvaluation(4, 65, 70, "Good conditions"),
                "prompt text", "{\"rating\":4}", 1500L,
                new TokenUsage(400, 80, 200, 100));
    }

    private void stubGitInfo() {
        lenient().when(gitInfoService.getCommitHash()).thenReturn("abc1234");
        lenient().when(gitInfoService.getCommitAbbrev()).thenReturn("abc1234");
        lenient().when(gitInfoService.getCommitDate()).thenReturn(LocalDateTime.of(2026, 3, 1, 10, 0));
        lenient().when(gitInfoService.isDirty()).thenReturn(false);
        lenient().when(gitInfoService.getBranch()).thenReturn("main");
    }

    /**
     * Stubs testRunRepository.save() to assign an ID, and findById() to return the same
     * entity. This is needed because runTest()/replayTest() call startRun() then
     * executeRun(), and executeRun() re-fetches the run via findById().
     *
     * @param seedRuns optional pre-existing runs to seed into the mock (e.g. parent runs)
     */
    private void stubRunRepository(PromptTestRunEntity... seedRuns) {
        java.util.Map<Long, PromptTestRunEntity> runs = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(1);
        for (PromptTestRunEntity seed : seedRuns) {
            if (seed.getId() != null) {
                runs.put(seed.getId(), seed);
                if (seed.getId() >= idSeq.get()) {
                    idSeq.set(seed.getId() + 1);
                }
            }
        }
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            PromptTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.getAndIncrement());
            }
            runs.put(e.getId(), e);
            return e;
        });
        lenient().when(testRunRepository.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(runs.get(id));
        });
    }

    // --- runTest tests ---

    @Test
    @DisplayName("runTest with no colour locations creates run with 0 processed")
    void runTest_noColourLocations() {
        LocationEntity wild = location(1L, "Bird Reserve", Set.of(LocationType.WILDLIFE));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(wild));
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));
        stubGitInfo();
        stubRunRepository();

        PromptTestRunEntity result = service.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM);

        assertThat(result.getLocationsCount()).isEqualTo(0);
        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        verify(testResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("runTest evaluates one colour location with chosen model for both SUNRISE+SUNSET")
    void runTest_oneLocation() {
        LocationEntity loc = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        stubRunRepository();
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(sampleDetail());
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubGitInfo();

        // SHORT_TERM = 3 dates × 2 target types (SUNRISE+SUNSET) × 1 location = 6 evaluations
        PromptTestRunEntity result = service.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM);

        assertThat(result.getLocationsCount()).isEqualTo(6);
        assertThat(result.getSucceeded()).isEqualTo(6);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getTotalCostPence()).isEqualTo(300);
        assertThat(result.getTotalCostMicroDollars()).isEqualTo(32400L);
        assertThat(result.getGitCommitHash()).isEqualTo("abc1234");
        assertThat(result.getGitBranch()).isEqualTo("main");
        verify(testResultRepository, times(6)).save(any(PromptTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest stamps git info on the run entity")
    void runTest_stampsGitInfo() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        stubGitInfo();
        when(gitInfoService.isDirty()).thenReturn(true);
        when(gitInfoService.getBranch()).thenReturn("feature/test");
        stubRunRepository();
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));

        PromptTestRunEntity result = service.runTest(EvaluationModel.SONNET, RunType.SHORT_TERM);

        assertThat(result.getGitCommitHash()).isEqualTo("abc1234");
        assertThat(result.getGitDirty()).isTrue();
        assertThat(result.getGitBranch()).isEqualTo("feature/test");
        assertThat(result.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("runTest isolates per-location failures")
    void runTest_perLocationFailureIsolation() {
        LocationEntity loc1 = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));
        LocationEntity loc2 = location(2L, "Bamburgh", Set.of(LocationType.SEASCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc2, loc1));
        stubRunRepository();
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);

        // First location (Bamburgh) evaluation fails; second (Durham) succeeds
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.HAIKU), any()))
                .thenThrow(new RuntimeException("API error"))
                .thenReturn(sampleDetail());
        lenient().when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any()))
                .thenReturn(50);
        lenient().when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubGitInfo();

        // SHORT_TERM = 3 dates × 2 types × 2 locations = 12 slots; first evaluation fails
        PromptTestRunEntity result = service.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM);

        assertThat(result.getSucceeded()).isEqualTo(11);
        assertThat(result.getFailed()).isEqualTo(1);
        verify(testResultRepository, times(12)).save(any(PromptTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest records failure on weather fetch error")
    void runTest_weatherFetchFailure() {
        LocationEntity loc = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        stubRunRepository();
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubGitInfo();

        // SHORT_TERM = 3 dates × 2 types × 1 location = 6 weather fetch attempts, all fail
        PromptTestRunEntity result = service.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(6);
        verify(evaluationService, never()).evaluateWithDetails(any(), any(), any());
    }

    @Test
    @DisplayName("runTest populates atmospheric data JSON on success results")
    void runTest_populatesAtmosphericDataJson() {
        LocationEntity loc = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        stubRunRepository();
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any())).thenReturn(sampleDetail());
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any())).thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(), any())).thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        stubGitInfo();

        ArgumentCaptor<PromptTestResultEntity> captor = ArgumentCaptor.forClass(PromptTestResultEntity.class);
        when(testResultRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.runTest(EvaluationModel.HAIKU, RunType.SHORT_TERM);

        // SHORT_TERM = 3 dates × 2 types × 1 location = 6 result saves; verify last captured
        PromptTestResultEntity saved = captor.getValue();
        assertThat(saved.getAtmosphericDataJson()).isNotNull();
        assertThat(saved.getAtmosphericDataJson()).contains("\"lowCloudPercent\":20");
        assertThat(saved.getLowCloudPercent()).isEqualTo(20);
        assertThat(saved.getHumidityPercent()).isEqualTo(65);
    }

    // --- replayTest tests ---

    @Test
    @DisplayName("replayTest uses stored atmospheric data without calling Open-Meteo")
    void replayTest_usesStoredData() throws Exception {
        AtmosphericData data = sampleAtmosphericData();
        String dataJson = objectMapper.writeValueAsString(data);

        PromptTestRunEntity parentRun = PromptTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).evaluationModel(EvaluationModel.SONNET)
                .build();
        PromptTestResultEntity parentResult = PromptTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .targetDate(LocalDate.of(2026, 3, 1)).targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.SONNET).succeeded(true)
                .atmosphericDataJson(dataJson).build();

        stubRunRepository(parentRun);
        when(testResultRepository.findByTestRunIdOrderByLocationNameAsc(10L))
                .thenReturn(List.of(parentResult));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.SONNET), any()))
                .thenReturn(sampleDetail());
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any())).thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(), any())).thenReturn(5400L);
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubGitInfo();

        PromptTestRunEntity result = service.replayTest(10L);

        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getParentRunId()).isEqualTo(10L);
        assertThat(result.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);

        // Verify NO Open-Meteo calls
        verify(openMeteoService, never()).getAtmosphericData(any(), any());
        verify(forecastService, never()).augmentWithTideData(any(), any(), any(), any());

        // Verify evaluation was called with parent's model
        verify(evaluationService).evaluateWithDetails(any(), eq(EvaluationModel.SONNET), any());
    }

    @Test
    @DisplayName("replayTest stamps fresh git info")
    void replayTest_stampsFreshGitInfo() throws Exception {
        AtmosphericData data = sampleAtmosphericData();
        String dataJson = objectMapper.writeValueAsString(data);

        PromptTestRunEntity parentRun = PromptTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).evaluationModel(EvaluationModel.HAIKU)
                .gitCommitHash("old1234").gitBranch("main")
                .build();
        PromptTestResultEntity parentResult = PromptTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .targetDate(LocalDate.of(2026, 3, 1)).targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true)
                .atmosphericDataJson(dataJson).build();

        stubRunRepository(parentRun);
        when(testResultRepository.findByTestRunIdOrderByLocationNameAsc(10L))
                .thenReturn(List.of(parentResult));
        when(evaluationService.evaluateWithDetails(any(), any(), any())).thenReturn(sampleDetail());
        lenient().when(costCalculator.calculateCost(any(), any())).thenReturn(50);
        lenient().when(costCalculator.calculateCostMicroDollars(any(), any())).thenReturn(5400L);
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubGitInfo();
        when(gitInfoService.getCommitHash()).thenReturn("new5678");

        PromptTestRunEntity result = service.replayTest(10L);

        assertThat(result.getGitCommitHash()).isEqualTo("new5678");
    }

    @Test
    @DisplayName("replayTest throws NoSuchElementException for unknown run")
    void replayTest_runNotFound() {
        when(testRunRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replayTest(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Prompt test run not found: 99");
    }

    @Test
    @DisplayName("replayTest throws when no results have atmospheric data")
    void replayTest_noAtmosphericData() {
        PromptTestRunEntity parentRun = PromptTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).evaluationModel(EvaluationModel.HAIKU)
                .build();
        PromptTestResultEntity parentResult = PromptTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true)
                .atmosphericDataJson(null).build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(parentRun));
        when(testResultRepository.findByTestRunIdOrderByLocationNameAsc(10L))
                .thenReturn(List.of(parentResult));

        assertThatThrownBy(() -> service.replayTest(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No atmospheric data stored");
    }

    @Test
    @DisplayName("replayTest throws when no results exist")
    void replayTest_noResults() {
        PromptTestRunEntity parentRun = PromptTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).evaluationModel(EvaluationModel.HAIKU)
                .build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(parentRun));
        when(testResultRepository.findByTestRunIdOrderByLocationNameAsc(10L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.replayTest(10L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No results found");
    }

    // --- Query tests ---

    @Test
    @DisplayName("getRecentRuns delegates to repository")
    void getRecentRuns_delegatesToRepo() {
        PromptTestRunEntity run = PromptTestRunEntity.builder().id(1L).build();
        when(testRunRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(run));

        List<PromptTestRunEntity> result = service.getRecentRuns();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getResults delegates to repository with full sort order")
    void getResults_delegatesToRepo() {
        PromptTestResultEntity r = PromptTestResultEntity.builder().id(1L).testRunId(1L).build();
        when(testResultRepository.findByTestRunIdOrderByLocationNameAscTargetDateAscTargetTypeAsc(1L))
                .thenReturn(List.of(r));

        List<PromptTestResultEntity> result = service.getResults(1L);

        assertThat(result).hasSize(1);
    }

    // --- Target resolution tests ---

    @Test
    @DisplayName("resolveTargetType returns SUNSET when before sunset")
    void resolveTargetType_beforeSunset() {
        LocationEntity loc = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));

        TargetType result = service.resolveTargetType(
                LocalDateTime.of(2026, 3, 1, 14, 0), List.of(loc));

        assertThat(result).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("resolveTargetType returns SUNRISE when after sunset")
    void resolveTargetType_afterSunset() {
        LocationEntity loc = location(1L, "Durham", Set.of(LocationType.LANDSCAPE));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));

        TargetType result = service.resolveTargetType(
                LocalDateTime.of(2026, 3, 1, 19, 0), List.of(loc));

        assertThat(result).isEqualTo(TargetType.SUNRISE);
    }

    @Test
    @DisplayName("resolveTargetDate returns today for SUNSET, tomorrow for SUNRISE")
    void resolveTargetDate_logic() {
        LocalDateTime now = LocalDateTime.of(2026, 3, 1, 14, 0);

        assertThat(service.resolveTargetDate(now, TargetType.SUNSET))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(service.resolveTargetDate(now, TargetType.SUNRISE))
                .isEqualTo(LocalDate.of(2026, 3, 2));
    }

    // --- resolveDates tests ---

    @Test
    @DisplayName("resolveDates returns 2 dates for VERY_SHORT_TERM")
    void resolveDates_veryShortTerm() {
        List<LocalDate> dates = service.resolveDates(RunType.VERY_SHORT_TERM);
        assertThat(dates).hasSize(2);
        assertThat(dates.get(0)).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(dates.get(1)).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1));
    }

    @Test
    @DisplayName("resolveDates returns 3 dates for SHORT_TERM")
    void resolveDates_shortTerm() {
        List<LocalDate> dates = service.resolveDates(RunType.SHORT_TERM);
        assertThat(dates).hasSize(3);
    }

    @Test
    @DisplayName("resolveDates returns 5 dates for LONG_TERM (T+3 to T+7)")
    void resolveDates_longTerm() {
        List<LocalDate> dates = service.resolveDates(RunType.LONG_TERM);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(dates).hasSize(5);
        assertThat(dates.get(0)).isEqualTo(today.plusDays(3));
        assertThat(dates.get(4)).isEqualTo(today.plusDays(7));
    }

    @Test
    @DisplayName("resolveDates throws for WEATHER run type")
    void resolveDates_weatherThrows() {
        assertThatThrownBy(() -> service.resolveDates(RunType.WEATHER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("runTest stores runType on run entity")
    void runTest_storesRunType() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        lenient().when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        stubGitInfo();
        stubRunRepository();
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 6, 30));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2099, 1, 1, 17, 30));

        PromptTestRunEntity result = service.runTest(EvaluationModel.HAIKU,
                RunType.VERY_SHORT_TERM);

        assertThat(result.getRunType()).isEqualTo(RunType.VERY_SHORT_TERM);
    }
}
