package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link ForecastPreEvalResult} overload of
 * {@link GridCellStabilityService#classifyGridCellsAndPublishSnapshot(List)} — the
 * synchronous-path entry point added when the executor's duplicated snapshot logic was
 * unified onto this service. The candidate-based overload is exercised through the
 * {@code ForecastTaskCollector} tests.
 */
@ExtendWith(MockitoExtension.class)
class GridCellStabilityServiceTest {

    @Mock
    private ForecastStabilityClassifier stabilityClassifier;

    @Mock
    private StabilitySnapshotProvider stabilitySnapshotProvider;

    private GridCellStabilityService service;

    @BeforeEach
    void setUp() {
        service = new GridCellStabilityService(stabilityClassifier, stabilitySnapshotProvider);
    }

    private static LocationEntity gridLocation(String name) {
        LocationEntity loc = LocationEntity.builder()
                .name(name)
                .lat(54.78)
                .lon(-1.6)
                .build();
        loc.setGridLat(54.7500);
        loc.setGridLng(-1.6250);
        return loc;
    }

    private static ForecastPreEvalResult preEval(LocationEntity loc,
            OpenMeteoForecastResponse resp) {
        LocalDate today = LocalDate.now();
        return new ForecastPreEvalResult(false, null, null, loc, today, TargetType.SUNSET,
                LocalDateTime.now(), 90, 0, EvaluationModel.HAIKU, loc.getTideType(),
                loc.getName() + "|" + today + "|SUNSET", resp);
    }

    @Test
    @DisplayName("classifies each grid cell once from its hourly data; snapshot names all its locations")
    void preEvalOverload_classifiesOncePerCell_andPublishes() {
        LocationEntity bamburgh = gridLocation("Bamburgh");
        LocationEntity seahouses = gridLocation("Seahouses");
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        resp.setHourly(hourly);
        String cellKey = bamburgh.gridCellKey();
        when(stabilityClassifier.classify(eq(cellKey), eq(54.7500), eq(-1.6250), same(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        cellKey, 54.7500, -1.6250,
                        ForecastStability.SETTLED, "High pressure", 3));

        Map<String, GridCellStabilityResult> result =
                service.classifyGridCellsAndPublishSnapshot(
                        List.of(preEval(bamburgh, resp), preEval(seahouses, resp)));

        assertThat(result).containsOnlyKeys(cellKey);
        verify(stabilityClassifier, times(1))
                .classify(eq(cellKey), eq(54.7500), eq(-1.6250), same(hourly));

        ArgumentCaptor<StabilitySummaryResponse> summaryCaptor =
                ArgumentCaptor.forClass(StabilitySummaryResponse.class);
        verify(stabilitySnapshotProvider).update(summaryCaptor.capture());
        StabilitySummaryResponse summary = summaryCaptor.getValue();
        assertThat(summary.totalGridCells()).isEqualTo(1);
        assertThat(summary.cells()).hasSize(1);
        assertThat(summary.cells().get(0).stability()).isEqualTo(ForecastStability.SETTLED);
        assertThat(summary.cells().get(0).locationNames())
                .containsExactlyInAnyOrder("Bamburgh", "Seahouses");
    }

    @Test
    @DisplayName("a task without a forecast response is not classified but still names its cell")
    void preEvalOverload_nullResponse_notClassified() {
        LocationEntity withWeather = gridLocation("Bamburgh");
        LocationEntity withoutWeather = gridLocation("Seahouses");
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        resp.setHourly(hourly);
        String cellKey = withWeather.gridCellKey();
        when(stabilityClassifier.classify(eq(cellKey), eq(54.7500), eq(-1.6250), same(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        cellKey, 54.7500, -1.6250,
                        ForecastStability.TRANSITIONAL, "Mixed signals", 1));

        service.classifyGridCellsAndPublishSnapshot(
                List.of(preEval(withoutWeather, null), preEval(withWeather, resp)));

        // Classified exactly once, from the weather-bearing task's hourly data — the
        // null-response task contributed only its name
        verify(stabilityClassifier, times(1))
                .classify(eq(cellKey), eq(54.7500), eq(-1.6250), same(hourly));
        ArgumentCaptor<StabilitySummaryResponse> summaryCaptor =
                ArgumentCaptor.forClass(StabilitySummaryResponse.class);
        verify(stabilitySnapshotProvider).update(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().cells().get(0).locationNames())
                .containsExactlyInAnyOrder("Bamburgh", "Seahouses");
    }

    @Test
    @DisplayName("stabilityFor: null-response task is TRANSITIONAL even when its cell is already classified")
    void stabilityFor_nullResponse_ignoresExistingCellClassification() {
        LocationEntity loc = gridLocation("Bamburgh");
        Map<String, GridCellStabilityResult> byCell = new java.util.LinkedHashMap<>();
        byCell.put(loc.gridCellKey(), new GridCellStabilityResult(
                loc.gridCellKey(), 54.7500, -1.6250,
                ForecastStability.UNSETTLED, "Deep low", 0));

        ForecastStability result = service.stabilityFor(loc, preEval(loc, null), byCell);

        // Matches the batch-path contract: no weather data → TRANSITIONAL, not the
        // sibling-classified cell's stability (the old executor filter inherited it)
        assertThat(result).isEqualTo(ForecastStability.TRANSITIONAL);
        verifyNoInteractions(stabilityClassifier);
    }

    @Test
    @DisplayName("stabilityFor: a weather-bearing task reuses its cell's existing classification")
    void stabilityFor_existingCellClassification_reused() {
        LocationEntity loc = gridLocation("Bamburgh");
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        Map<String, GridCellStabilityResult> byCell = new java.util.LinkedHashMap<>();
        byCell.put(loc.gridCellKey(), new GridCellStabilityResult(
                loc.gridCellKey(), 54.7500, -1.6250,
                ForecastStability.SETTLED, "High pressure", 3));

        ForecastStability result = service.stabilityFor(loc, preEval(loc, resp), byCell);

        assertThat(result).isEqualTo(ForecastStability.SETTLED);
        verifyNoInteractions(stabilityClassifier);
    }

    @Test
    @DisplayName("stabilityFor: an unseen cell is classified lazily from the task's hourly data and cached")
    void stabilityFor_unseenCell_lazilyClassified() {
        LocationEntity loc = gridLocation("Bamburgh");
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        resp.setHourly(hourly);
        String cellKey = loc.gridCellKey();
        when(stabilityClassifier.classify(eq(cellKey), eq(54.7500), eq(-1.6250), same(hourly)))
                .thenReturn(new GridCellStabilityResult(
                        cellKey, 54.7500, -1.6250,
                        ForecastStability.UNSETTLED, "Deep low", 0));
        Map<String, GridCellStabilityResult> byCell = new java.util.LinkedHashMap<>();

        ForecastStability result = service.stabilityFor(loc, preEval(loc, resp), byCell);

        assertThat(result).isEqualTo(ForecastStability.UNSETTLED);
        assertThat(byCell).containsKey(cellKey);
    }

    @Test
    @DisplayName("no classifiable cells → empty map and the previous snapshot is left untouched")
    void preEvalOverload_noCells_doesNotPublish() {
        LocationEntity noGrid = LocationEntity.builder()
                .name("No Grid")
                .lat(54.78)
                .lon(-1.6)
                .build();

        Map<String, GridCellStabilityResult> result =
                service.classifyGridCellsAndPublishSnapshot(List.of(preEval(noGrid, null)));

        assertThat(result).isEmpty();
        verify(stabilitySnapshotProvider, never()).update(any());
    }
}
