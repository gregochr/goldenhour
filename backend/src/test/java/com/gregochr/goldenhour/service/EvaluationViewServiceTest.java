package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TriageDetails;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static final double BAMBURGH_LAT = 55.609;
    private static final double BAMBURGH_LON = -1.709;

    private LocationEntity bamburgh;
    private LocationEntity sandsend;
    private RegionEntity region;

    @BeforeEach
    void setUp() {
        // A REAL SolarService, not a mock: it is a pure Meeus calculator with no I/O, and the
        // light-times tests below assert its OUTPUT against clock times computed by an independent
        // NOAA solar-position solver outside this codebase (see `assertAlmanac`). A mock returning
        // fixed times would make every one of those assertions a statement about the mock.
        service = new EvaluationViewService(
                briefingEvaluationService, cachedEvaluationRepository,
                forecastEvaluationRepository, locationService,
                new ObjectMapper(), new SolarService());

        region = new RegionEntity();
        region.setId(REGION_ID);
        region.setName(REGION_NAME);

        // Real coordinates, because the light-times tests below assert real solar geometry and
        // `lat`/`lon` are primitive doubles: an unset fixture is a location at 0N 0E, which is a
        // silent lie the moment anything on this record is derived from position.
        bamburgh = new LocationEntity();
        bamburgh.setId(1L);
        bamburgh.setName("Bamburgh");
        bamburgh.setRegion(region);
        bamburgh.setLat(BAMBURGH_LAT);
        bamburgh.setLon(BAMBURGH_LON);

        sandsend = new LocationEntity();
        sandsend.setId(2L);
        sandsend.setName("Sandsend");
        sandsend.setRegion(region);
        sandsend.setLat(54.508);
        sandsend.setLon(-0.663);
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
        @DisplayName("1c. Cache hit carries the cache evaluatedAt as the view's evaluatedAt")
        void cacheHitCarriesEvaluatedAt() {
            Instant evaluatedAt = Instant.parse("2026-04-22T05:00:00Z");
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(evaluatedAt));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            // The batch/SSE run time must survive onto the view so it can be shown as the honest
            // "forecast generated" timestamp — never fabricated downstream as the request time.
            assertThat(v.evaluatedAt()).isEqualTo(evaluatedAt);
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
                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "Low cloud 85%"))
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
                    .triage(new TriageDetails(TriageReason.PRECIPITATION, "Rain 80%"))
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
                            .triage(new TriageDetails(TriageReason.LOW_VISIBILITY, "Visibility 5km"))
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
                            .triage(new TriageDetails(TriageReason.PRECIPITATION, "Rain 80%"))
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

        /** Stubs the region's single bulk forecast query with the given rows. */
        private void forecastRows(ForecastEvaluationEntity... rows) {
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(rows));
        }

        @Test
        @DisplayName("returns cached scores supplemented with forecast_evaluation fallback")
        void mergedResult() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));

            // Bamburgh in cache, Sandsend only in forecast_evaluation
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 65, "Good")));

            forecastRows(ForecastEvaluationEntity.builder()
                    .location(sandsend).targetDate(DATE).targetType(SUNRISE)
                    .rating(3).fierySkyPotential(50).goldenHourPotential(45)
                    .summary("OK").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build());

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

            // The row IS now loaded for Bamburgh — gating needs one for every location, not only
            // for cache misses — but an unknown-freshness cache still wins the merge.

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

            forecastRows(ForecastEvaluationEntity.builder()
                    .location(bamburgh).targetDate(DATE).targetType(SUNRISE)
                    .triage(new TriageDetails(TriageReason.PRECIPITATION, "Rain expected"))
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build());

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).hasSize(1);
            BriefingEvaluationResult r = result.get("Bamburgh");
            assertThat(r.rating()).isNull();
            assertThat(r.triageReason()).isEqualTo(TriageReason.PRECIPITATION);
        }

        @Test
        @DisplayName("resolves the whole region in ONE query — never a findTop per location")
        void resolvesTheWholeRegionInOneQuery() {
            // This nest used to pin the opposite: that a cached location was never queried at all.
            // Freshness gating ended that — a cached rating cannot be compared against a row that
            // was never loaded — so the guarantee worth keeping is the one about fan-out. The build
            // path calls this once per region x date x event, so a per-location point lookup here
            // would be ~550 round trips on a five-day plan.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good")));
            forecastRows();

            service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.captor();
            verify(forecastEvaluationRepository, times(1))
                    .findLatestRunPerSlotByLocationIds(idsCaptor.capture(), eq(DATE), eq(DATE));
            assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
            verify(forecastEvaluationRepository, never())
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            any(), any(), any());
        }

        @Test
        @DisplayName("forecast row with neither rating nor triageReason is excluded")
        void forecastWithNeitherRatingNorTriageExcluded() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());

            // A forecast row that has no rating AND no triageReason (e.g. incomplete)
            forecastRows(ForecastEvaluationEntity.builder()
                    .location(bamburgh).targetDate(DATE).targetType(SUNRISE)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build());

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

            forecastRows(ForecastEvaluationEntity.builder()
                    .location(sandsend).targetDate(DATE).targetType(SUNRISE)
                    .rating(3).fierySkyPotential(55).goldenHourPotential(45)
                    .summary("Decent").evaluationModel(EvaluationModel.SONNET)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build());

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            BriefingEvaluationResult r = result.get("Sandsend");
            assertThat(r).isNotNull();
            assertThat(r.locationName()).isEqualTo("Sandsend");
            assertThat(r.fierySkyPotential()).isEqualTo(55);
            assertThat(r.goldenHourPotential()).isEqualTo(45);
        }

        @Test
        @DisplayName("a cached entry for a location no longer in the roster is still carried")
        void cachedEntryOutsideRosterSurvives() {
            // The map used to START as a copy of the cache, so an entry for a renamed, disabled or
            // moved location came along for the ride. Rebuilding it per roster location would drop
            // those silently — an unrelated behaviour change riding on the freshness fix.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Retired Cove",
                            new BriefingEvaluationResult("Retired Cove", 3, 60, 55, "Fine")));
            forecastRows();

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result.get("Retired Cove").rating()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("getScoresForEnrichmentBulk — batched Plan re-enrichment")
    class GetScoresForEnrichmentBulk {

        private static final String KEY = REGION_NAME + "|" + DATE + "|" + SUNSET;

        @Test
        @DisplayName("cached score keeps its Claude headline (not dropped like the view path)")
        void cachedHeadlinePreserved() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh", new BriefingEvaluationResult(
                            "Bamburgh", 4, 75, 65, "Good", null, null, "Fiery skies at dusk")));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            BriefingEvaluationResult r = index.get(KEY).get("Bamburgh");
            assertThat(r.rating()).isEqualTo(4);
            assertThat(r.headline()).isEqualTo("Fiery skies at dusk");
        }

        @Test
        @DisplayName("uncached location falls back to its latest forecast_evaluation row")
        void forecastFallback() {
            when(locationService.findAllEnabled()).thenReturn(List.of(sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(sandsend)
                            .targetDate(DATE).targetType(SUNSET)
                            .rating(3).fierySkyPotential(55).goldenHourPotential(45)
                            .summary("Decent").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 18, 0))
                            .build()));

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            assertThat(index.get(KEY).get("Sandsend").rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("cached score wins over a forecast row for the same location")
        void cachedWinsOverForecast() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Stunning")));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh)
                            .targetDate(DATE).targetType(SUNSET).rating(2)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 18, 0))
                            .build()));

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            assertThat(index.get(KEY).get("Bamburgh").rating()).isEqualTo(5);
        }

        @Test
        @DisplayName("forecast triage row surfaces as a triage result")
        void triageFallback() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh)
                            .targetDate(DATE).targetType(SUNSET)
                            .triage(new TriageDetails(TriageReason.PRECIPITATION, "Rain"))
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 18, 0))
                            .build()));

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            BriefingEvaluationResult r = index.get(KEY).get("Bamburgh");
            assertThat(r.rating()).isNull();
            assertThat(r.triageReason()).isEqualTo(TriageReason.PRECIPITATION);
        }

        @Test
        @DisplayName("issues ONE bulk query for all locations — never per-location, never a per-date findTop")
        void oneBulkQueryForAllLocations() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());

            service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            // Exactly one round trip regardless of location count, carrying every location id.
            ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.captor();
            verify(forecastEvaluationRepository, times(1))
                    .findLatestRunPerSlotByLocationIds(
                            idsCaptor.capture(), eq(DATE), eq(DATE));
            assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);

            // Neither of the superseded shapes is used any more.
            verify(forecastEvaluationRepository, never())
                    .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                            any(), any(), any());
            verify(forecastEvaluationRepository, never())
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            any(), any(), any());
        }

        @Test
        @DisplayName("no region-assigned locations → no query at all (an empty IN list is invalid)")
        void noRegionLocations_skipsQueryEntirely() {
            LocationEntity unregioned = new LocationEntity();
            unregioned.setId(3L);
            unregioned.setName("Orphan");
            when(locationService.findAllEnabled()).thenReturn(List.of(unregioned));

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            assertThat(index).isEmpty();
            verify(forecastEvaluationRepository, never())
                    .findLatestRunPerSlotByLocationIds(anyCollection(), any(), any());
        }
    }

    @Nested
    @DisplayName("forDateRange — bulk loading for Map tab")
    class ForDateRange {

        @Test
        @DisplayName("queries EVERY enabled location in one call — including region-less ones")
        void queriesEveryEnabledLocationInOneCall() {
            // Pins the one axis the bulk-query refactor changed: the id list. Without this, a future
            // edit that copied the sibling's `region != null` filter (getScoresForEnrichmentBulk
            // legitimately applies it) — or otherwise truncated the list — would silently drop those
            // locations to Source.NONE on the Plan/Map load with no test failing.
            LocationEntity unregioned = new LocationEntity();
            unregioned.setId(3L);
            unregioned.setName("Orphan");
            when(locationService.findAllEnabled())
                    .thenReturn(List.of(bamburgh, sandsend, unregioned));
            when(briefingEvaluationService.getCachedScores(eq(REGION_NAME), eq(DATE), eq(SUNRISE)))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedScores(eq(REGION_NAME), eq(DATE), eq(SUNSET)))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            service.forDateRange(DATE, DATE, Set.of(SUNRISE, SUNSET));

            ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.captor();
            verify(forecastEvaluationRepository, times(1))
                    .findLatestRunPerSlotByLocationIds(
                            idsCaptor.capture(), eq(DATE), eq(DATE));
            // A location with no region still gets its forecast rows here — unlike the briefing
            // fallback, this path is not region-scoped.
            assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
        }

        @Test
        @DisplayName("no enabled locations → no query at all (an empty IN list is invalid)")
        void noEnabledLocations_skipsQueryEntirely() {
            when(locationService.findAllEnabled()).thenReturn(List.of());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.forDateRange(
                    DATE, DATE, Set.of(SUNRISE, SUNSET));

            assertThat(views).isEmpty();
            verify(forecastEvaluationRepository, never())
                    .findLatestRunPerSlotByLocationIds(anyCollection(), any(), any());
        }

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
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
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
                    .location(bamburgh)
                    .targetDate(DATE).targetType(SUNRISE)
                    .rating(2).fierySkyPotential(20).goldenHourPotential(15)
                    .summary("Stale").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 21, 6, 0))
                    .build();
            ForecastEvaluationEntity fresh = ForecastEvaluationEntity.builder()
                    .location(bamburgh)
                    .targetDate(DATE).targetType(SUNRISE)
                    .rating(4).fierySkyPotential(70).goldenHourPotential(65)
                    .summary("Fresh").evaluationModel(EvaluationModel.SONNET)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
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
                    .location(bamburgh)
                    .targetDate(DATE).targetType(TargetType.HOURLY)
                    .rating(3).fierySkyPotential(50).goldenHourPotential(40)
                    .summary("Hourly").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
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

            // Sandsend: triaged sunset in forecast_evaluation. The bulk query returns rows for every
            // requested location in one list, with the location relation initialised by JOIN FETCH —
            // the service groups on row.getLocation().getId(), so the fixture must set it.
            ForecastEvaluationEntity sandsendTriage = ForecastEvaluationEntity.builder()
                    .location(sandsend)
                    .targetDate(DATE).targetType(SUNSET)
                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "Overcast"))
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 4, 0))
                    .build();

            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
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

        @Test
        @DisplayName("in-memory cache hit carries its evaluatedAt onto the view")
        void inMemoryCacheCarriesEvaluatedAt() {
            Instant evaluatedAt = Instant.parse("2026-04-23T04:30:00Z");
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Great")));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(evaluatedAt));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            LocationEvaluationView v = service.forDateRange(DATE, DATE, Set.of(SUNRISE, SUNSET))
                    .stream()
                    .filter(view -> view.targetType() == SUNRISE)
                    .findFirst().orElseThrow();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.evaluatedAt()).isEqualTo(evaluatedAt);
        }

        @Test
        @DisplayName("DB-fallback cache entry carries updated_at, NOT the creation stamp")
        void dbFallbackCacheCarriesUpdatedAtNotEvaluatedAt() throws Exception {
            // The two columns mean different things and the fixture now sets them apart, which the
            // previous version could not: it set evaluated_at alone, so it could not distinguish
            // "carries the last write" from "carries the row's birthday". persistToDb only sets
            // evaluated_at when the row is CREATED, so a slot re-evaluated for three days running
            // still reports day one — and the merge's freshness rule is only sound on the last write.
            Instant createdAt = Instant.parse("2026-04-20T01:00:00Z");
            Instant evaluatedAt = Instant.parse("2026-04-23T04:30:00Z");
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            // Not in the in-memory cache — forces the DB-fallback branch.
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());

            CachedEvaluationEntity dbEntry = new CachedEvaluationEntity();
            dbEntry.setCacheKey(REGION_NAME + "|" + DATE + "|SUNRISE");
            dbEntry.setEvaluationDate(DATE);
            dbEntry.setTargetType("SUNRISE");
            dbEntry.setResultsJson(new ObjectMapper().writeValueAsString(List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Great"))));
            dbEntry.setEvaluatedAt(createdAt);
            dbEntry.setUpdatedAt(evaluatedAt);
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of(dbEntry));

            LocationEvaluationView v = service.forDateRange(DATE, DATE, Set.of(SUNRISE, SUNSET))
                    .stream()
                    .filter(view -> view.targetType() == SUNRISE)
                    .findFirst().orElseThrow();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.evaluatedAt()).isEqualTo(evaluatedAt).isNotEqualTo(createdAt);
        }
    }

    @Nested
    @DisplayName("Merge freshness gate — a stale cached rating must not outrank a newer triage")
    class MergeFreshness {

        /** A triaged row for Bamburgh, run at the given LONDON-naive local time. */
        private void triagedRunAt(LocalDateTime runAt) {
            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "Low cloud 94% — sun blocked"))
                    .forecastRunAt(runAt)
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(row));
        }

        private void cachedRatingAt(int rating, Instant writtenAt) {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", rating, 80, 70, "Worth it")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.ofNullable(writtenAt));
        }

        @Test
        @DisplayName("Stale 4-star cache loses to a newer triage — the production case")
        void staleCacheLosesToNewerTriage() {
            // Measured in production: a 4-star cached rating 47.9 hours older than a row triaged
            // HIGH_CLOUD on 87-99% low cloud at the solar horizon. A triaged slot makes no Claude
            // call and so writes no cached rating, which is why the only way the two coexist is
            // that the rating predates the cloud arriving.
            cachedRatingAt(4, Instant.parse("2026-04-21T00:09:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
            assertThat(v.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }

        /** A Bamburgh row run at the given LONDON-naive time carrying NO rating and NO triage. */
        private void emptyRowRunAt(LocalDateTime runAt) {
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .forecastRunAt(runAt)
                            .build()));
        }

        @Test
        @DisplayName("A NEWER but EMPTY row does not blank a cached rating — it has no opinion")
        void newerEmptyRowDoesNotBlankTheCache() {
            // The asymmetry this closes. The gate is a comparison of write times, not of claims:
            // losing it is not the same as being contradicted. A row with neither a rating nor a
            // triage reason — a bare base-forecast row, and roughly three quarters of
            // `forecast_evaluation` carries a null rating — says nothing about this slot, so there
            // is nothing for the cache to be wrong about.
            //
            // Before this, `mergeToView` fell through every branch to `Source.NONE`, which
            // `buildViews` drops: /api/briefing/evaluate/scores lost the location entirely while
            // the briefing payload kept its 4 stars, because `resolveForEnrichment` fell back to
            // the cache and `mergeToView` did not. Same tables, same gate, opposite answer.
            cachedRatingAt(4, Instant.parse("2026-04-21T00:09:00Z"));
            emptyRowRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.summary()).isEqualTo("Worth it");
        }

        @Test
        @DisplayName("...and the gate still bites when that newer row DOES say something")
        void theGateStillBitesWhenTheRowSpeaks() {
            // The pair, and the objection to check first: the fallback must not become a way for a
            // stale rating to outlive a current contradiction. Identical fixture to the test above
            // except that the newer row carries a triage reason — and the cache loses, exactly as
            // it did before. `staleCacheLosesToNewerTriage` covers the same ground from the other
            // direction; this one exists so the two sit side by side under one fixture, because
            // the whole risk of the change is that they stop differing.
            cachedRatingAt(4, Instant.parse("2026-04-21T00:09:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("With no cache, a newer empty row is still NONE — nothing to fall back to")
        void emptyRowWithNoCacheIsStillNone() {
            // The fallback is a fallback, not a licence to invent a view. `buildViews` drops NONE,
            // and a location neither store can describe must stay dropped.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            emptyRowRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.NONE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("Fresher cache still wins over an older triage")
        void fresherCacheBeatsOlderTriage() {
            cachedRatingAt(2, Instant.parse("2026-04-22T14:08:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 22, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("A tie goes to the cache — one batch run writing both halves")
        void tieGoesToTheCache() {
            // 01:05 London in April is 00:05Z. Same instant, so this is one run writing the
            // forecast row and the cache together; the cache carries strictly more.
            cachedRatingAt(3, Instant.parse("2026-04-23T00:05:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("forecast_run_at is LONDON-naive, so BST cannot invert the comparison")
        void bstDoesNotInvertTheComparison() {
            // The trap: forecast_run_at is a naive LocalDateTime recorded in Europe/London, and
            // 04:00 London in April is 03:00Z. The cache here is written at 03:30Z — half an hour
            // AFTER the forecast ran — so it must win. Compare the two raw and 03:30 reads as
            // earlier than 04:00, handing it to the triage and losing a live rating. The nightly
            // cycle writes both halves within the hour routinely, so this is the common case.
            cachedRatingAt(4, Instant.parse("2026-04-23T03:30:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 4, 0));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("Unknown cache freshness keeps the cache — deliberately the prior behaviour")
        void unknownFreshnessKeepsTheCache() {
            // Both cached_evaluation stamps are nullable = false and every in-memory writer sets
            // Instant.now(), so this is a cannot-happen. It resolves to the old behaviour rather
            // than a newly invented one, and it is why the existing precedence tests above -- none
            // of which stub getCachedEvaluatedAt -- still describe precedence under unknown freshness.
            cachedRatingAt(5, null);
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 1, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Enrichment freshness gate — the briefing payload obeys the same rule as the view")
    class EnrichmentFreshness {

        private static final String KEY = REGION_NAME + "|" + DATE + "|" + SUNSET;

        /** A cached rating for Bamburgh written at the given instant. */
        private void cachedRatingAt(int rating, Instant writtenAt) {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", rating, 80, 70, "Worth it")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.ofNullable(writtenAt));
        }

        /** A scored Bamburgh row run at the given LONDON-naive local time. */
        private void scoredRunAt(int rating, LocalDateTime runAt) {
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNSET)
                            .rating(rating).fierySkyPotential(30).goldenHourPotential(25)
                            .summary("Low cloud builds into the solar horizon")
                            .evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(runAt)
                            .build()));
        }

        @Test
        @DisplayName("Stale 4-star cache loses to a newer scored row — the Close to home case")
        void staleCacheLosesToNewerScoredRow() {
            // Observed 2026-08-02: Angel of the North read 4.0 stars on the Close to home panel and
            // 2 on both the region drill-down and the map popup, for one location on one evening.
            // The cached rating was written before the newest forecast row and lost the merge on
            // the view path (which gates) while winning it here (which did not).
            cachedRatingAt(4, Instant.parse("2026-07-31T09:00:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 22, 29));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("Stale 4-star cache loses to a newer scored row on the BULK serve path too")
        void staleCacheLosesToNewerScoredRowInBulk() {
            // The serve path resolves through the bulk index, so gating one method and not the
            // other would leave every re-served briefing — i.e. the panel as users actually load
            // it — carrying the stale rating.
            cachedRatingAt(4, Instant.parse("2026-07-31T09:00:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 22, 29));

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            assertThat(index.get(KEY).get("Bamburgh").rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("Stale cache loses to a newer TRIAGE row, surfacing the stand-down")
        void staleCacheLosesToNewerTriage() {
            cachedRatingAt(4, Instant.parse("2026-07-29T09:00:00Z"));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNSET)
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "94% low cloud"))
                            .forecastRunAt(LocalDateTime.of(2026, 7, 31, 22, 29))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            BriefingEvaluationResult r = result.get("Bamburgh");
            assertThat(r.rating()).isNull();
            assertThat(r.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }

        @Test
        @DisplayName("A fresher cached rating still wins — the gate is not a forecast-always rule")
        void fresherCacheStillWins() {
            cachedRatingAt(4, Instant.parse("2026-08-01T06:00:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 22, 29));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("A tie goes to the cache — one run writing both halves")
        void tieGoesToTheCache() {
            // 22:29 London on 31 July is 21:29Z (BST). Same instant, so this is one run writing
            // the forecast row and the cache together. The tie resolves to the cache for
            // determinism and to match mergeToView, NOT because the row is poorer — a scored row
            // carries its own summary and headline too.
            cachedRatingAt(4, Instant.parse("2026-07-31T21:29:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 22, 29));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("The winning row brings its OWN headline — it must not blank the card header")
        void winningRowCarriesItsHeadline() {
            // enrichSlot ASSIGNS the headline it is handed, and the drill-down renders that header
            // with no fallback, so returning null here would silently delete a card title for every
            // slot the gate routes to a newer row. Both stores carry a headline; the winner's must
            // travel with the rating and summary it belongs to.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh", new BriefingEvaluationResult(
                            "Bamburgh", 4, 80, 70, "Worth it", null, null, "Fiery skies at dusk")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.of(Instant.parse("2026-07-31T09:00:00Z")));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNSET)
                            .rating(2).fierySkyPotential(30).goldenHourPotential(25)
                            .summary("Low cloud builds into the solar horizon")
                            .headline("Low cloud builds in")
                            .evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 7, 31, 22, 29))
                            .build()));

            BriefingEvaluationResult r =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET).get("Bamburgh");

            assertThat(r.rating()).isEqualTo(2);
            assertThat(r.headline()).isEqualTo("Low cloud builds in");
            assertThat(r.summary()).isEqualTo("Low cloud builds into the solar horizon");
        }

        @Test
        @DisplayName("forecast_run_at is LONDON-naive, so BST cannot invert the comparison here either")
        void bstDoesNotInvertTheComparison() {
            // 23:00 London on 31 July is 22:00Z. A cache written at 22:30Z is half an hour AFTER
            // the run and must win; compared raw, 22:30 reads as earlier than 23:00 and a live
            // rating would be thrown away for a superseded row.
            cachedRatingAt(4, Instant.parse("2026-07-31T22:30:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 23, 0));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("A newer but unusable row does not blank a location the cache can describe")
        void newerUnusableRowKeepsTheCachedEntry() {
            // Losing the freshness comparison is not the same as having something to say. A row
            // carrying neither a rating nor a triage reason must not delete the only description
            // of the slot that exists.
            cachedRatingAt(4, Instant.parse("2026-07-29T09:00:00Z"));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNSET)
                            .forecastRunAt(LocalDateTime.of(2026, 7, 31, 22, 29))
                            .build()));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("cachedOnlyViewsForDateRange — GET /api/forecast cached-only path")
    class CachedOnlyViewsForDateRange {

        @Test
        @DisplayName("returns cached views WITHOUT a per-location forecast query or a repeat findAllEnabled")
        void skipsPerLocationForecastQuery() {
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.cachedOnlyViewsForDateRange(
                    DATE, DATE, Set.of(SUNRISE), List.of(bamburgh));

            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.summary()).isEqualTo("Great sky");
            // The whole point: no forecast_evaluation re-query, and no repeat findAllEnabled
            // (the caller supplied the locations).
            verify(forecastEvaluationRepository, never())
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), any(), any());
            verify(locationService, never()).findAllEnabled();
        }

        @Test
        @DisplayName("returns empty (no NONE views) when there is no cached score")
        void noCache_returnsEmpty() {
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of());

            List<LocationEvaluationView> views = service.cachedOnlyViewsForDateRange(
                    DATE, DATE, Set.of(SUNRISE), List.of(bamburgh));

            assertThat(views).isEmpty();
            verify(forecastEvaluationRepository, never())
                    .findLatestRunPerSlotByLocationIds(
                            anyCollection(), any(), any());
        }
    }

    @Nested
    @DisplayName("light times — the golden/blue hour boundaries (superset plan, Phase 2)")
    class LightTimes {

        @Test
        @DisplayName("a served sunrise row carries all four boundaries, chronologically ordered")
        void sunriseRowCarriesOrderedBoundaries() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            // ⚠️ The CLOCK VALUES, not just non-nullness, and this is the only assertion in the
            // nest that interrogates the calculator's output rather than its internal consistency.
            // Without it a transposed `goldenBlueWindow(loc.getLon(), loc.getLat(), ...)` — which
            // puts Bamburgh in the Seychelles — passes every other test here, because ordering,
            // shared-instant equality and per-location inequality all hold at any non-polar point.
            // The reference figures come from an INDEPENDENT NOAA solar-position solver run
            // outside this codebase, not from the code under test; the ±3 min window is that
            // approximation's own error against Meeus, not slack for a wrong answer.
            assertAlmanac(v.blueHourStart(), 4, 2);      // civil dawn, sun at −6°
            assertAlmanac(v.blueHourEnd(), 4, 44);       // sunrise
            assertAlmanac(v.goldenHourStart(), 4, 44);   // sunrise
            assertAlmanac(v.goldenHourEnd(), 5, 35);     // sun at +6°
            // SolarService's own documented sunrise semantics: civil dawn -> sunrise -> sunrise ->
            // +6 degrees. The shared instant is what makes "blue then golden" the chronological
            // order the client must print for a sunrise, so it is asserted rather than assumed.
            assertThat(v.blueHourStart()).isBefore(v.blueHourEnd());
            assertThat(v.blueHourEnd()).isEqualTo(v.goldenHourStart());
            assertThat(v.goldenHourStart()).isBefore(v.goldenHourEnd());
        }

        @Test
        @DisplayName("a served sunset row orders golden before blue — the mirror of sunrise")
        void sunsetRowOrdersGoldenBeforeBlue() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNSET))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).getFirst();

            assertAlmanac(v.goldenHourStart(), 18, 37);  // sun at +6°
            assertAlmanac(v.goldenHourEnd(), 19, 28);    // sunset
            assertAlmanac(v.blueHourStart(), 19, 28);    // sunset
            assertAlmanac(v.blueHourEnd(), 20, 10);      // civil dusk, sun at −6°
            assertThat(v.goldenHourStart()).isBefore(v.goldenHourEnd());
            assertThat(v.goldenHourEnd()).isEqualTo(v.blueHourStart());
            assertThat(v.blueHourStart()).isBefore(v.blueHourEnd());
        }

        /**
         * Asserts a boundary lands within ±3 minutes of an externally computed UTC time for
         * Bamburgh on {@link #DATE}. See the caller for where the reference figures come from.
         */
        private void assertAlmanac(LocalDateTime actual, int hour, int minute) {
            LocalDateTime expected = LocalDateTime.of(DATE, java.time.LocalTime.of(hour, minute));
            assertThat(actual)
                    .isBetween(expected.minusMinutes(3), expected.plusMinutes(3));
        }

        @Test
        @DisplayName("the times are this location's own — two locations on one date differ")
        void timesArePerLocation() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of(
                            "Bamburgh", new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "a"),
                            "Sandsend", new BriefingEvaluationResult("Sandsend", 3, 50, 40, "b")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            any(), any(), any()))
                    .thenReturn(Optional.empty());

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            // The whole point of serving these per row rather than per window: 110 miles of
            // latitude is minutes of sunrise, and the sheet is about ONE place. A single
            // roster-wide time would be somebody else's clock, which is the defect the sheet's
            // own event time already had to fix.
            assertThat(views.getFirst().goldenHourEnd())
                    .isNotEqualTo(views.get(1).goldenHourEnd());
        }

        @Test
        @DisplayName("the times are this date's own — one location over two dates differs")
        void timesArePerDate() {
            LocalDate next = DATE.plusDays(1);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "a")));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, next, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "a")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            any(), any(), any()))
                    .thenReturn(Optional.empty());

            // The third axis of the join. Location and event are pinned by the tests either side
            // of this one; without this, a `withLight` that read a fixed date would pass them all.
            LocationEvaluationView today = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();
            LocationEvaluationView tomorrow = service.forRegion(REGION_ID, next, SUNRISE).getFirst();

            assertThat(today.goldenHourEnd().toLocalDate()).isEqualTo(DATE);
            assertThat(tomorrow.goldenHourEnd().toLocalDate()).isEqualTo(next);
        }

        @Test
        @DisplayName("a triaged row still carries them — light is astronomy, not an evaluation")
        void triagedRowStillCarriesThem() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.of(ForecastEvaluationEntity.builder()
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "Low cloud 85%"))
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
            assertThat(v.goldenHourStart()).isNotNull();
            assertThat(v.blueHourEnd()).isNotNull();
        }

        @Test
        @DisplayName("forDateRange carries them onto every emitted row")
        void forDateRangeCarriesThem() throws Exception {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            CachedEvaluationEntity dbEntry = new CachedEvaluationEntity();
            dbEntry.setCacheKey(REGION_NAME + "|" + DATE + "|SUNRISE");
            dbEntry.setEvaluationDate(DATE);
            dbEntry.setTargetType("SUNRISE");
            dbEntry.setResultsJson(new ObjectMapper().writeValueAsString(List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "nice"))));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of(dbEntry));
            when(forecastEvaluationRepository.findLatestRunPerSlotByLocationIds(
                    anyCollection(), eq(DATE), eq(DATE))).thenReturn(List.of());

            List<LocationEvaluationView> views =
                    service.forDateRange(DATE, DATE, Set.of(SUNRISE));

            // ⚠️ The ordering, per-location and per-date claims above all ride `forRegion`, which
            // has NO production caller — `/api/briefing/evaluate/scores` reaches `forDateRange`.
            // So the served path asserts values of its own rather than mere attachment; otherwise
            // it could be hoisted, memoised or moved with every claim still green.
            assertThat(views).hasSize(1);
            LocationEvaluationView v = views.getFirst();
            assertAlmanac(v.blueHourStart(), 4, 2);
            assertAlmanac(v.blueHourEnd(), 4, 44);
            assertAlmanac(v.goldenHourStart(), 4, 44);
            assertAlmanac(v.goldenHourEnd(), 5, 35);
        }

        @Test
        @DisplayName("⚠️ the MAP's own path pays for none of it — a value nothing there reads")
        void cachedOnlyPathAttachesNothing() throws Exception {
            // `cachedOnlyViewsForDateRange` feeds `GET /api/forecast`, whose mapper
            // (`toSparseListDto`) reads none of the four and whose controller drops most of these
            // rows anyway as already covered by a persisted forecast row. Attaching in the shared
            // `buildViews` spent three Meeus calls per cached-only row on every map mount for
            // nothing. One endpoint serialises these fields; exactly one path may pay for them.
            CachedEvaluationEntity dbEntry = new CachedEvaluationEntity();
            dbEntry.setCacheKey(REGION_NAME + "|" + DATE + "|SUNRISE");
            dbEntry.setEvaluationDate(DATE);
            dbEntry.setTargetType("SUNRISE");
            dbEntry.setResultsJson(new ObjectMapper().writeValueAsString(List.of(
                    new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "nice"))));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(DATE))
                    .thenReturn(List.of(dbEntry));

            List<LocationEvaluationView> views = service.cachedOnlyViewsForDateRange(
                    DATE, DATE, Set.of(SUNRISE), List.of(bamburgh));

            assertThat(views).hasSize(1);
            assertThat(views.getFirst().rating()).isEqualTo(4);
            assertThat(views.getFirst().goldenHourStart()).isNull();
            assertThat(views.getFirst().blueHourStart()).isNull();
        }

        @Test
        @DisplayName("⚠️ a midnight sentinel is dropped, not served as a real clock time")
        void midnightSentinelIsDropped() {
            // solar-utils returns midnight-of-the-date — NOT null, NOT a throw — for an event that
            // never occurs: `hourAngle` takes acos of an out-of-domain cosine, gets NaN, and
            // `Math.round(NaN)` is 0. So the try/catch this method also has could never have caught
            // the polar case it was written for, and a Shetland midwinter sunset would have served
            // `golden 00:00–14:49`: a fabricated fourteen-hour golden hour that passes every
            // bracket check, because 00:00 genuinely is before sunset.
            SolarService sentinel = mock(SolarService.class);
            LocalDateTime midnight = DATE.atStartOfDay();
            when(sentinel.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                    .thenReturn(new SolarService.SolarWindow(
                            midnight, LocalDateTime.of(DATE, java.time.LocalTime.of(4, 44)),
                            LocalDateTime.of(DATE, java.time.LocalTime.of(4, 44)),
                            LocalDateTime.of(DATE, java.time.LocalTime.of(5, 35))));
            EvaluationViewService polar = new EvaluationViewService(
                    briefingEvaluationService, cachedEvaluationRepository,
                    forecastEvaluationRepository, locationService,
                    new ObjectMapper(), sentinel);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = polar.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            // PER BOUNDARY, not all-or-nothing: the blue window loses its start and is dropped by
            // the client, while the golden window is a true partial answer and survives.
            assertThat(v.blueHourStart()).isNull();
            assertAlmanac(v.blueHourEnd(), 4, 44);
            assertAlmanac(v.goldenHourStart(), 4, 44);
            assertAlmanac(v.goldenHourEnd(), 5, 35);
        }

        @Test
        @DisplayName("a calculator failure degrades to no times — the row is still served")
        void calculatorFailureDegradesToSilence() {
            // The polar edge case, forced. Silence, never synthesis: a row that cannot be given a
            // light line keeps everything else it had rather than being dropped or given guesses.
            SolarService throwing = mock(SolarService.class);
            when(throwing.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("no sunrise at this latitude"));
            EvaluationViewService degraded = new EvaluationViewService(
                    briefingEvaluationService, cachedEvaluationRepository,
                    forecastEvaluationRepository, locationService,
                    new ObjectMapper(), throwing);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v = degraded.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.summary()).isEqualTo("Great sky");
            assertThat(v.goldenHourStart()).isNull();
            assertThat(v.goldenHourEnd()).isNull();
            assertThat(v.blueHourStart()).isNull();
            assertThat(v.blueHourEnd()).isNull();
        }

        @Test
        @DisplayName("an HOURLY row gets none — there is no solar event to bound")
        void hourlyRowGetsNone() {
            // The one guard `withLight` keeps. HOURLY is the comfort-row target type (wildlife and
            // waterfall sites); it names a whole day rather than a moment, so a "golden hour for
            // this event" is a question it does not ask. The first cut also guarded a null
            // location, a null date and a null target type — all three unreachable from either
            // call site, so they were dead branches dragging JaCoCo down while this real one had
            // no test at all.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, TargetType.HOURLY))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, TargetType.HOURLY))
                    .thenReturn(Optional.empty());

            LocationEvaluationView v =
                    service.forRegion(REGION_ID, DATE, TargetType.HOURLY).getFirst();

            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.goldenHourStart()).isNull();
            assertThat(v.goldenHourEnd()).isNull();
            assertThat(v.blueHourStart()).isNull();
            assertThat(v.blueHourEnd()).isNull();
        }
    }
}
