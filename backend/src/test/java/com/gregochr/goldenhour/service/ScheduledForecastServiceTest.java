package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledForecastService}.
 *
 * <p>The service holds the tide-refresh and daily-briefing job targets plus the
 * admin tide backfill; forecast orchestration is tested in
 * {@link ForecastCommandExecutorTest}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledForecastServiceTest {

    @Mock
    private TideService tideService;

    @Mock
    private LocationService locationService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private BriefingService briefingService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private ScheduledForecastService scheduledForecastService;

    @BeforeEach
    void setUp() {
        scheduledForecastService = new ScheduledForecastService(
                tideService, locationService, jobRunService, briefingService,
                dynamicSchedulerService);
    }

    // -------------------------------------------------------------------------
    // refreshTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("refreshTideExtremes() calls tideService for each SEASCAPE coastal location")
    void refreshTideExtremes_callsTideService_forCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));
        when(locationService.isCoastal(durham)).thenReturn(true);

        scheduledForecastService.refreshTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(1)).fetchAndStoreTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getValue().getName()).isEqualTo("Durham UK");
    }

    @Test
    @DisplayName("refreshTideExtremes() skips non-SEASCAPE locations")
    void refreshTideExtremes_skipsNonCoastalLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));

        scheduledForecastService.refreshTideExtremes();

        verify(tideService, never()).fetchAndStoreTideExtremes(any(), any());
    }

    @Test
    @DisplayName("refreshTideExtremes() continues after a single location failure")
    void refreshTideExtremes_continuesAfterFailure() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        LocationEntity scarborough = LocationEntity.builder().name("Scarborough")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham, scarborough));
        when(locationService.isCoastal(durham)).thenReturn(true);
        when(locationService.isCoastal(scarborough)).thenReturn(true);
        doThrow(new RuntimeException("API error"))
                .when(tideService).fetchAndStoreTideExtremes(
                        org.mockito.ArgumentMatchers.argThat(
                                loc -> "Durham UK".equals(loc.getName())),
                        org.mockito.ArgumentMatchers.isNull());

        scheduledForecastService.refreshTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(2)).fetchAndStoreTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getAllValues()).extracting(LocationEntity::getName)
                .containsExactly("Durham UK", "Scarborough");
    }

    // -------------------------------------------------------------------------
    // Tide overlap guard — one refresh at a time, whichever route started it
    //
    // The guard is an AtomicBoolean, owned by no thread, so these tests need none: a refresh is
    // held open either by an executor that queues the admin task without running it, or from
    // inside a running refresh by a stubbed per-location fetch.
    // -------------------------------------------------------------------------

    /** Stubs one SEASCAPE coastal location, so a refresh that runs makes exactly one fetch. */
    private LocationEntity oneCoastalLocation() {
        LocationEntity bamburgh = LocationEntity.builder().name("Bamburgh")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
        when(locationService.isCoastal(bamburgh)).thenReturn(true);
        return bamburgh;
    }

    @Test
    @DisplayName("startTideRefresh() runs the refresh on the given executor and returns true")
    void startTideRefresh_runsOnTheExecutor() {
        LocationEntity bamburgh = oneCoastalLocation();
        List<Runnable> queued = new ArrayList<>();

        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isTrue();
        // Nothing runs on the caller's thread — the admin endpoint answers before the refresh.
        verify(tideService, never()).fetchAndStoreTideExtremes(any(), any());

        queued.forEach(Runnable::run);
        verify(tideService).fetchAndStoreTideExtremes(bamburgh, null);
    }

    /**
     * ⚠️ The guard is taken when the admin route ACCEPTS the refresh, not when the executor gets
     * round to it. Taken inside the task, a second admin press in that gap would be accepted too.
     */
    @Test
    @DisplayName("a second admin trigger is refused while the first is accepted but not yet run")
    void startTideRefresh_refusesWhileTheFirstIsQueued() {
        List<Runnable> queued = new ArrayList<>();

        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isTrue();
        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isFalse();
        // Still held: a refusal must not release a guard it never took.
        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isFalse();

        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("a scheduled refresh (or Run Now) skips while an admin refresh is in progress")
    void refreshTideExtremes_skipsWhileAnAdminRefreshIsInProgress() {
        scheduledForecastService.startTideRefresh(task -> { });

        scheduledForecastService.refreshTideExtremes();

        verifyNoInteractions(jobRunService, locationService, tideService);
        // ⚠️ And the skip must leave the guard held. A skip that released it in a `finally` it
        // shares with the real run would let the next admin press start a second refresh.
        assertThat(scheduledForecastService.startTideRefresh(task -> { }))
                .as("the skipped fire released a guard it never took").isFalse();
    }

    @Test
    @DisplayName("the admin route is refused while a scheduled refresh is running")
    void startTideRefresh_refusedWhileAScheduledRefreshRuns() {
        LocationEntity bamburgh = oneCoastalLocation();
        List<Runnable> queued = new ArrayList<>();
        List<Boolean> accepted = new ArrayList<>();
        doAnswer(inv -> accepted.add(scheduledForecastService.startTideRefresh(queued::add)))
                .when(tideService).fetchAndStoreTideExtremes(bamburgh, null);

        scheduledForecastService.refreshTideExtremes();

        assertThat(accepted).containsExactly(false);
        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("an admin refresh releases the guard when it finishes")
    void startTideRefresh_releasesTheGuardWhenItFinishes() {
        oneCoastalLocation();
        List<Runnable> queued = new ArrayList<>();
        scheduledForecastService.startTideRefresh(queued::add);

        queued.forEach(Runnable::run);

        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isTrue();
        assertThat(queued).hasSize(2);
    }

    /**
     * A refresh that dies must still release the guard, or every later tide refresh — scheduled,
     * Run Now or admin — is refused until the next restart. Driven through both routes, since
     * each has its own {@code finally}.
     */
    @Test
    @DisplayName("a scheduled refresh that throws releases the guard")
    void refreshTideExtremes_releasesTheGuardWhenItThrows() {
        when(jobRunService.startRun(RunType.TIDE, false, null))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(null);

        assertThatThrownBy(scheduledForecastService::refreshTideExtremes)
                .hasMessage("database unavailable");
        scheduledForecastService.refreshTideExtremes();

        verify(jobRunService, times(2)).startRun(RunType.TIDE, false, null);
    }

    @Test
    @DisplayName("an admin refresh that throws on the executor releases the guard")
    void startTideRefresh_releasesTheGuardWhenTheTaskThrows() {
        when(jobRunService.startRun(RunType.TIDE, false, null))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(null);
        List<Runnable> queued = new ArrayList<>();
        scheduledForecastService.startTideRefresh(queued::add);

        // CompletableFuture captures the throw rather than letting it escape the task.
        queued.forEach(Runnable::run);

        assertThat(scheduledForecastService.startTideRefresh(queued::add)).isTrue();
    }

    /**
     * An executor that refuses the task never runs its {@code finally}, so the guard must be
     * released on the way out — or one rejected submission locks tide refresh until a restart.
     */
    @Test
    @DisplayName("an executor that rejects the task releases the guard")
    void startTideRefresh_releasesTheGuardWhenTheExecutorRejects() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("executor shut down");
        };

        assertThatThrownBy(() -> scheduledForecastService.startTideRefresh(rejecting))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(scheduledForecastService.startTideRefresh(task -> { })).isTrue();
    }

    // -------------------------------------------------------------------------
    // refreshDailyBriefing — the dormant daily_briefing scheduler target
    // -------------------------------------------------------------------------

    /**
     * The scheduler target must take the REFUSING entry point: a fire that meets the pipeline's
     * build in progress would otherwise queue a second full build behind it.
     */
    @Test
    @DisplayName("refreshDailyBriefing() uses the refusing entry point, never the waiting one")
    void refreshDailyBriefing_usesTheRefusingEntryPoint() {
        scheduledForecastService.refreshDailyBriefing();

        verify(briefingService).refreshBriefingIfIdle();
        verify(briefingService, never()).refreshBriefing();
    }

    // -------------------------------------------------------------------------
    // backfillTideExtremes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backfillTideExtremes() calls tideService.backfillTideExtremes for SEASCAPE locations")
    void backfillTideExtremes_callsBackfill_forSeascapeLocations() {
        LocationEntity bamburgh = LocationEntity.builder().name("Bamburgh")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
        when(locationService.isCoastal(bamburgh)).thenReturn(true);
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(bamburgh),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(1)).backfillTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getValue().getName()).isEqualTo("Bamburgh");
    }

    @Test
    @DisplayName("backfillTideExtremes() skips LANDSCAPE locations")
    void backfillTideExtremes_skipsLandscapeLocations() {
        LocationEntity durham = LocationEntity.builder().name("Durham UK")
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(durham));

        scheduledForecastService.backfillTideExtremes();

        verify(tideService, never()).backfillTideExtremes(any(), any());
    }

    @Test
    @DisplayName("backfillTideExtremes() continues after a single location failure")
    void backfillTideExtremes_continuesAfterFailure() {
        LocationEntity loc1 = LocationEntity.builder().name("Loc1")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        LocationEntity loc2 = LocationEntity.builder().name("Loc2")
                .locationType(Set.of(LocationType.SEASCAPE)).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));
        when(locationService.isCoastal(loc1)).thenReturn(true);
        when(locationService.isCoastal(loc2)).thenReturn(true);
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(loc1),
                org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new RuntimeException("API error"));
        when(tideService.backfillTideExtremes(org.mockito.ArgumentMatchers.eq(loc2),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(52);

        scheduledForecastService.backfillTideExtremes();

        ArgumentCaptor<LocationEntity> locCaptor = ArgumentCaptor.forClass(LocationEntity.class);
        verify(tideService, times(2)).backfillTideExtremes(locCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(locCaptor.getAllValues()).extracting(LocationEntity::getName)
                .containsExactly("Loc1", "Loc2");
    }
}
