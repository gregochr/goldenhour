package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.EvaluationDetail;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.ModelTestResultRepository;
import com.gregochr.goldenhour.repository.ModelTestRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private ForecastService forecastService;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private SolarService solarService;
    @Mock
    private CostCalculator costCalculator;

    private ModelTestService service;

    @BeforeEach
    void setUp() {
        service = new ModelTestService(regionRepository, locationRepository,
                testRunRepository, testResultRepository, openMeteoService,
                forecastService, evaluationService, solarService, costCalculator);
    }

    private RegionEntity region(Long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true)
                .createdAt(LocalDateTime.now()).build();
    }

    private LocationEntity location(Long id, String name, RegionEntity region,
            Set<LocationType> types) {
        return LocationEntity.builder()
                .id(id).name(name).lat(54.77).lon(-1.57)
                .goldenHourType(GoldenHourType.BOTH_TIMES)
                .locationType(types)
                .tideType(new HashSet<>())
                .region(region).enabled(true)
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

    private EvaluationDetail sampleDetail(EvaluationModel model) {
        return new EvaluationDetail(
                new SunsetEvaluation(4, 65, 70, "Good conditions for " + model),
                "prompt text", "{\"rating\":4,\"fiery_sky\":65,\"golden_hour\":70}",
                1500L);
    }

    @Test
    @DisplayName("runTest with zero regions creates run with 0 regions processed")
    void runTest_noRegions() {
        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));

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
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getTotalCostPence()).isEqualTo(150);

        // 3 results saved (one per model)
        verify(testResultRepository, times(3)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest skips region with no colour locations")
    void runTest_regionWithNoColourLocations() {
        RegionEntity r = region(1L, "Wildlife Region");
        LocationEntity wildLoc = location(1L, "Bird Reserve", r, Set.of(LocationType.WILDLIFE));

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(wildLoc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
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
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);

        // Haiku succeeds, Sonnet fails, Opus succeeds
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.HAIKU), any()))
                .thenReturn(sampleDetail(EvaluationModel.HAIKU));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.SONNET), any()))
                .thenThrow(new RuntimeException("Sonnet overloaded"));
        when(evaluationService.evaluateWithDetails(any(), eq(EvaluationModel.OPUS), any()))
                .thenReturn(sampleDetail(EvaluationModel.OPUS));
        lenient().when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any()))
                .thenReturn(50);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getSucceeded()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(1);
        // 3 results saved: 2 success + 1 failure
        verify(testResultRepository, times(3)).save(any(ModelTestResultEntity.class));
    }

    @Test
    @DisplayName("runTest handles weather data fetch failure for a region")
    void runTest_weatherDataFetchFailure() {
        RegionEntity r = region(1L, "North East");
        LocationEntity loc = location(1L, "Durham", r, Set.of(LocationType.LANDSCAPE));

        when(regionRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(r));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(loc));
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTest();

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(3); // 3 failures (one per model)
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
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTestForLocation(1L);

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getTotalCostPence()).isEqualTo(150);
        verify(testResultRepository, times(3)).save(any(ModelTestResultEntity.class));
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
        when(testRunRepository.save(any())).thenAnswer(inv -> {
            ModelTestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any()))
                .thenThrow(new RuntimeException("Open-Meteo timeout"));
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.runTestForLocation(1L);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(3);
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
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenReturn(LocalDateTime.of(2026, 3, 1, 17, 30));
        when(openMeteoService.getAtmosphericData(any(), any())).thenReturn(data);
        when(forecastService.augmentWithTideData(any(), any(), any(), any())).thenReturn(data);
        when(evaluationService.evaluateWithDetails(any(), any(), any()))
                .thenAnswer(inv -> sampleDetail(inv.getArgument(1)));
        when(costCalculator.calculateCost(eq(ServiceName.ANTHROPIC), any(EvaluationModel.class)))
                .thenReturn(50);
        when(testResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelTestRunEntity result = service.rerunTest(10L);

        assertThat(result.getRegionsCount()).isEqualTo(1);
        assertThat(result.getSucceeded()).isEqualTo(3);
        assertThat(result.getFailed()).isEqualTo(0);
        verify(testResultRepository, times(3)).save(any(ModelTestResultEntity.class));
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
}
