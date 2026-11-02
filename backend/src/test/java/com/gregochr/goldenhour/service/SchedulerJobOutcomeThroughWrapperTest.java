package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real jobs through the real {@code DynamicSchedulerService.wrapTarget}, for the threading shapes reachable from
 * this package (scheduler-redesign-plan.md S2): a synchronous job, and a manual-aware job fired both by its
 * schedule and by Run now. The submit-then-async cycle is in {@code PipelineOrchestratorFireLogTest}. The
 * fire-log repository is a mock, so each test asserts the exact {@code complete(...)} arguments the scheduler
 * would write.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class SchedulerJobOutcomeThroughWrapperTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final long LOG_ID = 11L;

    @Mock
    private SchedulerJobConfigRepository repository;
    @Mock
    private SchedulerFireLogRepository fireLog;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private SolarTriggerFactory solarTriggers;
    @Mock
    private SolarCatchUpPolicy solarCatchUp;
    @Mock
    private ScheduledFuture<?> future;

    private DynamicSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DynamicSchedulerService(repository, fireLog, taskScheduler, auroraProperties,
                solarTriggers, solarCatchUp, Clock.fixed(NOW, ZoneOffset.UTC));
        when(fireLog.save(any(SchedulerFireLogEntity.class))).thenAnswer(inv -> {
            SchedulerFireLogEntity row = inv.getArgument(0);
            row.setId(LOG_ID);
            return row;
        });
    }

    @Test
    @DisplayName("a synchronous cleanup job: the wrapper stores SUCCEEDED and the job's own one-line summary")
    void syncJob_cleanup_storesItsSummary() {
        RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
        LocalDateTime before = LocalDateTime.now();
        when(tokens.deleteAllByExpiresAtBefore(argThat(cutoff -> cutoff != null && !cutoff.isBefore(before)
                && !cutoff.isAfter(LocalDateTime.now())))).thenReturn(3L);
        new RefreshTokenCleanupService(tokens, scheduler).registerJob();

        scheduledRunnable("refresh_token_cleanup").run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SUCCEEDED, "3 expired tokens deleted", null);
    }

    @Test
    @DisplayName("a manual-aware job fired by Run now: logged MANUAL, and its summary stored")
    void manualAwareJob_runNow_storesItsSummary() {
        AppUserRepository users = mock(AppUserRepository.class);
        LocationRepository locations = mock(LocationRepository.class);
        DriveDurationService durations = mock(DriveDurationService.class);
        UserDriveTimeWriter writer = mock(UserDriveTimeWriter.class);
        List<UserDriveTimeEntity> rows = List.of(new UserDriveTimeEntity(1L, 1L, 1200));
        when(users.findAll()).thenReturn(List.of(homeUser(1L)));
        when(durations.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(rows));
        when(writer.storeIfHomeUnchanged(1L, 54.97, -1.61, rows, NOW)).thenReturn(true);
        new DriveTimeRefreshJob(users, locations, durations, writer, scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC)).registerJob();
        when(repository.findByJobKey("drive_time_refresh"))
                .thenReturn(Optional.of(cronJob("drive_time_refresh")));

        scheduler.triggerNow("drive_time_refresh");
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(run.capture(), any(Instant.class));
        run.getValue().run();

        ArgumentCaptor<SchedulerFireLogEntity> row = ArgumentCaptor.forClass(SchedulerFireLogEntity.class);
        verify(fireLog).save(row.capture());
        assertThat(row.getValue().getTriggerKind()).isEqualTo(SchedulerFireTrigger.MANUAL);
        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SUCCEEDED,
                "1 refreshed · 0 superseded · 0 failed · 0 unchanged", null);
    }

    @Test
    @DisplayName("a manual-aware job fired by its schedule: a partial failure is stored PARTIAL")
    void manualAwareJob_scheduled_storesPartial() {
        AppUserRepository users = mock(AppUserRepository.class);
        LocationRepository locations = mock(LocationRepository.class);
        DriveDurationService durations = mock(DriveDurationService.class);
        UserDriveTimeWriter writer = mock(UserDriveTimeWriter.class);
        List<UserDriveTimeEntity> rows = List.of(new UserDriveTimeEntity(2L, 1L, 1200));
        when(users.findAll()).thenReturn(List.of(homeUser(1L), homeUser(2L)));
        when(durations.measureForUser(1L, 54.97, -1.61)).thenThrow(new IllegalStateException("ORS down"));
        when(durations.measureForUser(2L, 54.97, -1.61)).thenReturn(Optional.of(rows));
        when(writer.storeIfHomeUnchanged(2L, 54.97, -1.61, rows, NOW)).thenReturn(true);
        new DriveTimeRefreshJob(users, locations, durations, writer, scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC)).registerJob();

        scheduledRunnable("drive_time_refresh").run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.PARTIAL,
                "1 refreshed · 0 superseded · 1 failed · 0 unchanged", null);
    }

    private static AppUserEntity homeUser(long id) {
        AppUserEntity user = new AppUserEntity();
        user.setId(id);
        user.setHomeLatitude(54.97);
        user.setHomeLongitude(-1.61);
        user.setEnabled(true);
        user.setRole(UserRole.PRO_USER);
        return user;
    }

    /** Installs a CRON schedule for the key and returns the wrapped runnable the scheduler was given. */
    private Runnable scheduledRunnable(String jobKey) {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenReturn((ScheduledFuture) future);
        scheduler.scheduleJob(cronJob(jobKey));
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(run.capture(), any(Trigger.class));
        return run.getValue();
    }

    private static SchedulerJobConfigEntity cronJob(String key) {
        return SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(key)
                .displayName(key)
                .scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 2 * * MON")
                .status(SchedulerJobStatus.ACTIVE)
                .build();
    }
}
