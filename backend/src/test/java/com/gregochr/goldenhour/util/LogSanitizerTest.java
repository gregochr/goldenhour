package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogSanitizer}.
 *
 * <p>The behaviour under protection is log forging: a caller must never be able to smuggle a
 * line break into a logged value and invent a log entry that never happened.
 */
class LogSanitizerTest {

    /** Mirrors {@code LogSanitizer.MAX_LOGGED_LENGTH}; a change there must be a deliberate one. */
    private static final int MAX_LOGGED_LENGTH = 200;

    /** NUL, BEL, ESC and DEL — ESC in particular can drive a terminal that renders the log. */
    private static final String NUL = String.valueOf((char) 0x00);
    private static final String BEL = String.valueOf((char) 0x07);
    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String DEL = String.valueOf((char) 0x7F);

    @Test
    @DisplayName("A CRLF-bearing value is flattened to a single line, so a log entry cannot be forged")
    void crlfValue_isFlattenedToOneLine() {
        String forged = "attacker\r\n2026-07-26 12:00:00 INFO  Login successful for admin";

        String sanitised = LogSanitizer.sanitize(forged);

        assertThat(sanitised)
                .isEqualTo("attacker__2026-07-26 12:00:00 INFO  Login successful for admin")
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(sanitised.lines()).hasSize(1);
    }

    @Test
    @DisplayName("A lone line feed and a lone carriage return are both replaced")
    void bareLineBreaks_areReplaced() {
        assertThat(LogSanitizer.sanitize("a\nb")).isEqualTo("a_b");
        assertThat(LogSanitizer.sanitize("a\rb")).isEqualTo("a_b");
    }

    @Test
    @DisplayName("Other control characters are stripped, not just line breaks")
    void otherControlCharacters_areStripped() {
        String withControls = "tab\there" + NUL + "nul" + BEL + "bel" + ESC + "esc" + DEL;

        assertThat(LogSanitizer.sanitize(withControls)).isEqualTo("tab_here_nul_bel_esc_");
    }

    @Test
    @DisplayName("A value longer than the cap is truncated and marked")
    void overlongValue_isTruncatedAndMarked() {
        String overlong = "x".repeat(MAX_LOGGED_LENGTH + 1);

        String sanitised = LogSanitizer.sanitize(overlong);

        assertThat(sanitised).isEqualTo("x".repeat(MAX_LOGGED_LENGTH) + "...");
    }

    @Test
    @DisplayName("A value exactly at the cap is left whole and unmarked")
    void valueAtCap_isLeftWhole() {
        String atCap = "x".repeat(MAX_LOGGED_LENGTH);

        assertThat(LogSanitizer.sanitize(atCap)).isEqualTo(atCap);
    }

    @Test
    @DisplayName("Null renders as \"null\" so callers need no null check of their own")
    void nullValue_rendersAsNullLabel() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("An ordinary clean value passes through completely unchanged")
    void cleanValue_isUnchanged() {
        String clean = "chris@thegregorysonline.com";

        assertThat(LogSanitizer.sanitize(clean)).isEqualTo(clean);
        assertThat(LogSanitizer.sanitize("Bamburgh Castle / SUNSET (T+2)"))
                .isEqualTo("Bamburgh Castle / SUNSET (T+2)");
    }

    @Test
    @DisplayName("An empty value stays empty rather than becoming a placeholder")
    void emptyValue_staysEmpty() {
        assertThat(LogSanitizer.sanitize("")).isEmpty();
    }

    @Test
    @DisplayName("A forged log line embedded in a value cannot survive sanitising")
    void forgedLogLine_isFlattenedToOneLine() {
        String forged = "id=7\r\nINFO  Registration complete: user='admin' role=ADMIN";

        assertThat(LogSanitizer.sanitize(forged))
                .isEqualTo("id=7__INFO  Registration complete: user='admin' role=ADMIN")
                .doesNotContain("\r", "\n");
    }
}
