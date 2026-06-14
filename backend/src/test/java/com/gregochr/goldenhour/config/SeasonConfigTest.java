package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.model.SeasonalWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SeasonConfig} — the parse from {@code MM-dd} config strings into a
 * {@link SeasonalWindow}.
 */
class SeasonConfigTest {

    private final SeasonConfig config = new SeasonConfig();

    @Test
    @DisplayName("default config produces the historic Apr 18 – May 18 window")
    void bluebellSeasonWindow_defaults_matchHistoricWindow() {
        SeasonalWindow window = config.bluebellSeasonWindow(new SeasonProperties());

        assertThat(window.start()).isEqualTo(MonthDay.of(4, 18));
        assertThat(window.end()).isEqualTo(MonthDay.of(5, 18));
        assertThat(window.name()).isEqualTo("BLUEBELL");
        assertThat(window.isActive(LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(window.isActive(LocalDate.of(2026, 6, 1))).isFalse();
    }

    @Test
    @DisplayName("custom config boundaries are honoured")
    void bluebellSeasonWindow_customConfig_parsesBoundaries() {
        SeasonProperties props = new SeasonProperties();
        props.getBluebell().setStart("04-10");
        props.getBluebell().setEnd("05-25");

        SeasonalWindow window = config.bluebellSeasonWindow(props);

        assertThat(window.start()).isEqualTo(MonthDay.of(4, 10));
        assertThat(window.end()).isEqualTo(MonthDay.of(5, 25));
        // A date inside the widened window but outside the default window is now active.
        assertThat(window.isActive(LocalDate.of(2026, 4, 12))).isTrue();
        assertThat(window.isActive(LocalDate.of(2026, 5, 22))).isTrue();
    }

    @Test
    @DisplayName("a non MM-dd boundary fails fast with a helpful message")
    void bluebellSeasonWindow_invalidConfig_throws() {
        SeasonProperties props = new SeasonProperties();
        props.getBluebell().setStart("April 18th");

        assertThatThrownBy(() -> config.bluebellSeasonWindow(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("photocast.season.bluebell.start")
                .hasMessageContaining("MM-dd");
    }
}
