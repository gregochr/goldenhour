package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LunarEclipseSight}'s own compact-constructor invariant: {@code moonset} must be after
 * {@code moonrise} whenever both are present.
 *
 * <p>{@code LunarEclipseCalculator} already guarantees this by construction — it derives
 * {@code moonset} as the first set strictly after the chosen {@code moonrise}, never picks the two
 * independently (Codex review, #911) — so this check is defence in depth rather than the primary
 * fix. It is tested here in its own right so a future change that breaks the calculator's guarantee
 * fails loudly at construction instead of silently shipping a chronologically impossible sight.
 */
class LunarEclipseSightTest {

    private static final LocalDateTime MOONRISE = LocalDateTime.of(2028, 12, 31, 15, 36);
    private static final LocalDateTime MOONSET_AFTER = LocalDateTime.of(2029, 1, 1, 9, 8);

    private static LunarEclipseSight sightWith(LocalDateTime moonset, LocalDateTime moonrise) {
        return new LunarEclipseSight(7, 244, "WSW", moonset, moonrise, false, true,
                LocalDateTime.of(2028, 12, 31, 15, 7), LocalDateTime.of(2028, 12, 31, 18, 37), true);
    }

    @Test
    @DisplayName("a moonset strictly after moonrise constructs without complaint")
    void moonsetAfterMoonriseIsAccepted() {
        LunarEclipseSight sight = sightWith(MOONSET_AFTER, MOONRISE);

        assertThat(sight.moonset()).isEqualTo(MOONSET_AFTER);
        assertThat(sight.moonrise()).isEqualTo(MOONRISE);
    }

    @Test
    @DisplayName("a moonset before moonrise is rejected")
    void moonsetBeforeMoonriseIsRejected() {
        LocalDateTime moonsetBefore = MOONRISE.minusHours(1);

        assertThatThrownBy(() -> sightWith(moonsetBefore, MOONRISE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moonset must be after moonrise");
    }

    @Test
    @DisplayName("a moonset equal to moonrise is rejected — 'after' is strict")
    void moonsetEqualToMoonriseIsRejected() {
        assertThatThrownBy(() -> sightWith(MOONRISE, MOONRISE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moonset must be after moonrise");
    }

    @Test
    @DisplayName("a null moonset with a non-null moonrise is accepted (the moon does not set nearby)")
    void nullMoonsetIsAccepted() {
        LunarEclipseSight sight = sightWith(null, MOONRISE);

        assertThat(sight.moonset()).isNull();
        assertThat(sight.moonrise()).isEqualTo(MOONRISE);
    }

    @Test
    @DisplayName("a null moonrise with a non-null moonset is accepted (the moon does not rise nearby)")
    void nullMoonriseIsAccepted() {
        LunarEclipseSight sight = sightWith(MOONSET_AFTER, null);

        assertThat(sight.moonset()).isEqualTo(MOONSET_AFTER);
        assertThat(sight.moonrise()).isNull();
    }

    @Test
    @DisplayName("both null is accepted (neither event falls near the umbral span)")
    void bothNullIsAccepted() {
        LunarEclipseSight sight = sightWith(null, null);

        assertThat(sight.moonset()).isNull();
        assertThat(sight.moonrise()).isNull();
    }
}
