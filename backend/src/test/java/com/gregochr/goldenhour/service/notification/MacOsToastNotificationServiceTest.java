package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link MacOsToastNotificationService}.
 */
@ExtendWith(MockitoExtension.class)
class MacOsToastNotificationServiceTest {

    @Mock
    private NotificationProperties properties;

    @Test
    @DisplayName("notify() does nothing when macOS toast notifications are disabled")
    void notify_whenDisabled_completesWithoutException() {
        NotificationProperties.MacosToast toast = new NotificationProperties.MacosToast();
        toast.setEnabled(false);
        org.mockito.Mockito.when(properties.getMacosToast()).thenReturn(toast);

        MacOsToastNotificationService toastService = new MacOsToastNotificationService(properties);

        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(3, "Moderate."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    @Test
    @DisplayName("notify() does not throw when macOS toast is enabled and osascript runs")
    void notify_whenEnabled_doesNotThrow() {
        NotificationProperties.MacosToast toast = new NotificationProperties.MacosToast();
        toast.setEnabled(true);
        org.mockito.Mockito.when(properties.getMacosToast()).thenReturn(toast);

        MacOsToastNotificationService toastService = new MacOsToastNotificationService(properties);

        // osascript may or may not be available in CI — service must not throw
        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(4, "Good light expected."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }
}
