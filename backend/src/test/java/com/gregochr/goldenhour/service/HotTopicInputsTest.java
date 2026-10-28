package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link HotTopicInputs}: each accessor runs its source once, hands back an
 * unmodifiable list, keeps the source's order, and an unconfigured source fails loudly.
 */
class HotTopicInputsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    private static LocationEntity location(String name) {
        LocationEntity l = new LocationEntity();
        l.setName(name);
        return l;
    }

    @Test
    @DisplayName("slotSignals runs the source once however often it is asked")
    void slotSignals_memoised() {
        AtomicInteger calls = new AtomicInteger();
        HotTopicInputs inputs = HotTopicInputs.ofSlotSignals(() -> {
            calls.incrementAndGet();
            return List.of();
        });

        inputs.slotSignals();
        inputs.slotSignals();
        inputs.slotSignals();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("each roster accessor runs its own source exactly once and none runs unasked")
    void rosters_memoisedIndependently() {
        AtomicInteger coastal = new AtomicInteger();
        AtomicInteger enabled = new AtomicInteger();
        AtomicInteger darkSky = new AtomicInteger();
        AtomicInteger bluebell = new AtomicInteger();
        HotTopicInputs inputs = HotTopicInputs.ofRosters(
                () -> {
                    coastal.incrementAndGet();
                    return List.of(location("c"));
                },
                () -> {
                    enabled.incrementAndGet();
                    return List.of(location("e"));
                },
                () -> {
                    darkSky.incrementAndGet();
                    return List.of(location("d"));
                },
                () -> {
                    bluebell.incrementAndGet();
                    return List.of(location("b"));
                });

        assertThat(coastal).hasValue(0);
        for (int i = 0; i < 3; i++) {
            inputs.coastalLocations();
            inputs.enabledLocations();
            inputs.darkSkyLocations();
            inputs.bluebellLocations();
        }

        assertThat(coastal).hasValue(1);
        assertThat(enabled).hasValue(1);
        assertThat(darkSky).hasValue(1);
        assertThat(bluebell).hasValue(1);
        assertThat(inputs.coastalLocations()).extracting(LocationEntity::getName).containsExactly("c");
        assertThat(inputs.bluebellLocations()).extracting(LocationEntity::getName).containsExactly("b");
    }

    @Test
    @DisplayName("an empty roster is memoised too — it is not re-queried because it is empty")
    void emptyRoster_memoised() {
        AtomicInteger calls = new AtomicInteger();
        HotTopicInputs inputs = HotTopicInputs.ofCoastal(() -> {
            calls.incrementAndGet();
            return List.of();
        });

        inputs.coastalLocations();
        inputs.coastalLocations();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("the enabled roster keeps the source's order")
    void enabledLocations_keepsOrder() {
        HotTopicInputs inputs = HotTopicInputs.ofEnabled(
                () -> List.of(location("Alnwick"), location("Bamburgh"), location("Cullercoats")));

        assertThat(inputs.enabledLocations()).extracting(LocationEntity::getName)
                .containsExactly("Alnwick", "Bamburgh", "Cullercoats");
    }

    @Test
    @DisplayName("lists are unmodifiable, and a later change to the source list is not seen")
    void lists_areUnmodifiableSnapshots() {
        List<LocationEntity> backing = new ArrayList<>(List.of(location("a")));
        HotTopicInputs inputs = HotTopicInputs.ofDarkSky(() -> backing);

        List<LocationEntity> read = inputs.darkSkyLocations();
        backing.add(location("b"));

        assertThatThrownBy(() -> read.add(location("x"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(inputs.darkSkyLocations()).hasSize(1);
        assertThat(HotTopicInputs.ofSlotSignals(List::of).slotSignals())
                .isUnmodifiable();
    }

    @Test
    @DisplayName("isTravelDay asks the source once per date and remembers both answers")
    void isTravelDay_memoisedPerDate() {
        AtomicInteger calls = new AtomicInteger();
        HotTopicInputs inputs = new HotTopicInputs(null, null, null, null, null, date -> {
            calls.incrementAndGet();
            return date.equals(DAY);
        });

        assertThat(inputs.isTravelDay(DAY)).isTrue();
        assertThat(inputs.isTravelDay(DAY)).isTrue();
        assertThat(inputs.isTravelDay(DAY.plusDays(1))).isFalse();
        assertThat(inputs.isTravelDay(DAY.plusDays(1))).isFalse();

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a source that was not provided fails loudly instead of returning an empty list")
    void missingSource_failsLoudly() {
        HotTopicInputs inputs = HotTopicInputs.ofSlotSignals(List::of);

        assertThatThrownBy(inputs::coastalLocations).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coastal locations");
        assertThatThrownBy(inputs::enabledLocations).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(inputs::darkSkyLocations).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(inputs::bluebellLocations).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inputs.isTravelDay(DAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HotTopicInputs.ofCoastal(List::of).slotSignals())
                .isInstanceOf(IllegalStateException.class);
    }
}
