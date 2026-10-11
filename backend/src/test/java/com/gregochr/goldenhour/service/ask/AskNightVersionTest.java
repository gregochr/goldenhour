package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskNightFixtures.GENERATION;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TOMORROW_NIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskNightVersion}: what a night answer is versioned on and when the version is live. */
class AskNightVersionTest {

    private static AskSnapshot snapshot(AuroraState state) {
        return nightsOnly(state, astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3)),
                astroNight(TOMORROW_NIGHT, astro(1L, "Whitby", "Coast", 4, 3)));
    }

    private static String version(AskSnapshot snapshot, boolean canSeeAurora, LocalDate... dates) {
        return AskNightVersion.of(snapshot, Set.of(dates), canSeeAurora).orElseThrow();
    }

    @Test
    @DisplayName("no night consulted or named is no version")
    void noNights_noVersion() {
        assertThat(AskNightVersion.of(snapshot(null), Set.of(), true)).isEmpty();
        assertThat(AskNightVersion.of(snapshot(null), null, true)).isEmpty();
    }

    @Test
    @DisplayName("the version carries the astro generation of each night, in date order, and the aurora level "
            + "when the asker could see it and tonight is among the nights")
    void composition() {
        long millis = GENERATION.toEpochMilli();
        AskSnapshot snapshot = snapshot(new AuroraState("MODERATE", 6.0));

        assertThat(version(snapshot, true, TOMORROW_NIGHT, TONIGHT))
                .isEqualTo("n1;2026-10-05=" + millis + ";2026-10-06=" + millis + ";a=MODERATE");
        assertThat(version(snapshot, false, TONIGHT)).isEqualTo("n1;2026-10-05=" + millis);
        assertThat(version(snapshot, true, TOMORROW_NIGHT)).isEqualTo("n1;2026-10-06=" + millis);
    }

    @Test
    @DisplayName("a night with no astro rows versions as '-', and an unreadable state as '-'")
    void missingParts() {
        AskSnapshot snapshot = nightsOnly(null);

        assertThat(version(snapshot, true, TONIGHT)).isEqualTo("n1;2026-10-05=-;a=-");
    }

    @Test
    @DisplayName("a version is live against the snapshot it was made from")
    void liveAgainstItself() {
        AskSnapshot snapshot = snapshot(new AuroraState("MODERATE", 6.0));

        assertThat(AskNightVersion.isLive(version(snapshot, true, TONIGHT, TOMORROW_NIGHT), snapshot)).isTrue();
        assertThat(AskNightVersion.isLive(version(snapshot, false, TONIGHT), snapshot)).isTrue();
    }

    @Test
    @DisplayName("a moved astro generation, a changed aurora level or a night that has gone makes it stale")
    void staleWhenTheForecastMoves() {
        AskSnapshot before = snapshot(new AuroraState("MODERATE", 6.0));
        String version = version(before, true, TONIGHT);

        AskSnapshot refreshed = new AskSnapshot(before.generatedAt(), before.runLabel(), before.today(),
                before.windows(), before.hotTopics(), before.comingUp(), false,
                new AskSnapshot.Nights(TONIGHT, List.of(new AskSnapshot.Night("2026-10-05_astro", TONIGHT,
                        AskWindowKind.ASTRO, null, null, Instant.parse("2026-10-05T15:30:00Z"),
                        astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3)).spots())),
                        new AuroraState("MODERATE", 6.0)));
        AskSnapshot quieter = snapshot(new AuroraState("QUIET", null));
        AskSnapshot gone = nightsOnly(new AuroraState("MODERATE", 6.0));

        assertThat(AskNightVersion.isLive(version, refreshed)).isFalse();
        assertThat(AskNightVersion.isLive(version, quieter)).isFalse();
        assertThat(AskNightVersion.isLive(version, gone)).isFalse();
    }

    @Test
    @DisplayName("a LITE asker's version does not move with the aurora state they were never told")
    void liteVersionIgnoresTheState() {
        String version = version(snapshot(new AuroraState("MODERATE", 6.0)), false, TONIGHT);

        assertThat(AskNightVersion.isLive(version, snapshot(new AuroraState("QUIET", null)))).isTrue();
    }

    @Test
    @DisplayName("only the server's own format is accepted back: a forged, edited or oversized string is not live")
    void formatIsStrict() {
        AskSnapshot snapshot = snapshot(null);

        assertThat(AskNightVersion.wellFormed("n1;2026-10-05=-")).isTrue();
        assertThat(AskNightVersion.wellFormed("n1;2026-10-05=123;a=QUIET")).isTrue();
        assertThat(AskNightVersion.wellFormed(null)).isFalse();
        assertThat(AskNightVersion.wellFormed("")).isFalse();
        assertThat(AskNightVersion.wellFormed("n1")).isFalse();
        assertThat(AskNightVersion.wellFormed("n2;2026-10-05=-")).isFalse();
        assertThat(AskNightVersion.wellFormed("n1;2026-13-45=-")).isFalse();
        assertThat(AskNightVersion.wellFormed("n1;2026-10-05=-;a=quiet")).isFalse();
        assertThat(AskNightVersion.wellFormed("n1;2026-10-05=-" + ";2026-10-06=1".repeat(30))).isFalse();
        assertThat(AskNightVersion.isLive("n1;2026-13-45=-", snapshot)).isFalse();
        assertThat(AskNightVersion.isLive(null, snapshot)).isFalse();
    }
}
