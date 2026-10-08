package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskClock}: the one {@code HH:mm} London clock face Ask prints. */
class AskClockTest {

    @Test
    @DisplayName("the label is the UTC time in London: 05:02 UTC is 06:02 BST, 06:02 UTC is 06:02 GMT")
    void isLondonLocal() {
        // Moved from AskSnapshotBuilderTest.runLabel_isLondonLocal when the formatter moved here.
        assertThat(AskClock.londonHHmm(LocalDateTime.of(2026, 10, 5, 5, 2, 11))).isEqualTo("06:02");
        assertThat(AskClock.londonHHmm(LocalDateTime.of(2026, 12, 5, 6, 2, 11))).isEqualTo("06:02");
        assertThat(AskClock.londonHHmm(null)).isNull();
    }

    @Test
    @DisplayName("the clock crosses midnight at the BST offset and pads to two digits: 23:30 UTC in summer is "
            + "00:30, 00:05 UTC in winter stays 00:05")
    void padsAndCrossesMidnight() {
        assertThat(AskClock.londonHHmm(LocalDateTime.of(2026, 7, 1, 23, 30))).isEqualTo("00:30");
        assertThat(AskClock.londonHHmm(LocalDateTime.of(2026, 1, 1, 0, 5))).isEqualTo("00:05");
    }

    @Test
    @DisplayName("seconds are dropped, not rounded")
    void ignoresSeconds() {
        assertThat(AskClock.londonHHmm(LocalDateTime.of(2026, 10, 5, 17, 59, 59))).isEqualTo("18:59");
    }
}
