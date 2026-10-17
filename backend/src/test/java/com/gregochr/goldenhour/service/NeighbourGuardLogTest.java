package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link NeighbourGuardLog}: first-time lines, the bound, and the change-only summary. */
class NeighbourGuardLogTest {

    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 18, 6, 0);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 18);

    private List<String> lines;
    private NeighbourGuardLog log;

    @BeforeEach
    void setUp() {
        lines = new ArrayList<>();
        log = new NeighbourGuardLog(lines::add);
    }

    private static NeighbourOutlierGuard.Key key(long id) {
        return new NeighbourOutlierGuard.Key(id, DATE, TargetType.SUNSET);
    }

    private static NeighbourOutlierGuard.Result result(Map<NeighbourOutlierGuard.Key, Integer> flagged) {
        return new NeighbourOutlierGuard.Result(Set.copyOf(flagged.keySet()), 40, 38, 12, flagged);
    }

    private List<String> slotLines() {
        return lines.stream().filter(l -> l.contains("flagged as a neighbour outlier")).toList();
    }

    private List<String> summaryLines() {
        return lines.stream().filter(l -> l.contains(" of ")).toList();
    }

    @Test
    @DisplayName("a flagged slot logs once with ids and ints only, and not again on the next serve")
    void firstTimeOnly() {
        NeighbourOutlierGuard.Result r = result(Map.of(key(7), 4));

        log.record(BUILT, r);
        log.record(BUILT, r);

        assertThat(slotLines()).containsExactly(
                "[NEIGHBOUR GUARD] Location 7 flagged as a neighbour outlier for 2026-10-18 SUNSET"
                        + " (sky rating 4)");
    }

    @Test
    @DisplayName("the same place at a different sky rating, date or type is a new line")
    void keyIncludesRatingDateAndType() {
        log.record(BUILT, result(Map.of(key(7), 4)));
        log.record(BUILT, result(Map.of(key(7), 5)));
        log.record(BUILT, result(Map.of(new NeighbourOutlierGuard.Key(7, DATE.plusDays(1), TargetType.SUNSET),
                4)));
        log.record(BUILT, result(Map.of(new NeighbourOutlierGuard.Key(7, DATE, TargetType.SUNRISE), 4)));

        assertThat(slotLines()).hasSize(4);
    }

    @Test
    @DisplayName("at most 1,024 keys are remembered, and the oldest is evicted so it logs again")
    void theBoundEvicts() {
        Map<NeighbourOutlierGuard.Key, Integer> first = Map.of(key(0), 4);
        log.record(BUILT, result(first));
        for (long id = 1; id <= NeighbourGuardLog.MAX_KEYS; id++) {
            log.record(BUILT, result(Map.of(key(id), 4)));
        }
        lines.clear();

        assertThat(log.rememberedKeys()).isEqualTo(NeighbourGuardLog.MAX_KEYS);
        log.record(BUILT, result(first));
        assertThat(slotLines()).as("key 0 was evicted, so it is new again").hasSize(1);
        lines.clear();
        log.record(BUILT, result(Map.of(key(NeighbourGuardLog.MAX_KEYS), 4)));
        assertThat(slotLines()).as("a recent key is still remembered").isEmpty();
    }

    @Test
    @DisplayName("the summary reads flagged N of M rated sky slots over W windows with the coverage")
    void summaryText() {
        log.record(BUILT, result(Map.of(key(1), 4, key(2), 3)));

        assertThat(summaryLines()).containsExactly(
                "[NEIGHBOUR GUARD] Flagged 2 of 40 rated sky slots over 12 windows, sky-rating coverage 95%");
    }

    @Test
    @DisplayName("the summary is written once per briefing and flagged count, not on every serve")
    void summaryOnChangeOnly() {
        NeighbourOutlierGuard.Result one = result(Map.of(key(1), 4));
        log.record(BUILT, one);
        log.record(BUILT, one);
        assertThat(summaryLines()).hasSize(1);

        log.record(BUILT.plusHours(8), one);
        assertThat(summaryLines()).as("a new generatedAt").hasSize(2);

        log.record(BUILT.plusHours(8), result(Map.of(key(1), 4, key(2), 4)));
        assertThat(summaryLines()).as("a new flagged count").hasSize(3);

        log.record(BUILT.plusHours(8), result(Map.of(key(1), 4, key(2), 4)));
        assertThat(summaryLines()).as("a repeat serve").hasSize(3);
    }

    @Test
    @DisplayName("the summary is written again when only the sky-rating coverage changes")
    void summaryOnCoverageChange() {
        Map<NeighbourOutlierGuard.Key, Integer> flagged = Map.of(key(1), 4);
        log.record(BUILT, new NeighbourOutlierGuard.Result(Set.copyOf(flagged.keySet()), 40, 38, 12, flagged));
        log.record(BUILT, new NeighbourOutlierGuard.Result(Set.copyOf(flagged.keySet()), 40, 38, 12, flagged));
        assertThat(summaryLines()).hasSize(1);

        log.record(BUILT, new NeighbourOutlierGuard.Result(Set.copyOf(flagged.keySet()), 40, 30, 12, flagged));

        assertThat(summaryLines()).hasSize(2);
        assertThat(summaryLines().get(1)).endsWith("sky-rating coverage 75%");
    }

    @Test
    @DisplayName("a pass that flags nothing still writes its summary once, with a zero count")
    void zeroFlaggedStillSummarised() {
        NeighbourOutlierGuard.Result none = new NeighbourOutlierGuard.Result(Set.of(), 40, 40, 12, Map.of());

        log.record(BUILT, none);
        log.record(BUILT, none);

        assertThat(slotLines()).isEmpty();
        assertThat(summaryLines()).containsExactly(
                "[NEIGHBOUR GUARD] Flagged 0 of 40 rated sky slots over 12 windows, sky-rating coverage 100%");
    }

    @Test
    @DisplayName("the default constructor writes through the INFO logger without failing")
    void defaultSinkDoesNotFail() {
        NeighbourGuardLog logging = new NeighbourGuardLog();

        logging.record(BUILT, result(Map.of(key(1), 4)));

        assertThat(logging.rememberedKeys()).isEqualTo(1);
    }
}
