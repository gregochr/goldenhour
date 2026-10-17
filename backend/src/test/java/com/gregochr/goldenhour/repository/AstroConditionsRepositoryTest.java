package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
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
 * Repository slice tests for {@link AstroConditionsRepository#findByForecastDateFetchingLocation}: the read
 * Ask PhotoCast's snapshot makes, which must hand back rows whose location and region are already loaded
 * (the Ready precompute builds a snapshot outside any transaction, where a lazy association is a
 * {@code LazyInitializationException}) and only for places that may be recommended.
 */
@DataJpaTest
class AstroConditionsRepositoryTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final Instant RUN = Instant.parse("2026-10-11T14:00:00Z");

    @Autowired
    private AstroConditionsRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private EntityManager entityManager;

    private LocationEntity location(String name, Integer bortle, boolean enabled, RegionEntity region) {
        return locationRepository.save(LocationEntity.builder().name(name).lat(54.9).lon(-1.6)
                .bortleClass(bortle).enabled(enabled).region(region)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
    }

    private AstroConditionsEntity row(LocationEntity location, LocalDate date) {
        return repository.save(AstroConditionsEntity.builder().location(location).forecastDate(date)
                .runTimestamp(RUN).stars(4).summary("Clear").build());
    }

    @Test
    @DisplayName("the location and its region are loaded by the query itself: nothing is left to a lazy read "
            + "outside a transaction")
    void fetchesTheLocationAndItsRegion() {
        RegionEntity region = regionRepository.save(
                RegionEntity.builder().name("Northumberland").createdAt(CREATED).build());
        row(location("Embleton Bay", 3, true, region), NIGHT);
        entityManager.flush();
        entityManager.clear();

        List<AstroConditionsEntity> found = repository.findByForecastDateFetchingLocation(NIGHT);

        assertThat(found).hasSize(1);
        AstroConditionsEntity loaded = found.getFirst();
        assertThat(Hibernate.isInitialized(loaded.getLocation())).isTrue();
        assertThat(Hibernate.isInitialized(loaded.getLocation().getRegion())).isTrue();
        assertThat(loaded.getLocation().getName()).isEqualTo("Embleton Bay");
        assertThat(loaded.getLocation().getRegion().getName()).isEqualTo("Northumberland");
        assertThat(loaded.getLocation().getBortleClass()).isEqualTo(3);
    }

    @Test
    @DisplayName("a place with no region is still returned (the region is a LEFT JOIN), for the builder to skip")
    void aLocationWithNoRegionIsReturned() {
        row(location("Regionless", 3, true, null), NIGHT);
        entityManager.flush();
        entityManager.clear();

        List<AstroConditionsEntity> found = repository.findByForecastDateFetchingLocation(NIGHT);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getLocation().getRegion()).isNull();
    }

    @Test
    @DisplayName("a disabled place and a place with no Bortle class are excluded: an upsert never deletes, so "
            + "their old rows are still there")
    void excludesDisabledAndNonBortlePlaces() {
        RegionEntity region = regionRepository.save(RegionEntity.builder().name("Teesdale").createdAt(CREATED).build());
        row(location("Good", 2, true, region), NIGHT);
        row(location("Disabled", 2, false, region), NIGHT);
        row(location("NoBortle", null, true, region), NIGHT);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByForecastDateFetchingLocation(NIGHT))
                .extracting(r -> r.getLocation().getName()).containsExactly("Good");
    }

    @Test
    @DisplayName("only the asked night's rows come back")
    void onlyTheAskedNight() {
        RegionEntity region = regionRepository.save(RegionEntity.builder().name("Teesdale").createdAt(CREATED).build());
        LocationEntity place = location("Good", 2, true, region);
        row(place, NIGHT);
        row(place, NIGHT.plusDays(1));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByForecastDateFetchingLocation(NIGHT.plusDays(1)))
                .extracting(AstroConditionsEntity::getForecastDate).containsExactly(NIGHT.plusDays(1));
        assertThat(repository.findByForecastDateFetchingLocation(NIGHT.plusDays(2))).isEmpty();
    }
}
