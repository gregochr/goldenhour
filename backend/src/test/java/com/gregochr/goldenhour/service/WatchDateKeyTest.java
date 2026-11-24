package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The watched-date key (one-screen-plan D16): {@code <lower-kebab type>:<ISO start date>}, validated against the
 * almanac sources' own {@code TYPE} constants and the almanac's horizon.
 */
class WatchDateKeyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    @ParameterizedTest(name = "{0} is a type a reader may watch")
    @ValueSource(strings = {
        "spring-tide:2026-12-03", "king-tide:2026-12-03", "supermoon:2026-12-03", "meteor:2026-12-14",
        "eclipse:2026-12-12", "lunar-eclipse:2026-12-28", "equinox:2027-02-20", "solstice:2026-12-21"})
    @DisplayName("every watchable almanac type is accepted, and the key names its start date")
    void acceptsEveryWatchableType(String key) {
        assertThat(WatchDateKey.parse(key, TODAY)).isEqualTo(LocalDate.parse(key.substring(key.indexOf(':') + 1)));
    }

    @Test
    @DisplayName("the accepted types are the source TYPE constants, without the NLC season (clipped start)")
    void typesAreTheSourceConstantsLessNlc() {
        assertThat(WatchDateKey.TYPES).containsExactlyInAnyOrder("spring-tide", "king-tide", "supermoon", "meteor",
                "eclipse", "lunar-eclipse", "equinox", "solstice");
        assertThatThrownBy(() -> WatchDateKey.parse("nlc-season:2026-12-03", TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
        "", "nonsense", "king-tide", "king-tide:", "king-tide:2026-13-01", "king-tide:2026-02-30",
        "king-tide:3-Dec", "king-tide:2026-1-3", "KING-TIDE:2026-12-03", "aurora:2026-12-03", ":2026-12-03",
        "king-tide:2026-12-03:2026-12-05"})
    @DisplayName("an unknown type, a missing or bad date, or a three-part entry id is an IllegalArgumentException")
    void refusesBadKeys(String key) {
        assertThatThrownBy(() -> WatchDateKey.parse(key, TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("a null key is an IllegalArgumentException")
    void refusesNull(String key) {
        assertThatThrownBy(() -> WatchDateKey.parse(key, TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a start beyond the almanac's horizon is refused, and the last day inside it is not; the message "
            + "does not echo the key")
    void refusesBeyondTheHorizon() {
        LocalDate last = TODAY.plusDays(AlmanacService.DEFAULT_DAYS);

        assertThat(WatchDateKey.parse("king-tide:" + last, TODAY)).isEqualTo(last);
        assertThatThrownBy(() -> WatchDateKey.parse("king-tide:" + last.plusDays(1), TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a date that can be watched.");
        assertThatThrownBy(() -> WatchDateKey.parse("<script>", TODAY)).hasMessageNotContaining("script");
    }

    @Test
    @DisplayName("tryParse never throws: a withdrawn type or a bad date is null")
    void tryParseIsNullNotAnException() {
        assertThat(WatchDateKey.tryParse("nlc-season:2026-12-03")).isNull();
        assertThat(WatchDateKey.tryParse("king-tide:garbage")).isNull();
        assertThat(WatchDateKey.tryParse(null)).isNull();
        assertThat(WatchDateKey.tryParse("king-tide:2026-12-03")).isEqualTo(LocalDate.of(2026, 12, 3));
    }

    @ParameterizedTest(name = "{0} can be un-watched")
    @ValueSource(strings = {"king-tide:2026-12-03", "nlc-season:2026-11-24", "aurora:2026-12-03", "x:y"})
    @DisplayName("a key to un-watch is checked for shape and length only, never for type")
    void storableAnyType(String key) {
        assertThatCode(() -> WatchDateKey.requireStorable(key)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "\"{0}\" cannot be a stored key")
    @ValueSource(strings = {"", "nonsense", "King Tide:2026", "a:b:c", "<script>:1"})
    @DisplayName("a key that cannot be a stored key is refused")
    void notStorable(String key) {
        assertThatThrownBy(() -> WatchDateKey.requireStorable(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key over the column's 64 characters, or a null one, cannot be a stored key")
    void notStorableByLength() {
        assertThatThrownBy(() -> WatchDateKey.requireStorable("a".repeat(60) + ":2026-12-03"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchDateKey.requireStorable(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
