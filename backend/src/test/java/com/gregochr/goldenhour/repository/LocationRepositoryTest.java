package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link LocationRepository}.
 * Uses an H2 in-memory database with schema generated from JPA entities.
 */
@DataJpaTest
class LocationRepositoryTest {

    @Autowired
    private LocationRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("existsByName returns true when a location with that name is saved")
    void existsByName_existingName_returnsTrue() {
        repository.save(buildLocation("Durham UK", 54.7753, -1.5849));

        assertThat(repository.existsByName("Durham UK")).isTrue();
    }

    @Test
    @DisplayName("existsByName returns false when no location with that name exists")
    void existsByName_unknownName_returnsFalse() {
        assertThat(repository.existsByName("Nowhere")).isFalse();
    }

    @Test
    @DisplayName("findAllByOrderByNameAsc returns locations sorted alphabetically")
    void findAllByOrderByNameAsc_returnsAlphabeticalOrder() {
        repository.save(buildLocation("Keswick", 54.6, -3.13));
        repository.save(buildLocation("Ambleside", 54.43, -2.96));
        repository.save(buildLocation("Durham UK", 54.7753, -1.5849));

        List<LocationEntity> results = repository.findAllByOrderByNameAsc();

        assertThat(results).extracting(LocationEntity::getName)
                .containsExactly("Ambleside", "Durham UK", "Keswick");
    }

    @Test
    @DisplayName("findAllByOrderByNameAsc returns empty list when no locations exist")
    void findAllByOrderByNameAsc_noLocations_returnsEmptyList() {
        assertThat(repository.findAllByOrderByNameAsc()).isEmpty();
    }

    @Test
    @DisplayName("saved location can be retrieved by ID with correct coordinates")
    void save_andFindById_returnsCorrectCoordinates() {
        LocationEntity saved = repository.save(buildLocation("Bamburgh Castle", 55.6090, -1.7099));

        LocationEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Bamburgh Castle");
        assertThat(found.getLat()).isEqualTo(55.6090);
        assertThat(found.getLon()).isEqualTo(-1.7099);
    }

    private LocationEntity reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return repository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("recordFailure writes only the counter and the failure time, and leaves "
            + "every other column alone, an admin's concurrent edit included")
    void recordFailure_touchesOnlyCounterAndTime() {
        LocationEntity saved = repository.save(buildLocation("Bamburgh Castle", 55.6090, -1.7099));
        LocalDateTime at = LocalDateTime.of(2026, 10, 2, 3, 0);

        int rows = repository.recordFailure(saved.getId(), 2, at);

        LocationEntity found = reload(saved.getId());
        assertThat(rows).isEqualTo(1);
        assertThat(found.getConsecutiveFailures()).isEqualTo(2);
        assertThat(found.getLastFailureAt()).isEqualTo(at);
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getDisabledReason()).isNull();
        assertThat(found.getName()).isEqualTo("Bamburgh Castle");
    }

    @Test
    @DisplayName("recordFailure is a no-op for a place that is already disabled")
    void recordFailure_disabledPlace_noOp() {
        LocationEntity location = buildLocation("Bamburgh Castle", 55.6090, -1.7099);
        location.setEnabled(false);
        LocationEntity saved = repository.save(location);

        int rows = repository.recordFailure(saved.getId(), 2, LocalDateTime.of(2026, 10, 2, 3, 0));

        assertThat(rows).isZero();
        assertThat(reload(saved.getId()).getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("autoDisable switches the place off and stores the count, time and reason")
    void autoDisable_disablesAndStoresReason() {
        LocationEntity saved = repository.save(buildLocation("Bamburgh Castle", 55.6090, -1.7099));
        LocalDateTime at = LocalDateTime.of(2026, 10, 2, 3, 0);
        String reason = "Auto-disabled after 3 consecutive failed scheduled runs "
                + "(last 2026-10-02: weather data could not be fetched).";

        int rows = repository.autoDisable(saved.getId(), 3, at, reason);

        LocationEntity found = reload(saved.getId());
        assertThat(rows).isEqualTo(1);
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getConsecutiveFailures()).isEqualTo(3);
        assertThat(found.getLastFailureAt()).isEqualTo(at);
        assertThat(found.getDisabledReason()).isEqualTo(reason);
        assertThat(repository.findAllByEnabledTrueOrderByNameAsc()).isEmpty();
    }

    @Test
    @DisplayName("resetFailureCounts zeroes only enabled places with a positive counter, "
            + "leaving others, and the historical failure time, untouched")
    void resetFailureCounts_resetsOnlyEnabledPositiveCounters() {
        LocationEntity failing = buildLocation("Failing", 55.0, -1.0);
        failing.setConsecutiveFailures(2);
        failing.setLastFailureAt(LocalDateTime.of(2026, 10, 1, 3, 0));
        LocationEntity failingSaved = repository.save(failing);
        LocationEntity disabled = buildLocation("Disabled", 55.1, -1.1);
        disabled.setEnabled(false);
        disabled.setConsecutiveFailures(3);
        LocationEntity disabledSaved = repository.save(disabled);
        LocationEntity untouched = buildLocation("Untouched", 55.2, -1.2);
        untouched.setConsecutiveFailures(2);
        LocationEntity untouchedSaved = repository.save(untouched);

        int rows = repository.resetFailureCounts(
                List.of(failingSaved.getId(), disabledSaved.getId()));

        assertThat(rows).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findById(failingSaved.getId()).orElseThrow().getConsecutiveFailures())
                .isZero();
        assertThat(repository.findById(failingSaved.getId()).orElseThrow().getLastFailureAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 3, 0));
        assertThat(repository.findById(disabledSaved.getId()).orElseThrow()
                .getConsecutiveFailures()).isEqualTo(3);
        assertThat(repository.findById(untouchedSaved.getId()).orElseThrow()
                .getConsecutiveFailures()).isEqualTo(2);
    }

    private LocationEntity buildLocation(String name, double lat, double lon) {
        return LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0))
                .build();
    }
}
