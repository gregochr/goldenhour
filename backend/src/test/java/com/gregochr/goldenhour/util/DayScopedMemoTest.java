package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link DayScopedMemo}. */
class DayScopedMemoTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    private final DayScopedMemo<String, String> memo = new DayScopedMemo<>();

    @Test
    @DisplayName("a hit does not call the loader again")
    void aHitSkipsTheLoader() {
        AtomicInteger loads = new AtomicInteger();

        String first = memo.get(DAY, "k", () -> "v" + loads.incrementAndGet());
        String second = memo.get(DAY, "k", () -> "v" + loads.incrementAndGet());

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads).hasValue(1);
        assertThat(memo.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the next day drops every entry — yesterday's value is never served today")
    void theDayRollDropsEntries() {
        memo.get(DAY, "k", () -> "yesterday");

        assertThat(memo.get(DAY.plusDays(1), "k", () -> "today")).isEqualTo("today");
        assertThat(memo.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("evict(key) drops only that key")
    void evictDropsOneKey() {
        memo.get(DAY, "a", () -> "a1");
        memo.get(DAY, "b", () -> "b1");

        memo.evict("a");

        assertThat(memo.get(DAY, "a", () -> "a2")).isEqualTo("a2");
        assertThat(memo.get(DAY, "b", () -> "b2")).isEqualTo("b1");
    }

    @Test
    @DisplayName("evictAll() drops everything")
    void evictAllDropsEverything() {
        memo.get(DAY, "a", () -> "a1");
        memo.get(DAY, "b", () -> "b1");

        memo.evictAll();

        assertThat(memo.size()).isZero();
        assertThat(memo.get(DAY, "b", () -> "b2")).isEqualTo("b2");
    }

    @Test
    @DisplayName("a value loaded while an eviction lands is returned to its caller but never stored, "
            + "so a write that races a read cannot leave the old value cached")
    void aLoadOverlappingAnEvictionIsNotStored() {
        // The loader stands in for a slow read of rows that a writer then changes and commits: the
        // writer's eviction runs before the read returns its (now stale) answer.
        String stale = memo.get(DAY, "k", () -> {
            memo.evict("k");
            return "stale";
        });

        assertThat(stale).isEqualTo("stale");
        assertThat(memo.size()).isZero();
        assertThat(memo.get(DAY, "k", () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    @DisplayName("an eviction of another key also stops an in-flight store: the generation is "
            + "shared, which errs towards recomputing")
    void anyEvictionInvalidatesAnInFlightLoad() {
        memo.get(DAY, "k", () -> {
            memo.evict("other");
            return "v";
        });

        assertThat(memo.size()).isZero();
    }

    @Test
    @DisplayName("a load that straddles the day roll is not stored under the new day")
    void aLoadStraddlingTheDayRollIsNotStored() {
        memo.get(DAY, "k", () -> {
            memo.get(DAY.plusDays(1), "other", () -> "tomorrow");
            return "today";
        });

        assertThat(memo.size()).isEqualTo(1);
        assertThat(memo.get(DAY.plusDays(1), "other", () -> "reloaded")).isEqualTo("tomorrow");
    }
}
