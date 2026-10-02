package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that {@link ForecastCommandExecutor}'s colour (sky) run evaluates only sky subjects —
 * whether the command names its locations or leaves them to be defaulted — so a hand-started
 * {@code POST /api/forecast/run} (which always names them) cannot send a wildlife hide, a wood or
 * a bluebell wood to the sky prompt.
 *
 * <p>Every candidate is triaged away by the stub, so the run stops after the triage phase and the
 * only thing observed is which locations reached {@code fetchWeatherAndTriage}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCommandExecutorSkyFilterTest {

    /** Fixed so the "today" the executor derives never moves under the test. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @Mock private ForecastService forecastService;
    @Mock private LocationService locationService;
    @Mock private JobRunService jobRunService;
    @Mock private SolarService solarService;
    @Mock private ForecastCommandFactory commandFactory;
    @Mock private OptimisationStrategyService optimisationStrategyService;
    @Mock private RunProgressTracker progressTracker;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SentinelSelector sentinelSelector;
    @Mock private AstroConditionsService astroConditionsService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private EvaluationStrategy haikuStrategy;

    private ForecastCommandExecutor executor;
    private Map<String, WeatherExtractionResult> prefetchedWeather;
    private CloudPointCache cloudCache;

    private static LocationEntity place(long id, String name, Set<LocationType> types) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(54.0 + id)
                .lon(-1.5)
                .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                .locationType(types)
                .build();
    }

    private static LocationEntity landscape() {
        return place(1L, "Durham", Set.of(LocationType.LANDSCAPE));
    }

    private static LocationEntity hide() {
        return place(2L, "Reserve Hide", Set.of(LocationType.WILDLIFE));
    }

    private static LocationEntity wood() {
        return place(3L, "Canopy Wood", Set.of(LocationType.WOODLAND));
    }

    private static LocationEntity bluebellWood() {
        return place(4L, "Bluebell Wood", Set.of(LocationType.BLUEBELL));
    }

    @BeforeEach
    void setUp() {
        prefetchedWeather = new LinkedHashMap<>();
        cloudCache = new CloudPointCache(Map.of());
        executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService,
                commandFactory, Runnable::run,
                optimisationStrategyService, progressTracker, eventPublisher,
                sentinelSelector, astroConditionsService, stabilityClassifier,
                openMeteoService, stabilitySnapshotProvider, CLOCK);

        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(1L);
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        when(commandFactory.resolveEvaluationModel(any())).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(any())).thenReturn(List.of());
        when(optimisationStrategyService.serialiseEnabledStrategies(any())).thenReturn("");
        when(jobRunService.startRun(any(), any(boolean.class), any(), any())).thenReturn(jobRun);
        when(openMeteoService.prefetchWeatherBatch(anyList(), any())).thenReturn(prefetchedWeather);
        when(openMeteoService.prefetchCloudBatch(anyList(), any())).thenReturn(cloudCache);
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any())).thenReturn(LocalDateTime.MAX);
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any())).thenReturn(LocalDateTime.MAX);
        when(forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                any(), any()))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    LocalDate date = inv.getArgument(1);
                    TargetType type = inv.getArgument(2);
                    return new ForecastPreEvalResult(true, "Low cloud 88%", null,
                            loc, date, type, LocalDateTime.of(2026, 10, 2, 9, 0), 90, 0,
                            EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + type, null);
                });
    }

    /** The distinct names of the locations the run handed to the fetch/triage phase. */
    private Set<String> locationNamesFetched() {
        ArgumentCaptor<LocationEntity> captor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(forecastService, atLeastOnce()).fetchWeatherAndTriage(
                captor.capture(), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                any(), any());
        return captor.getAllValues().stream()
                .map(LocationEntity::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("an explicit list mixing sky and non-sky places evaluates only the sky one")
    void explicitList_evaluatesOnlyTheSkyLocation() {
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(TODAY),
                List.of(landscape(), hide(), wood(), bluebellWood()), haikuStrategy, true);

        executor.execute(cmd);

        assertThat(locationNamesFetched()).containsExactly("Durham");
    }

    @Test
    @DisplayName("an explicit list of sky locations only is evaluated whole")
    void explicitList_ofSkyLocationsOnly_isUnchanged() {
        LocationEntity seascape = place(5L, "Bamburgh", Set.of(LocationType.SEASCAPE));
        LocationEntity waterfall = place(6L, "High Force", Set.of(LocationType.WATERFALL));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(TODAY),
                List.of(landscape(), seascape, waterfall), haikuStrategy, true);

        executor.execute(cmd);

        assertThat(locationNamesFetched()).containsExactly("Bamburgh", "Durham", "High Force");
    }

    @Test
    @DisplayName("a location carrying a sky type alongside a wood type stays in the sky run")
    void explicitList_woodWithALandscapeType_staysIn() {
        LocationEntity both = place(7L, "Woods With A View",
                Set.of(LocationType.WOODLAND, LocationType.LANDSCAPE));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(TODAY),
                List.of(both, wood()), haikuStrategy, true);

        executor.execute(cmd);

        assertThat(locationNamesFetched()).containsExactly("Woods With A View");
    }

    @Test
    @DisplayName("a null location list still defaults to every enabled sky location")
    void nullList_defaultsToEnabledSkyLocations() {
        when(locationService.findAllEnabled())
                .thenReturn(List.of(landscape(), hide(), wood(), bluebellWood()));
        ForecastCommand cmd = new ForecastCommand(RunType.SHORT_TERM, List.of(TODAY), null,
                haikuStrategy, true);

        executor.execute(cmd);

        assertThat(locationNamesFetched()).containsExactly("Durham");
    }
}
