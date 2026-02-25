package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link ForecastEvaluationRepository}.
 * Uses an H2 in-memory database with schema generated from JPA entities.
 */
@DataJpaTest
class ForecastEvaluationRepositoryTest {

    @Autowired
    private ForecastEvaluationRepository repository;

    @Test
    @DisplayName("Saved entity can be retrieved by its generated ID")
    void save_andFindById_returnsEntity() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                LocalDate.of(2026, 2, 20), TargetType.SUNSET, LocalDateTime.of(2026, 2, 18, 6, 0), 2);

        ForecastEvaluationEntity saved = repository.save(entity);

        Optional<ForecastEvaluationEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getLocationName()).isEqualTo("Durham UK");
        assertThat(found.get().getFierySkyPotential()).isEqualTo(65);
        assertThat(found.get().getGoldenHourPotential()).isEqualTo(72);
        assertThat(found.get().getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("findByLocationNameAndTargetDateBetween returns evaluations in the date range")
    void findByLocationNameAndTargetDateBetween_returnsEvaluationsInRange() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        repository.save(buildSonnetEvaluation(today, TargetType.SUNSET, run, 2));
        repository.save(buildSonnetEvaluation(today.plusDays(1), TargetType.SUNRISE, run, 3));
        repository.save(buildSonnetEvaluation(today.plusDays(8), TargetType.SUNSET, run, 10));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        "Durham UK", today, today.plusDays(7));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTargetDate()).isEqualTo(today);
        assertThat(results.get(1).getTargetDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("findByLocationNameAndTargetDateBetween excludes evaluations for a different location")
    void findByLocationNameAndTargetDateBetween_excludesDifferentLocation() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastEvaluationEntity otherLocation = buildSonnetEvaluation(
                today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        otherLocation.setLocationName("Edinburgh UK");
        repository.save(otherLocation);

        List<ForecastEvaluationEntity> results =
                repository.findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        "Durham UK", today, today.plusDays(7));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("findByLocationNameAndTargetDateBetweenAndEvaluationModel returns only matching model rows")
    void findByLocationNameAndTargetDateBetweenAndEvaluationModel_filtersByModel() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime runSonnet = LocalDateTime.of(2026, 2, 18, 6, 0);
        LocalDateTime runHaiku = LocalDateTime.of(2026, 2, 18, 12, 0);
        repository.save(buildSonnetEvaluation(today, TargetType.SUNSET, runSonnet, 2));
        repository.save(buildHaikuEvaluation(today, TargetType.SUNSET, runHaiku, 2));
        repository.save(buildSonnetEvaluation(today.plusDays(1), TargetType.SUNRISE, runSonnet, 3));

        List<ForecastEvaluationEntity> sonnetResults =
                repository.findByLocationAndDateRangeAndModel(
                        "Durham UK", today, today.plusDays(7), EvaluationModel.SONNET);

        assertThat(sonnetResults).hasSize(2);
        assertThat(sonnetResults).allMatch(e -> e.getEvaluationModel() == EvaluationModel.SONNET);

        List<ForecastEvaluationEntity> haikuResults =
                repository.findByLocationAndDateRangeAndModel(
                        "Durham UK", today, today.plusDays(7), EvaluationModel.HAIKU);

        assertThat(haikuResults).hasSize(1);
        assertThat(haikuResults.get(0).getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(haikuResults.get(0).getRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("findByLocationNameAndTargetDateAndTargetType returns evaluations ordered by forecast_run_at")
    void findByLocationNameAndTargetDateAndTargetType_orderedByForecastRunAt() {
        LocalDate target = LocalDate.of(2026, 2, 27);
        LocalDateTime runDay0 = LocalDateTime.of(2026, 2, 20, 6, 0);
        LocalDateTime runDay4 = LocalDateTime.of(2026, 2, 23, 6, 0);
        LocalDateTime runDay7 = LocalDateTime.of(2026, 2, 26, 18, 0);

        repository.save(buildSonnetEvaluation(target, TargetType.SUNSET, runDay7, 1));
        repository.save(buildSonnetEvaluation(target, TargetType.SUNSET, runDay0, 7));
        repository.save(buildSonnetEvaluation(target, TargetType.SUNSET, runDay4, 4));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        "Durham UK", target, TargetType.SUNSET);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getForecastRunAt()).isEqualTo(runDay0);
        assertThat(results.get(1).getForecastRunAt()).isEqualTo(runDay4);
        assertThat(results.get(2).getForecastRunAt()).isEqualTo(runDay7);
    }

    @Test
    @DisplayName("findByLocationNameAndTargetDateAndTargetType excludes the other target type")
    void findByLocationNameAndTargetDateAndTargetType_excludesOtherTargetType() {
        LocalDate target = LocalDate.of(2026, 2, 27);
        repository.save(buildSonnetEvaluation(target, TargetType.SUNRISE, LocalDateTime.of(2026, 2, 20, 6, 0), 7));
        repository.save(buildSonnetEvaluation(target, TargetType.SUNSET, LocalDateTime.of(2026, 2, 20, 18, 0), 7));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationNameAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        "Durham UK", target, TargetType.SUNSET);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTargetType()).isEqualTo(TargetType.SUNSET);
    }

    private ForecastEvaluationEntity buildSonnetEvaluation(
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt, int daysAhead) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .locationName("Durham UK")
                .targetDate(targetDate)
                .targetType(targetType)
                .forecastRunAt(forecastRunAt)
                .daysAhead(daysAhead)
                .lowCloud(20)
                .midCloud(60)
                .highCloud(40)
                .visibility(20000)
                .windSpeed(new BigDecimal("5.50"))
                .windDirection(225)
                .precipitation(new BigDecimal("0.10"))
                .evaluationModel(EvaluationModel.SONNET)
                .fierySkyPotential(65)
                .goldenHourPotential(72)
                .summary("Moderate cloud mix with a clear horizon — worth watching.")
                .build();
    }

    private ForecastEvaluationEntity buildHaikuEvaluation(
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt, int daysAhead) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .locationName("Durham UK")
                .targetDate(targetDate)
                .targetType(targetType)
                .forecastRunAt(forecastRunAt)
                .daysAhead(daysAhead)
                .lowCloud(20)
                .midCloud(60)
                .highCloud(40)
                .visibility(20000)
                .windSpeed(new BigDecimal("5.50"))
                .windDirection(225)
                .precipitation(new BigDecimal("0.10"))
                .evaluationModel(EvaluationModel.HAIKU)
                .rating(3)
                .summary("Moderate cloud mix.")
                .build();
    }
}
