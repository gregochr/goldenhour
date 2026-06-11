package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository slice tests for {@link ForecastScoreRepository} (Pass 1
 * scaffolding). Uses H2 with schema generated from the entity annotations,
 * so the {@code uq_forecast_score_component} unique constraint asserted
 * here is the one declared on {@code ForecastScoreEntity} — kept in sync
 * with V108 by the integration-test Flyway schema in CI.
 */
@DataJpaTest
class ForecastScoreRepositoryTest {

    private static final LocalDate EVALUATION_DATE = LocalDate.of(2026, 6, 10);
    private static final Instant EVALUATED_AT = Instant.parse("2026-06-10T01:15:00Z");

    @Autowired
    private ForecastScoreRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocationEntity location;

    @BeforeEach
    void persistLocation() {
        location = locationRepository.save(LocationEntity.builder()
                .name("Roseberry Topping")
                .lat(54.5054)
                .lon(-1.1075)
                .createdAt(LocalDateTime.of(2026, 6, 1, 12, 0))
                .build());
    }

    @Test
    @DisplayName("a row per forecast type round-trips through the unique-key accessor")
    void saveAndFindByUniqueKey_roundTripsEveryType() {
        for (ForecastType type : ForecastType.values()) {
            repository.save(buildScore(type, TargetType.SUNSET, 3, "prose for " + type));
        }

        for (ForecastType type : ForecastType.values()) {
            Optional<ForecastScoreEntity> found =
                    repository.findComponent(
                            type, location.getId(), EVALUATION_DATE, TargetType.SUNSET);

            assertThat(found).as("row for %s", type).isPresent();
            ForecastScoreEntity row = found.get();
            assertThat(row.getForecastType()).isEqualTo(type);
            assertThat(row.getLocation().getId()).isEqualTo(location.getId());
            assertThat(row.getEvaluationDate()).isEqualTo(EVALUATION_DATE);
            assertThat(row.getEventType()).isEqualTo(TargetType.SUNSET);
            assertThat(row.getScore()).isEqualTo(3);
            assertThat(row.getSummary()).isEqualTo("prose for " + type);
            assertThat(row.getPipelineRunId()).isEqualTo(42L);
            assertThat(row.getEvaluatedAt()).isEqualTo(EVALUATED_AT);
        }
    }

    @Test
    @DisplayName("forecast_type_id column stores the enum's lookup id, not its ordinal or name")
    void forecastTypeAccessor_writesLookupIdToColumn() {
        ForecastScoreEntity saved =
                repository.saveAndFlush(buildScore(ForecastType.BLUEBELL, TargetType.SUNRISE, 4, null));

        Long columnValue = jdbcTemplate.queryForObject(
                "SELECT forecast_type_id FROM forecast_score WHERE id = ?",
                Long.class, saved.getId());

        assertThat(columnValue).isEqualTo(ForecastType.BLUEBELL.getId());
        assertThat(columnValue).isEqualTo(5L);
    }

    @Test
    @DisplayName("duplicate (type, location, date, event) violates the component unique constraint")
    void duplicateComponentKey_violatesUniqueConstraint() {
        repository.saveAndFlush(buildScore(ForecastType.SKY, TargetType.SUNSET, 4, "first"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                buildScore(ForecastType.SKY, TargetType.SUNSET, 2, "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("same type/location/date with a different event is a distinct component row")
    void differentEventType_isAllowed() {
        repository.saveAndFlush(buildScore(ForecastType.SKY, TargetType.SUNSET, 4, null));
        repository.saveAndFlush(buildScore(ForecastType.SKY, TargetType.SUNRISE, 2, null));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("nullable summary round-trips as null for deterministic types")
    void nullSummary_roundTripsNull() {
        repository.saveAndFlush(buildScore(ForecastType.TIDAL, TargetType.SUNSET, 5, null));

        Optional<ForecastScoreEntity> found =
                repository.findComponent(
                        ForecastType.TIDAL, location.getId(), EVALUATION_DATE, TargetType.SUNSET);

        assertThat(found).isPresent();
        assertThat(found.get().getSummary()).isNull();
    }

    @Test
    @DisplayName("unique-key accessor returns empty when no row has been written")
    void findByUniqueKey_absentRow_returnsEmpty() {
        assertThat(repository.findComponent(
                ForecastType.GOLDEN_HOUR, location.getId(), EVALUATION_DATE, TargetType.SUNSET))
                .isEmpty();
    }

    private ForecastScoreEntity buildScore(ForecastType type, TargetType eventType,
            int score, String summary) {
        ForecastScoreEntity entity = new ForecastScoreEntity();
        entity.setForecastType(type);
        entity.setLocation(location);
        entity.setEvaluationDate(EVALUATION_DATE);
        entity.setEventType(eventType);
        entity.setScore(score);
        entity.setSummary(summary);
        entity.setPipelineRunId(42L);
        entity.setEvaluatedAt(EVALUATED_AT);
        return entity;
    }
}
