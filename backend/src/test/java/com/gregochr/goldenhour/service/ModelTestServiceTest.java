package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RerunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.ModelTestResultRepository;
import com.gregochr.goldenhour.repository.ModelTestRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelTestService}.
 */
@ExtendWith(MockitoExtension.class)
class ModelTestServiceTest {

    @Mock
    private RegionRepository regionRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ModelTestRunRepository testRunRepository;
    @Mock
    private ModelTestResultRepository testResultRepository;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private ForecastDataAugmentor augmentor;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private SolarService solarService;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private ExchangeRateService exchangeRateService;

    private ModelTestService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        service = new ModelTestService(regionRepository, locationRepository,
                testRunRepository, testResultRepository, openMeteoService,
                augmentor, evaluationService, solarService, costCalculator,
                exchangeRateService);
    }

    private RegionEntity region(Long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true)
                .createdAt(LocalDateTime.now()).build();
    }

    private LocationEntity location(Long id, String name, RegionEntity region,
            Set<LocationType> types) {
        return LocationEntity.builder()
                .id(id).name(name).lat(54.77).lon(-1.57)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(types)
                .tideType(new HashSet<>())
                .region(region).enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AtmosphericData sampleAtmosphericData() {
        return TestAtmosphericData.builder()
                .locationName("Test Location")
                .solarEventTime(LocalDateTime.of(2026, 3, 1, 17, 30))
                .lowCloud(20)
                .midCloud(40)
                .visibility(15000)
                .windSpeed(new BigDecimal("3.5"))
                .windDirection(270)
                .humidity(65)
                .weatherCode(2)
                .shortwaveRadiation(new BigDecimal("350.0"))
                .pm25(new BigDecimal("5.0"))
                .dust(new BigDecimal("2.0"))
                .aod(new BigDecimal("0.15"))
                .temperature(8.0)
                .apparentTemperature(6.0)
                .precipProbability(10)
                .build();
    }

    private EvaluationDetail sampleDetail(EvaluationModel model) {
        return new EvaluationDetail(
                new SunsetEvaluation(4, 65, 70, "Good conditions for " + model),
                "prompt text", "{\"rating\":4,\"fiery_sky\":65,\"golden_hour\":70}",
                1500L, new TokenUsage(400, 80, 200, 100));
    }

    @Test
    @DisplayName("runTest with zero regions creates run with 0 regions processed")
    void runTest_noRegions() {
        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getRegionsCount()).isEqualTo(0);
        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(testResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("runTest processes one region with one location across all three models")
    void runTest_oneRegionOneLocation() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(6);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getTotalCostPence()).isEqualTo(300);
        assertThat(result.getTotalCostMicroDollars()).isEqualTo(32400L);
        assertThat(result.getExchangeRateGbpPerUsd()).isEqualTo(0.79);

        // 6 results saved (2 target types × 3 models)
        verify(testResultRepository, times(6)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest skips region with no colour locations")
    void runTest_regionWithNoColourLocations() {
        RegionEntity r = region(1L, "Wildlife Region");
        LocationEntity wildLoc = location(1L, "Bird Reserve", r, Set.of(LocationType.WILDLIFE));

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(wildLoc));
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getRegionsCount()).isEqualTo(0);
        verify(evaluationService, never()).evaluateWithDetails(any(), any(), any());
    }

    @Test
    @DisplayName("runTest isolates per-model failures")
    void runTest_perModelFailureIsolation() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any())).thenReturn(data);

        // Haiku succeeds, Sonnet fails, Opus succeeds — per target type
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(sampleDetail(EvaluationModel.HAIKU));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.SONNET), any()))
                .thenThrow(new RuntimeException("Sonnet overloaded"));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.OPUS), any()))
                .thenReturn(sampleDetail(EvaluationModel.OPUS));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any()))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        // 2 target types × (Haiku OK + Sonnet fail + Opus OK) = 4 succeeded, 2 failed
        assertThat(result.getSucceeded()).isEqualTo(4);
        assertThat(result.getFailed()).isEqualTo(2);
        verify(testResultRepository, times(6)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest handles weather data fetch failure for a region")
    void runTest_weatherDataFetchFailure() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        // 2 target types × 3 failures (one per model) = 6 failures
        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(6);
        verify(evaluationService, never()).evaluateWithDetails(any(), any(), any());
    }

    @Test
    @DisplayName("resolveTargetType returns SUNSET when before today's sunset")
    void resolveTargetType_beforeSunset() {
        LocationEntity loc = location(1L, "Durham", null, Set.of(LocationType.LANDSCAPE));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));

        TargetType result = service.resolveTargetType(
                LocalDateTime.of(2026, 3, 1, 14, 0), List.of(loc));

        assertThat(result).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("resolveTargetType returns SUNRISE when after today's sunset")
    void resolveTargetType_afterSunset() {
        LocationEntity loc = location(1L, "Durham", null, Set.of(LocationType.LANDSCAPE));
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

        assertThat(service.resolveTargetDate(now, TargetType.SUNSET, List.of()))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(service.resolveTargetDate(now, TargetType.SUNRISE, List.of()))
                .isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("findRepresentativeLocation picks first colour location in region")
    void findRepresentativeLocation_picksFirstColour() {
        RegionEntity r = region(1L, "North East");
        LocationEntity wild = location(1L, "Bird Reserve", r, Set.of(LocationType.WILDLIFE));
        LocationEntity colour = location(2L, "Durham", r, Set.of(LocationType.LANDSCAPE));

        LocationEntity result = service.findRepresentativeLocation(List.of(wild, colour), r);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Durham");
    }

    @Test
    @DisplayName("findRepresentativeLocation returns null when no colour locations")
    void findRepresentativeLocation_noColourLocations() {
        RegionEntity r = region(1L, "Wildlife Region");
        LocationEntity wild = location(1L, "Bird Reserve", r, Set.of(LocationType.WILDLIFE));

        LocationEntity result = service.findRepresentativeLocation(List.of(wild), r);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getRecentRuns delegates to repository")
    void getRecentRuns_delegatesToRepo() {
        ModelTestRunEntity run = ModelTestRunEntity.builder().id(1L).build();
        when(testRunRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(run));

        List<ModelTestRunEntity> result = service.getRecentRuns();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getResults delegates to repository")
    void getResults_delegatesToRepo() {
        ModelTestResultEntity r = ModelTestResultEntity.builder().id(1L).testRunId(1L).build();
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(1L))
                .thenReturn(List.of(r));

        List<ModelTestResultEntity> result = service.getResults(1L);

        assertThat(result).hasSize(1);
    }

    // --- runTestForLocation tests ---

    @Test
    @DisplayName("runTestForLocation succeeds with 3 models evaluated")
    void runTestForLocation_success() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTestForLocation(1L);

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(6);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getTotalCostPence()).isEqualTo(300);
        assertThat(result.getTotalCostMicroDollars()).isEqualTo(32400L);
        verify(testResultRepository, times(6)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("runTestForLocation throws NoSuchElementException for unknown location")
    void runTestForLocation_locationNotFound() {
        when(locationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runTestForLocation(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Location not found: 99");
    }

    @Test
    @DisplayName("runTestForLocation throws IllegalArgumentException for pure WILDLIFE location")
    void runTestForLocation_pureWildlife() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Bird Reserve", r, Set.of(LocationType.WILDLIFE));

        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));

        assertThatThrownBy(() -> service.runTestForLocation(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no colour types");
    }

    @Test
    @DisplayName("runTestForLocation throws IllegalArgumentException for disabled location")
    void runTestForLocation_disabledLocation() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        loc.setEnabled(false);

        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));

        assertThatThrownBy(() -> service.runTestForLocation(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("runTestForLocation throws IllegalArgumentException for location with no region")
    void runTestForLocation_noRegion() {
        LocationEntity loc = location(1L, "Durham", null, Set.of(LocationType.LANDSCAPE));

        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));

        assertThatThrownBy(() -> service.runTestForLocation(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no region assigned");
    }

    @Test
    @DisplayName("runTestForLocation records 3 failed results on weather fetch failure")
    void runTestForLocation_weatherFetchFailure() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));

        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTestForLocation(1L);

        // 2 target types × 3 model failures = 6
        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(6);
        verify(evaluationService, never()).evaluateWithDetails(any(), any(), any());
    }

    // --- rerunTest tests ---

    @Test
    @DisplayName("rerunTest re-runs previous test locations with fresh data")
    void rerunTest_success() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        ModelTestRunEntity previousRun = ModelTestRunEntity.builder().id(10L).build();
        ModelTestResultEntity prevResult = ModelTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .regionId(1L).regionName("North East")
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true).build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(previousRun));
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(10L))
                .thenReturn(List.of(prevResult,
                        ModelTestResultEntity.builder().testRunId(10L).locationId(1L)
                                .locationName("Durham").regionId(1L).regionName("North East")
                                .evaluationModel(EvaluationModel.SONNET).succeeded(true).build(),
                        ModelTestResultEntity.builder().testRunId(10L).locationId(1L)
                                .locationName("Durham").regionId(1L).regionName("North East")
                                .evaluationModel(EvaluationModel.OPUS).succeeded(true).build()));
        when(locationRepository.findById(1L)).thenReturn(Optional.of(loc));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(20L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.rerunTest(10L);

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(6);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getParentRunId()).isEqualTo(10L);
        assertThat(result.getRerunType()).isEqualTo(RerunType.FRESH_DATA);
        verify(testResultRepository, times(6)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("rerunTest throws NoSuchElementException for unknown run")
    void rerunTest_runNotFound() {
        when(testRunRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rerunTest(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Test run not found: 99");
    }

    @Test
    @DisplayName("rerunTest throws NoSuchElementException when previous run has no results")
    void rerunTest_noResults() {
        ModelTestRunEntity previousRun = ModelTestRunEntity.builder().id(10L).build();
        when(testRunRepository.findById(10L)).thenReturn(Optional.of(previousRun));
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(10L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.rerunTest(10L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No results found");
    }

    // --- rerunTestDeterministic tests ---

    @Test
    @DisplayName("rerunTestDeterministic uses stored atmospheric data without calling Open-Meteo")
    void rerunTestDeterministic_usesStoredAtmosphericData() throws Exception {
        AtmosphericData data = sampleAtmosphericData();
        String dataJson = objectMapper.writeValueAsString(data);

        ModelTestRunEntity previousRun = ModelTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).build();
        ModelTestResultEntity prevResult = ModelTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .regionId(1L).regionName("North East")
                .targetDate(LocalDate.of(2026, 3, 1)).targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true)
                .atmosphericDataJson(dataJson).build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(previousRun));
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(10L))
                .thenReturn(List.of(prevResult));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(20L);
            }
            return e;
        });
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.rerunTestDeterministic(10L);

        assertThat(result.getSucceeded()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getRegionsCount()).isEqualTo(1);

        // Verify NO Open-Meteo calls were made
        verify(openMeteoService, never()).getAtmosphericData(any(), any());
        verify(augmentor, never()).augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any());

        // Verify 3 evaluations (one per model)
        verify(evaluationService, times(3)).evaluateWithDetails(any(), any(), any());
        verify(testResultRepository, times(3)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("rerunTestDeterministic sets parentRunId and SAME_DATA rerunType")
    void rerunTestDeterministic_setsParentAndRerunType() throws Exception {
        AtmosphericData data = sampleAtmosphericData();
        String dataJson = objectMapper.writeValueAsString(data);

        ModelTestRunEntity previousRun = ModelTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).build();
        ModelTestResultEntity prevResult = ModelTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .regionId(1L).regionName("North East")
                .targetDate(LocalDate.of(2026, 3, 1)).targetType(TargetType.SUNSET)
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true)
                .atmosphericDataJson(dataJson).build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(previousRun));
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(10L))
                .thenReturn(List.of(prevResult));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(20L);
            }
            return e;
        });
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(any(), any())).thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(), any())).thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.rerunTestDeterministic(10L);

        assertThat(result.getParentRunId()).isEqualTo(10L);
        assertThat(result.getRerunType()).isEqualTo(RerunType.SAME_DATA);
    }

    @Test
    @DisplayName("rerunTestDeterministic throws when no results have atmospheric data")
    void rerunTestDeterministic_failsWhenNoAtmosphericData() {
        ModelTestRunEntity previousRun = ModelTestRunEntity.builder()
                .id(10L).targetDate(LocalDate.of(2026, 3, 1))
                .targetType(TargetType.SUNSET).build();
        ModelTestResultEntity prevResult = ModelTestResultEntity.builder()
                .testRunId(10L).locationId(1L).locationName("Durham")
                .regionId(1L).regionName("North East")
                .evaluationModel(EvaluationModel.HAIKU).succeeded(true)
                .atmosphericDataJson(null).build();

        when(testRunRepository.findById(10L)).thenReturn(Optional.of(previousRun));
        when(testResultRepository.findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(10L))
                .thenReturn(List.of(prevResult));

        assertThatThrownBy(() -> service.rerunTestDeterministic(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No atmospheric data stored");
    }

    @Test
    @DisplayName("rerunTestDeterministic throws when run not found")
    void rerunTestDeterministic_runNotFound() {
        when(testRunRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rerunTestDeterministic(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Test run not found: 99");
    }

    // --- Atmospheric data population tests ---

    @Test
    @DisplayName("runTest populates atmospheric data JSON on successful results")
    void runTest_populatesAtmosphericDataJson() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));
        AtmosphericData data = sampleAtmosphericData();

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 6, 30));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(5400L);
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);

        ArgumentCaptor<ModelTestResultEntity> captor = ArgumentCaptor.forClass(ModelTestResultEntity.class);
        when(testResultRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.runTest();

        List<ModelTestResultEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(6);
        for (ModelTestResultEntity result : saved) {
            assertThat(result.getAtmosphericDataJson()).isNotNull();
            assertThat(result.getAtmosphericDataJson()).contains("\"lowCloudPercent\":20");
            assertThat(result.getLowCloudPercent()).isEqualTo(20);
            assertThat(result.getMidCloudPercent()).isEqualTo(40);
            assertThat(result.getHighCloudPercent()).isEqualTo(30);
            assertThat(result.getVisibilityMetres()).isEqualTo(15000);
            assertThat(result.getHumidityPercent()).isEqualTo(65);
            assertThat(result.getWeatherCode()).isEqualTo(2);
        }
    }
}
