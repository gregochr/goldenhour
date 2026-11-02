package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link SchedulerFireLogCleanupJob}: it registers under its seeded key, prunes with a 14-day cutoff,
 * and reports the count as the fire's summary.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerFireLogCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:57:00Z");

    @Mock
    private SchedulerFireLogRepository repository;

    @Mock
    private DynamicSchedulerService scheduler;

    private SchedulerFireLogCleanupJob job() {
        return new SchedulerFireLogCleanupJob(repository, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("it registers its prune under the V215-seeded job key")
    void registersUnderTheSeededKey() {
        SchedulerFireLogCleanupJob job = job();

        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(org.mockito.ArgumentMatchers.eq("scheduler_fire_log_cleanup"),
                target.capture());
        assertThat(SchedulerFireLogCleanupJob.JOB_KEY).isEqualTo("scheduler_fire_log_cleanup");
        assertThat(target.getValue()).isNotNull();
    }

    @Test
    @DisplayName("it deletes rows fired before now minus 14 days and returns the count")
    void prune_deletesBeforeTheFourteenDayCutoff() {
        when(repository.deleteByFiredAtBefore(Instant.parse("2026-10-19T03:57:00Z"))).thenReturn(4012);

        int deleted = job().prune();

        assertThat(deleted).isEqualTo(4012);
        assertThat(SchedulerFireLogCleanupJob.RETENTION.toDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("inside a fire it reports SUCCEEDED with 'n rows deleted'")
    void prune_reportsTheCount() {
        when(repository.deleteByFiredAtBefore(Instant.parse("2026-10-19T03:57:00Z"))).thenReturn(3);
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();

        SchedulerFireOutcome.runBound(carrier, () -> job().prune());

        assertThat(carrier.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(carrier.summary()).isEqualTo("3 rows deleted");
    }

    @Test
    @DisplayName("a repository failure propagates to the scheduler")
    void prune_failurePropagates() {
        when(repository.deleteByFiredAtBefore(Instant.parse("2026-10-19T03:57:00Z")))
                .thenThrow(new IllegalStateException("db down"));
        SchedulerFireLogCleanupJob job = job();

        assertThatThrownBy(job::prune).isInstanceOf(IllegalStateException.class);
    }
}
