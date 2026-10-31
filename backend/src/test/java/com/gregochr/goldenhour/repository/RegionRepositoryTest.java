package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

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
