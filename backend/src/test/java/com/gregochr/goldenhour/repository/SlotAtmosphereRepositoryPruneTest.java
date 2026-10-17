package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test (H2, schema from the entity annotations) for
 * {@link SlotAtmosphereRepository#deleteByEvaluationDateBefore} — the statement the nightly
 * {@code slot_atmosphere_cleanup} job runs. Only a real query can show the boundary: a mocked
 * repository can say which cutoff was passed, not what that cutoff deletes.
 */
@DataJpaTest
class SlotAtmosphereRepositoryPruneTest {

    private static final LocalDate CUTOFF = LocalDate.of(2026, 6, 18);

    @Autowired
    private SlotAtmosphereRepository repository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private EntityManager entityManager;

    private LocationEntity first;
    private LocationEntity second;

    @BeforeEach
    void persistLocations() {
        first = location("Roseberry Topping");
        second = location("Cat Bells");
    }

    private LocationEntity location(String name) {
        return locationRepository.save(LocationEntity.builder()
                .name(name).lat(54.5).lon(-1.1)
                .createdAt(LocalDateTime.of(2026, 6, 1, 12, 0)).build());
    }

    private SlotAtmosphereEntity row(LocationEntity location, LocalDate date, TargetType event) {
        SlotAtmosphereEntity entity = new SlotAtmosphereEntity();
        entity.setLocation(location);
        entity.setEvaluationDate(date);
        entity.setEventType(event);
        // Written yesterday whatever the slot date: the rule keys on the slot's own date.
        entity.setEvaluatedAt(Instant.parse("2026-12-14T03:00:00Z"));
        return repository.save(entity);
    }

    private List<LocalDate> survivingDates() {
        entityManager.flush();
        entityManager.clear();
        return repository.findAll().stream().map(SlotAtmosphereEntity::getEvaluationDate).sorted().toList();
    }

    @Test
    @DisplayName("rows dated before the cutoff are deleted, a row exactly at the cutoff and later "
            + "rows are kept, and the delete returns the number removed")
    void deleteBefore_deletesStrictlyOlderAndKeepsTheCutoffDay() {
        row(first, CUTOFF.minusDays(100), TargetType.SUNRISE);
        row(first, CUTOFF.minusDays(1), TargetType.SUNSET);
        row(first, CUTOFF, TargetType.SUNRISE);
        row(first, CUTOFF.plusDays(1), TargetType.SUNSET);
        row(first, CUTOFF.plusDays(40), TargetType.SUNRISE);

        int deleted = repository.deleteByEvaluationDateBefore(CUTOFF);

        assertThat(deleted).isEqualTo(2);
        assertThat(survivingDates()).containsExactly(
                CUTOFF, CUTOFF.plusDays(1), CUTOFF.plusDays(40));
    }

    @Test
    @DisplayName("another location's rows on the same dates follow the same rule, and both events "
            + "of a date go together")
    void deleteBefore_appliesTheSameRuleToEveryLocationAndEvent() {
        for (LocationEntity location : List.of(first, second)) {
            row(location, CUTOFF.minusDays(1), TargetType.SUNRISE);
            row(location, CUTOFF.minusDays(1), TargetType.SUNSET);
            row(location, CUTOFF, TargetType.SUNRISE);
            row(location, CUTOFF, TargetType.SUNSET);
        }

        int deleted = repository.deleteByEvaluationDateBefore(CUTOFF);

        assertThat(deleted).isEqualTo(4);
        assertThat(survivingDates()).containsExactly(CUTOFF, CUTOFF, CUTOFF, CUTOFF);
        assertThat(repository.findAll()).extracting(r -> r.getLocation().getName())
                .containsExactlyInAnyOrder("Roseberry Topping", "Roseberry Topping",
                        "Cat Bells", "Cat Bells");
    }

    @Test
    @DisplayName("an empty table, or nothing older than the cutoff, deletes zero rows")
    void deleteBefore_nothingToDelete_returnsZero() {
        assertThat(repository.deleteByEvaluationDateBefore(CUTOFF)).isZero();

        row(first, CUTOFF, TargetType.SUNRISE);

        assertThat(repository.deleteByEvaluationDateBefore(CUTOFF)).isZero();
        assertThat(survivingDates()).containsExactly(CUTOFF);
    }
}
