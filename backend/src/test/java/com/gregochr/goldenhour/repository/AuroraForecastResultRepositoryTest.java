package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
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
 * Repository slice tests for {@link AuroraForecastResultRepository} — in particular, that every
 * read method excludes {@code simulated = true} rows (V154), the fix for a real Claude call made
 * against an admin's fake aurora simulation being served to every PRO/ADMIN user on the map as if
 * it were a real forecast.
 */
@DataJpaTest
class AuroraForecastResultRepositoryTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 3, 21);

    @Autowired
    private AuroraForecastResultRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    private LocationEntity location;

    @BeforeEach
    void persistLocation() {
        location = locationRepository.save(LocationEntity.builder()
                .name("Embleton Bay")
                .lat(55.5)
                .lon(-1.6)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
    }

    private AuroraForecastResultEntity result(LocalDate forecastDate, boolean simulated) {
        return repository.save(AuroraForecastResultEntity.builder()
                .location(location)
                .forecastDate(forecastDate)
                .runTimestamp(Instant.parse("2026-03-21T18:00:00Z"))
                .stars(4)
                .summary("Great conditions")
                .triaged(false)
                .source("claude")
                .alertLevel("MODERATE")
                .maxKp(5.5)
                .simulated(simulated)
                .build());
    }

    @Test
    @DisplayName("findByForecastDateAndSimulatedFalse excludes a simulated row for the same night")
    void findByForecastDate_excludesSimulated() {
        AuroraForecastResultEntity real = result(NIGHT, false);
        result(NIGHT, true);

        List<AuroraForecastResultEntity> found = repository.findByForecastDateAndSimulatedFalse(NIGHT);

        assertThat(found).extracting(AuroraForecastResultEntity::getId).containsExactly(real.getId());
    }

    @Test
    @DisplayName("findByForecastDateAndSimulatedFalse returns nothing for a night that only has "
            + "simulated rows")
    void findByForecastDate_nightOnlySimulated_returnsEmpty() {
        result(NIGHT, true);

        assertThat(repository.findByForecastDateAndSimulatedFalse(NIGHT)).isEmpty();
    }

    @Test
    @DisplayName("findByForecastDateAndSimulatedFalseFetchingLocation excludes a simulated row and "
            + "still fetches the location")
    void findByForecastDateFetchingLocation_excludesSimulated() {
        AuroraForecastResultEntity real = result(NIGHT, false);
        result(NIGHT, true);

        List<AuroraForecastResultEntity> found =
                repository.findByForecastDateAndSimulatedFalseFetchingLocation(NIGHT);

        assertThat(found).extracting(AuroraForecastResultEntity::getId).containsExactly(real.getId());
        assertThat(found.get(0).getLocation().getName()).isEqualTo("Embleton Bay");
    }

    @Test
    @DisplayName("findDistinctForecastDatesExcludingSimulated omits a night whose only rows are "
            + "simulated")
    void findDistinctForecastDates_omitsSimulatedOnlyNight() {
        LocalDate realNight = NIGHT;
        LocalDate simulatedOnlyNight = NIGHT.plusDays(1);
        result(realNight, false);
        result(simulatedOnlyNight, true);

        assertThat(repository.findDistinctForecastDatesExcludingSimulated())
                .containsExactly(realNight);
    }

    @Test
    @DisplayName("deleteByForecastDateIn removes real and simulated rows alike, so a real re-run "
            + "clears out an earlier simulated test run for the same night")
    void deleteByForecastDateIn_removesBothRealAndSimulated() {
        result(NIGHT, false);
        result(NIGHT, true);

        repository.deleteByForecastDateIn(List.of(NIGHT));

        assertThat(repository.findByForecastDateAndSimulatedFalse(NIGHT)).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }
}
