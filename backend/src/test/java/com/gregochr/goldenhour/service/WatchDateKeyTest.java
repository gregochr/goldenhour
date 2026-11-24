package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The watched-date key (one-screen-plan D16): {@code <lower-kebab type>:<ISO start date>}, validated against the
 * almanac sources' own {@code TYPE} constants.
 */
class WatchDateKeyTest {

    @ParameterizedTest(name = "{0} is a type a reader may watch")
    @ValueSource(strings = {
        "spring-tide:2026-12-03", "king-tide:2026-12-03", "supermoon:2026-12-03", "meteor:2026-12-14",
        "nlc-season:2027-06-01", "eclipse:2026-08-12", "lunar-eclipse:2026-08-28", "equinox:2027-03-20",
        "solstice:2026-12-21"})
    @DisplayName("every almanac type the sources serve is accepted, and the key names its start date")
    void acceptsEveryServedType(String key) {
        assertThat(WatchDateKey.parse(key)).isEqualTo(LocalDate.parse(key.substring(key.indexOf(':') + 1)));
    }

    @Test
    @DisplayName("the accepted types are exactly the source TYPE constants")
    void typesAreTheSourceConstants() {
        assertThat(WatchDateKey.TYPES).containsExactlyInAnyOrder("spring-tide", "king-tide", "supermoon", "meteor",
                "nlc-season", "eclipse", "lunar-eclipse", "equinox", "solstice");
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
        "", "nonsense", "king-tide", "king-tide:", "king-tide:2026-13-01", "king-tide:2026-02-30",
        "king-tide:3-Dec", "king-tide:2026-1-3", "KING-TIDE:2026-12-03", "aurora:2026-12-03", ":2026-12-03",
        "king-tide:2026-12-03:2026-12-05"})
    @DisplayName("an unknown type, a missing or bad date, or a three-part entry id is an IllegalArgumentException")
    void refusesBadKeys(String key) {
        assertThatThrownBy(() -> WatchDateKey.parse(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("a null key is an IllegalArgumentException")
    void refusesNull(String key) {
        assertThatThrownBy(() -> WatchDateKey.parse(key)).isInstanceOf(IllegalArgumentException.class);
    }
}
