package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link MacOsToastNotificationService}.
 */
class MacOsToastNotificationServiceTest {

    /**
     * The disabled guard must short-circuit <em>before</em> the evaluation is dereferenced.
     *
     * <p>Passing {@code null} is what makes this assertion falsifiable: with the guard in place
     * nothing touches the evaluation, but move the guard below the title/script construction —
     * or drop it — and {@code evaluation.summary()} throws. A well-formed evaluation would make
     * this a no-op assertion, because {@code notify} cannot throw on any valid input.
     */
    @Test
    @DisplayName("notify() returns before dereferencing the evaluation when toasts are disabled")
    void notify_whenDisabled_returnsBeforeTouchingEvaluation() {
        NotificationProperties properties = new NotificationProperties();
        properties.getMacosToast().setEnabled(false);
        MacOsToastNotificationService toastService =
                new MacOsToastNotificationService(properties);

        assertThatNoException().isThrownBy(() ->
                toastService.notify(null, "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20)));
    }

    /**
     * Branch-execution cover for the dual-score title path ({@code rating == null}).
     *
     * <p>Honest billing: {@code notify} catches the only checked exception it can raise and
     * {@code String.format} is null-safe, so "does not throw" is not a strong claim. What this
     * does buy is that the dual-score branch is executed at all. The command it builds is not
     * observable from here — see {@code escapeForAppleScript} below for the assertions that
     * actually pin the script contents.
     */
    @Test
    @DisplayName("notify() executes the dual-score title branch for a Sonnet evaluation")
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

    /**
     * Branch-execution cover for the rating title path ({@code rating != null}).
     *
     * <p>Same honest billing as the Sonnet case above: it proves the rating branch runs, not
     * that the resulting notification is correct.
     */
    @Test
    @DisplayName("notify() executes the rating title branch for a Haiku evaluation")
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
    @DisplayName("escapeForAppleScript() escapes backslashes before quotes so the literal stays balanced")
    void escapeForAppleScript_escapesBackslashBeforeQuote() {
        // A trailing backslash would otherwise escape the closing quote of the literal
        assertThat(MacOsToastNotificationService.escapeForAppleScript("cloud \\ \"gap\""))
                .isEqualTo("cloud \\\\ \\\"gap\\\"");
    }

    @Test
    @DisplayName("escapeForAppleScript() replaces newlines and control characters with spaces")
    void escapeForAppleScript_flattensControlCharacters() {
        assertThat(MacOsToastNotificationService.escapeForAppleScript("line one\r\nline two\ttab"))
                .isEqualTo("line one  line two tab");
    }

    /**
     * Replaces the former {@code notify_summaryWithQuotes_doesNotThrow} case, which named the
     * escaping in its title but asserted only that no exception escaped — true whether or not the
     * quotes were escaped at all. A model-generated summary is untrusted input, so the claim worth
     * pinning is that the quote cannot close the AppleScript literal and start new statements.
     */
    @Test
    @DisplayName("escapeForAppleScript() neutralises a quote that would close the literal early")
    void escapeForAppleScript_quoteInSummary_cannotTerminateTheLiteral() {
        assertThat(MacOsToastNotificationService
                .escapeForAppleScript("nice\" with title \"pwned"))
                .isEqualTo("nice\\\" with title \\\"pwned");
    }

    @Test
    @DisplayName("escapeForAppleScript() renders a null summary as an empty literal")
    void escapeForAppleScript_nullValue_returnsEmptyString() {
        assertThat(MacOsToastNotificationService.escapeForAppleScript(null)).isEmpty();
    }
}
