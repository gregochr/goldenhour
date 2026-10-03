package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.repository.ForecastEvaluationPromptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ForecastPromptCleanupJob}. */
@ExtendWith(MockitoExtension.class)
class ForecastPromptCleanupJobTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-03T03:50:00Z"), ZoneOffset.UTC);

    @Mock
    private ForecastEvaluationPromptRepository repository;
    @Mock
    private DynamicSchedulerService scheduler;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(ForecastPromptCleanupJob.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("deletes strictly before now minus retention: the cutoff itself is kept")
    void cutoffIsNowMinusRetention() {
        when(repository.deleteByCreatedAtBefore(Instant.parse("2026-09-03T03:50:00Z")))
                .thenReturn(7);

        int deleted = new ForecastPromptCleanupJob(repository, scheduler, CLOCK, 30).prune();

        assertThat(deleted).isEqualTo(7);
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByCreatedAtBefore(captor.capture());
        assertThat(captor.getValue()).isEqualTo(Instant.parse("2026-09-03T03:50:00Z"));
    }

    @Test
    @DisplayName("a zero-row run still logs the cutoff and a count of 0 at INFO")
    void zeroRowRunLogsZero() {
        when(repository.deleteByCreatedAtBefore(Instant.parse("2026-09-26T03:50:00Z")))
                .thenReturn(0);

        new ForecastPromptCleanupJob(repository, scheduler, CLOCK, 7).prune();

        List<String> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(infos).containsExactly("[FORECAST PROMPT] Cleanup complete — deleted 0 row(s) "
                + "created before 2026-09-26T03:50:00Z (7 day retention)");
    }

    @Test
    @DisplayName("registers its prune against the job key")
    void registersJob() {
        new ForecastPromptCleanupJob(repository, scheduler, CLOCK, 30).registerJob();

        verify(scheduler).registerJobTarget(
                org.mockito.ArgumentMatchers.eq("forecast_prompt_cleanup"),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("a non-positive retention is refused")
    void rejectsNonPositiveRetention() {
        assertThatThrownBy(() -> new ForecastPromptCleanupJob(repository, scheduler, CLOCK, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-days=0");
    }
}
