package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * The whole chain, with nothing between the original run's FAILED tasks and the retry run's tasks
 * mocked except the outside world: a real {@link RunProgressTracker} holding the original run, the real
 * {@link FailedSlotRetryService}, the real {@link ForecastCommandFactory} and the real
 * {@link ForecastCommandExecutor} registering the retry run in the same tracker. The service builds
 * {@link ForecastSlot}s from the tracker's task fields and the executor builds them from a location's
 * name, a date and an event; a mismatch between the two (a name, a date format) would leave the retry
 * run empty, and each side's own unit test passes without it.
 */
@ExtendWith(MockitoExtension.class)
class FailedSlotRetryChainTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);

    @Mock private DynamicSchedulerService dynamicSchedulerService;
    @Mock private ForecastService forecastService;
    @Mock private LocationService locationService;
    @Mock private JobRunService jobRunService;
    @Mock private SolarService solarService;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private OptimisationStrategyService optimisationStrategyService;
    @Mock private SentinelSelector sentinelSelector;
    @Mock private AstroConditionsService astroConditionsService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private EvaluationStrategy haikuStrategy;

    private static LocationEntity sky(long id, String name) {
        return LocationEntity.builder().id(id).name(name).lat(54.0 + id).lon(-1.5)
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
    }

    @Test
    @DisplayName("failed tasks in a real tracker -> retry service -> real executor: the retry run's task keys "
            + "are exactly the two failed slots")
    void failedTasks_throughServiceAndExecutor_registerExactlyTheFailedSlotsInTheTracker() {
        RunProgressTracker tracker = new RunProgressTracker(dynamicSchedulerService);
        List<String[]> original = new ArrayList<>();
        for (String name : List.of("Durham", "Bamburgh")) {
            for (String date : List.of("2026-10-03", "2026-10-04")) {
                for (String type : List.of("SUNRISE", "SUNSET")) {
                    original.add(new String[]{name + "|" + date + "|" + type, name, date, type});
                }
            }
        }
        tracker.initRun(7L, original);
        for (String[] t : original) {
            boolean failed = t[0].equals("Durham|2026-10-03|SUNSET") || t[0].equals("Bamburgh|2026-10-04|SUNRISE");
            tracker.onTaskEvent(new LocationTaskEvent(this, 7L, t[0], t[1], t[2], t[3],
                    failed ? LocationTaskState.FAILED : LocationTaskState.COMPLETE,
                    failed ? "weather down" : null, null));
        }

        LocationEntity durham = sky(1L, "Durham");
        LocationEntity bamburgh = sky(2L, "Bamburgh");
        when(locationService.findAllEnabled()).thenReturn(List.of(durham, bamburgh));
        when(locationService.shouldEvaluateSunrise(any())).thenReturn(true);
        when(locationService.shouldEvaluateSunset(any())).thenReturn(true);
        JobRunEntity originalRow = new JobRunEntity();
        originalRow.setId(7L);
        originalRow.setRunType(RunType.SHORT_TERM);
        when(jobRunService.findRun(7L)).thenReturn(java.util.Optional.of(originalRow));
        JobRunEntity retryRow = new JobRunEntity();
        retryRow.setId(8L);
        retryRow.setRunType(RunType.SHORT_TERM);
        when(jobRunService.startRun(RunType.SHORT_TERM, true, null, null)).thenReturn(retryRow);
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(optimisationStrategyService.getEnabledStrategies(RunType.SHORT_TERM)).thenReturn(List.of());
        when(optimisationStrategyService.serialiseEnabledStrategies(RunType.SHORT_TERM)).thenReturn("");
        when(openMeteoService.prefetchWeatherBatch(anyList(), any())).thenReturn(Map.of());
        when(openMeteoService.prefetchCloudBatch(anyList(), any())).thenReturn(new CloudPointCache(Map.of()));
        // Every slot is stood down at triage, so the run ends after the triage phase; what is observed is
        // which slots the executor registered.
        when(forecastService.fetchWeatherAndTriage(
                any(LocationEntity.class), any(LocalDate.class), any(TargetType.class),
                any(), any(EvaluationModel.class), anyBoolean(), any(JobRunEntity.class),
                any(), any()))
                .thenAnswer(inv -> {
                    LocationEntity loc = inv.getArgument(0);
                    LocalDate date = inv.getArgument(1);
                    TargetType type = inv.getArgument(2);
                    return new ForecastPreEvalResult(true, "Low cloud 88%", null,
                            loc, date, type, LocalDateTime.of(2026, 10, 3, 9, 0), 90, 1,
                            EvaluationModel.HAIKU, loc.getTideType(),
                            loc.getName() + "|" + date + "|" + type, null);
                });

        ForecastCommandFactory factory = new ForecastCommandFactory(modelSelectionService,
                Map.of(EvaluationModel.HAIKU, haikuStrategy), CLOCK);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof LocationTaskEvent taskEvent) {
                tracker.onTaskEvent(taskEvent);
            }
        };
        ForecastCommandExecutor executor = new ForecastCommandExecutor(
                forecastService, locationService, jobRunService, solarService, factory, Runnable::run,
                optimisationStrategyService, tracker, publisher, sentinelSelector, astroConditionsService,
                stabilityClassifier, openMeteoService, stabilitySnapshotProvider, CLOCK);
        FailedSlotRetryService service = new FailedSlotRetryService(tracker, jobRunService, locationService,
                factory, executor, Runnable::run, CLOCK);

        FailedSlotRetryService.Outcome outcome = service.retry(7L);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(8L, RunType.SHORT_TERM, 2, List.of()));
        assertThat(tracker.getProgress(8L).getTasks().keySet())
                .containsExactlyInAnyOrder("Durham|2026-10-03|SUNSET", "Bamburgh|2026-10-04|SUNRISE");
    }
}
