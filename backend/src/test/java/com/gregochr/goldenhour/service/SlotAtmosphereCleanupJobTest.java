package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlotAtmosphereCleanupJob}: the cutoff arithmetic on the UK civil calendar,
 * the logging contract (cutoff and count, zero included), failure propagation, scheduler
 * registration, and the startup guard that refuses a retention shorter than the longest reader.
 */
@ExtendWith(MockitoExtension.class)
class SlotAtmosphereCleanupJobTest {

    /** A GMT winter noon: UK date 2026-12-15. 180 days earlier is 2026-06-18. */
    private static final Clock WINTER_NOON =
            Clock.fixed(Instant.parse("2026-12-15T12:00:00Z"), ZoneOffset.UTC);
    /** 23:30 UTC in BST is 00:30 on the NEXT UK day: UK date 2026-07-16; 180 days earlier is 2026-01-17. */
    private static final Clock BST_LATE_EVENING =
            Clock.fixed(Instant.parse("2026-07-15T23:30:00Z"), ZoneOffset.UTC);

    @Mock
    private SlotAtmosphereRepository repository;
    @Mock
    private DynamicSchedulerService scheduler;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger jobLogger;

    @BeforeEach
    void attachLogAppender() {
        jobLogger = (Logger) LoggerFactory.getLogger(SlotAtmosphereCleanupJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        jobLogger.detachAppender(logAppender);
    }

    private SlotAtmosphereCleanupJob job(Clock clock, int retentionDays) {
        return new SlotAtmosphereCleanupJob(repository, scheduler, clock,
                new ComingUpScoringProperties(), retentionDays);
    }

    private List<String> infoMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("the cutoff handed to the repository is the UK civil date minus exactly 180 days")
    void prune_cutoffIsUkTodayMinus180Days() {
        when(repository.deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18))).thenReturn(7);

        int deleted = job(WINTER_NOON, 180).prune();

        assertThat(deleted).isEqualTo(7);
        verify(repository).deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18));
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("at 23:30 UTC in summer the UK day is already tomorrow, so the cutoff follows the UK date")
    void prune_bstLateEvening_usesTheUkDateNotTheUtcDate() {
        job(BST_LATE_EVENING, 180).prune();

        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).deleteByEvaluationDateBefore(cutoff.capture());
        // UTC date 2026-07-15 minus 180 would be 2026-01-16; the UK date 2026-07-16 gives 2026-01-17.
        assertThat(cutoff.getValue()).isEqualTo(LocalDate.of(2026, 1, 17));
    }

    @Test
    @DisplayName("a configured retention other than the default moves the cutoff accordingly")
    void prune_customRetention_movesTheCutoff() {
        job(WINTER_NOON, 90).prune();

        verify(repository).deleteByEvaluationDateBefore(LocalDate.of(2026, 9, 16));
    }

    @Test
    @DisplayName("deleting nothing is logged at INFO with the cutoff and a zero count, and is not an error")
    void prune_zeroRowsDeleted_isLoggedAndNotAnError() {
        when(repository.deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18))).thenReturn(0);

        SlotAtmosphereCleanupJob job = job(WINTER_NOON, 180);

        assertThatCode(job::prune).doesNotThrowAnyException();
        assertThat(infoMessages()).containsExactly(
                "[SLOT ATMOSPHERE] Cleanup complete — deleted 0 row(s) with evaluation_date before "
                        + "2026-06-18 (180 day retention)");
        assertThat(logAppender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    @DisplayName("a non-zero delete logs the count and the cutoff date")
    void prune_rowsDeleted_logsCountAndCutoff() {
        when(repository.deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18))).thenReturn(1234);

        job(WINTER_NOON, 180).prune();

        assertThat(infoMessages()).containsExactly(
                "[SLOT ATMOSPHERE] Cleanup complete — deleted 1234 row(s) with evaluation_date before "
                        + "2026-06-18 (180 day retention)");
    }

    @Test
    @DisplayName("a repository failure propagates to the scheduler thread (as the model disposition "
            + "cleanup does) and no success line is logged")
    void prune_repositoryFailure_propagatesAndLogsNoSuccess() {
        when(repository.deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18)))
                .thenThrow(new IllegalStateException("db down"));

        SlotAtmosphereCleanupJob job = job(WINTER_NOON, 180);

        assertThatThrownBy(job::prune)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
        assertThat(infoMessages()).isEmpty();
    }

    @Test
    @DisplayName("registerJob wires the slot_atmosphere_cleanup key to the prune")
    void registerJob_registersTargetUnderTheSeededKey() {
        when(repository.deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18))).thenReturn(0);
        SlotAtmosphereCleanupJob job = job(WINTER_NOON, 180);

        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(org.mockito.ArgumentMatchers.eq("slot_atmosphere_cleanup"),
                target.capture());
        target.getValue().run();
        verify(repository).deleteByEvaluationDateBefore(LocalDate.of(2026, 6, 18));
    }

    @Test
    @DisplayName("the job key and default retention are the owner-decided values")
    void constants_areTheOwnerDecidedValues() {
        assertThat(SlotAtmosphereCleanupJob.JOB_KEY).isEqualTo("slot_atmosphere_cleanup");
        assertThat(SlotAtmosphereCleanupJob.DEFAULT_RETENTION_DAYS).isEqualTo(180);
    }

    // ── The startup guard ─────────────────────────────────────────────────

    @Test
    @DisplayName("a retention one day shorter than the real default trailing window is refused at "
            + "construction with a message naming both numbers")
    void guard_retentionBelowTheRealTrailingWindow_isRefused() {
        int window = new ComingUpScoringProperties().getRecurrent().getTrailingWindowDays();
        assertThat(window).as("the shipped default trailing window").isEqualTo(60);

        assertThatThrownBy(() -> job(WINTER_NOON, window - 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("photocast.slot-atmosphere.retention-days=59")
                .hasMessageContaining("60 days")
                .hasMessageContaining("Raise retention-days to at least 60");
    }

    @Test
    @DisplayName("a retention exactly equal to the trailing window is accepted")
    void guard_retentionEqualToTheWindow_isAccepted() {
        int window = new ComingUpScoringProperties().getRecurrent().getTrailingWindowDays();

        assertThatCode(() -> job(WINTER_NOON, window)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the guard follows the configured window: widening it to 200 days refuses the "
            + "default 180-day retention")
    void guard_widenedWindow_refusesTheDefaultRetention() {
        ComingUpScoringProperties properties = new ComingUpScoringProperties();
        properties.getRecurrent().setTrailingWindowDays(200);

        assertThatThrownBy(() -> new SlotAtmosphereCleanupJob(repository, scheduler, WINTER_NOON,
                properties, SlotAtmosphereCleanupJob.DEFAULT_RETENTION_DAYS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-days=180")
                .hasMessageContaining("200 days");
    }

    @Test
    @DisplayName("a zero or negative retention is refused even if the trailing window is misconfigured to 0")
    void guard_nonPositiveRetention_isRefused() {
        ComingUpScoringProperties properties = new ComingUpScoringProperties();
        properties.getRecurrent().setTrailingWindowDays(0);

        assertThatThrownBy(() -> new SlotAtmosphereCleanupJob(repository, scheduler, WINTER_NOON,
                properties, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-days=0")
                .hasMessageContaining("1 days");
    }

    @Test
    @DisplayName("the shipped default retention clears the longest real reader window with room to spare")
    void defaultRetention_exceedsTheLongestRealReadBack() {
        int longest = SlotAtmosphereCleanupJob.longestReadBackDays(new ComingUpScoringProperties());

        assertThat(longest).isEqualTo(60);
        assertThat(SlotAtmosphereCleanupJob.DEFAULT_RETENTION_DAYS).isGreaterThan(longest);
    }
}
