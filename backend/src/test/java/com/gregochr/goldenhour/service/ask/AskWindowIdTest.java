package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AskWindowId}, the one window-id codec. */
class AskWindowIdTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);

    @Test
    @DisplayName("formats yyyy-MM-dd_sunrise and yyyy-MM-dd_sunset, the encoding the rollup always used")
    void format_matchesTheRollupEncoding() {
        assertThat(AskWindowId.format(DATE, TargetType.SUNRISE)).isEqualTo("2026-10-05_sunrise");
        assertThat(AskWindowId.format(DATE, TargetType.SUNSET)).isEqualTo("2026-10-05_sunset");
    }

    @Test
    @DisplayName("formatting does not depend on the default locale (Turkish dotless i)")
    void format_isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(AskWindowId.format(DATE, TargetType.SUNRISE)).isEqualTo("2026-10-05_sunrise");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("parse is the inverse of format for both solar events")
    void parse_roundTrips() {
        assertThat(AskWindowId.parse("2026-10-05_sunrise"))
                .contains(new AskWindowId.Parts(DATE, AskWindowKind.SUNRISE));
        assertThat(AskWindowId.parse(AskWindowId.format(DATE, TargetType.SUNSET)))
                .contains(new AskWindowId.Parts(DATE, AskWindowKind.SUNSET));
    }

    @Test
    @DisplayName("a night id round-trips through the kind codec: yyyy-MM-dd_astro and yyyy-MM-dd_aurora")
    void nightIds_roundTrip() {
        assertThat(AskWindowId.format(DATE, AskWindowKind.ASTRO)).isEqualTo("2026-10-05_astro");
        assertThat(AskWindowId.format(DATE, AskWindowKind.AURORA)).isEqualTo("2026-10-05_aurora");
        assertThat(AskWindowId.parse("2026-10-05_astro")).contains(new AskWindowId.Parts(DATE, AskWindowKind.ASTRO));
        assertThat(AskWindowId.parse(AskWindowId.format(DATE, AskWindowKind.AURORA)))
                .contains(new AskWindowId.Parts(DATE, AskWindowKind.AURORA));
        assertThat(AskWindowKind.ASTRO.isNight()).isTrue();
        assertThat(AskWindowKind.SUNSET.isNight()).isFalse();
    }

    @Test
    @DisplayName("a solar kind is the TargetType of the same name, and the hourly comfort row has no kind")
    void kindOfTargetType() {
        assertThat(AskWindowKind.of(TargetType.SUNRISE)).isEqualTo(AskWindowKind.SUNRISE);
        assertThat(AskWindowKind.of(TargetType.SUNSET)).isEqualTo(AskWindowKind.SUNSET);
        assertThatThrownBy(() -> AskWindowKind.of(TargetType.HOURLY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse refuses anything that is not a well-formed window id")
    void parse_refusesMalformedIds() {
        assertThat(AskWindowId.parse(null)).isEmpty();
        assertThat(AskWindowId.parse("")).isEmpty();
        assertThat(AskWindowId.parse("tonight")).isEmpty();
        assertThat(AskWindowId.parse("2026-10-05")).isEmpty();
        assertThat(AskWindowId.parse("2026-10-05_")).isEmpty();
        assertThat(AskWindowId.parse("_sunset")).isEmpty();
        assertThat(AskWindowId.parse("2026-10-05_SUNSET")).isEmpty();
        assertThat(AskWindowId.parse("2026-10-05_hourly")).isEmpty();
        assertThat(AskWindowId.parse("2026-13-45_sunset")).isEmpty();
        assertThat(AskWindowId.parse("yesterday_sunset")).isEmpty();
    }
}
