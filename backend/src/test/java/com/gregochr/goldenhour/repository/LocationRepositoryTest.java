package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

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

    private LocationEntity buildLocation(String name, double lat, double lon) {
        return LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0))
                .build();
    }
}
