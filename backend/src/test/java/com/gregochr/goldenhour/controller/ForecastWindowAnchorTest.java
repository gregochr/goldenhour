package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the UK civil date as the anchor for everything the map and the synchronous engine count days
 * from, at the one instant per day where that is not the same thing as UTC.
 *
 * <p>Written as a plain unit test rather than through {@code MockMvc} for a specific reason: the
 * controller tests share one {@code @SpringBootTest} context, which injects the real
 * {@code Clock.systemUTC()} bean. An expectation computed from that same clock is the expression
 * the controller itself evaluates, so it agrees with the code under test whatever calendar either
 * of them is on — green in every timezone, at every hour, including after a revert. Constructing
 * the controllers directly is what makes a fixed clock, and therefore a failing test, possible.
 */
@ExtendWith(MockitoExtension.class)
class ForecastWindowAnchorTest {

    /** 00:30 on 12 August in Europe/London (BST); still 23:30 on the 11th in UTC. */
    private static final Clock LATE_BST_EVENING =
            Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), ZoneOffset.UTC);

    private static final LocalDate UK_TODAY = LocalDate.of(2026, 8, 12);
    private static final LocalDate UK_YESTERDAY = LocalDate.of(2026, 8, 11);

    private static final LocalDate EXPECTED_FROM =
            UK_TODAY.minusDays(ForecastController.PAST_WINDOW_DAYS);
    private static final LocalDate EXPECTED_TO =
            UK_TODAY.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);

    @Mock
    private ForecastEvaluationRepository repository;

    @Mock
    private LocationService locationService;

    @Mock
    private ForecastCommandFactory commandFactory;

    @Mock
    private ForecastCommandExecutor commandExecutor;

    @Mock
    private ScheduledForecastService scheduledForecastService;

    @Mock
    private ForecastDtoMapper dtoMapper;

    @Mock
    private EvaluationViewService evaluationViewService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private RunProgressTracker progressTracker;

    @Mock
    private BriefingEvaluationService briefingEvaluationService;

    private ForecastController forecastController;
    private BriefingEvaluationController briefingEvaluationController;

    private static LocationEntity durham() {
        return LocationEntity.builder().id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();
    }

    @BeforeEach
    void setUp() {
        forecastController = new ForecastController(repository, locationService, commandFactory,
                commandExecutor, scheduledForecastService, dtoMapper, evaluationViewService,
                jobRunService, progressTracker, Runnable::run, LATE_BST_EVENING);
        briefingEvaluationController = new BriefingEvaluationController(
                briefingEvaluationService, evaluationViewService, LATE_BST_EVENING);
    }

    @Test
    @DisplayName("GET /api/forecast queries the UK date's window, reaching the engine's furthest day")
    void forecastListWindowIsAnchoredOnTheUkDate() {
        when(locationService.findAllEnabled()).thenReturn(List.of(durham()));
        when(repository.findLatestRunPerSlotByLocationIds(eq(List.of(1L)), any(), any()))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(List.of(), true)).thenReturn(List.of());
        when(evaluationViewService.cachedOnlyViewsForDateRange(any(), any(), any(), any()))
                .thenReturn(List.of());

        forecastController.getForecasts(null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).findLatestRunPerSlotByLocationIds(
                eq(List.of(1L)), from.capture(), to.capture());

        // On the UTC anchor this window ran 9–16 August: one day of history nothing could show,
        // and a day short of the furthest date the engine now forecasts.
        assertThat(from.getValue()).isEqualTo(EXPECTED_FROM);
        assertThat(to.getValue()).isEqualTo(EXPECTED_TO);
    }

    @Test
    @DisplayName("the scores window matches the forecast window exactly — same days, not merely same width")
    void scoresWindowMatchesTheForecastWindow() {
        when(evaluationViewService.forDateRange(any(), any(), any())).thenReturn(List.of());

        briefingEvaluationController.getAllScores();

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(evaluationViewService).forDateRange(from.capture(), to.capture(),
                eq(Set.of(TargetType.SUNRISE, TargetType.SUNSET)));

        // These two endpoints are consumed together on Plan/Map mount, and the date strip is built
        // from the forecast payload alone — so a date with a chip and no score does not read as
        // "unscored", it reads as a stand-down, and no filter setting recovers the marker. They
        // share two constants; sharing the anchor is the other half of that agreement.
        assertThat(from.getValue()).isEqualTo(EXPECTED_FROM);
        assertThat(to.getValue()).isEqualTo(EXPECTED_TO);
    }

    @Test
    @DisplayName("POST /api/forecast/run with no dates runs the UK's today, whose events are still to come")
    void defaultRunDateIsUkToday() {
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(7L);
        when(locationService.findAllEnabled()).thenReturn(List.of(durham()));
        when(jobRunService.startRun(eq(RunType.SHORT_TERM), eq(true), any(), any()))
                .thenReturn(jobRun);

        forecastController.runForecast(null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalDate>> dates = ArgumentCaptor.forClass(List.class);
        verify(commandFactory).create(eq(RunType.SHORT_TERM), eq(true), anyList(),
                dates.capture(), eq(Set.of()));

        // The UTC anchor sent UK-yesterday, every event on which was hours past. The executor's
        // already-past gate used to absorb that; now that the gate guards the UK's today instead,
        // the wasted day would have become an evaluated one.
        assertThat(dates.getValue()).containsExactly(UK_TODAY);
        assertThat(dates.getValue()).doesNotContain(UK_YESTERDAY);
    }
}
