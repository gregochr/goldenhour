package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotificationDispatcher}.
 *
 * <p>Pins the two guarantees the previous hand-rolled dispatch in {@code ForecastService} did
 * not make: every channel is announced to, and a channel that throws does not stop its
 * siblings (the old single try/catch around all three calls meant an email failure silently
 * suppressed the Pushover and toast sends).
 */
class NotificationDispatcherTest {

    private static final SunsetEvaluation EVALUATION =
            new SunsetEvaluation(4, 80, 70, "Good conditions.");
    private static final String LOCATION = "Durham UK";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 16);

    @Test
    @DisplayName("dispatches to every registered channel")
    void dispatchesToEveryChannel() {
        NotificationChannel first = mock(NotificationChannel.class);
        NotificationChannel second = mock(NotificationChannel.class);
        NotificationChannel third = mock(NotificationChannel.class);

        new NotificationDispatcher(List.of(first, second, third))
                .dispatch(EVALUATION, LOCATION, TargetType.SUNSET, DATE);

        verify(first).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNSET), eq(DATE));
        verify(second).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNSET), eq(DATE));
        verify(third).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNSET), eq(DATE));
    }

    @Test
    @DisplayName("a failing channel does not stop the channels after it")
    void failingChannelDoesNotStopSiblings() {
        NotificationChannel failing = mock(NotificationChannel.class);
        NotificationChannel healthy = mock(NotificationChannel.class);
        doThrow(new RuntimeException("SMTP down"))
                .when(failing).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNSET), eq(DATE));

        new NotificationDispatcher(List.of(failing, healthy))
                .dispatch(EVALUATION, LOCATION, TargetType.SUNSET, DATE);

        // The old code wrapped all three sends in one try/catch, so this send was skipped.
        verify(healthy).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNSET), eq(DATE));
    }

    @Test
    @DisplayName("a failing channel never propagates — the forecast is already saved")
    void failingChannelDoesNotPropagate() {
        NotificationChannel failing = mock(NotificationChannel.class);
        doThrow(new RuntimeException("SMTP down"))
                .when(failing).notify(eq(EVALUATION), eq(LOCATION), eq(TargetType.SUNRISE), eq(DATE));
        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(failing));

        assertThatCode(() -> dispatcher.dispatch(EVALUATION, LOCATION, TargetType.SUNRISE, DATE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no channels registered is a no-op")
    void noChannelsIsNoOp() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of());

        assertThatCode(() -> dispatcher.dispatch(EVALUATION, LOCATION, TargetType.SUNSET, DATE))
                .doesNotThrowAnyException();
    }
}
