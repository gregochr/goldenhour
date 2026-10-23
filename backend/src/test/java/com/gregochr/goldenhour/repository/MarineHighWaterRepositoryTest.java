package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.service.MarineHighWaterWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database test (H2, schema from the entities) of {@link MarineHighWaterWriter}: replacing a
 * location's rows with an identical {@code hw_time} must not trip the UNIQUE key. That holds only
 * because the delete is a bulk query that reaches the database at once; a derived {@code deleteBy...}
 * would leave the old row for the insert to collide with.
 */
@DataJpaTest
@Import(MarineHighWaterWriter.class)
class MarineHighWaterRepositoryTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 12, 10, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 11, 0, 0);
    private static final LocalDateTime HW = LocalDateTime.of(2026, 12, 10, 12, 0);

    @Autowired
    private MarineHighWaterWriter writer;

    @Autowired
    private MarineHighWaterRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private MarineHighWaterEntity row(LocationEntity loc, double hs) {
        return MarineHighWaterEntity.builder().location(loc).hwTime(HW).hwHeightMetres(new BigDecimal("5.100"))
                .windowStart(HW.minusMinutes(90)).windowEnd(HW.plusMinutes(90)).peakTime(HW)
                .waveHeightMetres(hs).evaluatedAt(Instant.parse("2026-12-09T20:00:00Z")).build();
    }

    @Test
    @DisplayName("replacing a location's rows twice with the same hw_time leaves one row with the second values")
    void replace_sameHwTimeTwice_keepsSecond() {
        LocationEntity loc = entityManager.persist(LocationEntity.builder().name("Roker").lat(54.9).lon(-1.3)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());

        writer.replace(loc.getId(), FROM, TO, List.of(row(loc, 2.0)));
        writer.replace(loc.getId(), FROM, TO, List.of(row(loc, 4.5)));
        entityManager.flush();
        entityManager.clear();

        List<MarineHighWaterEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getWaveHeightMetres()).isEqualTo(4.5);
    }
}
