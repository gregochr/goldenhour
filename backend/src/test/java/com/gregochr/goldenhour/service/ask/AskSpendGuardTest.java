package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AskSpendGuard}: typed questions are refused when the day's cap is reached, when a
 * model call's cost cannot be recorded, or when today's spend cannot be read; the admin is told once a day.
 */
@ExtendWith(MockitoExtension.class)
class AskSpendGuardTest {

    private static final long CAP = 500_000L;
    private static final LocalDate DAY_ONE = LocalDate.of(2031, 3, 10);
    private static final LocalDate DAY_TWO = LocalDate.of(2031, 3, 11);

    @Mock
    private AskJobRunService jobRuns;
    @Mock
    private AdminAlertService adminAlerts;

    private final AskProperties properties = new AskProperties();
    private MutableClock clock;
    private AskSpendGuard guard;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setDailySpendCapUsd(0.50);
        clock = new MutableClock(Instant.parse("2031-03-10T12:00:00Z"));
        guard = new AskSpendGuard(properties, jobRuns, adminAlerts, clock);
    }

    @Test
    @DisplayName("typed questions are available while the feature is on, accounting works and spend is under the cap")
    void availableUnderCap() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP - 1);

        assertThat(guard.typedAvailable()).isTrue();
    }

    @Test
    @DisplayName("typed questions are unavailable when the feature is off, without reading any spend")
    void unavailableWhenDisabled() {
        properties.setEnabled(false);

        assertThat(guard.typedAvailable()).isFalse();
        verify(jobRuns, never()).typedSpendTodayMicroDollars();
    }

    @Test
    @DisplayName("typed questions are unavailable when a model call's cost cannot be recorded")
    void unavailableWhenAccountingDown() {
        when(jobRuns.accountingAvailable()).thenReturn(false);

        assertThat(guard.typedAvailable()).isFalse();
        verify(jobRuns, never()).typedSpendTodayMicroDollars();
    }

    @Test
    @DisplayName("typed questions are unavailable once spend equals the cap")
    void unavailableAtCap() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP);

        assertThat(guard.typedAvailable()).isFalse();
    }

    @Test
    @DisplayName("typed questions are reported unavailable when today's spend cannot be read")
    void unavailableWhenSpendUnreadable() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenThrow(new IllegalStateException("db down"));

        assertThat(guard.typedAvailable()).isFalse();
    }

    @Test
    @DisplayName("a question is refused, with no alert, when the cost of a model call could not be recorded")
    void refusesWhenAccountingDown() {
        when(jobRuns.accountingAvailable()).thenReturn(false);

        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts, never()).sendAskSpendCapAlert(DAY_ONE, 0L, CAP);
        verify(jobRuns, never()).typedSpendTodayMicroDollars();
    }

    @Test
    @DisplayName("a question is refused, with no alert, when today's spend cannot be read")
    void refusesWhenSpendUnreadable() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenThrow(new IllegalStateException("db down"));

        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts, never()).sendAskSpendCapAlert(DAY_ONE, 0L, CAP);
    }

    @Test
    @DisplayName("a question is allowed one micro-dollar under the cap")
    void allowedUnderCap() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP - 1);

        assertThat(guard.refuse()).isFalse();
        verify(adminAlerts, never()).sendAskSpendCapAlert(DAY_ONE, CAP - 1, CAP);
    }

    @Test
    @DisplayName("at the cap a question is refused and the admin is alerted with the day, the spend and the cap")
    void refusesAtCapAndAlerts() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP);

        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts).sendAskSpendCapAlert(DAY_ONE, CAP, CAP);
    }

    @Test
    @DisplayName("the admin is alerted once per day however many questions are refused, and again the next day")
    void alertsOncePerDay() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP + 10);

        assertThat(guard.refuse()).isTrue();
        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts).sendAskSpendCapAlert(DAY_ONE, CAP + 10, CAP);

        clock.advance(Duration.ofDays(1));
        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts).sendAskSpendCapAlert(DAY_TWO, CAP + 10, CAP);
    }

    @Test
    @DisplayName("a failing alert does not stop the refusal")
    void alertFailureStillRefuses() {
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP);
        doThrow(new IllegalStateException("smtp down")).when(adminAlerts)
                .sendAskSpendCapAlert(DAY_ONE, CAP, CAP);

        assertThat(guard.refuse()).isTrue();
        verify(adminAlerts).sendAskSpendCapAlert(DAY_ONE, CAP, CAP);
    }
}
