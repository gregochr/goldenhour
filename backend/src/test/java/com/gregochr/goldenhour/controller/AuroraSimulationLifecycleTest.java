package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraSimulationRequest;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.AuroraPollingJob;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * An admin's aurora simulation, followed through the polls that come after it, with the REAL
 * {@link AuroraStateCache}, the REAL {@link AuroraOrchestrator}, the REAL {@link AuroraPollingJob}
 * and both REAL aurora controllers.
 *
 * <p>{@code AuroraControllerTest}, {@code AuroraAdminControllerTest} and
 * {@code AuroraOrchestratorTest} all mock the state machine, so none of them can see what
 * {@code POST /api/aurora/admin/simulate} leaves behind for the polling job's next cycle, or what
 * that cycle leaves behind for {@code GET /api/aurora/status}. That hand-off is where a forgotten
 * simulation used to live on: a real CLEAR ended the alert but not the simulation, and the next real
 * alert was served to every Pro user as "(SIMULATED)" — the status carrying the simulation's Kp,
 * OVATION, Bz and G-scale.
 *
 * <p>Every scenario is a night poll (dusk already passed): {@code AuroraOrchestrator.runNightPoll}
 * is package-private, reached only through {@link AuroraPollingJob#runCycleIfIdle()}, and a night
 * poll needs only one NOAA snapshot, not a forecast lookahead's dusk/dawn window. Every snapshot's
 * {@code kpForecast} is empty, so the level comes from {@code recentKp} alone
 * ({@code AuroraOrchestrator.currentKp} falls back to the latest reading with no forecast blocks to
 * check), and {@code readingDue} is trivially false with no blocks at all — sidestepping the
 * hold-for-a-late-reading behaviour {@code AuroraOrchestrator} has for exactly that case, which is
 * outside the scope of this fix. Only the edges are faked: NOAA, weather triage, the location
 * roster, twilight and the night date the status carries. Nothing here reaches Claude — the one
 * dark-sky location is overcast, so triage rejects it and it is scored 1★ without a call, which is
 * still the scoring pipeline end to end.
 */
@ExtendWith(MockitoExtension.class)
class AuroraSimulationLifecycleTest {

    /**
     * 22:00 UTC, well after dusk, on a date no real clock returns. Nothing compares against it: the
     * orchestrator's own clock only dates a Claude task, and no test here reaches one.
     */
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2027, 1, 15, 22, 0, 0, 0, ZoneOffset.UTC);

    /** The admin's fake storm: Kp 7 is STRONG, above every real reading in this class. */
    private static final AuroraSimulationRequest SIMULATED_STORM =
            new AuroraSimulationRequest(7.0, 45.0, -12.0, "G3");

    @Mock
    private NoaaSwpcClient noaaClient;
    @Mock
    private WeatherTriageService weatherTriage;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private SolarCalculator solarCalculator;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private AuroraForecastRunService forecastRunService;
    @Mock
    private BortleEnrichmentService enrichmentService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private Executor forecastExecutor;

    /** Every default: Kp 5 and OVATION 20 % to alert, Bortle 4 (MODERATE) and 5 (STRONG). */
    private final AuroraProperties properties = new AuroraProperties();
    private final LocationEntity kielder = LocationEntity.builder()
            .id(1L).name("Kielder").lat(55.23).lon(-2.58).bortleClass(2).build();

    private AuroraStateCache stateCache;
    private AuroraPollingJob pollingJob;
    private AuroraAdminController adminController;
    private AuroraController statusController;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        stateCache = new AuroraStateCache();
        AuroraOrchestrator orchestrator = new AuroraOrchestrator(noaaClient, weatherTriage, stateCache,
                locationRepository, properties, evaluationService, modelSelectionService, clock);
        pollingJob = new AuroraPollingJob(orchestrator, properties, solarCalculator,
                dynamicSchedulerService, clock);
        adminController = new AuroraAdminController(enrichmentService, properties, stateCache,
                pollingJob, jobRunService, forecastExecutor, locationRepository);
        statusController = new AuroraController(stateCache, noaaClient, forecastRunService);

        // Dusk long past, dawn not yet due, at every date this class asks for: every poll here is a
        // night poll. lenient() because not every test's poll reaches the twilight check twice.
        lenient().when(solarCalculator.civilDusk(anyDouble(), anyDouble(), any(LocalDate.class),
                any(ZoneId.class))).thenAnswer(inv -> LocalDateTime.of(inv.getArgument(2), LocalTime.of(19, 0)));
        lenient().when(solarCalculator.civilDawn(anyDouble(), anyDouble(), any(LocalDate.class),
                any(ZoneId.class))).thenAnswer(inv -> LocalDateTime.of(inv.getArgument(2), LocalTime.of(7, 0)));
    }

    @Test
    @DisplayName("a simulation a quiet night poll ended does not dress up the next real alert")
    void simulationEndedByAQuietPoll_theNextRealAlertIsServedAsReal() {
        simulateAndForget();
        // Control: the admin's simulation really started, and it is what the status serves.
        assertThat(statusController.getStatus().getBody().simulated()).isTrue();

        // The live readings the status fetches for itself — quiet at the first poll, a storm later.
        when(noaaClient.fetchKp()).thenReturn(List.of(reading(2.0)), List.of(reading(5.3)));
        when(noaaClient.fetchOvation()).thenReturn(null);
        when(noaaClient.fetchSolarWind()).thenReturn(List.of());
        // What the two night polls fetch: quiet, then a real Kp 5.3 storm.
        when(noaaClient.fetchAll()).thenReturn(nightSnapshot(2.0), nightSnapshot(5.3));
        overcastAtKielder();

        assertThat(pollingJob.runCycleIfIdle()).isPresent();
        assertThat(stateCache.isActive()).isFalse(); // CLEARed
        // In between: quiet, and no longer simulated — the live reading, not the frozen fake one.
        // Broken, the banner went away (QUIET) but the flag stayed, and this read Kp 7. Asserted
        // here, not only below, because the real alert below would end a simulation a broken CLEAR
        // had left standing, and hide it.
        AuroraStatusResponse between = statusController.getStatus().getBody();
        assertThat(between.level()).isEqualTo(AlertLevel.QUIET);
        assertThat(between.simulated()).isFalse();
        assertThat(between.kp()).isEqualTo(2.0);

        assertThat(pollingJob.runCycleIfIdle()).isPresent();
        assertThat(stateCache.isActive()).isTrue(); // NOTIFYed
        AuroraStatusResponse status = statusController.getStatus().getBody();

        // Broken: simulated, Kp 7, OVATION 45, Bz −12, G3 — the admin's storm, not this one.
        assertThat(status.simulated()).isFalse();
        assertThat(status.level()).isEqualTo(AlertLevel.MODERATE);
        assertThat(status.kp()).isEqualTo(5.3);
        assertThat(status.triggerType()).isEqualTo("realtime");
        assertThat(status.forecastKp()).isEqualTo(5.3);
        assertThat(status.eligibleLocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("a real alert below a running simulation's level is scored, not suppressed")
    void realAlertBelowARunningSimulation_isScoredNotSuppressed() {
        simulateAndForget();
        // A real Kp 3 reading: MODERATE by neither Kp nor OVATION alone, but OVATION at 25 % clears
        // the MODERATE threshold — below the simulated STRONG either way.
        when(noaaClient.fetchAll()).thenReturn(nightSnapshot(3.0, 25.0));
        overcastAtKielder();
        when(noaaClient.fetchKp()).thenReturn(List.of(reading(3.0)));
        when(noaaClient.fetchOvation()).thenReturn(null);
        when(noaaClient.fetchSolarWind()).thenReturn(List.of());

        // Broken: SUPPRESS — measured against the fake storm, and never scored.
        assertThat(pollingJob.runCycleIfIdle()).isPresent();
        assertThat(stateCache.isActive()).isTrue();
        assertThat(stateCache.getCachedScores()).extracting(AuroraForecastScore::stars).containsExactly(1);

        AuroraStatusResponse status = statusController.getStatus().getBody();
        assertThat(status.simulated()).isFalse();
        assertThat(status.level()).isEqualTo(AlertLevel.MODERATE);
        assertThat(status.triggerType()).isEqualTo("realtime");
        assertThat(status.kp()).isEqualTo(3.0);
        assertThat(status.darkSkyLocationCount()).isEqualTo(1);
        assertThat(status.clearLocationCount()).isZero();
    }

    /**
     * The admin's screen polls its status, so it can show "🧪 Simulated" for minutes after a real
     * reading has ended the simulation, and it offers Clear. Clear used to be a reset: pressed after
     * a real alert had taken the simulation over, it wiped that alert, scores and all, for every Pro
     * user until the next poll paid to score it again.
     */
    @Test
    @DisplayName("a Clear pressed after a real alert took the simulation over leaves that alert")
    void clearAfterARealAlertTookTheSimulationOver_leavesTheRealAlert() {
        simulateAndForget();
        when(noaaClient.fetchAll()).thenReturn(nightSnapshot(3.0, 25.0));
        overcastAtKielder();
        when(noaaClient.fetchKp()).thenReturn(List.of(reading(3.0)));
        when(noaaClient.fetchOvation()).thenReturn(null);
        when(noaaClient.fetchSolarWind()).thenReturn(List.of());
        assertThat(pollingJob.runCycleIfIdle()).isPresent();
        assertThat(stateCache.isActive()).isTrue();

        assertThat(adminController.clearSimulation().getBody())
                .containsEntry("status", "No aurora simulation was running — nothing cleared");

        AuroraStatusResponse status = statusController.getStatus().getBody();
        assertThat(status.active()).isTrue();
        assertThat(status.level()).isEqualTo(AlertLevel.MODERATE);
        assertThat(status.simulated()).isFalse();
        assertThat(status.eligibleLocations()).isEqualTo(1);
    }

    /** {@code POST /api/aurora/admin/simulate} for a Kp 7 storm, and no Clear afterwards. */
    private void simulateAndForget() {
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getStrong())).thenReturn(List.of(kielder));
        assertThat(adminController.simulateAurora(SIMULATED_STORM).getBody().level())
                .isEqualTo(AlertLevel.STRONG);
    }

    /** The MODERATE roster is Kielder alone, and it is overcast: scored 1★, no Claude call. */
    private void overcastAtKielder() {
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(
                properties.getBortleThreshold().getModerate())).thenReturn(List.of(kielder));
        when(weatherTriage.triage(List.of(kielder))).thenReturn(new WeatherTriageService.TriageResult(
                List.of(), List.of(kielder), Map.of(kielder, 95)));
    }

    private static KpReading reading(double kp) {
        return new KpReading(NOW, kp);
    }

    /** A night-poll snapshot with no forecast blocks: the level comes from {@code recentKp} alone. */
    private static SpaceWeatherData nightSnapshot(double kp) {
        return nightSnapshot(kp, 0.0);
    }

    private static SpaceWeatherData nightSnapshot(double kp, double ovationPct) {
        OvationReading ovation = ovationPct > 0 ? new OvationReading(NOW, ovationPct, 55.0) : null;
        return new SpaceWeatherData(List.of(reading(kp)), List.of(), ovation, List.of(), List.of());
    }
}
