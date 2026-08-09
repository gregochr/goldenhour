package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AlmanacEvent}. */
class AlmanacEventTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 16);

    private static AlmanacEvent event(LocalDate start, LocalDate end, Map<String, String> meta) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, "spring-tide", "Spring tide run",
                "detail", meta, List.of("Northumberland"));
    }

    @Test
    @DisplayName("null collections normalise to empty so no consumer has to null-check them")
    void nullCollectionsBecomeEmpty() {
        AlmanacEvent event = new AlmanacEvent(DAY, DAY, AlmanacKind.ALMANAC, "t", "T", "d",
                null, null);

        assertThat(event.meta()).isEmpty();
        assertThat(event.regions()).isEmpty();
        assertThat(event.isDatesOnly()).isTrue();
    }

    @Test
    @DisplayName("collections are defensively copied, so a caller's later mutation cannot reach "
                 + "a cached feed")
    void collectionsAreCopied() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("range", "4.6 m");

        AlmanacEvent event = event(DAY, DAY, meta);
        meta.put("range", "tampered");

        assertThat(event.meta()).containsEntry("range", "4.6 m");
    }

    @Test
    @DisplayName("a span running backwards is rejected rather than silently rendered as nothing")
    void backwardsSpanIsRejected() {
        assertThatThrownBy(() -> event(DAY.plusDays(1), DAY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before startDate");
    }

    @Test
    @DisplayName("null dates are rejected")
    void nullDatesAreRejected() {
        assertThatThrownBy(() -> event(null, DAY, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(DAY, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("dayCount counts both ends, so a single day is 1 and not 0")
    void dayCountIsInclusive() {
        assertThat(event(DAY, DAY, Map.of()).dayCount()).isEqualTo(1);
        assertThat(event(DAY, DAY.plusDays(3), Map.of()).dayCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("onDay builds a single-day span")
    void onDayIsASingleDay() {
        AlmanacEvent event = AlmanacEvent.onDay(DAY, AlmanacKind.ALMANAC, "meteor", "Perseids",
                "detail", Map.of("zhr", "100"), List.of());

        assertThat(event.startDate()).isEqualTo(DAY);
        assertThat(event.endDate()).isEqualTo(DAY);
        assertThat(event.dayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("metaOf drops null and blank values, which is the degrade rule made mechanical")
    void metaOfDropsAbsentFacts() {
        Map<String, String> meta = AlmanacEvent.metaOf(
                "range", "4.6 m",
                "verdict", null,
                "highWater", "",
                "location", "   ",
                "peakDate", "2026-08-16");

        assertThat(meta).containsOnlyKeys("range", "peakDate");
    }

    @Test
    @DisplayName("metaOf preserves insertion order so the rendered facts do not shuffle per request")
    void metaOfKeepsOrder() {
        Map<String, String> meta = AlmanacEvent.metaOf("a", "1", "b", "2", "c", "3");

        assertThat(meta.keySet()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("metaOf rejects an odd argument count rather than dropping the last key")
    void metaOfRejectsOddArguments() {
        assertThatThrownBy(() -> AlmanacEvent.metaOf("a", "1", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternating");
    }

    @Test
    @DisplayName("isDatesOnly distinguishes a degraded entry from an enriched one")
    void isDatesOnlyReflectsMeta() {
        assertThat(event(DAY, DAY, Map.of()).isDatesOnly()).isTrue();
        assertThat(event(DAY, DAY, Map.of("range", "4.6 m")).isDatesOnly()).isFalse();
    }

    @Test
    @DisplayName("ordering is by start, then span length, then type — total, so sorting is stable")
    void orderingIsTotal() {
        AlmanacEvent earlyShort = event(DAY, DAY, Map.of());
        AlmanacEvent earlyLong = event(DAY, DAY.plusDays(4), Map.of());
        AlmanacEvent late = event(DAY.plusDays(1), DAY.plusDays(1), Map.of());

        assertThat(earlyShort).isLessThan(earlyLong);
        assertThat(earlyLong).isLessThan(late);

        AlmanacEvent sameSpanOtherType = new AlmanacEvent(DAY, DAY, AlmanacKind.ALMANAC,
                "aurora", "A", "d", Map.of(), List.of());
        assertThat(sameSpanOtherType).isLessThan(earlyShort);
        assertThat(earlyShort.compareTo(earlyShort)).isZero();
    }

    @Test
    @DisplayName("a null type does not break ordering")
    void nullTypeSortsWithoutThrowing() {
        AlmanacEvent nullType = new AlmanacEvent(DAY, DAY, AlmanacKind.ALMANAC, null, "T", "d",
                Map.of(), List.of());
        AlmanacEvent named = event(DAY, DAY, Map.of());

        assertThat(nullType.compareTo(named)).isNotZero();
    }
}
