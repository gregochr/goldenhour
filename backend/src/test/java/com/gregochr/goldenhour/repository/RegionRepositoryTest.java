package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link RegionRepository}'s column-scoped {@code enabled} write.
 * Uses an H2 in-memory database with schema generated from the JPA entities.
 */
@DataJpaTest
class RegionRepositoryTest {

    @Autowired
    private RegionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private RegionEntity reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return repository.findById(id).orElseThrow();
    }

    private RegionEntity region(String name, boolean enabled) {
        return RegionEntity.builder().name(name).enabled(enabled)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0)).build();
    }

    private static final double BASE_LAT = 54.601;
    private static final double BASE_LON = -3.135;
    private static final Instant MEASURED_AT = Instant.parse("2026-11-05T02:00:00Z");

    private RegionEntity based(String name) {
        RegionEntity region = region(name, true);
        region.setBaseName("Keswick");
        region.setBaseLat(BASE_LAT);
        region.setBaseLon(BASE_LON);
        return region;
    }

    @Test
    @DisplayName("stampDrivesMeasuredAtIfBaseIs stamps while the base is the one measured from")
    void stampDrivesMeasuredAtIfBaseIs_matchingBase_stamps() {
        RegionEntity saved = repository.save(based("Lake District"));

        int updated = repository.stampDrivesMeasuredAtIfBaseIs(saved.getId(), BASE_LAT, BASE_LON, MEASURED_AT);

        assertThat(updated).isEqualTo(1);
        assertThat(reload(saved.getId()).getDrivesMeasuredAt()).isEqualTo(MEASURED_AT);
    }

    @Test
    @DisplayName("stampDrivesMeasuredAtIfBaseIs writes nothing once the base has moved or been cleared")
    void stampDrivesMeasuredAtIfBaseIs_movedOrClearedBase_writesNothing() {
        RegionEntity saved = repository.save(based("Lake District"));

        assertThat(repository.stampDrivesMeasuredAtIfBaseIs(saved.getId(), BASE_LAT + 0.5, BASE_LON, MEASURED_AT))
                .isZero();
        RegionEntity baseless = repository.save(region("Northumberland", true));
        assertThat(repository.stampDrivesMeasuredAtIfBaseIs(baseless.getId(), BASE_LAT, BASE_LON, MEASURED_AT))
                .isZero();

        assertThat(reload(saved.getId()).getDrivesMeasuredAt()).isNull();
        assertThat(reload(baseless.getId()).getDrivesMeasuredAt()).isNull();
    }

    @Test
    @DisplayName("existsByIdAndBase is true only for the base that is stored")
    void existsByIdAndBase_matchesTheStoredBase() {
        RegionEntity saved = repository.save(based("Lake District"));

        assertThat(repository.existsByIdAndBase(saved.getId(), BASE_LAT, BASE_LON)).isTrue();
        assertThat(repository.existsByIdAndBase(saved.getId(), BASE_LAT, BASE_LON + 1)).isFalse();
    }

    @Test
    @DisplayName("clearDrivesMeasuredAt nulls the stamp")
    void clearDrivesMeasuredAt_nullsTheStamp() {
        RegionEntity saved = repository.save(based("Lake District"));
        repository.stampDrivesMeasuredAtIfBaseIs(saved.getId(), BASE_LAT, BASE_LON, MEASURED_AT);

        assertThat(repository.clearDrivesMeasuredAt(saved.getId())).isEqualTo(1);

        assertThat(reload(saved.getId()).getDrivesMeasuredAt()).isNull();
    }

    @Test
    @DisplayName("a whole-entity save of a stale snapshot cannot rewrite the stamp (column-scoped)")
    void staleSave_doesNotRewriteTheStamp() {
        RegionEntity saved = repository.save(based("Lake District"));
        entityManager.flush();
        entityManager.clear();
        RegionEntity stale = repository.findById(saved.getId()).orElseThrow();
        entityManager.clear();
        repository.stampDrivesMeasuredAtIfBaseIs(saved.getId(), BASE_LAT, BASE_LON, MEASURED_AT);

        stale.setName("Lakes");
        repository.save(stale);

        RegionEntity stored = reload(saved.getId());
        assertThat(stored.getName()).isEqualTo("Lakes");
        assertThat(stored.getDrivesMeasuredAt()).isEqualTo(MEASURED_AT);
    }

    @Test
    @DisplayName("updateEnabled flips the column and nothing else")
    void updateEnabled_writesTheColumn() {
        RegionEntity saved = repository.save(region("Northumberland", true));

        assertThat(repository.updateEnabled(saved.getId(), false)).isEqualTo(1);
        assertThat(reload(saved.getId()).isEnabled()).isFalse();

        assertThat(repository.updateEnabled(saved.getId(), true)).isEqualTo(1);
        assertThat(reload(saved.getId()).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("an insert still writes the initial enabled value")
    void insert_writesInitialEnabledValue() {
        RegionEntity off = repository.save(region("Born off", false));

        assertThat(reload(off.getId()).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a rename saved from a stale snapshot does not write enabled back over a concurrent turn-on")
    void renameFromStaleSnapshot_doesNotUndoTurnOn() {
        RegionEntity saved = repository.save(region("Northumberland", false));
        entityManager.flush();
        entityManager.clear();
        RegionEntity stale = repository.findById(saved.getId()).orElseThrow();
        entityManager.clear();

        repository.updateEnabled(saved.getId(), true);

        stale.setName("Northumbria");
        repository.save(stale);

        RegionEntity stored = reload(saved.getId());
        assertThat(stored.getName()).isEqualTo("Northumbria");
        assertThat(stored.isEnabled()).isTrue();
    }
}
