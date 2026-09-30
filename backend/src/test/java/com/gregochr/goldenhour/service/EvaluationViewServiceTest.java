package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.CachedEvaluationEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TriageDetails;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.LocationEvaluationView.Source;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private ForecastRunDispositionRepository forecastRunDispositionRepository;
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
                forecastEvaluationRepository, forecastRunDispositionRepository, locationService,
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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.fierySkyPotential()).isEqualTo(50);
            assertThat(v.goldenHourPotential()).isEqualTo(40);
            assertThat(v.triageReason()).isNull();
            assertThat(v.triageMessage()).isNull();

            // evaluatedAt must be the forecastRunAt read as a naive UTC wall clock — the zone it is
            // actually stamped in (ForecastService.buildEntity: LocalDateTime.now(ZoneOffset.UTC)).
            Instant expectedInstant = runAt.toInstant(ZoneOffset.UTC);
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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(triageRow));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(scoredRow));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .rating(2).fierySkyPotential(30).goldenHourPotential(25)
                            .summary("Mediocre").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            3L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            5L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            any(), any(), any(), any());
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

            // The superseded per-location shape is gone entirely; the other retired shape
            // (per-slot findTop) is still on the repository but unused by this path.
            verify(forecastEvaluationRepository, never())
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            any(), any(), any(), any());
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

        /** A triaged row for Bamburgh, run at the given naive UTC wall-clock time. */
        private void triagedRunAt(LocalDateTime runAt) {
            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "Low cloud 94% — sun blocked"))
                    .forecastRunAt(runAt)
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));
        }

        /**
         * ⚠️ Stamps the write time on BOTH the per-location result and the region entry.
         *
         * <p>Every production write path stamps the per-location field, so an entry carrying only
         * a region stamp is a <em>legacy</em> row, not a live one — and while this helper built the
         * 5-arg (stampless) shape, every test below was pinning the tie rule and the BST conversion
         * on the fallback branch alone. Setting both keeps each assertion below saying exactly what
         * it said before, on the branch production actually takes. The legacy branch keeps its own
         * fixture in {@code TriageRatchet}.
         */
        private void cachedRatingAt(int rating, Instant writtenAt) {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", rating, 80, 70, "Worth it",
                                    null, null, null, writtenAt)));
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

        /** A Bamburgh row run at the given naive UTC wall-clock time, carrying NO rating and NO triage. */
        private void emptyRowRunAt(LocalDateTime runAt) {
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
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
            // forecast_run_at is a naive UTC wall clock (ForecastService.buildEntity stamps
            // LocalDateTime.now(ZoneOffset.UTC)), so a naive 00:05 IS 00:05Z — no conversion. Same
            // instant as the cache, so this is one run writing the forecast row and the cache
            // together; the cache carries strictly more.
            cachedRatingAt(3, Instant.parse("2026-04-23T00:05:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 0, 5));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(3);
        }

        /**
         * ⚠️ {@code forecastRunAt} is a naive UTC wall clock, not London — see {@link
         * EvaluationViewService#forecastRunInstant}'s javadoc for the production defect (a Codex
         * review of #940) this class of test now guards against: zoning it as London made every
         * comparison read one hour off during BST, which could let a cached rating outrank a
         * genuinely newer triage row (or vice versa) whenever the two landed within an hour of each
         * other — exactly the gap the nightly cycle produces routinely. Six cases below, at three
         * gap sizes and in both a BST month and a GMT month, prove the fix holds regardless of
         * season — a shifted-offset "fix" that merely swapped which direction was wrong would still
         * fail half of these.
         */
        @Test
        @DisplayName("BST: cached 13:30Z loses to a row run at 14:04 (naive UTC wall clock)")
        void bstCached1330LosesToRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-04-23T13:30:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 14, 4));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
            assertThat(v.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }

        @Test
        @DisplayName("BST: cached 13:50Z (a 14-minute gap) still loses to a row run at 14:04")
        void bstCached1350LosesToRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-04-23T13:50:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 14, 4));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("BST: a genuinely newer cache at 14:10Z still beats a row run at 14:04")
        void bstCached1410BeatsRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-04-23T14:10:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 4, 23, 14, 4));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("GMT: cached 13:30Z loses to a row run at 14:04 — same answer as BST")
        void gmtCached1330LosesToRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-01-23T13:30:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 1, 23, 14, 4));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("GMT: cached 13:50Z (a 14-minute gap) still loses to a row run at 14:04")
        void gmtCached1350LosesToRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-01-23T13:50:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 1, 23, 14, 4));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("GMT: a genuinely newer cache at 14:10Z still beats a row run at 14:04")
        void gmtCached1410BeatsRowAt1404() {
            cachedRatingAt(4, Instant.parse("2026-01-23T14:10:00Z"));
            triagedRunAt(LocalDateTime.of(2026, 1, 23, 14, 4));

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
    @DisplayName("The triage ratchet — a retained rating must not inherit the region's write time")
    class TriageRatchet {

        // The production shape, confirmed 2026-08-28. Two locations 11 km apart share one region
        // cache entry. The 01:22 overnight batch scored both. At 14:04 the briefing cycle's triage
        // read 84% low cloud at the first one's solar horizon and stood the slot down, which
        // EXCLUDED it from the 14:04 batch; the second passed triage, was in that batch, and was
        // re-scored. The 14:09 merge retained the first location's entry untouched — documented
        // design, since no path deletes individual rows — and moved the REGION stamp to 14:09, past
        // the 14:04 stand-down that had just excluded it.
        //
        // Gated on the region stamp, the retained rating therefore outranked its own contradiction,
        // and the worse the weather got the more firmly it held: only a fresh evaluation could
        // overwrite it, and only passing triage produced one. Bamburgh plays the stood-down
        // location here, Sandsend the re-scored one.
        //
        // forecast_run_at is a naive UTC wall clock, so these naive values ARE their own instant —
        // 15:04 here IS 15:04Z, with no conversion. The margins between these constants and
        // AFTERNOON_MERGE are all several hours, so none of the tests below that use them turn on
        // that margin; the one test that needs a genuinely close margin
        // (aNewerPerLocationStampStillBeatsATriageRow) uses its own dedicated literal instead.
        private static final Instant OVERNIGHT_BATCH = Instant.parse("2026-04-23T01:22:00Z");
        private static final LocalDateTime OVERNIGHT_TRIAGE = LocalDateTime.of(2026, 4, 23, 2, 5);
        private static final LocalDateTime AFTERNOON_TRIAGE = LocalDateTime.of(2026, 4, 23, 15, 4);
        private static final Instant AFTERNOON_MERGE = Instant.parse("2026-04-23T14:09:00Z");

        private static final String KEY = REGION_NAME + "|" + DATE + "|" + SUNSET;

        /**
         * The region entry exactly as the 14:09 merge left it: Bamburgh's overnight 4 retained with
         * its own 01:22 stamp, Sandsend re-scored to 2 and stamped 14:09, and the region-level
         * stamp at 14:09 for both.
         */
        private void regionEntryAfterPartialRefresh() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of(
                            "Bamburgh", new BriefingEvaluationResult("Bamburgh", 4, 58, 52,
                                    "Solar horizon opens to 57%", null, null,
                                    "Fiery skies at dusk", OVERNIGHT_BATCH),
                            "Sandsend", new BriefingEvaluationResult("Sandsend", 2, 20, 18,
                                    "A wall of low cloud, no light penetration", null, null,
                                    "Low cloud shuts the door", AFTERNOON_MERGE)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.of(AFTERNOON_MERGE));
        }

        /**
         * The latest row for each location. Bamburgh's is this afternoon's stand-down. Sandsend's
         * is an OVERNIGHT stand-down, superseded by the 14:09 evaluation that is now in the cache.
         *
         * <p>⚠️ Both rows must carry a triage reason, and an earlier draft gave Sandsend a bare
         * row instead. That was realistic — a <em>passing</em> triage writes no row at all, since
         * both {@code repository.save} calls in {@code ForecastService.fetchWeatherAndTriage} sit
         * inside an {@code isPresent()} branch — but it made the neighbour assertion decoration:
         * {@code cachedWins} short-circuits on {@code || !hasSomethingToSay(forecastRow)}, so
         * Sandsend won without the freshness comparison being consulted at all, and every mutation
         * of the stamp resolution left it green.
         */
        private void triageRowsLatestPerLocation() {
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD,
                                    "Low cloud 84% at the solar horizon — sun blocked"))
                            .forecastRunAt(AFTERNOON_TRIAGE)
                            .build()));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD,
                                    "Low cloud 91% overnight — sun blocked"))
                            .forecastRunAt(OVERNIGHT_TRIAGE)
                            .build()));
        }

        @Test
        @DisplayName("The retained rating loses to the stand-down that excluded it — the ratchet")
        void retainedRatingLosesToTheStandDownThatExcludedIt() {
            regionEntryAfterPartialRefresh();
            triageRowsLatestPerLocation();

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).getFirst();

            assertThat(v.locationName()).isEqualTo("Bamburgh");
            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
            assertThat(v.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }

        @Test
        @DisplayName("...while its re-scored neighbour in the SAME entry keeps its rating")
        void theRefreshedNeighbourInTheSameEntryIsUntouched() {
            // The pair, and the objection to check first: reading the per-location stamp must not
            // become a way to blank every cached rating in a region one location was stood down in.
            //
            // What this pins that the two single-location tests cannot: BOTH outcomes are reached
            // from ONE region entry in ONE call, so the gate is proved to resolve per LOCATION. A
            // mutant that decided once per cache key — the shape the region stamp invites — passes
            // every other test in this class and fails here. Sandsend's own stand-down is the
            // overnight one its 14:09 evaluation superseded, so it wins on the freshness comparison
            // itself rather than by having nothing to argue with.
            regionEntryAfterPartialRefresh();
            triageRowsLatestPerLocation();

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).get(1);

            assertThat(v.locationName()).isEqualTo("Sandsend");
            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("A per-location stamp NEWER than a triage row still wins — not a lose-always rule")
        void aNewerPerLocationStampStillBeatsATriageRow() {
            // The other edge of the same comparison. Here the region stamp is the misleading one in
            // the opposite direction — older than the row — and the location's own write is newer,
            // so the cache must win. Without this the change could pass every test above by simply
            // preferring the forecast row whenever the two stamps differ.
            //
            // A dedicated, close-margin literal rather than AFTERNOON_TRIAGE: the cache (14:09Z) must
            // be newer than the row by minutes, not hours, or a mutant that always prefers the cache
            // would pass this test too.
            LocalDateTime rowRunAt = LocalDateTime.of(2026, 4, 23, 14, 4);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh", new BriefingEvaluationResult(
                            "Bamburgh", 3, 40, 35, "Thin strip may light up",
                            null, null, null, AFTERNOON_MERGE)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.of(OVERNIGHT_BATCH));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "84% low cloud"))
                            .forecastRunAt(rowRunAt)
                            .build()));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("A legacy entry with no per-location stamp falls back to the region stamp")
        void legacyEntryWithoutAPerLocationStampFallsBackToTheRegionStamp() {
            // Results cached before the field existed carry no write time of their own, so they get
            // the answer they have always had rather than a newly invented one.
            //
            // ⚠️ The region stamp here is OLDER than the row, so the cache must LOSE — and that
            // direction is the whole test. Written the other way round, with a region stamp newer
            // than the row, "consult the fallback" and "treat a null as unknown and keep the cache"
            // return the same answer, so deleting the fallback outright would leave it green.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 58, 52, "Legacy entry")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.of(OVERNIGHT_BATCH));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "84% low cloud"))
                            .forecastRunAt(AFTERNOON_TRIAGE)
                            .build()));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_TRIAGE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("A winning cached view reports ITS OWN write time, not the region's")
        void aWinningCachedViewReportsItsOwnWriteTime() {
            // The view's evaluatedAt is served as `forecastRunAt` on the map DTO — the "generated
            // at" a reader judges the forecast's age by. Carrying the region stamp told them a
            // location was scored at whatever time some other location's batch happened to land,
            // and it would now differ from the instant the gate above actually compared.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh", new BriefingEvaluationResult(
                            "Bamburgh", 4, 58, 52, "Solar horizon opens to 57%",
                            null, null, null, OVERNIGHT_BATCH)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.of(AFTERNOON_MERGE));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNSET).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.evaluatedAt()).isEqualTo(OVERNIGHT_BATCH).isNotEqualTo(AFTERNOON_MERGE);
        }

        /**
         * The same two latest rows in the bulk query shape the enrichment paths use.
         *
         * <p>Sandsend's row carries a reason here for the same reason it does on the view path: a
         * row with nothing to say makes {@code toEnrichmentResult} return null, and both enrichment
         * paths then leave the cached entry in place — via {@code putIfAbsent} on the per-region
         * path and via the pre-seeded region map on the bulk one — so its rating would survive
         * whatever the gate decided.
         */
        private void triageRowsForEnrichment() {
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(
                            ForecastEvaluationEntity.builder()
                                    .location(bamburgh).targetDate(DATE).targetType(SUNSET)
                                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD,
                                            "Low cloud 84% at the solar horizon — sun blocked"))
                                    .forecastRunAt(AFTERNOON_TRIAGE)
                                    .build(),
                            ForecastEvaluationEntity.builder()
                                    .location(sandsend).targetDate(DATE).targetType(SUNSET)
                                    .triage(new TriageDetails(TriageReason.HIGH_CLOUD,
                                            "Low cloud 91% overnight — sun blocked"))
                                    .forecastRunAt(OVERNIGHT_TRIAGE)
                                    .build()));
        }

        @Test
        @DisplayName("The briefing payload retracts it too — one rule, not one surface")
        void theBriefingPayloadRetractsItToo() {
            // The Plan tab reads through the enrichment path, the map through the view path. Fixing
            // one and not the other is how this merge came to run under three different rules once
            // before (PR #405) — the same location reading 4 on one panel and 2 on another.
            regionEntryAfterPartialRefresh();
            triageRowsForEnrichment();

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNSET);

            assertThat(result.get("Bamburgh").rating()).isNull();
            assertThat(result.get("Bamburgh").triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
            assertThat(result.get("Sandsend").rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("...and so does the BULK serve path a re-served briefing actually uses")
        void theBulkServePathRetractsItToo() {
            regionEntryAfterPartialRefresh();
            triageRowsForEnrichment();

            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNSET));

            assertThat(index.get(KEY).get("Bamburgh").rating()).isNull();
            assertThat(index.get(KEY).get("Bamburgh").triageReason())
                    .isEqualTo(TriageReason.HIGH_CLOUD);
            assertThat(index.get(KEY).get("Sandsend").rating()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Enrichment freshness gate — the briefing payload obeys the same rule as the view")
    class EnrichmentFreshness {

        private static final String KEY = REGION_NAME + "|" + DATE + "|" + SUNSET;

        /**
         * A cached rating for Bamburgh written at the given instant, stamped on BOTH the
         * per-location result and the region entry — see the twin helper in {@code MergeFreshness}
         * for why the per-location one is not optional.
         */
        private void cachedRatingAt(int rating, Instant writtenAt) {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", rating, 80, 70, "Worth it",
                                    null, null, null, writtenAt)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNSET))
                    .thenReturn(Optional.ofNullable(writtenAt));
        }

        /** A scored Bamburgh row run at the given naive UTC wall-clock time. */
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
            // forecast_run_at is a naive UTC wall clock, so a naive 22:29 IS 22:29Z — no conversion.
            // Same instant as the cache, so this is one run writing the forecast row and the cache
            // together. The tie resolves to the cache for determinism and to match mergeToView, NOT
            // because the row is poorer — a scored row carries its own summary and headline too.
            cachedRatingAt(4, Instant.parse("2026-07-31T22:29:00Z"));
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
        @DisplayName("A cache written minutes after the row still wins — no zone conversion involved")
        void cacheWrittenMinutesAfterTheRowStillWins() {
            // forecast_run_at is a naive UTC wall clock (see EvaluationViewService
            // #forecastRunInstant's javadoc for the production defect — a Codex review of #940 —
            // this class of test used to encode: zoning it as London made a naive 22:30 read as
            // 21:30Z during BST, which could invert a close-margin comparison like this one). A
            // cache written at 22:30Z, thirty minutes after a row run at 22:00, must win — compared
            // with either side wrongly zoned, the relationship would flip.
            cachedRatingAt(4, Instant.parse("2026-07-31T22:30:00Z"));
            scoredRunAt(2, LocalDateTime.of(2026, 7, 31, 22, 0));

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNSET, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            any(), any(), any(), any()))
                    .thenReturn(List.of());

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
                            any(), any(), any(), any()))
                    .thenReturn(List.of());

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
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
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
                    forecastEvaluationRepository, forecastRunDispositionRepository, locationService,
                    new ObjectMapper(), sentinel);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                    forecastEvaluationRepository, forecastRunDispositionRepository, locationService,
                    new ObjectMapper(), throwing);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

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
                            1L, DATE, TargetType.HOURLY, PageRequest.of(0, 1)))
                    .thenReturn(List.of());

            LocationEvaluationView v =
                    service.forRegion(REGION_ID, DATE, TargetType.HOURLY).getFirst();

            assertThat(v.rating()).isEqualTo(4);
            assertThat(v.goldenHourStart()).isNull();
            assertThat(v.goldenHourEnd()).isNull();
            assertThat(v.blueHourStart()).isNull();
            assertThat(v.blueHourEnd()).isNull();
        }
    }

    /**
     * A nightly Gate 4 stability skip is evidence that outdates a cached rating or a
     * {@code forecast_evaluation} row exactly like a newer row would — except it writes no row to
     * either table, so it has to be checked separately (see the class javadoc on
     * {@link EvaluationViewService#retractStaleEvidence}). These tests drive that rule through the
     * real public entry points, mocking only {@link ForecastRunDispositionRepository
     * #findLatestStabilitySkipTimestamps}, which is exactly what production's bulk query returns.
     */
    @Nested
    @DisplayName("stability-skip retraction")
    class StabilitySkipRetraction {

        private static final Instant CACHED_AT = Instant.parse("2026-04-22T01:00:00Z");

        /**
         * Builds one row of what {@link ForecastRunDispositionRepository
         * #findLatestStabilitySkipTimestamps} returns: locationName, evaluationDate, eventType
         * name, and the instant of that slot's most recent {@code SKIPPED_STABILITY} disposition.
         */
        private static Object[] skipRow(String locationName, LocalDate date, TargetType type,
                Instant lastSkippedAt) {
            return new Object[] {locationName, date, type.name(), lastSkippedAt};
        }

        @Test
        @DisplayName("a skip newer than the cached rating retracts it — served as never-rated")
        void skipNewerThanRatingRetracts() {
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.NONE);
            assertThat(v.rating()).isNull();
            assertThat(v.summary()).isNull();
            assertThat(v.triageReason()).isNull();
            assertThat(v.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        }

        @Test
        @DisplayName("a skip older than the cached rating keeps it")
        void skipOlderThanRatingKeeps() {
            Instant skipAt = CACHED_AT.minusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("a later real evaluation after the skip restores a rating")
        void laterEvaluationAfterSkipRestores() {
            // The cached rating predates the skip (stale, retracted), but a later run — visible
            // here as a forecast_evaluation row whose run instant is AFTER the skip — is a decision
            // the pipeline made about this slot since, and must win normally.
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 2, 30, 20, "Grey")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            // forecast_run_at is a naive UTC wall clock, so a naive 04:00 IS 04:00Z — after the
            // 02:00Z skip, with no zone conversion involved.
            ForecastEvaluationEntity later = ForecastEvaluationEntity.builder()
                    .rating(5).fierySkyPotential(90).goldenHourPotential(85)
                    .summary("Clear now").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 4, 0))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(later));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_SCORED);
            assertThat(v.rating()).isEqualTo(5);
            assertThat(v.summary()).isEqualTo("Clear now");
        }

        @Test
        @DisplayName("Codex #940: a forecast row run at 01:30 survives a 01:00Z skip — no cache "
                + "involved, isolating the row-side retraction")
        void rowAt0130SurvivesA0100SkipOnItsOwn() {
            // The literal case a Codex review of #940 raised: forecastRunInstant used to zone this
            // naive value as Europe/London, reading 01:30 as 00:30Z during BST — BEFORE the skip —
            // and wrongly withdrawing a row the pipeline had not, in fact, decided against. Naive
            // UTC means 01:30 IS 01:30Z, after the skip, so the row must survive untouched.
            Instant skipAt = Instant.parse("2026-04-22T01:00:00Z");
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .rating(4).fierySkyPotential(70).goldenHourPotential(60)
                    .summary("Fiery dawn").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 1, 30))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.FORECAST_EVALUATION_SCORED);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("Codex #940 mirror: a forecast row run at 00:30 IS withdrawn by a 01:00Z skip")
        void rowAt0030IsWithdrawnByA0100SkipOnItsOwn() {
            // The mirror of the case above: 00:30Z genuinely predates the 01:00Z skip, so this row
            // (with no cache to fall back to) must be retracted down to nothing being served.
            Instant skipAt = Instant.parse("2026-04-22T01:00:00Z");
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                    .rating(4).fierySkyPotential(70).goldenHourPotential(60)
                    .summary("Fiery dawn").evaluationModel(EvaluationModel.HAIKU)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 0, 30))
                    .build();
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(row));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.NONE);
            assertThat(v.rating()).isNull();
        }

        @Test
        @DisplayName("a legacy entry with no known write time keeps serving despite a skip")
        void legacyNullEvaluatedAtKeeps() {
            // Neither the per-location evaluatedAt nor the region stamp is known — the same
            // "unknown age keeps the cache" convention cachedIsAtLeastAsFresh already applies.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.empty());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE,
                            Instant.parse("2026-04-23T09:00:00Z"))));

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("a region-level CACHED skip carries no stability-skip signal, so it never retracts")
        void cachedSkipNeverRetracts() {
            // BriefingCandidateCollector's region-level "fresh cache, deliberately reused" skip is
            // not SKIPPED_STABILITY, so production's findLatestStabilitySkipTimestamps (filtered to
            // that one category — see ForecastRunDispositionRepositoryTest for the SQL-level proof)
            // never returns a row for it; simulated here by the repository legitimately returning
            // nothing for this slot.
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.of());

            LocationEvaluationView v = service.forRegion(REGION_ID, DATE, SUNRISE).getFirst();

            assertThat(v.source()).isEqualTo(Source.CACHED_EVALUATION);
            assertThat(v.rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("a retracted slot reads exactly like a genuinely never-rated slot")
        void retractedSlotMatchesNeverRatedSlot() {
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh, sandsend));
            // Bamburgh has a rating that will be retracted; Sandsend has never been evaluated.
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            2L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);
            LocationEvaluationView retracted = views.stream()
                    .filter(v -> v.locationName().equals("Bamburgh")).findFirst().orElseThrow();
            LocationEvaluationView neverRated = views.stream()
                    .filter(v -> v.locationName().equals("Sandsend")).findFirst().orElseThrow();

            assertThat(retracted.source()).isEqualTo(neverRated.source()).isEqualTo(Source.NONE);
            assertThat(retracted.displayVerdict()).isEqualTo(neverRated.displayVerdict())
                    .isEqualTo(DisplayVerdict.AWAITING);
            assertThat(retracted.rating()).isEqualTo(neverRated.rating());
            assertThat(retracted.summary()).isEqualTo(neverRated.summary());
            assertThat(retracted.triageReason()).isEqualTo(neverRated.triageReason());
            assertThat(retracted.triageMessage()).isEqualTo(neverRated.triageMessage());
        }

        @Test
        @DisplayName("the Plan payload and the scores view agree: both retract the same stale rating")
        void planPayloadAndScoresViewAgree() {
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            LocalDate start = DATE;
            LocalDate end = DATE;

            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(cachedEvaluationRepository.findByEvaluationDateGreaterThanEqual(start))
                    .thenReturn(List.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(List.of(1L), start, end))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(start, end))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            // Plan tab side — BriefingRegionEvaluationRollup.enrich resolves through this map. The
            // entry is the RETRACTED MARKER, not an absent key — enrichSlot must be able to tell
            // "this slot's rating is retracted" apart from "the resolver never covered this slot",
            // because the persisted BriefingSlot may already carry an embedded claudeRating from an
            // earlier build that an absent entry would leave untouched. See
            // BriefingRegionEvaluationRollupTest for that half of the fix.
            Map<String, Map<String, BriefingEvaluationResult>> planIndex =
                    service.getScoresForEnrichmentBulk(start, end, Set.of(SUNRISE));
            String key = REGION_NAME + "|" + DATE + "|" + SUNRISE;
            BriefingEvaluationResult planResult =
                    planIndex.getOrDefault(key, Map.of()).get("Bamburgh");

            // /api/briefing/evaluate/scores and the map DTO path — both read forDateRange.
            List<LocationEvaluationView> scoresViews = service.forDateRange(
                    start, end, Set.of(SUNRISE));

            assertThat(planResult).isNotNull();
            assertThat(planResult.retracted()).isTrue();
            assertThat(planResult.rating()).isNull();
            assertThat(scoresViews).isEmpty();
        }

        @Test
        @DisplayName("getScoresForEnrichment returns the retracted marker, not a bare absent entry")
        void getScoresForEnrichmentReturnsRetractedMarker() {
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, skipAt)));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).containsKey("Bamburgh");
            assertThat(result.get("Bamburgh").retracted()).isTrue();
            assertThat(result.get("Bamburgh").rating()).isNull();
        }
    }

    /**
     * The verdict-minimum-sample rule's force-evaluation exemption (owner decision, 2026-09-29;
     * see {@code docs/engineering/plan-verdict-consolidation-plan.md} and
     * {@code VerdictSampleGate}) needs one fact per rated slot: was its CURRENT rating written by
     * a force evaluation? {@link BriefingEvaluationResult#forced} answers it directly — provenance
     * stamped ONCE, at write time, by {@code ForecastResultHandler#buildResult} — so unlike the
     * class's other resolver behaviour, these tests need no disposition-repository stubbing at
     * all: they seed a cached or forecast-row-derived result and assert what
     * {@link EvaluationViewService#getScoresForEnrichment}/{@code Bulk} read back off it.
     *
     * <p>⚠️ <b>This nested class replaces one that drove the exemption through a
     * disposition-timestamp comparison</b> ({@code EvaluationViewService#loadForceEvaluatedAt} /
     * {@code #isCurrentlyForced}, both since removed). A second Codex review of #943 (P1-B) found
     * that comparison provably wrong: {@code ScheduledBatchEvaluationService
     * #persistCycleDispositions} anchors every disposition in a cycle to the cycle's FIRST
     * submitted bucket's job run, not the specific bucket that actually force-evaluated a slot, so
     * an entirely unrelated, ordinary rating that merely landed after the disposition's timestamp
     * satisfied the same comparison and was wrongly exempted (a hand-started synchronous admin run
     * was the reported case). The regression coverage for that exact scenario — a forced batch
     * pending/failed at T1, an unrelated rating landing at T2 — now lives where the provenance is
     * actually decided: {@code ForecastResultHandlerTest} (does {@code buildResult} stamp {@code
     * forced} only from the task's own flag) and {@code CustomIdFactoryTest} (does the {@code -f}
     * marker survive the batch custom id round trip). This class's job narrows to "does the
     * resolver propagate whichever result's {@code forced} field it was handed, honestly, for both
     * readers, and never invent one for a forecast-row winner or a retraction".
     */
    @Nested
    @DisplayName("force-evaluation flag")
    class ForcedEvaluationFlag {

        @Test
        @DisplayName("a cached result stamped forced at write time reads forced")
        void cachedResultStampedForced_readsForced() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")
                                    .withForced(true)));

            BriefingEvaluationResult result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(result.rating()).isEqualTo(4);
            assertThat(result.forced()).isTrue();
        }

        @Test
        @DisplayName("an ordinary cached result (no forced stamp) reads not forced")
        void ordinaryCachedResult_readsNotForced() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));

            BriefingEvaluationResult result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(result.rating()).isEqualTo(4);
            assertThat(result.forced()).isFalse();
        }

        @Test
        @DisplayName("a forecast-row winner is never forced — the entity carries no such marker, "
                + "whatever a stale forced cache entry once said")
        void forecastRowWinner_neverForced() {
            // The cache is empty (or stale — either way it loses the precedence gate), so the
            // forecast_evaluation row wins. toEnrichmentResult builds a fresh
            // BriefingEvaluationResult via the plain convenience constructor, which always
            // defaults forced to false — ForecastEvaluationEntity has no forced column to read one
            // from (see BriefingEvaluationResult#forced's own javadoc for why that is safe:
            // unknown never grants the exemption).
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNRISE)
                            .rating(5).fierySkyPotential(90).goldenHourPotential(85)
                            .summary("Blazing").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            BriefingEvaluationResult result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(result.rating()).isEqualTo(5);
            assertThat(result.forced()).isFalse();
        }

        @Test
        @DisplayName("a triaged (unrated) slot never reads forced")
        void triagedSlot_neverReadsForced() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburgh).targetDate(DATE).targetType(SUNRISE)
                            .triage(new TriageDetails(TriageReason.PRECIPITATION, "Rain expected"))
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 6, 0))
                            .build()));

            BriefingEvaluationResult result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(result.rating()).isNull();
            assertThat(result.triageReason()).isEqualTo(TriageReason.PRECIPITATION);
            assertThat(result.forced()).isFalse();
        }

        @Test
        @DisplayName("getScoresForEnrichmentBulk agrees with getScoresForEnrichment on forced — "
                + "same slot, both readers, same stamped-forced cache entry")
        void bulkAgreesWithSingleRegion() {
            LocalDate start = DATE;
            LocalDate end = DATE;
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")
                                    .withForced(true)));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(List.of(1L), start, end))
                    .thenReturn(List.of());

            // Both readers now resolve every slot through the SAME private method,
            // resolveForEnrichmentRetractionAware — this proves that structurally, not just for
            // this fixture, by driving both public entry points against the identical stubs and
            // asserting they agree, rather than trusting the shared implementation by inspection
            // alone.
            Map<String, Map<String, BriefingEvaluationResult>> bulk =
                    service.getScoresForEnrichmentBulk(start, end, Set.of(SUNRISE));
            String key = REGION_NAME + "|" + DATE + "|" + SUNRISE;
            BriefingEvaluationResult bulkResult = bulk.get(key).get("Bamburgh");

            BriefingEvaluationResult singleResult =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(bulkResult.forced()).isTrue();
            assertThat(singleResult.forced()).isTrue();
            assertThat(bulkResult.forced()).isEqualTo(singleResult.forced());
        }

        @Test
        @DisplayName("force-evaluated then stability-skipped: retracted, contributes no rating, "
                + "and grants its region no exemption — withForced's null-rating guard on the "
                + "retraction marker, not branch ordering, is what stops the leak")
        void forcedThenStabilitySkipped_retractedContributesNoRatingNoExemption() {
            // Night one: a force evaluation rates this slot 4* and the result is stamped forced.
            // Night two: the nightly cycle declines to re-look at the slot (SKIPPED_STABILITY)
            // AFTER that force evaluation — the parent stability-skip retraction rule's own
            // scenario ("Where a rating lives" in CLAUDE.md). The two facts must combine to a
            // retraction that carries NO exemption, never to an exempt rating a region's verdict,
            // pick or ranking could use: a rating that no longer exists cannot be "the rating a
            // force evaluation wrote". BriefingEvaluationResult.retracted() always constructs with
            // forced = false regardless of what the superseded evidence carried, so this is never
            // reachable through the ordinary cache/forecast-row precedence at all.
            Instant forceEvaluatedAt = Instant.parse("2026-04-22T01:00:00Z");
            Instant skipAt = forceEvaluatedAt.plusSeconds(3600);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")
                                    .withForced(true)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(forceEvaluatedAt));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(List.of(1L), DATE, DATE))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            new Object[] {"Bamburgh", DATE, SUNRISE.name(), skipAt}));

            BriefingEvaluationResult single =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(single.retracted()).isTrue();
            assertThat(single.rating()).isNull();
            assertThat(single.forced()).isFalse();

            // The bulk read resolves the identical slot through the same
            // resolveForEnrichmentRetractionAware call and must agree — see
            // bulkAgreesWithSingleRegion, above, for the positive (unretracted) case this test
            // mirrors on the retracted side.
            Map<String, Map<String, BriefingEvaluationResult>> bulk = service
                    .getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNRISE));
            BriefingEvaluationResult bulkResult =
                    bulk.get(REGION_NAME + "|" + DATE + "|" + SUNRISE).get("Bamburgh");

            assertThat(bulkResult.retracted()).isTrue();
            assertThat(bulkResult.rating()).isNull();
            assertThat(bulkResult.forced()).isFalse();
        }
    }

    /**
     * The verdict-minimum-sample rule's "examined" evidence (a Codex review of #943, P1-A):
     * {@link EvaluationViewService#loadTriagedByBatch} must be sourced from the BATCH's own
     * {@code forecast_run_disposition} table, never from a slot's independently-computed
     * weather-triage {@code Verdict}. These tests drive it through the real public method, mocking
     * only {@link ForecastRunDispositionRepository#findLatestNonCachedDispositions} — exactly what
     * production's bulk query returns — and separately prove {@link
     * EvaluationViewService#getTriagedByBatchLocationNames}/{@code
     * getTriagedByBatchLocationNamesBulk} report the disposition-sourced set for a region/date/event,
     * INDEPENDENTLY of whatever {@code getScoresForEnrichment}/{@code Bulk} separately resolve for
     * the same slot.
     *
     * <p>⚠️ <b>A second Codex review of #943 (P1-A, round 2) replaced a synthetic {@code
     * BriefingEvaluationResult#triagedByBatch} marker with this dedicated resolver.</b> The marker
     * rode on the score map and was stamped only in the branch where {@code
     * resolveForEnrichmentRetractionAware} had nothing else to return — but production always
     * writes a real {@code forecast_evaluation} triage row alongside {@code SKIPPED_TRIAGED} (see
     * {@code ForecastService#fetchWeatherAndTriage}), so that branch almost never fired and {@code
     * VerdictSampleGate#examinedCount} silently collapsed to rated-only on the exact production
     * shape the gate exists to protect. The two lookups below now answer independently and are
     * combined by {@code BriefingRegionEvaluationRollup} itself, never by one deriving from the
     * other's map entry.
     */
    @Nested
    @DisplayName("batch-triage evidence for the examined count")
    class TriagedByBatch {

        private static Object[] nonCachedRow(String locationName, LocalDate date, TargetType type,
                String disposition, Instant createdAt) {
            return new Object[] {locationName, date, type.name(), disposition, createdAt};
        }

        @Test
        @DisplayName("a slot whose latest non-cached disposition is SKIPPED_TRIAGED is in the set")
        void latestSkippedTriaged_isInTheSet() {
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).containsExactly("Bamburgh|" + DATE + "|SUNRISE");
        }

        @Test
        @DisplayName("a slot whose latest non-cached disposition is SKIPPED_STABILITY is absent — "
                + "SKIPPED_STABILITY never counts as examined")
        void latestSkippedStability_isAbsent() {
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_STABILITY",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).isEmpty();
        }

        @Test
        @DisplayName("triaged last night, SKIPPED_CACHED today: still counts as examined — the "
                + "A3 decision. findLatestNonCachedDispositions excludes SKIPPED_CACHED from BOTH "
                + "sides of its correlated subquery, so the query itself re-surfaces last night's "
                + "SKIPPED_TRIAGED as \"the latest\" once tonight's SKIPPED_CACHED is out of scope")
        void triagedLastNightCachedTonight_stillCountsAsExamined() {
            // The repository method itself is what does the excluding (see its own javadoc) — this
            // test proves loadTriagedByBatch's OWN handling of whatever the query hands it, taking
            // "the query already filtered SKIPPED_CACHED out" as given, exactly as the sibling
            // force-evaluation tests take findLatestEvaluatingDispositions' own filtering as given.
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-21T20:00:00Z"))));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).containsExactly("Bamburgh|" + DATE + "|SUNRISE");
        }

        @Test
        @DisplayName("triaged last night, SKIPPED_STABILITY today: not examined — a later "
                + "non-cached decision supersedes the triage")
        void triagedLastNightStabilitySkippedTonight_notExamined() {
            // Unlike SKIPPED_CACHED, SKIPPED_STABILITY is NOT excluded from the query's scope, so
            // it legitimately becomes "the latest" and the earlier triage no longer counts.
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_STABILITY",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).isEmpty();
        }

        @Test
        @DisplayName("triaged last night, this slot's bucket fails to submit tonight "
                + "(SUBMISSION_FAILED): still counts as examined, exactly like SKIPPED_CACHED — "
                + "findLatestNonCachedDispositions excludes SUBMISSION_FAILED from BOTH sides of "
                + "its correlated subquery too, so last night's SKIPPED_TRIAGED is what the query "
                + "hands back as \"the latest\"")
        void triagedLastNightSubmissionFailedTonight_stillCountsAsExamined() {
            // As with the SKIPPED_CACHED sibling test above: the repository method itself is what
            // does the excluding (see its own javadoc) — this proves loadTriagedByBatch's OWN
            // handling of whatever the (now-corrected) query hands it, taking "the query already
            // filtered SUBMISSION_FAILED out" as given.
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-21T20:00:00Z"))));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).containsExactly("Bamburgh|" + DATE + "|SUNRISE");
        }

        @Test
        @DisplayName("a tie between SKIPPED_TRIAGED and a different category at the same instant "
                + "folds to NOT examined")
        void tieBetweenTriagedAndAnotherCategory_foldsToNotExamined() {
            Instant tieAt = Instant.parse("2026-04-22T01:00:00Z");
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED", tieAt),
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_STABILITY", tieAt)));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).isEmpty();
        }

        @Test
        @DisplayName("a failed lookup counts nothing as triaged — the safe under-counting direction")
        void failedLookup_countsNothing() {
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenThrow(new RuntimeException("DB unavailable"));

            Set<String> triaged = service.loadTriagedByBatch(DATE, DATE);

            assertThat(triaged).isEmpty();
        }

        @Test
        @DisplayName("getTriagedByBatchLocationNames reports a purely-batch-triaged slot (no "
                + "rating or cache entry of its own) — and getScoresForEnrichment separately "
                + "resolves nothing for it, proving the two lookups are genuinely independent")
        void getTriagedByBatchLocationNames_reportsPurelyBatchTriagedSlot() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Set<String> triagedNames =
                    service.getTriagedByBatchLocationNames(REGION_NAME, DATE, SUNRISE);
            BriefingEvaluationResult scoreResult =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            assertThat(triagedNames).containsExactly("Bamburgh");
            // No forecast_evaluation row was mocked at all (findLatestRunPerSlotByLocationIds
            // defaults to empty), so the score map has genuinely nothing to say for this slot —
            // exactly the "resolved to null" case BriefingRegionEvaluationRollup must still see
            // this slot as examined for, via the SEPARATE triagedResolver channel.
            assertThat(scoreResult).isNull();
        }

        @Test
        @DisplayName("getTriagedByBatchLocationNamesBulk agrees with getTriagedByBatchLocationNames "
                + "— same slot, both readers")
        void bulkAgreesWithSingleRegionOnTriagedNames() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Map<String, Set<String>> bulk =
                    service.getTriagedByBatchLocationNamesBulk(DATE, DATE, Set.of(SUNRISE));
            Set<String> bulkResult = bulk.get(REGION_NAME + "|" + DATE + "|" + SUNRISE);
            Set<String> singleResult =
                    service.getTriagedByBatchLocationNames(REGION_NAME, DATE, SUNRISE);

            assertThat(bulkResult).containsExactly("Bamburgh");
            assertThat(singleResult).containsExactly("Bamburgh");
        }

        @Test
        @DisplayName("getTriagedByBatchLocationNames reports a slot regardless of whether it is "
                + "SEPARATELY rated — the two lookups are independent, so a caller (the rollup) "
                + "combines them rather than either one silently overriding the other")
        void triagedNameSetIsIndependentOfWhatTheScoreMapResolves() {
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky")));
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            nonCachedRow("Bamburgh", DATE, SUNRISE, "SKIPPED_TRIAGED",
                                    Instant.parse("2026-04-22T01:00:00Z"))));

            Set<String> triagedNames =
                    service.getTriagedByBatchLocationNames(REGION_NAME, DATE, SUNRISE);
            BriefingEvaluationResult scoreResult =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE).get("Bamburgh");

            // The disposition-based lookup does not consult ratings at all — it still names the
            // slot. VerdictSampleGate#examinedCount's own claudeRating()==null guard, not this
            // lookup, is what prevents a rated slot from being double-counted.
            assertThat(triagedNames).containsExactly("Bamburgh");
            assertThat(scoreResult.rating()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("getLiveScoresForEnrichment — the raw-cache retraction gap (Codex review of #940 "
            + "at 92c2ad37)")
    class GetLiveScoresForEnrichment {

        private static final Instant CACHED_AT = Instant.parse("2026-04-22T01:00:00Z");

        private static Object[] skipRow(String locationName, LocalDate date, TargetType type,
                Instant lastSkippedAt) {
            return new Object[] {locationName, date, type.name(), lastSkippedAt};
        }

        private static LocationEntity location(long id, String name, RegionEntity region) {
            LocationEntity loc = new LocationEntity();
            loc.setId(id);
            loc.setName(name);
            loc.setRegion(region);
            loc.setLat(54.5 + id * 0.1);
            loc.setLon(-1.0 - id * 0.1);
            return loc;
        }

        /**
         * {@code PipelineRunPickService.lookupAverageRating} is the sole remaining caller of this
         * single-key method — see {@code PipelineRunPickServiceTest
         * .cached_scores_lookup_uses_pick_coordinates} for the pin on that call site.
         * {@code BriefingRollupBuilder.computeRegionStats} used to call this once per region/event
         * and now reads {@link #getLiveScoresForEnrichmentBulk} once per rollup instead — see
         * {@code BriefingBestBetAdvisorTest.cacheLookupUsesExactParameters} for that pin, and
         * {@link LiveScoresBulkAgreesWithSingle} below for proof the two accessors answer
         * identically for the same slot. These tests exercise the filtering itself: what the raw
         * {@code BriefingEvaluationService} cache would have said, versus what a caller that must
         * never see a retracted rating actually receives.
         */
        @Test
        @DisplayName("Codex's case: 5 cached ratings, 2 superseded by a newer stability skip — only "
                + "the 3 live ones survive")
        void fiveRatingsTwoRetractedByNewerSkip_onlyThreeLiveSurvive() {
            RegionEntity region = new RegionEntity();
            region.setId(REGION_ID);
            region.setName(REGION_NAME);
            LocationEntity bamburghL = location(1, "Bamburgh", region);
            LocationEntity sandsendL = location(2, "Sandsend", region);
            LocationEntity dunstanburghL = location(3, "Dunstanburgh", region);
            LocationEntity alnmouthL = location(4, "Alnmouth", region);
            LocationEntity crasterL = location(5, "Craster", region);
            when(locationService.findAllEnabled()).thenReturn(
                    List.of(bamburghL, sandsendL, dunstanburghL, alnmouthL, crasterL));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of(
                            "Bamburgh", new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Fiery"),
                            "Sandsend", new BriefingEvaluationResult("Sandsend", 4, 80, 70, "Good"),
                            "Dunstanburgh",
                            new BriefingEvaluationResult("Dunstanburgh", 3, 60, 50, "Decent"),
                            "Alnmouth", new BriefingEvaluationResult("Alnmouth", 2, 30, 20, "Poor"),
                            "Craster", new BriefingEvaluationResult("Craster", 1, 10, 5, "Very poor")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            // Alnmouth and Craster were skipped AFTER the cache was written; the other three were
            // not skipped at all (absent from the disposition rows).
            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            skipRow("Alnmouth", DATE, SUNRISE, skipAt),
                            skipRow("Craster", DATE, SUNRISE, skipAt)));

            Map<String, BriefingEvaluationResult> live =
                    service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(live).hasSize(3);
            assertThat(live.keySet()).containsExactlyInAnyOrder(
                    "Bamburgh", "Sandsend", "Dunstanburgh");
            assertThat(live.values()).noneMatch(BriefingEvaluationResult::retracted);
            assertThat(live.get("Bamburgh").rating()).isEqualTo(5);
            assertThat(live.get("Sandsend").rating()).isEqualTo(4);
            assertThat(live.get("Dunstanburgh").rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("A skip older than all 5 ratings retracts none of them — all 5 survive")
        void skipOlderThanAllRatings_allFiveSurvive() {
            RegionEntity region = new RegionEntity();
            region.setId(REGION_ID);
            region.setName(REGION_NAME);
            LocationEntity bamburghL = location(1, "Bamburgh", region);
            LocationEntity sandsendL = location(2, "Sandsend", region);
            LocationEntity dunstanburghL = location(3, "Dunstanburgh", region);
            LocationEntity alnmouthL = location(4, "Alnmouth", region);
            LocationEntity crasterL = location(5, "Craster", region);
            when(locationService.findAllEnabled()).thenReturn(
                    List.of(bamburghL, sandsendL, dunstanburghL, alnmouthL, crasterL));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of(
                            "Bamburgh", new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Fiery"),
                            "Sandsend", new BriefingEvaluationResult("Sandsend", 4, 80, 70, "Good"),
                            "Dunstanburgh",
                            new BriefingEvaluationResult("Dunstanburgh", 3, 60, 50, "Decent"),
                            "Alnmouth", new BriefingEvaluationResult("Alnmouth", 2, 30, 20, "Poor"),
                            "Craster", new BriefingEvaluationResult("Craster", 1, 10, 5, "Very poor")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            Instant skipAt = CACHED_AT.minusSeconds(3600);
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            skipRow("Alnmouth", DATE, SUNRISE, skipAt),
                            skipRow("Craster", DATE, SUNRISE, skipAt)));

            Map<String, BriefingEvaluationResult> live =
                    service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(live).hasSize(5);
            assertThat(live.values()).noneMatch(BriefingEvaluationResult::retracted);
        }

        @Test
        @DisplayName("Every rating retracted yields the same result as an empty cache")
        void everyRatingRetracted_matchesEmptyCache() {
            RegionEntity region = new RegionEntity();
            region.setId(REGION_ID);
            region.setName(REGION_NAME);
            LocationEntity bamburghL = location(1, "Bamburgh", region);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburghL));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 5, 90, 80, "Fiery")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            skipRow("Bamburgh", DATE, SUNRISE, CACHED_AT.plusSeconds(3600))));

            Map<String, BriefingEvaluationResult> live =
                    service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(live).isEmpty();
        }

        @Test
        @DisplayName("A rating superseded by a newer triage row is excluded, and never marked "
                + "retracted — triage is a different kind of newer evidence")
        void ratingSupersededByNewerTriage_excludedNotMarkedRetracted() {
            RegionEntity region = new RegionEntity();
            region.setId(REGION_ID);
            region.setName(REGION_NAME);
            LocationEntity bamburghL = location(1, "Bamburgh", region);
            when(locationService.findAllEnabled()).thenReturn(List.of(bamburghL));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh",
                            new BriefingEvaluationResult("Bamburgh", 4, 75, 60, "Great sky",
                                    null, null, null, CACHED_AT)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(bamburghL).targetDate(DATE).targetType(SUNRISE)
                            .triage(new TriageDetails(TriageReason.HIGH_CLOUD, "91% low cloud"))
                            .forecastRunAt(CACHED_AT.plusSeconds(3600)
                                    .atZone(ZoneOffset.UTC).toLocalDateTime())
                            .build()));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.of());

            Map<String, BriefingEvaluationResult> live =
                    service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            // Present (unlike a stability-skip retraction, which drops the entry entirely from
            // this method's output), but the stale numeric rating is gone — the rollup would
            // average over nothing for this location rather than the superseded 4.
            assertThat(live).containsKey("Bamburgh");
            BriefingEvaluationResult r = live.get("Bamburgh");
            assertThat(r.rating()).isNull();
            assertThat(r.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
            assertThat(r.retracted()).isFalse();
        }
    }

    @Nested
    @DisplayName("bulk and single live-score reads agree (Codex re-review of #940 at cbbb1b85)")
    class LiveScoresBulkAgreesWithSingle {

        private static final Instant CACHED_AT = Instant.parse("2026-04-22T01:00:00Z");

        private static Object[] skipRow(String locationName, LocalDate date, TargetType type,
                Instant lastSkippedAt) {
            return new Object[] {locationName, date, type.name(), lastSkippedAt};
        }

        private static LocationEntity location(long id, String name, RegionEntity region) {
            LocationEntity loc = new LocationEntity();
            loc.setId(id);
            loc.setName(name);
            loc.setRegion(region);
            loc.setLat(54.5 + id * 0.1);
            loc.setLon(-1.0 - id * 0.1);
            return loc;
        }

        /**
         * One region carrying every slot shape the query-count fix (a Codex re-review of #940)
         * must not have changed the ANSWER for, only the number of round trips: a retracted slot
         * (a stability skip newer than its cached rating), a triaged slot (a newer triage row
         * supersedes a cached rating), and a forecast-row-only slot (no cache entry at all, a
         * scored {@code forecast_evaluation} row). A fourth case — an uncovered region — is
         * checked with a second pair of calls against a region with no locations at all.
         * {@link EvaluationViewService#getLiveScoresForEnrichment} and
         * {@link EvaluationViewService#getLiveScoresForEnrichmentBulk} must return the identical
         * answer for every one of them, since {@code BriefingRollupBuilder} switched from the
         * single-key read (once per region/event) to one bulk read per rollup, and the two
         * accessors sharing a rule is what makes that switch safe.
         */
        @Test
        @DisplayName("bulk and single reads agree for a retracted, a triaged, a "
                + "forecast-row-only and an uncovered slot")
        void bulkAndSingleAgreeForEverySlotShape() {
            RegionEntity region = new RegionEntity();
            region.setId(REGION_ID);
            region.setName(REGION_NAME);
            LocationEntity retractedLoc = location(1, "Retracted", region);
            LocationEntity triagedLoc = location(2, "Triaged", region);
            LocationEntity forecastOnlyLoc = location(3, "ForecastOnly", region);
            when(locationService.findAllEnabled()).thenReturn(
                    List.of(retractedLoc, triagedLoc, forecastOnlyLoc));

            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of(
                            "Retracted", new BriefingEvaluationResult(
                                    "Retracted", 5, 90, 80, "Fiery"),
                            "Triaged", new BriefingEvaluationResult(
                                    "Triaged", 4, 75, 60, "Good")));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));

            LocalDateTime triageRunAt =
                    CACHED_AT.plusSeconds(3600).atZone(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime forecastOnlyRunAt =
                    CACHED_AT.minusSeconds(3600).atZone(ZoneOffset.UTC).toLocalDateTime();
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(
                            ForecastEvaluationEntity.builder()
                                    .location(triagedLoc).targetDate(DATE).targetType(SUNRISE)
                                    .triage(new TriageDetails(
                                            TriageReason.HIGH_CLOUD, "88% low cloud"))
                                    .forecastRunAt(triageRunAt)
                                    .build(),
                            ForecastEvaluationEntity.builder()
                                    .location(forecastOnlyLoc).targetDate(DATE).targetType(SUNRISE)
                                    .rating(3).fierySkyPotential(50).goldenHourPotential(40)
                                    .summary("Base forecast only")
                                    .evaluationModel(EvaluationModel.HAIKU)
                                    .forecastRunAt(forecastOnlyRunAt)
                                    .build()));

            Instant skipAt = CACHED_AT.plusSeconds(3600);
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Retracted", DATE, SUNRISE, skipAt)));

            Map<String, BriefingEvaluationResult> single =
                    service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE);
            Map<String, Map<String, BriefingEvaluationResult>> bulk =
                    service.getLiveScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNRISE));
            Map<String, BriefingEvaluationResult> bulkForKey =
                    bulk.getOrDefault(REGION_NAME + "|" + DATE + "|" + SUNRISE, Map.of());

            // Sanity on the single read first, so a failed agreement assertion below points at
            // the bulk side rather than leaving both suspect.
            assertThat(single).doesNotContainKey("Retracted");
            assertThat(single.get("Triaged").rating()).isNull();
            assertThat(single.get("Triaged").triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
            assertThat(single.get("ForecastOnly").rating()).isEqualTo(3);

            assertThat(bulkForKey).isEqualTo(single);

            // The uncovered region: no locations at all, so both accessors answer empty rather
            // than one throwing and the other defaulting silently.
            Map<String, BriefingEvaluationResult> singleUncovered =
                    service.getLiveScoresForEnrichment("Nonexistent Region", DATE, SUNRISE);
            Map<String, BriefingEvaluationResult> bulkUncovered = bulk.getOrDefault(
                    "Nonexistent Region|" + DATE + "|" + SUNRISE, Map.of());
            assertThat(singleUncovered).isEmpty();
            assertThat(bulkUncovered).isEmpty();
        }
    }

    @Nested
    @DisplayName("a newer EMPTY row hides an older RATED one (a second Codex re-review of #940)")
    class HiddenRatedRowRetraction {

        private static final Instant CACHED_AT = Instant.parse("2026-04-22T01:00:00Z");

        private static Object[] skipRow(String locationName, LocalDate date, TargetType type,
                Instant lastSkippedAt) {
            return new Object[] {locationName, date, type.name(), lastSkippedAt};
        }

        private static LocationEntity location(long id, String name, RegionEntity region) {
            LocationEntity loc = new LocationEntity();
            loc.setId(id);
            loc.setName(name);
            loc.setRegion(region);
            loc.setLat(54.5 + id * 0.1);
            loc.setLon(-1.0 - id * 0.1);
            return loc;
        }

        /**
         * The batch's newest row for a slot, carrying neither a rating nor a triage reason — the
         * shape an {@code ABANDONED} (or otherwise closed-out-empty) {@code PENDING} row takes.
         * The dedup-at-source "latest row" query returns ONLY this row, exactly as the real query
         * would: an older RATED row from an earlier cycle exists in the fiction this test tells
         * (T0, before the skip), but it is never handed to the mock at all, because the real query
         * would never return it either once a newer row exists for the same slot.
         */
        private static ForecastEvaluationEntity abandonedRow(LocationEntity loc, LocalDate date,
                TargetType type, LocalDateTime forecastRunAt) {
            return ForecastEvaluationEntity.builder()
                    .location(loc).targetDate(date).targetType(type)
                    .forecastRunAt(forecastRunAt)
                    .build();
        }

        private RegionEntity region() {
            RegionEntity r = new RegionEntity();
            r.setId(REGION_ID);
            r.setName(REGION_NAME);
            return r;
        }

        @Test
        @DisplayName("Codex's case, single-key read: a forecast-only rating hidden behind a newer "
                + "empty row is retracted, not silently absent — must fail against c7f31e5d")
        void hiddenRatedRowIsRetracted_singleKey() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            // T0 (an earlier, rated row) < T1 (the skip) < T2 (the newer, empty row the "latest
            // row" query actually returns). T0 itself is never constructed — see abandonedRow's
            // javadoc.
            Instant t1 = Instant.parse("2026-04-22T02:00:00Z");
            LocalDateTime t2 = LocalDateTime.of(2026, 4, 22, 3, 0);
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t2)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result).containsKey("Bamburgh");
            assertThat(result.get("Bamburgh").retracted()).isTrue();
        }

        @Test
        @DisplayName("Codex's case through the bulk read — agrees with the single-key read")
        void hiddenRatedRowIsRetracted_bulkAgreesWithSingle() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            Instant t1 = Instant.parse("2026-04-22T02:00:00Z");
            LocalDateTime t2 = LocalDateTime.of(2026, 4, 22, 3, 0);
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t2)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            Map<String, BriefingEvaluationResult> single =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);
            Map<String, Map<String, BriefingEvaluationResult>> bulk =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNRISE));
            Map<String, BriefingEvaluationResult> bulkForKey =
                    bulk.getOrDefault(REGION_NAME + "|" + DATE + "|" + SUNRISE, Map.of());

            assertThat(single.get("Bamburgh").retracted()).isTrue();
            assertThat(bulkForKey).isEqualTo(single);
            // And the live variants of both strip the marker down to "not present at all".
            assertThat(service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE))
                    .doesNotContainKey("Bamburgh");
            assertThat(service.getLiveScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNRISE))
                    .getOrDefault(REGION_NAME + "|" + DATE + "|" + SUNRISE, Map.of()))
                    .doesNotContainKey("Bamburgh");
        }

        @Test
        @DisplayName("end to end through BriefingRegionEvaluationRollup: a persisted slot's "
                + "embedded rating is cleared, and its verdict fields read as never-rated")
        void hiddenRatedRowEndToEnd_clearsEmbeddedRatingThroughRollup() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            Instant t1 = Instant.parse("2026-04-22T02:00:00Z");
            LocalDateTime t2 = LocalDateTime.of(2026, 4, 22, 3, 0);
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t2)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            java.time.ZoneId london = java.time.ZoneId.of("Europe/London");
            BriefingRegionEvaluationRollup rollup = new BriefingRegionEvaluationRollup(
                    Clock.fixed(DATE.atStartOfDay(london).toInstant(), london));
            LocalDateTime eventTime = LocalDateTime.of(2026, 4, 22, 5, 30);
            // A persisted slot exactly as `daily_briefing_cache` would carry it after an earlier
            // build rated it 4★ — the embedded rating the "absent means untouched" trap would
            // otherwise leave alone.
            BriefingSlot persisted = new BriefingSlot("Bamburgh", eventTime, Verdict.GO,
                    null, BriefingSlot.TideInfo.NONE, List.of(), null)
                    .withClaudeScores(4, 78, 65, "Fiery dawn expected", "Great colour");
            BriefingRegion region = new BriefingRegion(REGION_NAME, Verdict.GO, "Clear skies",
                    List.of(), List.of(persisted), 12.0, 11.0, 3.0, 0, null, null,
                    DisplayVerdict.WORTH_IT, 1);
            List<BriefingDay> days = List.of(new BriefingDay(DATE, List.of(
                    new BriefingEventSummary(SUNRISE, List.of(region), List.of()))));

            List<BriefingDay> enriched = rollup.enrich(days, service::getScoresForEnrichment);

            BriefingSlot result = enriched.getFirst().eventSummaries().getFirst()
                    .regions().getFirst().slots().getFirst();
            assertThat(result.claudeRating()).isNull();
            assertThat(result.claudeSummary()).isNull();
            assertThat(result.claudeHeadline()).isNull();
            assertThat(result.fierySkyPotential()).isNull();
            assertThat(result.goldenHourPotential()).isNull();
            // Never-rated, not a weather stand-down: the slot's own GO triage verdict and null
            // standdownReason survive untouched.
            assertThat(result.verdict()).isEqualTo(Verdict.GO);
            assertThat(result.standdownReason()).isNull();
        }

        @Test
        @DisplayName("a RATED row written after the skip is live — no marker, rating served")
        void ratedRowAfterSkip_noMarkerRatingServed() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            Instant t1 = Instant.parse("2026-04-22T02:00:00Z");
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(ForecastEvaluationEntity.builder()
                            .location(loc).targetDate(DATE).targetType(SUNRISE)
                            .rating(5).fierySkyPotential(90).goldenHourPotential(80)
                            .summary("Clear and fiery").evaluationModel(EvaluationModel.HAIKU)
                            .forecastRunAt(LocalDateTime.of(2026, 4, 22, 3, 0))
                            .build()));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result.get("Bamburgh").retracted()).isFalse();
            assertThat(result.get("Bamburgh").rating()).isEqualTo(5);
        }

        @Test
        @DisplayName("cached rating after the skip, empty forecast row after that: no marker, "
                + "cached rating served")
        void cachedRatingAfterSkipWithEmptyForecastRow_noMarkerCachedRatingServed() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of("Bamburgh", new BriefingEvaluationResult(
                            "Bamburgh", 4, 75, 60, "Good colour", null, null, null, CACHED_AT)));
            when(briefingEvaluationService.getCachedEvaluatedAt(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Optional.of(CACHED_AT));
            Instant t1 = CACHED_AT.minusSeconds(3600); // skip precedes the cached rating (T2)
            LocalDateTime t3 = CACHED_AT.plusSeconds(7200)
                    .atZone(ZoneOffset.UTC).toLocalDateTime(); // empty row, after both
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t3)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            assertThat(result.get("Bamburgh").retracted()).isFalse();
            assertThat(result.get("Bamburgh").rating()).isEqualTo(4);
        }

        @Test
        @DisplayName("a slot with a skip and no evidence of any kind is retracted; enrichSlot on "
                + "an unrated slot is a no-op; the live variants return no entry")
        void skipWithNoEvidenceAtAll_retracted() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(
                            skipRow("Bamburgh", DATE, SUNRISE, Instant.parse(
                                    "2026-04-22T02:00:00Z"))));

            Map<String, BriefingEvaluationResult> single =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);
            assertThat(single.get("Bamburgh").retracted()).isTrue();
            assertThat(service.getLiveScoresForEnrichment(REGION_NAME, DATE, SUNRISE))
                    .doesNotContainKey("Bamburgh");

            // enrichSlot on an unrated slot is a no-op: clearing already-null Claude fields is
            // indistinguishable from leaving them alone.
            BriefingRegionEvaluationRollup rollup =
                    new BriefingRegionEvaluationRollup(Clock.fixed(
                            DATE.atStartOfDay(java.time.ZoneId.of("Europe/London")).toInstant(),
                            java.time.ZoneId.of("Europe/London")));
            LocalDateTime eventTime = LocalDateTime.of(2026, 4, 22, 5, 30);
            BriefingSlot neverRated = new BriefingSlot("Bamburgh", eventTime, Verdict.GO,
                    null, BriefingSlot.TideInfo.NONE, List.of(), null);
            BriefingRegion region = new BriefingRegion(REGION_NAME, Verdict.GO, "Clear skies",
                    List.of(), List.of(neverRated), 12.0, 11.0, 3.0, 0, null, null,
                    DisplayVerdict.AWAITING, 1);
            List<BriefingDay> days = List.of(new BriefingDay(DATE, List.of(
                    new BriefingEventSummary(SUNRISE, List.of(region), List.of()))));

            List<BriefingDay> enriched = rollup.enrich(days, service::getScoresForEnrichment);

            BriefingSlot result = enriched.getFirst().eventSummaries().getFirst()
                    .regions().getFirst().slots().getFirst();
            assertThat(result).isEqualTo(neverRated);
        }

        @Test
        @DisplayName("NO skip recorded: a newer empty row hiding an older rating is unaffected — "
                + "this fix changes nothing about that case")
        void noSkipRecorded_hiddenRatedRowUnaffected() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            LocalDateTime t2 = LocalDateTime.of(2026, 4, 22, 3, 0);
            when(forecastEvaluationRepository
                    .findLatestRunPerSlotByLocationIds(anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t2)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.of());

            Map<String, BriefingEvaluationResult> result =
                    service.getScoresForEnrichment(REGION_NAME, DATE, SUNRISE);

            // No skip at all: the empty row is simply an unscored slot, exactly as it always was —
            // absent from the map, not a marker (the "no skip" branch has no marker to produce).
            assertThat(result).doesNotContainKey("Bamburgh");
        }

        @Test
        @DisplayName("mergeToView agrees with the Plan payload on Codex's case: the slot is "
                + "absent from the view, not served with a stale rating")
        void mergeToViewAgreesOnCodexCase() {
            LocationEntity loc = location(1, "Bamburgh", region());
            when(locationService.findAllEnabled()).thenReturn(List.of(loc));
            when(briefingEvaluationService.getCachedScores(REGION_NAME, DATE, SUNRISE))
                    .thenReturn(Map.of());
            Instant t1 = Instant.parse("2026-04-22T02:00:00Z");
            LocalDateTime t2 = LocalDateTime.of(2026, 4, 22, 3, 0);
            when(forecastEvaluationRepository
                    .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                            1L, DATE, SUNRISE, PageRequest.of(0, 1)))
                    .thenReturn(List.of(abandonedRow(loc, DATE, SUNRISE, t2)));
            when(forecastRunDispositionRepository.findLatestStabilitySkipTimestamps(DATE, DATE))
                    .thenReturn(List.<Object[]>of(skipRow("Bamburgh", DATE, SUNRISE, t1)));

            List<LocationEvaluationView> views = service.forRegion(REGION_ID, DATE, SUNRISE);

            assertThat(views).hasSize(1);
            assertThat(views.getFirst().source()).isEqualTo(Source.NONE);
            assertThat(views.getFirst().rating()).isNull();
        }
    }

    /**
     * End-to-end proof, through the REAL {@link EvaluationViewService} under test (mocked
     * repositories only) and a REAL {@link BriefingRegionEvaluationRollup}, that the
     * verdict-minimum-sample rule's examined evidence reaches the rollup for both the bulk (build
     * and serve) resolver shape and the single-key shape, and that the two AGREE.
     *
     * <p>A second Codex review of #943 (P1-A, round 2) found the first fix's own rollup-level tests
     * used a hand-rolled resolver that never modelled what production actually writes — a real
     * {@code forecast_evaluation} triage row alongside every {@code SKIPPED_TRIAGED} disposition —
     * which is exactly why the bug survived a green suite once already. These tests build that
     * shape through the real repositories this class already mocks, wiring resolvers in the SAME
     * shape {@code BriefingService.bulkScoreResolver}/{@code bulkTriagedResolver} and {@code
     * ServedBriefingAssembler#reEnrichVerdicts} actually use — never a second stand-in resolver.
     */
    @Nested
    @DisplayName("end-to-end: BriefingRegionEvaluationRollup over real EvaluationViewService "
            + "resolvers (a Codex review of #943, P1-A, round 2)")
    class EndToEndVerdictSampleGate {

        private static final RegionEntity ROSTER_REGION = new RegionEntity();
        private static final String ROSTER_REGION_NAME = "Tyne and Wear";

        static {
            ROSTER_REGION.setId(77L);
            ROSTER_REGION.setName(ROSTER_REGION_NAME);
        }

        private final Clock fixedClock =
                Clock.fixed(DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        private final BriefingRegionEvaluationRollup rollup =
                new BriefingRegionEvaluationRollup(fixedClock);

        private static LocationEntity location(long id, String name) {
            LocationEntity loc = new LocationEntity();
            loc.setId(id);
            loc.setName(name);
            loc.setRegion(ROSTER_REGION);
            loc.setLat(54.5 + id * 0.001);
            loc.setLon(-1.0 - id * 0.001);
            return loc;
        }

        /** An un-embedded voting slot — every field the rollup reads comes from the resolvers. */
        private static BriefingSlot slot(String name) {
            return new BriefingSlot(name, LocalDateTime.of(2026, 4, 23, 6, 0), Verdict.GO,
                    null, BriefingSlot.TideInfo.NONE, List.of(), null);
        }

        private static List<BriefingDay> daysWith(List<BriefingSlot> slots, Verdict triageFallback) {
            BriefingRegion region = new BriefingRegion(ROSTER_REGION_NAME, triageFallback,
                    "Clear skies", List.of(), slots, 12.0, 11.0, 3.0, 0, null, null,
                    DisplayVerdict.resolve(null, triageFallback), 1);
            BriefingEventSummary summary = new BriefingEventSummary(
                    SUNRISE, List.of(region), List.of());
            return List.of(new BriefingDay(DATE, List.of(summary)));
        }

        /** A real, rating-less {@code forecast_evaluation} triage row — what the batch actually
         * writes alongside a {@code SKIPPED_TRIAGED} disposition (and what a hand-started run
         * writes with NO disposition at all). */
        private static ForecastEvaluationEntity triageRow(LocationEntity loc) {
            return ForecastEvaluationEntity.builder()
                    .location(loc)
                    .targetDate(DATE)
                    .targetType(SUNRISE)
                    .forecastRunAt(LocalDateTime.of(2026, 4, 22, 1, 0))
                    .triage(new TriageDetails(TriageReason.GENERIC, "Grey ceiling"))
                    .build();
        }

        private static Object[] triagedDispositionRow(String locationName) {
            return new Object[] {locationName, DATE, SUNRISE.name(), "SKIPPED_TRIAGED",
                    Instant.parse("2026-04-22T01:00:00Z")};
        }

        /** Mirrors {@code BriefingService.bulkScoreResolver} exactly. */
        private RegionScoreResolver bulkScoreResolver() {
            Map<String, Map<String, BriefingEvaluationResult>> index =
                    service.getScoresForEnrichmentBulk(DATE, DATE, Set.of(SUNRISE));
            return (regionName, date, targetType) ->
                    index.getOrDefault(regionName + "|" + date + "|" + targetType, Map.of());
        }

        /** Mirrors {@code BriefingService.bulkTriagedResolver} exactly. */
        private TriagedByBatchResolver bulkTriagedResolver() {
            Map<String, Set<String>> index =
                    service.getTriagedByBatchLocationNamesBulk(DATE, DATE, Set.of(SUNRISE));
            return (regionName, date, targetType) ->
                    index.getOrDefault(regionName + "|" + date + "|" + targetType, Set.of());
        }

        /** The single-key sibling of {@link #bulkScoreResolver}. */
        private RegionScoreResolver singleKeyScoreResolver() {
            return service::getScoresForEnrichment;
        }

        /** The single-key sibling of {@link #bulkTriagedResolver}. */
        private TriagedByBatchResolver singleKeyTriagedResolver() {
            return service::getTriagedByBatchLocationNames;
        }

        private BriefingRegion enrichedRegion(List<BriefingSlot> slots, Verdict triageFallback,
                RegionScoreResolver scoreResolver, TriagedByBatchResolver triagedResolver) {
            List<BriefingDay> enriched = rollup.enrich(
                    daysWith(slots, triageFallback), scoreResolver, triagedResolver);
            return enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
        }

        /** The near-window fixture both the bulk and single-key tests below share: a 50-slot
         * voting roster, 35 with a genuine batch triage (disposition AND forecast_evaluation row),
         * 15 rated via {@code cached_evaluation}. */
        private List<BriefingSlot> nearWindowRoster(List<LocationEntity> allLocations) {
            List<BriefingSlot> slots = new ArrayList<>();
            for (LocationEntity loc : allLocations) {
                slots.add(slot(loc.getName()));
            }
            return slots;
        }

        private void stubNearWindowRoster(List<LocationEntity> triagedLocations,
                List<LocationEntity> ratedLocations, List<LocationEntity> allLocations) {
            when(locationService.findAllEnabled()).thenReturn(allLocations);
            Map<String, BriefingEvaluationResult> cachedRatings = new HashMap<>();
            for (LocationEntity loc : ratedLocations) {
                cachedRatings.put(loc.getName(),
                        new BriefingEvaluationResult(loc.getName(), 4, 75, 60, "summary"));
            }
            when(briefingEvaluationService.getCachedScores(ROSTER_REGION_NAME, DATE, SUNRISE))
                    .thenReturn(cachedRatings);
            List<ForecastEvaluationEntity> triageRows = triagedLocations.stream()
                    .map(EndToEndVerdictSampleGate::triageRow)
                    .toList();
            when(forecastEvaluationRepository.findLatestRunPerSlotByLocationIds(
                    anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(triageRows);
            List<Object[]> dispositionRows = triagedLocations.stream()
                    .map(loc -> triagedDispositionRow(loc.getName()))
                    .toList();
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(dispositionRows);
        }

        @Test
        @DisplayName("near window (35 triaged + 15 rated of 50), BULK resolvers: sufficient, "
                + "verdict from the rated average — must FAIL against 72e7b612 (whose synthetic "
                + "marker never reached a slot production actually resolves to a real triage row)")
        void nearWindow_bulkResolvers_sufficient() {
            List<LocationEntity> triagedLocations = new ArrayList<>();
            List<LocationEntity> ratedLocations = new ArrayList<>();
            List<LocationEntity> allLocations = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                LocationEntity loc = location(i, "Triaged" + i);
                triagedLocations.add(loc);
                allLocations.add(loc);
            }
            for (int i = 0; i < 15; i++) {
                LocationEntity loc = location(100 + i, "Rated" + i);
                ratedLocations.add(loc);
                allLocations.add(loc);
            }
            stubNearWindowRoster(triagedLocations, ratedLocations, allLocations);

            BriefingRegion region = enrichedRegion(nearWindowRoster(allLocations),
                    Verdict.STANDDOWN, bulkScoreResolver(), bulkTriagedResolver());

            assertThat(region.sampleSufficient()).isTrue();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.meanRating()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("the identical near window, SINGLE-KEY resolvers: agrees with the bulk result")
        void nearWindow_singleKeyResolvers_agreesWithBulk() {
            List<LocationEntity> triagedLocations = new ArrayList<>();
            List<LocationEntity> ratedLocations = new ArrayList<>();
            List<LocationEntity> allLocations = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                LocationEntity loc = location(i, "Triaged" + i);
                triagedLocations.add(loc);
                allLocations.add(loc);
            }
            for (int i = 0; i < 15; i++) {
                LocationEntity loc = location(100 + i, "Rated" + i);
                ratedLocations.add(loc);
                allLocations.add(loc);
            }
            stubNearWindowRoster(triagedLocations, ratedLocations, allLocations);

            BriefingRegion fromSingleKey = enrichedRegion(nearWindowRoster(allLocations),
                    Verdict.STANDDOWN, singleKeyScoreResolver(), singleKeyTriagedResolver());

            assertThat(fromSingleKey.sampleSufficient()).isTrue();
            assertThat(fromSingleKey.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(fromSingleKey.meanRating()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("far window: 5 rated + 25 SKIPPED_STABILITY (never SKIPPED_TRIAGED, and no "
                + "forecast_evaluation row at all — exactly what Gate 4 leaves behind) — "
                + "insufficient. Must FAIL against 72e7b612")
        void farWindow_stabilitySkipped_insufficient() {
            List<LocationEntity> ratedLocations = new ArrayList<>();
            List<LocationEntity> allLocations = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                LocationEntity loc = location(i, "Rated" + i);
                ratedLocations.add(loc);
                allLocations.add(loc);
            }
            for (int i = 0; i < 25; i++) {
                allLocations.add(location(100 + i, "StabilitySkipped" + i));
            }
            for (int i = 0; i < 20; i++) {
                allLocations.add(location(200 + i, "Untouched" + i));
            }
            when(locationService.findAllEnabled()).thenReturn(allLocations);
            Map<String, BriefingEvaluationResult> cachedRatings = new HashMap<>();
            for (LocationEntity loc : ratedLocations) {
                cachedRatings.put(loc.getName(),
                        new BriefingEvaluationResult(loc.getName(), 4, 75, 60, "summary"));
            }
            when(briefingEvaluationService.getCachedScores(ROSTER_REGION_NAME, DATE, SUNRISE))
                    .thenReturn(cachedRatings);
            // No forecast_evaluation rows at all for the 25 stability-skipped slots — Gate 4
            // writes neither a row nor anything this resolver can see for them.
            when(forecastEvaluationRepository.findLatestRunPerSlotByLocationIds(
                    anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(List.of());
            // Their latest disposition is SKIPPED_STABILITY, never SKIPPED_TRIAGED.
            List<Object[]> stabilityRows = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                stabilityRows.add(new Object[] {"StabilitySkipped" + i, DATE, SUNRISE.name(),
                        "SKIPPED_STABILITY", Instant.parse("2026-04-22T01:00:00Z")});
            }
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(stabilityRows);

            BriefingRegion region = enrichedRegion(nearWindowRoster(allLocations),
                    Verdict.MARGINAL, bulkScoreResolver(), bulkTriagedResolver());

            // examined = 5 rated + 0 = 5 of 50 = 10% < 50%.
            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("item 4: a hand-started/synchronous-engine triage — a real forecast_evaluation "
                + "triage row with NO matching forecast_run_disposition row at all — still counts "
                + "as examined, via the resolved-evidence channel alone")
        void handStartedTriage_noDispositionRow_stillExamined() {
            List<LocationEntity> ratedLocations = new ArrayList<>();
            List<LocationEntity> allLocations = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                LocationEntity loc = location(i, "Rated" + i);
                ratedLocations.add(loc);
                allLocations.add(loc);
            }
            List<LocationEntity> handStartedLocations = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                LocationEntity loc = location(100 + i, "HandStarted" + i);
                handStartedLocations.add(loc);
                allLocations.add(loc);
            }
            for (int i = 0; i < 20; i++) {
                allLocations.add(location(200 + i, "Untouched" + i));
            }
            when(locationService.findAllEnabled()).thenReturn(allLocations);
            Map<String, BriefingEvaluationResult> cachedRatings = new HashMap<>();
            for (LocationEntity loc : ratedLocations) {
                cachedRatings.put(loc.getName(),
                        new BriefingEvaluationResult(loc.getName(), 4, 75, 60, "summary"));
            }
            when(briefingEvaluationService.getCachedScores(ROSTER_REGION_NAME, DATE, SUNRISE))
                    .thenReturn(cachedRatings);
            // A real forecast_evaluation triage row for each hand-started location — this is the
            // ONLY evidence: no disposition table entry at all (the hand-started/synchronous
            // engine writes no forecast_run_disposition row).
            List<ForecastEvaluationEntity> triageRows = handStartedLocations.stream()
                    .map(EndToEndVerdictSampleGate::triageRow)
                    .toList();
            when(forecastEvaluationRepository.findLatestRunPerSlotByLocationIds(
                    anyCollection(), eq(DATE), eq(DATE)))
                    .thenReturn(triageRows);
            when(forecastRunDispositionRepository.findLatestNonCachedDispositions(DATE, DATE))
                    .thenReturn(List.of());

            BriefingRegion region = enrichedRegion(nearWindowRoster(allLocations),
                    Verdict.MARGINAL, bulkScoreResolver(), bulkTriagedResolver());

            // examined = 5 rated + 25 resolved-triage (channel B alone) = 30 of 50 = 60% >= 50%.
            assertThat(region.sampleSufficient()).isTrue();
        }
    }
}
