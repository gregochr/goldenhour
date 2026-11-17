package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.service.warnings.WarningTimeline.ActiveWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarningTimeline}. Dates are in 2031 so that a test cannot pass by accident against the
 * real clock.
 */
class WarningTimelineTest {

    private static final Instant T0 = Instant.parse("2031-03-10T06:00:00Z");

    private static WarningTimeline at(String instant) {
        return new WarningTimeline(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static WeatherWarningVersionEntity version(String id, int v, String level, String receivedAt,
            String validTo) {
        WeatherWarningVersionEntity row = new WeatherWarningVersionEntity();
        row.setWarningId(id);
        row.setWarningVersion(v);
        row.setStatus(WeatherWarningVersionEntity.STATUS_ACTIVE);
        row.setWarningLevel(level);
        row.setReceivedAt(Instant.parse(receivedAt));
        row.setValidTo(validTo == null ? null : Instant.parse(validTo));
        return row;
    }

    private static WeatherWarningVersionEntity cancelled(String id, int v, String receivedAt) {
        WeatherWarningVersionEntity row = version(id, v, null, receivedAt, null);
        row.setStatus(WeatherWarningVersionEntity.STATUS_CANCELLED);
        return row;
    }

    @Test
    @DisplayName("Rewind: with v1 Yellow received before T and v2 Amber received after T, T sees Yellow")
    void rewindShowsEarlierVersion() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-12T00:00:00Z");
        var v2 = version("W", 2, "Amber", "2031-03-10T09:00:00Z", "2031-03-12T00:00:00Z");

        List<ActiveWarning> between = at("2031-03-10T07:30:00Z").active(List.of(v1, v2), false);
        List<ActiveWarning> after = at("2031-03-10T09:00:00Z").active(List.of(v1, v2), false);

        assertThat(between).hasSize(1);
        assertThat(between.get(0).version().getWarningLevel()).isEqualTo("Yellow");
        assertThat(between.get(0).upgradedFrom()).isNull();
        assertThat(after.get(0).version().getWarningLevel()).isEqualTo("Amber");
    }

    @Test
    @DisplayName("nothing is active before the first version was received")
    void nothingBeforeReceipt() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-12T00:00:00Z");

        assertThat(at("2031-03-10T05:59:59Z").active(List.of(v1), false)).isEmpty();
        assertThat(at("2031-03-10T06:00:00Z").active(List.of(v1), false)).hasSize(1);
    }

    @Test
    @DisplayName("a warning stops being active at its valid_to, exactly")
    void validToBoundary() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-10T18:00:00Z");

        assertThat(at("2031-03-10T17:59:59Z").active(List.of(v1), false)).hasSize(1);
        assertThat(at("2031-03-10T18:00:00Z").active(List.of(v1), false)).isEmpty();
    }

    @Test
    @DisplayName("a live version with no valid_to is never active")
    void noValidTo() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", null);

        assertThat(at("2031-03-10T07:00:00Z").active(List.of(v1), false)).isEmpty();
    }

    @Test
    @DisplayName("a cancellation ends the warning from when it was received, not before")
    void cancellation() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-12T00:00:00Z");
        var v2 = cancelled("W", 2, "2031-03-10T08:00:00Z");

        assertThat(at("2031-03-10T07:00:00Z").active(List.of(v1, v2), false)).hasSize(1);
        assertThat(at("2031-03-10T08:00:00Z").active(List.of(v1, v2), false)).isEmpty();
    }

    @Test
    @DisplayName("a cancellation with no earlier version stored shows nothing")
    void lonelyCancellation() {
        assertThat(at("2031-03-10T09:00:00Z").active(List.of(cancelled("W", 2, "2031-03-10T08:00:00Z")), false))
                .isEmpty();
    }

    @Test
    @DisplayName("an upgrade is flagged with the previous level and receipt time for 24 hours, then no longer")
    void upgradeFlagWindow() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var v2 = version("W", 2, "Amber", "2031-03-10T08:00:00Z", "2031-03-14T00:00:00Z");

        ActiveWarning fresh = at("2031-03-11T07:59:59Z").active(List.of(v1, v2), false).get(0);
        ActiveWarning stale = at("2031-03-11T08:00:00Z").active(List.of(v1, v2), false).get(0);

        assertThat(fresh.upgradedFrom()).isEqualTo("Yellow");
        assertThat(fresh.upgradedAt()).isEqualTo(Instant.parse("2031-03-10T08:00:00Z"));
        assertThat(stale.upgradedFrom()).isNull();
        assertThat(stale.upgradedAt()).isNull();
    }

    @Test
    @DisplayName("a downgrade and an area-only edit carry no upgrade flag")
    void downgradeAndEditUnflagged() {
        var amber = version("D", 1, "Amber", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var yellow = version("D", 2, "Yellow", "2031-03-10T08:00:00Z", "2031-03-14T00:00:00Z");
        var same1 = version("E", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var same2 = version("E", 2, "Yellow", "2031-03-10T08:00:00Z", "2031-03-15T00:00:00Z");

        List<ActiveWarning> active = at("2031-03-10T09:00:00Z").active(List.of(amber, yellow, same1, same2), false);

        assertThat(active).hasSize(2);
        assertThat(active).allSatisfy(a -> assertThat(a.upgradedFrom()).isNull());
    }

    @Test
    @DisplayName("versions are ordered by version number, whatever order they are passed in")
    void versionOrderIndependent() {
        var v1 = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var v2 = version("W", 2, "Red", "2031-03-10T08:00:00Z", "2031-03-14T00:00:00Z");

        ActiveWarning a = at("2031-03-10T09:00:00Z").active(List.of(v2, v1), false).get(0);

        assertThat(a.version().getWarningVersion()).isEqualTo(2);
        assertThat(a.upgradedFrom()).isEqualTo("Yellow");
    }

    @Test
    @DisplayName("simulated rows are ignored unless asked for, and an ignored simulated v2 leaves the real v1")
    void simulatedExcluded() {
        var real = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var sim = version("S", 1, "Red", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        sim.setSimulated(true);
        var simOverReal = version("W", 2, "Red", "2031-03-10T07:00:00Z", "2031-03-14T00:00:00Z");
        simOverReal.setSimulated(true);
        WarningTimeline timeline = at("2031-03-10T08:00:00Z");

        List<ActiveWarning> shared = timeline.active(List.of(real, sim, simOverReal), false);
        List<ActiveWarning> admin = timeline.active(List.of(real, sim, simOverReal), true);

        assertThat(shared).extracting(a -> a.version().getWarningId()).containsExactly("W");
        assertThat(shared.get(0).version().getWarningLevel()).isEqualTo("Yellow");
        assertThat(admin).hasSize(2);
    }

    @Test
    @DisplayName("the most severe warning comes first, ties by warning id")
    void orderedBySeverity() {
        var yellowB = version("B", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var red = version("Z", 1, "Red", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var yellowA = version("A", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var amber = version("M", 1, "amber", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");

        List<ActiveWarning> active = at("2031-03-10T07:00:00Z").active(List.of(yellowB, red, yellowA, amber), false);

        assertThat(active).extracting(a -> a.version().getWarningId()).containsExactly("Z", "M", "A", "B");
    }

    @Test
    @DisplayName("levelRank orders Yellow, Amber, Red, ignoring case and space; anything else is 0")
    void levelRank() {
        assertThat(WarningTimeline.levelRank("Yellow")).isEqualTo(1);
        assertThat(WarningTimeline.levelRank(" AMBER ")).isEqualTo(2);
        assertThat(WarningTimeline.levelRank("red")).isEqualTo(3);
        assertThat(WarningTimeline.levelRank("Purple")).isZero();
        assertThat(WarningTimeline.levelRank(null)).isZero();
    }

    @Test
    @DisplayName("an unknown level is not an upgrade over Yellow, and Yellow is an upgrade over an unknown one")
    void unknownLevelRanks() {
        var yellow = version("W", 1, "Yellow", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var purple = version("W", 2, "Purple", "2031-03-10T08:00:00Z", "2031-03-14T00:00:00Z");
        var other = version("X", 1, "Purple", "2031-03-10T06:00:00Z", "2031-03-14T00:00:00Z");
        var yellowAfter = version("X", 2, "Yellow", "2031-03-10T08:00:00Z", "2031-03-14T00:00:00Z");

        List<ActiveWarning> active = at("2031-03-10T09:00:00Z").active(List.of(yellow, purple, other, yellowAfter),
                false);

        assertThat(active).hasSize(2);
        ActiveWarning x = active.stream().filter(a -> a.version().getWarningId().equals("X")).findFirst()
                .orElseThrow();
        ActiveWarning w = active.stream().filter(a -> a.version().getWarningId().equals("W")).findFirst()
                .orElseThrow();
        assertThat(x.upgradedFrom()).isEqualTo("Purple");
        assertThat(w.upgradedFrom()).isNull();
    }
}
