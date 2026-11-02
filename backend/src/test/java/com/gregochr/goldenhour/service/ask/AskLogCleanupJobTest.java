package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.AskLogRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SchedulerFireOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** What {@link AskLogCleanupJob} reports to the scheduler's fire log. */
@ExtendWith(MockitoExtension.class)
class AskLogCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Instant CUTOFF_90_DAYS = Instant.parse("2026-07-08T12:00:00Z");

    @Mock
    private AskLogRepository repository;
    @Mock
    private DynamicSchedulerService scheduler;

    private AskLogCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new AskLogCleanupJob(repository, scheduler, Clock.fixed(NOW, ZoneOffset.UTC), new AskProperties());
    }

    @Test
    @DisplayName("prune reports SUCCEEDED with the rows deleted before now minus the 90-day retention")
    void prune_reportsRowsDeleted() {
        when(repository.deleteOlderThan(CUTOFF_90_DAYS)).thenReturn(3);

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(job::prune);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("3 rows deleted");
    }

    @Test
    @DisplayName("a prune that finds nothing reports SUCCEEDED with 0 rows deleted")
    void prune_nothingToDelete() {
        when(repository.deleteOlderThan(CUTOFF_90_DAYS)).thenReturn(0);

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(job::prune);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("0 rows deleted");
    }
}
