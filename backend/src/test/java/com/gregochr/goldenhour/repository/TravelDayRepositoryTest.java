package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TravelDayRepository}'s derived queries against H2 — the containment check used by
 * the batch gate, and the future-only descending list used by the admin panel + overlay.
 */
@DataJpaTest
class TravelDayRepositoryTest {

    @Autowired
    private TravelDayRepository repository;

    private TravelDayEntity range(LocalDate start, LocalDate end, String note) {
        return repository.save(TravelDayEntity.builder()
                .startDate(start).endDate(end).note(note).build());
    }

    @Test
    @DisplayName("existsForDate is inclusive of both range bounds")
    void existsForDateInclusive() {
        range(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "London");

        assertThat(repository.existsForDate(LocalDate.of(2026, 6, 30))).isFalse();
        assertThat(repository.existsForDate(LocalDate.of(2026, 7, 1))).isTrue();  // start bound
        assertThat(repository.existsForDate(LocalDate.of(2026, 7, 2))).isTrue();
        assertThat(repository.existsForDate(LocalDate.of(2026, 7, 3))).isTrue();  // end bound
        assertThat(repository.existsForDate(LocalDate.of(2026, 7, 4))).isFalse();
    }

    @Test
    @DisplayName("future-only list excludes already-ended ranges and sorts furthest-future first")
    void futureOnlyDescending() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        range(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), "past");          // ends before today
        TravelDayEntity ending = range(LocalDate.of(2026, 7, 8), today, "ends today"); // end == today: kept
        TravelDayEntity soon = range(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16), "soon");
        TravelDayEntity far = range(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 22), "far");

        List<TravelDayEntity> result =
                repository.findByEndDateGreaterThanEqualOrderByStartDateDesc(today);

        assertThat(result).extracting(TravelDayEntity::getId)
                .containsExactly(far.getId(), soon.getId(), ending.getId());  // desc by start, past dropped
    }
}
