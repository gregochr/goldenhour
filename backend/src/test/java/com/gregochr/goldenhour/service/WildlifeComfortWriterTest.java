package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WildlifeComfortWriter} against H2: the replace is atomic, never deletes without
 * replacements in hand, and refuses to touch anything but one place's HOURLY rows for one date.
 */
@DataJpaTest
@Import(WildlifeComfortWriter.class)
class WildlifeComfortWriterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private static final LocalDateTime MORNING_RUN = LocalDateTime.of(2026, 10, 2, 5, 30);
    private static final LocalDateTime EVENING_RUN = LocalDateTime.of(2026, 10, 2, 17, 30);

    @Autowired
    private WildlifeComfortWriter writer;

    @Autowired
    private ForecastEvaluationRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    private LocationEntity hide;
    private LocationEntity other;

    @BeforeEach
    void setUp() {
        hide = locationRepository.save(place("Low Barns"));
        other = locationRepository.save(place("Saltholme"));
    }

    private LocationEntity place(String name) {
        return LocationEntity.builder().name(name).lat(54.65).lon(-1.7)
                .locationType(new HashSet<>(Set.of(LocationType.WILDLIFE)))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
    }

    private ForecastEvaluationEntity row(LocationEntity location, LocalDate date, TargetType type,
            int hour, double temperature, LocalDateTime runAt) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.650000"))
                .locationLon(new BigDecimal("-1.700000"))
                .location(location)
                .targetDate(date)
                .targetType(type)
                .solarEventTime(date.atTime(hour, 0))
                .forecastRunAt(runAt)
                .daysAhead(0)
                .evaluationModel(EvaluationModel.WILDLIFE)
                .temperatureCelsius(temperature)
                .build();
    }

    @Test
    @DisplayName("replaces the place's HOURLY rows for the date and reports how many it inserted")
    void replace_swapsOldRowsForNew() {
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));
        repository.save(row(hide, DAY, TargetType.HOURLY, 10, 99.0, MORNING_RUN));

        int inserted = writer.replaceHourlyRows(hide.getId(), DAY, List.of(
                row(hide, DAY, TargetType.HOURLY, 9, 14.0, EVENING_RUN),
                row(hide, DAY, TargetType.HOURLY, 10, 15.0, EVENING_RUN),
                row(hide, DAY, TargetType.HOURLY, 11, 16.0, EVENING_RUN)));

        assertThat(inserted).isEqualTo(3);
        assertThat(repository.findAll())
                .extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .containsExactlyInAnyOrder(14.0, 15.0, 16.0);
    }

    @Test
    @DisplayName("leaves another place, another date and the solar rows of the same place and date alone")
    void replace_leavesEverythingElse() {
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));
        repository.save(row(hide, DAY, TargetType.SUNRISE, 6, 1.0, MORNING_RUN));
        repository.save(row(hide, DAY, TargetType.SUNSET, 17, 2.0, MORNING_RUN));
        repository.save(row(hide, DAY.plusDays(1), TargetType.HOURLY, 9, 3.0, MORNING_RUN));
        repository.save(row(other, DAY, TargetType.HOURLY, 9, 4.0, MORNING_RUN));

        writer.replaceHourlyRows(hide.getId(), DAY, List.of(
                row(hide, DAY, TargetType.HOURLY, 9, 14.0, EVENING_RUN)));

        assertThat(repository.findAll())
                .extracting(e -> e.getLocationName() + "|" + e.getTargetDate() + "|" + e.getTargetType()
                        + "|" + e.getTemperatureCelsius())
                .containsExactlyInAnyOrder(
                        "Low Barns|2026-10-02|HOURLY|14.0",
                        "Low Barns|2026-10-02|SUNRISE|1.0",
                        "Low Barns|2026-10-02|SUNSET|2.0",
                        "Low Barns|2026-10-03|HOURLY|3.0",
                        "Saltholme|2026-10-02|HOURLY|4.0");
    }

    @Test
    @DisplayName("an empty replacement deletes nothing: the previous rows survive")
    void emptyReplacement_keepsThePreviousRows() {
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));

        assertThat(writer.replaceHourlyRows(hide.getId(), DAY, List.of())).isZero();
        assertThat(writer.replaceHourlyRows(hide.getId(), DAY, null)).isZero();

        assertThat(repository.findAll()).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .containsExactly(99.0);
    }

    @Test
    @DisplayName("a replacement row that is not HOURLY is refused before anything is deleted")
    void nonHourlyRow_isRefusedAndNothingIsDeleted() {
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));

        assertThatThrownBy(() -> writer.replaceHourlyRows(hide.getId(), DAY, List.of(
                row(hide, DAY, TargetType.SUNRISE, 6, 1.0, EVENING_RUN))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refusing to replace HOURLY rows for location")
                .hasMessageContaining("SUNRISE");

        assertThat(repository.findAll()).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .containsExactly(99.0);
    }

    @Test
    @DisplayName("a replacement row for another date or another place is refused before anything is deleted")
    void wrongDateOrPlace_isRefused() {
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));

        assertThatThrownBy(() -> writer.replaceHourlyRows(hide.getId(), DAY, List.of(
                row(hide, DAY.plusDays(1), TargetType.HOURLY, 9, 1.0, EVENING_RUN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.replaceHourlyRows(hide.getId(), DAY, List.of(
                row(other, DAY, TargetType.HOURLY, 9, 1.0, EVENING_RUN))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.findAll()).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .containsExactly(99.0);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("an insert that fails rolls the delete back: the previous rows survive")
    void failingInsert_rollsTheDeleteBack() {
        // NOT_SUPPORTED so the writer's own transaction is the real one (a DataJpaTest otherwise
        // wraps the test in one that the writer merely joins, and an exception could not undo it).
        repository.deleteAll();
        repository.save(row(hide, DAY, TargetType.HOURLY, 9, 99.0, MORNING_RUN));
        ForecastEvaluationEntity poison = row(hide, DAY, TargetType.HOURLY, 9, 14.0, EVENING_RUN);
        poison.setLocationLat(null); // location_lat is NOT NULL: the insert fails after the delete ran

        try {
            assertThatThrownBy(() -> writer.replaceHourlyRows(hide.getId(), DAY, List.of(poison)))
                    .isInstanceOf(DataAccessException.class);

            assertThat(repository.findAll()).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                    .containsExactly(99.0);
        } finally {
            repository.deleteAll();
            locationRepository.deleteAll();
        }
    }
}
