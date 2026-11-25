package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.SnowStateRepository;
import com.gregochr.goldenhour.service.SnowStateWriter.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code snow_state} write and read path on H2 (schema from the entity, no Flyway, no Docker): upserts keep one
 * row per (point, date, event), a null never overwrites a stored value, a partial run keeps the rows it did not
 * touch, rows past seven days are pruned, and the reader ignores a row more than 36 hours old.
 */
@DataJpaTest
@Import({SnowStateWriter.class, SnowStateReader.class, SnowStateStoreJpaTest.FixedClock.class})
class SnowStateStoreJpaTest {

    private static final Instant NOW = Instant.parse("2024-01-15T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2024, 1, 15);
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2024, 1, 15, 12, 0);
    private static final String HELVELLYN = "summit:helvellyn";
    private static final String PLACE = "loc:7";

    /** The clock the reader's freshness rule measures with. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private SnowStateWriter writer;

    @Autowired
    private SnowStateReader reader;

    @Autowired
    private SnowStateRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private static Slot slot(String key, LocalDate date, TargetType event, Double water, Double fresh,
            Integer cloud) {
        return new Slot(key, date, event, water, fresh, cloud);
    }

    private SnowStateEntity row(String key, LocalDate date, TargetType event) {
        entityManager.flush();
        entityManager.clear();
        return repository.findByEvaluationDateBetween(date, date).stream()
                .filter(r -> r.getPointKey().equals(key) && r.getEventType() == event)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a first write inserts one row per slot with every value and the computed-at stamp")
    void insertsRows() {
        int written = writer.write(List.of(
                slot(HELVELLYN, TODAY, TargetType.SUNRISE, 35.5, 2.1, 20),
                slot(HELVELLYN, TODAY, TargetType.SUNSET, 30.0, 0.0, null)), TODAY, NOW_UTC);

        SnowStateEntity sunrise = row(HELVELLYN, TODAY, TargetType.SUNRISE);
        assertThat(written).isEqualTo(2);
        assertThat(sunrise.getSnowWaterMm()).isEqualTo(35.5);
        assertThat(sunrise.getSnowfall24hCm()).isEqualTo(2.1);
        assertThat(sunrise.getLowCloudPct()).isEqualTo(20);
        assertThat(sunrise.getComputedAt()).isEqualTo(NOW_UTC);
        assertThat(row(HELVELLYN, TODAY, TargetType.SUNSET).getLowCloudPct()).isNull();
    }

    @Test
    @DisplayName("a second write updates the same row rather than adding one, and restamps it")
    void upsertKeepsOneRow() {
        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, 1.0, 0.5, 10)), TODAY, NOW_UTC.minusHours(8));
        Long firstId = row(PLACE, TODAY, TargetType.SUNRISE).getId();

        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, 4.0, 1.5, 40)), TODAY, NOW_UTC);

        SnowStateEntity updated = row(PLACE, TODAY, TargetType.SUNRISE);
        assertThat(repository.findAll()).hasSize(1);
        assertThat(updated.getId()).isEqualTo(firstId);
        assertThat(updated.getSnowWaterMm()).isEqualTo(4.0);
        assertThat(updated.getLowCloudPct()).isEqualTo(40);
        assertThat(updated.getComputedAt()).isEqualTo(NOW_UTC);
    }

    @Test
    @DisplayName("a null never overwrites a stored value")
    void nullNeverOverwrites() {
        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, 5.0, 1.0, 30)), TODAY, NOW_UTC.minusHours(8));

        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, null, 2.0, null)), TODAY, NOW_UTC);

        SnowStateEntity kept = row(PLACE, TODAY, TargetType.SUNRISE);
        assertThat(kept.getSnowWaterMm()).isEqualTo(5.0);
        assertThat(kept.getSnowfall24hCm()).isEqualTo(2.0);
        assertThat(kept.getLowCloudPct()).isEqualTo(30);
    }

    @Test
    @DisplayName("a slot with every value null writes nothing and leaves the stored row and its stamp alone")
    void emptySlotIsIgnored() {
        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, 5.0, 1.0, 30)), TODAY, NOW_UTC.minusHours(8));

        int written = writer.write(List.of(
                slot(PLACE, TODAY, TargetType.SUNRISE, null, null, null),
                slot("loc:8", TODAY, TargetType.SUNRISE, null, null, null)), TODAY, NOW_UTC);

        assertThat(written).isZero();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(row(PLACE, TODAY, TargetType.SUNRISE).getComputedAt()).isEqualTo(NOW_UTC.minusHours(8));
    }

    @Test
    @DisplayName("a partial run keeps the rows of points it did not compute")
    void partialRunKeepsOtherPoints() {
        writer.write(List.of(
                slot(PLACE, TODAY, TargetType.SUNRISE, 5.0, 1.0, 30),
                slot(HELVELLYN, TODAY, TargetType.SUNRISE, 40.0, 3.0, 10)), TODAY, NOW_UTC.minusHours(8));

        writer.write(List.of(slot(HELVELLYN, TODAY, TargetType.SUNRISE, 41.0, 0.0, 12)), TODAY, NOW_UTC);

        assertThat(row(PLACE, TODAY, TargetType.SUNRISE).getSnowWaterMm()).isEqualTo(5.0);
        assertThat(row(PLACE, TODAY, TargetType.SUNRISE).getComputedAt()).isEqualTo(NOW_UTC.minusHours(8));
        assertThat(row(HELVELLYN, TODAY, TargetType.SUNRISE).getSnowWaterMm()).isEqualTo(41.0);
    }

    @Test
    @DisplayName("rows dated more than seven days back are pruned; the seventh day back is kept")
    void prunesPastSevenDays() {
        LocalDate keptDay = TODAY.minusDays(SnowStateWriter.RETAIN_DAYS);
        LocalDate prunedDay = keptDay.minusDays(1);
        writer.write(List.of(
                slot(PLACE, keptDay, TargetType.SUNRISE, 1.0, 0.0, 5),
                slot(PLACE, prunedDay, TargetType.SUNRISE, 1.0, 0.0, 5)), prunedDay, NOW_UTC);

        writer.write(List.of(slot(PLACE, TODAY, TargetType.SUNRISE, 2.0, 0.0, 5)), TODAY, NOW_UTC);

        entityManager.flush();
        entityManager.clear();
        List<LocalDate> remaining = repository.findAll().stream().map(SnowStateEntity::getEvaluationDate).toList();
        assertThat(remaining).containsExactlyInAnyOrder(keptDay, TODAY);
    }

    @Test
    @DisplayName("an empty run still prunes")
    void emptyRunStillPrunes() {
        LocalDate old = TODAY.minusDays(SnowStateWriter.RETAIN_DAYS + 1);
        writer.write(List.of(slot(PLACE, old, TargetType.SUNSET, 1.0, 0.0, 5)), old, NOW_UTC);

        int written = writer.write(List.of(), TODAY, NOW_UTC);

        assertThat(written).isZero();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a slot carrying only some values is written, and each missing value stays as stored")
    void partialSlotsAreWritten() {
        writer.write(List.of(
                slot("loc:1", TODAY, TargetType.SUNRISE, null, null, 55),
                slot("loc:2", TODAY, TargetType.SUNRISE, null, 1.5, null),
                slot("loc:3", TODAY, TargetType.SUNRISE, 6.0, null, null)), TODAY, NOW_UTC);

        SnowStateEntity cloudOnly = row("loc:1", TODAY, TargetType.SUNRISE);
        assertThat(cloudOnly.getLowCloudPct()).isEqualTo(55);
        assertThat(cloudOnly.getSnowWaterMm()).isNull();
        assertThat(row("loc:2", TODAY, TargetType.SUNRISE).getSnowfall24hCm()).isEqualTo(1.5);
        SnowStateEntity waterOnly = row("loc:3", TODAY, TargetType.SUNRISE);
        assertThat(waterOnly.getSnowWaterMm()).isEqualTo(6.0);
        assertThat(waterOnly.getSnowfall24hCm()).isNull();
    }

    // ── The reader ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the reader serves a row computed exactly 36 hours ago and ignores one a minute older")
    void freshnessEdge() {
        writer.write(List.of(slot("loc:1", TODAY, TargetType.SUNRISE, 5.0, 0.0, 5)), TODAY, NOW_UTC.minusHours(36));
        writer.write(List.of(slot("loc:2", TODAY, TargetType.SUNRISE, 5.0, 0.0, 5)), TODAY,
                NOW_UTC.minusHours(36).minusMinutes(1));
        writer.write(List.of(slot("loc:3", TODAY, TargetType.SUNRISE, 5.0, 0.0, 5)), TODAY, NOW_UTC);

        List<String> fresh = reader.freshRows(TODAY, TODAY).stream().map(SnowStateEntity::getPointKey).toList();

        assertThat(fresh).containsExactlyInAnyOrder("loc:1", "loc:3");
    }

    @Test
    @DisplayName("a stale or missing row is unknown (empty), never a zero")
    void staleAndMissingAreUnknown() {
        writer.write(List.of(slot("loc:1", TODAY, TargetType.SUNRISE, 0.0, 0.0, 5)), TODAY, NOW_UTC.minusHours(40));

        assertThat(reader.freshRow("loc:1", TODAY, TargetType.SUNRISE)).isEmpty();
        assertThat(reader.freshRow("loc:99", TODAY, TargetType.SUNRISE)).isEmpty();
    }

    @Test
    @DisplayName("freshRow finds nothing when only the point matches, or only the event matches")
    void freshRowNeedsPointAndEvent() {
        writer.write(List.of(slot("loc:1", TODAY, TargetType.SUNRISE, 6.0, 0.0, 5)), TODAY, NOW_UTC);

        assertThat(reader.freshRow("loc:1", TODAY, TargetType.SUNSET)).isEmpty();
        assertThat(reader.freshRow("loc:2", TODAY, TargetType.SUNRISE)).isEmpty();
        assertThat(reader.freshRow("loc:1", TODAY, TargetType.SUNRISE)).isPresent();
    }

    @Test
    @DisplayName("freshRow matches on point, date and event together")
    void freshRowMatchesAllThree() {
        writer.write(List.of(
                slot("loc:1", TODAY, TargetType.SUNRISE, 6.0, 0.0, 5),
                slot("loc:1", TODAY, TargetType.SUNSET, 7.0, 0.0, 5),
                slot("loc:1", TODAY.plusDays(1), TargetType.SUNRISE, 8.0, 0.0, 5),
                slot("loc:2", TODAY, TargetType.SUNRISE, 9.0, 0.0, 5)), TODAY, NOW_UTC);

        Optional<SnowStateEntity> found = reader.freshRow("loc:1", TODAY, TargetType.SUNSET);

        assertThat(found).isPresent();
        assertThat(found.get().getSnowWaterMm()).isEqualTo(7.0);
        assertThat(reader.freshRows(TODAY, TODAY.plusDays(1))).hasSize(4);
        assertThat(SnowStateEntity.locationKey(42)).isEqualTo("loc:42");
    }
}
