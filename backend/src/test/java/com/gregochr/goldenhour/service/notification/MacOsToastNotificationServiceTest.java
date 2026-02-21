package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link MacOsToastNotificationService}.
 */
class MacOsToastNotificationServiceTest {

    @Test
    @DisplayName("notify() does nothing when macOS toast notifications are disabled")
    void notify_whenDisabled_completesWithoutException() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(false);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(3, "Moderate."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    @Test
    @DisplayName("notify() does not throw when macOS toast is enabled and osascript runs")
    void notify_whenEnabled_doesNotThrow() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(true);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        // osascript may or may not be available in CI — service must not throw
        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(4, "Good light expected."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }
}
