package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.LocationEvaluationView.Source;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationViewService} merge logic.
 *
 * <p>Each test targets a specific merge precedence scenario: cached evaluation wins
 * over forecast scored rows, scored wins over triaged, and triage wins over nothing.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationViewServiceTest {

    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private CachedEvaluationRepository cachedEvaluationRepository;
    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock
    private LocationService locationService;

    private EvaluationViewService service;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 23);
    private static final TargetType SUNRISE = TargetType.SUNRISE;
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final Long REGION_ID = 10L;
    private static final String REGION_NAME = "NE Yorkshire Coast";

    private LocationEntity bamburgh;
    private LocationEntity sandsend;
    private RegionEntity region;

    @BeforeEach
    void setUp() {
        service = new EvaluationViewService(
                briefingEvaluationService, cachedEvaluationRepository,
                forecastEvaluationRepository, locationService,
                new ObjectMapper());

        region = new RegionEntity();
        region.setId(REGION_ID);
        region.setName(REGION_NAME);

        bamburgh = new LocationEntity();
        bamburgh.setId(1L);
        bamburgh.setName("Bamburgh");
        bamburgh.setRegion(region);

        sandsend = new LocationEntity();
        sandsend.setId(2L);
        sandsend.setName("Sandsend");
        sandsend.setRegion(region);
    }

    @Nested
    @DisplayName("forRegion — merge precedence")
    class ForRegion {

        @Test
        @DisplayName("1. Cache hit only → CACHED_EVALUATION with rating and summary")
        void cacheHitOnly() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.fierySkyPotential()).isEqualTo(75);
            assertThat(v.goldenHourPotential()).isEqualTo(60);
            assertThat(v.summary()).isEqualTo("Great sky");
            assertThat(v.triageReason()).isNull();
        }

        @Test
        @DisplayName("2. No cache, scored forecast row → FORECAST_EVALUATION_SCORED")
        void noCacheScoredForecast() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .rating(3).fierySkyPotential(50).goldenHourPotential(40)
                    .summary("Decent").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(row));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_SCORED);
            assertThat(v.rating()).isEqualTo(3);
            assertThat(v.summary()).isEqualTo("Decent");
            assertThat(v.evaluationModel()).isEqualTo("HAIKU");
        }

        @Test
        @DisplayName("3. No cache, no scored row, triage row → FORECAST_EVALUATION_TRIAGE")
        void noCacheTriageForecast() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .triageReason(TriageReason.HIGH_CLOUD)
                    .triageMessage("Low cloud 85%")
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(row));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
            assertThat(v.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
            assertThat(v.triageMessage()).isEqualTo("Low cloud 85%");
        }

        @Test
        @DisplayName("4. Nothing anywhere → NONE with all scorable fields null")
        void noDataAnywhere() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.NONE);
            assertThat(v.rating()).isNull();
            assertThat(v.fierySkyPotential()).isNull();
            assertThat(v.goldenHourPotential()).isNull();
            assertThat(v.summary()).isNull();
            assertThat(v.triageReason()).isNull();
        }

        @Test
        @DisplayName("5. Cache hit AND triage row → cache wins, triage ignored")
        void cacheWinsOverTriage() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Stunning")));

            ForecastEvaluationEntity triageRow = ForecastEvaluationEntity.builder()
                    .triageReason(TriageReason.PRECIPITATION)
                    .triageMessage("Rain 80%")
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 4, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(triageRow));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(5);
            assertThat(v.triageReason()).isNull();
        }

        @Test
        @DisplayName("6. Cache has region entry but location not in results_json + triage exists → triage returned")
        void cacheExistsButLocationMissing() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));

            // Cache has Bamburgh but not Sandsend
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good")));

            // Sandsend has triage in forecast_evaluation
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .triageReason(TriageReason.LOW_VISIBILITY)
                            .triageMessage("Visibility 5km")
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(2);

            LocationEvaluationView bamburghView = views.stream()
                    .filter(v -> "Bamburgh".equals(v.locationName())).findFirst().orElseThrow();
            assertThat(bamburghView.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(bamburghView.rating()).isEqualTo(4);

            LocationEvaluationView sandsendView = views.stream()
                    .filter(v -> "Sandsend".equals(v.locationName())).findFirst().orElseThrow();
            assertThat(sandsendView.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(sandsendView.triageReason()).isEqualTo(TriageReason.LOW_VISIBILITY);
        }

        @Test
        @DisplayName("7. Multiple locations, mixed states → each gets correct view")
        void mixedStates() {
            LocationEntity whitby = new LocationEntity();
            whitby.setId(3L);
            whitby.setName("Whitby");
            whitby.setRegion(region);

            when(locationService.findAllEnabled())
                    .thenReturn(List.of(bamburgh, sandsend, whitby));

            // Bamburgh: cached; Sandsend: scored forecast; Whitby: nothing
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good")));

            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .rating(2).fierySkyPotential(30).goldenHourPotential(25)
                            .summary("Mediocre").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            3L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);
            assertThat(views).hasSize(3);

            assertThat(views.stream().filter(v -> "Bamburgh".equals(v.locationName()))
                    .findFirst().orElseThrow().source())
                    .isEqualTo(Source.CACHED_EVALUATION);
            assertThat(views.stream().filter(v -> "Sandsend".equals(v.locationName()))
                    .findFirst().orElseThrow().source())
                    .isEqualTo(Source.FORECAST_EVALUATION_SCORED);
            assertThat(views.stream().filter(v -> "Whitby".equals(v.locationName()))
                    .findFirst().orElseThrow().source())
                    .isEqualTo(Source.NONE);
        }
    }

    @Nested
    @DisplayName("getScoresForEnrichment — Plan tab delegate")
    class GetScoresForEnrichment {

        @Test
        @DisplayName("returns cached scores supplemented with forecast_evaluation fallback")
        void mergedResult() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));

            // Bamburgh in cache, Sandsend only in forecast_evaluation
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 65, "Good")));

            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .rating(3).fierySkyPotential(50).goldenHourPotential(45)
                            .summary("OK").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).hasSize(2);
            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
            assertThat(result.get("Sandsend").rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("cached entry takes precedence over forecast row for same location")
        void cachePrecedence() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Stunning")));

            // forecast_evaluation should NOT be queried for Bamburgh since cache has it

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).hasSize(1);
            assertThat(result.get("Bamburgh").rating()).isEqualTo(5);
        }

        @Test
        @DisplayName("forecast triage row surfaces as triage result")
        void forecastTriageFallback() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .triageReason(TriageReason.PRECIPITATION)
                            .triageMessage("Rain expected")
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).hasSize(1);
            BriefingEvaluationResult r = result.get("Bamburgh");
            assertThat(r.rating()).isNull();
            assertThat(r.triageReason()).isEqualTo(TriageReason.PRECIPITATION);
        }
    }

    @Nested
    @DisplayName("forDateRange — bulk loading for Map tab")
    class ForDateRange {

        @Test
        @DisplayName("filters out NONE-source views from results")
        void excludesNone() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

            // No cached scores
            when(briefingEvaluationService.getCachedScores(eq(REGION_NAME), eq(DATE), eq(SUNRISE)))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedScores(eq(REGION_NAME), eq(DATE), eq(SUNSET)))
                    .thenReturn(Map.of());

            // No forecast rows
            when(forecastEvaluationRepository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            eq(1L), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.forDateRange(
                    DATE, DATE, Set.of(SUNRISE, SUNSET));

            assertThat(views).isEmpty();
        }

        @Test
        @DisplayName("returns cached + triaged locations in a single call")
        void mixedSourcesInDateRange() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));

            // Bamburgh: cached sunrise
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Great")));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());

            // Sandsend: triaged sunset in forecast_evaluation
            ForecastEvaluationEntity sandsendTriage = ForecastEvaluationEntity.builder()
                    .targetDate(DATE).targetType(SUNSET)
                    .triageReason(TriageReason.HIGH_CLOUD)
                    .triageMessage("Overcast")
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 4, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            eq(1L), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(forecastEvaluationRepository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            eq(2L), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(sandsendTriage));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.forDateRange(
                    DATE, DATE, Set.of(SUNRISE, SUNSET));

            assertThat(views).hasSize(2);

            LocationEvaluationView bamburghSunrise = views.stream()
                    .filter(v -> "Bamburgh".equals(v.locationName())
                            && v.targetType() == SUNRISE)
                    .findFirst().orElseThrow();
            assertThat(bamburghSunrise.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(bamburghSunrise.rating()).isEqualTo(4);

            LocationEvaluationView sandsendSunset = views.stream()
                    .filter(v -> "Sandsend".equals(v.locationName())
                            && v.targetType() == SUNSET)
                    .findFirst().orElseThrow();
            assertThat(sandsendSunset.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(sandsendSunset.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }
    }
}
