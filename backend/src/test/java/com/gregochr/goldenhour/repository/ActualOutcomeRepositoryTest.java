package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link ActualOutcomeRepository}.
 * Uses an H2 in-memory database with schema generated from JPA entities.
 */
@DataJpaTest
class ActualOutcomeRepositoryTest {

    private static final BigDecimal DURHAM_LAT = new BigDecimal("54.775300");
    private static final BigDecimal DURHAM_LON = new BigDecimal("-1.584900");

    @Autowired
    private ActualOutcomeRepository repository;

    @Test
    @DisplayName("Saved outcome can be retrieved by its generated ID")
    void save_andFindById_returnsOutcome() {
        ActualOutcomeEntity entity = buildOutcome(LocalDate.of(2026, 2, 20), TargetType.SUNSET);

        ActualOutcomeEntity saved = repository.save(entity);

        Optional<ActualOutcomeEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getFierySkyActual()).isEqualTo(68);
        assertThat(found.get().getGoldenHourActual()).isEqualTo(75);
        assertThat(found.get().getWentOut()).isTrue();
    }

    @Test
    @DisplayName("findByLocationLatAndLocationLonAndOutcomeDateBetween returns outcomes in range")
    void findByLocationAndDateRange_returnsOutcomesInRange() {
        LocalDate from = LocalDate.of(2026, 2, 20);
        repository.save(buildOutcome(from, TargetType.SUNSET));
        repository.save(buildOutcome(from.plusDays(1), TargetType.SUNRISE));
        repository.save(buildOutcome(from.plusDays(8), TargetType.SUNSET));

        List<ActualOutcomeEntity> results =
                repository.findByLocationLatAndLocationLonAndOutcomeDateBetweenOrderByOutcomeDateAsc(
                        DURHAM_LAT, DURHAM_LON, from, from.plusDays(7));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getOutcomeDate()).isEqualTo(from);
        assertThat(results.get(1).getOutcomeDate()).isEqualTo(from.plusDays(1));
    }

    @Test
    @DisplayName("findByLocationAndDateRange excludes outcomes for a different location")
    void findByLocationAndDateRange_excludesDifferentLocation() {
        LocalDate from = LocalDate.of(2026, 2, 20);
        ActualOutcomeEntity elsewhere = buildOutcome(from, TargetType.SUNSET);
        elsewhere.setLocationLat(new BigDecimal("51.507400")); // London
        elsewhere.setLocationLon(new BigDecimal("-0.127800"));
        repository.save(elsewhere);

        List<ActualOutcomeEntity> results =
                repository.findByLocationLatAndLocationLonAndOutcomeDateBetweenOrderByOutcomeDateAsc(
                        DURHAM_LAT, DURHAM_LON, from, from.plusDays(7));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("findByLocationAndDateRange returns results ordered by outcome date ascending")
    void findByLocationAndDateRange_orderedByOutcomeDateAsc() {
        LocalDate base = LocalDate.of(2026, 2, 20);
        repository.save(buildOutcome(base.plusDays(3), TargetType.SUNSET));
        repository.save(buildOutcome(base, TargetType.SUNSET));
        repository.save(buildOutcome(base.plusDays(1), TargetType.SUNRISE));

        List<ActualOutcomeEntity> results =
                repository.findByLocationLatAndLocationLonAndOutcomeDateBetweenOrderByOutcomeDateAsc(
                        DURHAM_LAT, DURHAM_LON, base, base.plusDays(7));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getOutcomeDate()).isEqualTo(base);
        assertThat(results.get(1).getOutcomeDate()).isEqualTo(base.plusDays(1));
        assertThat(results.get(2).getOutcomeDate()).isEqualTo(base.plusDays(3));
    }

    private ActualOutcomeEntity buildOutcome(LocalDate outcomeDate, TargetType targetType) {
        return ActualOutcomeEntity.builder()
                .locationLat(DURHAM_LAT)
                .locationLon(DURHAM_LON)
                .locationName("Durham UK")
                .outcomeDate(outcomeDate)
                .targetType(targetType)
                .wentOut(true)
                .fierySkyActual(68)
                .goldenHourActual(75)
                .notes("Beautiful warm light — golden hour lived up to the forecast.")
                .recordedAt(LocalDateTime.now())
                .build();
    }
}
