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
                toastService.notify(new SunsetEvaluation(null, 30, 40, "Moderate."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    @Test
    @DisplayName("notify() does not throw for Sonnet evaluation when macOS toast is enabled")
    void notify_sonnetEvaluation_whenEnabled_doesNotThrow() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(true);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        // osascript may or may not be available in CI — service must not throw
        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(null, 70, 75, "Good light expected."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    @Test
    @DisplayName("notify() does not throw for Haiku evaluation when macOS toast is enabled")
    void notify_haikuEvaluation_whenEnabled_doesNotThrow() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(true);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(4, null, null, "Good conditions."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    @Test
    @DisplayName("notify() handles SUNRISE target type without throwing")
    void notify_sunriseTargetType_doesNotThrow() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(true);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        assertThatNoException().isThrownBy(() ->
                toastService.notify(new SunsetEvaluation(null, 60, 55, "Decent sunrise."),
                        "Bamburgh", TargetType.SUNRISE, LocalDate.of(2026, 3, 1)));
    }

    @Test
    @DisplayName("notify() handles summary with double quotes without throwing")
    void notify_summaryWithQuotes_doesNotThrow() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(true);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        // Double quotes in the summary must be escaped for osascript
        assertThatNoException().isThrownBy(() ->
                toastService.notify(
                        new SunsetEvaluation(null, 70, 65, "A \"beautiful\" golden sky expected."),
                        "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 3, 1)));
    }
}
