package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.DisplayVerdict;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        @DisplayName("1b. Cache hit propagates all identity fields faithfully")
        void cacheHitIdentityFields() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 3, 55, 40, "Fine")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.locationId()).isEqualTo(1L);
            assertThat(v.locationName()).isEqualTo("Bamburgh");
            assertThat(v.regionId()).isEqualTo(REGION_ID);
            assertThat(v.regionName()).isEqualTo(REGION_NAME);
            assertThat(v.date()).isEqualTo(DATE);
            assertThat(v.targetType()).isEqualTo(SUNRISE);
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
        @DisplayName("2b. Scored forecast row carries all scorable fields and evaluatedAt")
        void scoredForecastAllFields() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            LocalDateTime runAt = LocalDateTime.of(2026, 4, 22, 6, 0);
            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .rating(3).fierySkyPotential(50).goldenHourPotential(40)
                    .summary("Decent").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(runAt)
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(row));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.fierySkyPotential()).isEqualTo(50);
            assertThat(v.goldenHourPotential()).isEqualTo(40);
            assertThat(v.triageReason()).isNull();
            assertThat(v.triageMessage()).isNull();

            // evaluatedAt must be the forecastRunAt converted via Europe/London
            Instant expectedInstant = runAt.atZone(ZoneId.of("Europe/London")).toInstant();
            assertThat(v.evaluatedAt()).isEqualTo(expectedInstant);
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
            assertThat(v.fierySkyPotential()).isNull();
            assertThat(v.goldenHourPotential()).isNull();
            assertThat(v.summary()).isNull();
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
        @DisplayName("5b. Cache hit AND scored forecast row → cache wins, forecast ignored")
        void cacheWinsOverScoredForecast() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 85, "Stunning")));

            ForecastEvaluationEntity scoredRow = ForecastEvaluationEntity.builder()
                    .rating(2).fierySkyPotential(30).goldenHourPotential(25)
                    .summary("Poor").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 4, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(scoredRow));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(5);
            assertThat(v.fierySkyPotential()).isEqualTo(90);
            assertThat(v.summary()).isEqualTo("Stunning");
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
    @DisplayName("forLocation — single-location lookup")
    class ForLocation {

        @Test
        @DisplayName("unknown location id returns NONE with null identity fields")
        void unknownLocationReturnsNone() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

            LocationEvaluationView v = service.forLocation(999L, DATE, SUNRISE);

            assertThat(v.source()).isEqualTo(Source.NONE);
            assertThat(v.locationId()).isEqualTo(999L);
            assertThat(v.locationName()).isNull();
            assertThat(v.regionId()).isNull();
            assertThat(v.regionName()).isNull();
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("unregioned location skips cache, falls back to forecast row")
        void unregiondLocationSkipsCache() {
            LocationEntity solo = new LocationEntity();
            solo.setId(5L);
            solo.setName("Solo");
            solo.setRegion(null);

            when(locationService.findAllEnabled()).thenReturn(List.of(solo));

            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .rating(2).fierySkyPotential(25).goldenHourPotential(20)
                    .summary("Weak").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            5L, DATE, SUNRISE))
                    .thenReturn(Optional.of(row));

            LocationEvaluationView v = service.forLocation(5L, DATE, SUNRISE);

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_SCORED);
            assertThat(v.rating()).isEqualTo(2);
            assertThat(v.regionId()).isNull();
            assertThat(v.regionName()).isNull();
        }
    }

    @Nested
    @DisplayName("displayVerdict — unified colour/label signal")
    class DisplayVerdictField {

        @Test
        @DisplayName("cached scored rating 5 → WORTH_IT")
        void cachedHighRatingIsWorthIt() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Fire")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("cached scored rating 3 → MAYBE")
        void cachedMediumRatingIsMaybe() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 3, 55, 40, "OK")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("cached scored rating 2 → STAND_DOWN")
        void cachedLowRatingIsStandDown() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 2, 30, 25, "Poor")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("cached triage (null rating + triageReason) → STAND_DOWN")
        void cachedTriageIsStandDown() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", null, null, null,
                                    null, TriageReason.HIGH_CLOUD, "Cloud 90%")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("scored forecast row rating 4 → WORTH_IT")
        void forecastScoredHighIsWorthIt() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .rating(4).fierySkyPotential(70).goldenHourPotential(60)
                            .summary("Good").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("triaged forecast row → STAND_DOWN")
        void forecastTriageIsStandDown() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .triageReason(TriageReason.PRECIPITATION)
                            .triageMessage("Rain 80%")
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("no data anywhere → AWAITING")
        void noDataIsAwaiting() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
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

        @Test
        @DisplayName("cached location is not queried from forecast repo")
        void cachedLocationSkipsForecastQuery() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good")));

            service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            verify(forecastEvaluationRepository, never())
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            eq(1L), eq(DATE), eq(SUNRISE));
        }

        @Test
        @DisplayName("forecast row with neither rating nor triageReason is excluded")
        void forecastWithNeitherRatingNorTriageExcluded() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            // A forecast row that has no rating AND no triageReason (e.g. incomplete)
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("forecast fallback result carries correct locationName")
        void forecastFallbackLocationName() {
            when(locationService.findAllEnabled()).thenReturn(List.of(sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .rating(3).fierySkyPotential(55).goldenHourPotential(45)
                            .summary("Decent").evaluationModel(EvaluationModel.SONNET)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            BriefingEvaluationResult r = result.get("Sandsend");
            assertThat(r).isNotNull();
            assertThat(r.locationName()).isEqualTo("Sandsend");
            assertThat(r.fierySkyPotential()).isEqualTo(55);
            assertThat(r.goldenHourPotential()).isEqualTo(45);
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
        @DisplayName("latest forecastRunAt wins when multiple rows exist for same key")
        void latestForecastRunAtWins() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());

            // Two forecast rows for the same location/date/type — stale and fresh
            ForecastEvaluationEntity stale = ForecastEvaluationEntity.builder()
                    .targetDate(DATE).targetType(SUNRISE)
                    .rating(2).fierySkyPotential(20).goldenHourPotential(15)
                    .summary("Stale").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 21, 6, 0))
                    .build();
            ForecastEvaluationEntity fresh = ForecastEvaluationEntity.builder()
                    .targetDate(DATE).targetType(SUNRISE)
                    .rating(4).fierySkyPotential(70).goldenHourPotential(65)
                    .summary("Fresh").evaluationModel(EvaluationModel.SONNET)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            eq(1L), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(stale, fresh));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.forDateRange(
                    DATE, DATE, Set.of(SUNRISE));

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.summary()).isEqualTo("Fresh");
            assertThat(v.evaluationModel()).isEqualTo("SONNET");
        }

        @Test
        @DisplayName("HOURLY target type rows are filtered out")
        void hourlyFilteredOut() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());

            ForecastEvaluationEntity hourlyRow = ForecastEvaluationEntity.builder()
                    .targetDate(DATE).targetType(TargetType.HOURLY)
                    .rating(3).fierySkyPotential(50).goldenHourPotential(40)
                    .summary("Hourly").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            eq(1L), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(hourlyRow));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            // Request only SUNRISE and SUNSET — HOURLY row should not appear
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
