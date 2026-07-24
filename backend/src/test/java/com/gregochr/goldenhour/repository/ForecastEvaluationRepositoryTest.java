package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.service.ConfidenceDeriver;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link ForecastEvaluationRepository}.
 * Uses an H2 in-memory database with schema generated from JPA entities.
 */
@DataJpaTest
class ForecastEvaluationRepositoryTest {

    @Autowired
    private ForecastEvaluationRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    private LocationEntity durham;
    private LocationEntity edinburgh;

    @BeforeEach
    void setUp() {
        durham = locationRepository.save(LocationEntity.builder()
                .name("Durham UK")
                .lat(54.7753)
                .lon(-1.5849)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
        edinburgh = locationRepository.save(LocationEntity.builder()
                .name("Edinburgh UK")
                .lat(55.9533)
                .lon(-3.1883)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
    }

    @Test
    @DisplayName("Saved entity can be retrieved by its generated ID")
    void save_andFindById_returnsEntity() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);

        ForecastEvaluationEntity saved = repository.save(entity);

        Optional<ForecastEvaluationEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getLocationName()).isEqualTo("Durham UK");
        assertThat(found.get().getFierySkyPotential()).isEqualTo(65);
        assertThat(found.get().getGoldenHourPotential()).isEqualTo(72);
        assertThat(found.get().getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("Horizon-derived confidence persists and round-trips to the Confidence enum")
    void confidence_persistsAndRoundTrips() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        entity.setConfidence(ConfidenceDeriver.fromHorizon(2).name()); // T+2 → MEDIUM

        ForecastEvaluationEntity saved = repository.save(entity);

        ForecastEvaluationEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getConfidence()).isEqualTo("MEDIUM");
        assertThat(Confidence.fromString(found.getConfidence())).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("A row with confidence left unset (legacy pre-migration) round-trips as null")
    void nullConfidence_roundTrips() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 21), TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 18, 6, 0), 3);
        // confidence intentionally left unset

        ForecastEvaluationEntity saved = repository.save(entity);

        assertThat(repository.findById(saved.getId()).orElseThrow().getConfidence()).isNull();
    }

    @Test
    @DisplayName("findByLocationIdAndTargetDateBetween returns evaluations in the date range")
    void findByLocationIdAndTargetDateBetween_returnsEvaluationsInRange() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET, run, 2));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(1), TargetType.SUNRISE, run, 3));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(8), TargetType.SUNSET, run, 10));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        durham.getId(), today, today.plusDays(7));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTargetDate()).isEqualTo(today);
        assertThat(results.get(1).getTargetDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("findByLocationIdAndTargetDateBetween excludes evaluations for a different location")
    void findByLocationIdAndTargetDateBetween_excludesDifferentLocation() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        repository.save(buildSonnetEvaluation(edinburgh, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        durham.getId(), today, today.plusDays(7));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("findByLocationAndDateRangeAndModel returns only matching model rows")
    void findByLocationAndDateRangeAndModel_filtersByModel() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime runSonnet = LocalDateTime.of(2026, 2, 18, 6, 0);
        LocalDateTime runHaiku = LocalDateTime.of(2026, 2, 18, 12, 0);
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET, runSonnet, 2));
        repository.save(buildHaikuEvaluation(durham, today, TargetType.SUNSET, runHaiku, 2));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(1), TargetType.SUNRISE, runSonnet, 3));

        List<ForecastEvaluationEntity> sonnetResults =
                repository.findByLocationAndDateRangeAndModel(
                        durham.getId(), today, today.plusDays(7), EvaluationModel.SONNET);

        assertThat(sonnetResults).hasSize(2);
        assertThat(sonnetResults).allMatch(e -> e.getEvaluationModel() == EvaluationModel.SONNET);

        List<ForecastEvaluationEntity> haikuResults =
                repository.findByLocationAndDateRangeAndModel(
                        durham.getId(), today, today.plusDays(7), EvaluationModel.HAIKU);

        assertThat(haikuResults).hasSize(1);
        assertThat(haikuResults.get(0).getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(haikuResults.get(0).getRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("findByLocationIdAndTargetDateAndTargetType returns evaluations ordered by forecast_run_at")
    void findByLocationIdAndTargetDateAndTargetType_orderedByForecastRunAt() {
        LocalDate target = LocalDate.of(2026, 2, 27);
        LocalDateTime runDay0 = LocalDateTime.of(2026, 2, 20, 6, 0);
        LocalDateTime runDay4 = LocalDateTime.of(2026, 2, 23, 6, 0);
        LocalDateTime runDay7 = LocalDateTime.of(2026, 2, 26, 18, 0);

        repository.save(buildSonnetEvaluation(durham, target, TargetType.SUNSET, runDay7, 1));
        repository.save(buildSonnetEvaluation(durham, target, TargetType.SUNSET, runDay0, 7));
        repository.save(buildSonnetEvaluation(durham, target, TargetType.SUNSET, runDay4, 4));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        durham.getId(), target, TargetType.SUNSET);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getForecastRunAt()).isEqualTo(runDay0);
        assertThat(results.get(1).getForecastRunAt()).isEqualTo(runDay4);
        assertThat(results.get(2).getForecastRunAt()).isEqualTo(runDay7);
    }

    @Test
    @DisplayName("findByLocationIdAndTargetDateAndTargetType excludes the other target type")
    void findByLocationIdAndTargetDateAndTargetType_excludesOtherTargetType() {
        LocalDate target = LocalDate.of(2026, 2, 27);
        repository.save(buildSonnetEvaluation(durham, target, TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 20, 6, 0), 7));
        repository.save(buildSonnetEvaluation(durham, target, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 20, 18, 0), 7));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        durham.getId(), target, TargetType.SUNSET);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTargetType()).isEqualTo(TargetType.SUNSET);
    }

    // ── findLatestRunPerSlotByLocationIds ───────────────────────────────────

    @Test
    @DisplayName("findLatestRunPerSlot keeps only the most recent run for each slot")
    void findLatestRunPerSlot_keepsOnlyLatestRun() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        // Three runs of the same sunset slot, plus one earlier run of a different slot.
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        ForecastEvaluationEntity latest = repository.save(buildSonnetEvaluation(durham, today,
                TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 18, 0), 1));
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 12, 0), 2));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(latest.getId());
        assertThat(results.get(0).getForecastRunAt())
                .isEqualTo(LocalDateTime.of(2026, 2, 19, 18, 0));
    }

    @Test
    @DisplayName("findLatestRunPerSlot de-duplicates independently per slot across locations")
    void findLatestRunPerSlot_batchesAcrossLocationsAndSlots() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        // Durham sunrise: two runs; Durham sunset: one run; Edinburgh sunrise: two runs.
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 19, 6, 0), 1));
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 19, 18, 0), 1));
        repository.save(buildSonnetEvaluation(edinburgh, today, TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        repository.save(buildSonnetEvaluation(edinburgh, today, TargetType.SUNRISE,
                LocalDateTime.of(2026, 2, 19, 6, 0), 1));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId(), edinburgh.getId()), today, today.plusDays(7));

        // One row per (location, date, type) slot: Durham sunrise, Durham sunset, Edinburgh sunrise.
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(e ->
                e.getForecastRunAt().equals(LocalDateTime.of(2026, 2, 19, 6, 0))
                || e.getForecastRunAt().equals(LocalDateTime.of(2026, 2, 19, 18, 0)));
    }

    @Test
    @DisplayName("findLatestRunPerSlot keeps HOURLY rows distinct per solar event time")
    void findLatestRunPerSlot_keepsHourlySlotsDistinctBySolarEventTime() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime slot9 = LocalDateTime.of(2026, 2, 20, 9, 0);
        LocalDateTime slot10 = LocalDateTime.of(2026, 2, 20, 10, 0);
        // Two hourly slots on the same date/type, each evaluated twice.
        repository.save(buildHourlyEvaluation(durham, today, slot9,
                LocalDateTime.of(2026, 2, 18, 6, 0)));
        repository.save(buildHourlyEvaluation(durham, today, slot9,
                LocalDateTime.of(2026, 2, 19, 6, 0)));
        repository.save(buildHourlyEvaluation(durham, today, slot10,
                LocalDateTime.of(2026, 2, 18, 6, 0)));
        repository.save(buildHourlyEvaluation(durham, today, slot10,
                LocalDateTime.of(2026, 2, 19, 6, 0)));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        // Both hourly slots survive, each collapsed to its latest run.
        assertThat(results).hasSize(2);
        assertThat(results).extracting(ForecastEvaluationEntity::getSolarEventTime)
                .containsExactlyInAnyOrder(slot9, slot10);
        assertThat(results).allMatch(e ->
                e.getForecastRunAt().equals(LocalDateTime.of(2026, 2, 19, 6, 0)));
    }

    @Test
    @DisplayName("findLatestRunPerSlot excludes rows outside the date range")
    void findLatestRunPerSlot_excludesRowsOutsideRange() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET, run, 2));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(8), TargetType.SUNSET, run, 10));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTargetDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("findLatestRunPerSlot collapses a SUNRISE/SUNSET slot across differing solar event times")
    void findLatestRunPerSlot_collapsesNonHourlyAcrossSolarEventTime() {
        // Simulates an admin lat/lon edit: the same (location, date, SUNSET) slot is evaluated
        // before and after the edit, producing two rows with DIFFERENT solar event times. Only the
        // latest run should be returned — solar event time is not part of the key for solar slots.
        LocalDate today = LocalDate.of(2026, 2, 20);
        repository.save(buildSolarRun(durham, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 20, 18, 0), LocalDateTime.of(2026, 2, 18, 6, 0),
                EvaluationModel.SONNET));
        ForecastEvaluationEntity afterEdit = repository.save(buildSolarRun(durham, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 20, 18, 7), LocalDateTime.of(2026, 2, 19, 6, 0),
                EvaluationModel.SONNET));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(afterEdit.getId());
        assertThat(results.get(0).getSolarEventTime())
                .isEqualTo(LocalDateTime.of(2026, 2, 20, 18, 7));
    }

    @Test
    @DisplayName("findLatestRunPerSlot returns the latest run regardless of evaluation model")
    void findLatestRunPerSlot_returnsLatestRunRegardlessOfModel() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime solar = LocalDateTime.of(2026, 2, 20, 18, 0);
        // Earlier HAIKU run, later SONNET run for the same slot — the later run wins.
        repository.save(buildSolarRun(durham, today, TargetType.SUNSET, solar,
                LocalDateTime.of(2026, 2, 18, 6, 0), EvaluationModel.HAIKU));
        ForecastEvaluationEntity latest = repository.save(buildSolarRun(durham, today, TargetType.SUNSET,
                solar, LocalDateTime.of(2026, 2, 19, 18, 0), EvaluationModel.SONNET));

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(latest.getId());
        assertThat(results.get(0).getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
    }

    // ── countTideAlignedByTargetType ────────────────────────────────────────

    @Test
    @DisplayName("countTideAlignedByTargetType groups aligned coastal evaluations by target type")
    void countTideAlignedByTargetType_groupsByTargetType() {
        LocationEntity coastal = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.HIGH))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
        LocationEntity coastal2 = locationRepository.save(LocationEntity.builder()
                .name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.MID))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());

        LocalDate date = LocalDate.of(2026, 4, 20);
        LocalDateTime run = LocalDateTime.of(2026, 4, 18, 6, 0);

        // 2 aligned at sunrise, 1 aligned at sunset
        repository.save(buildEvaluation(coastal, date, TargetType.SUNRISE, run, true));
        repository.save(buildEvaluation(coastal2, date, TargetType.SUNRISE, run, true));
        repository.save(buildEvaluation(coastal, date, TargetType.SUNSET, run, true));
        // Not aligned — should be excluded
        repository.save(buildEvaluation(coastal2, date, TargetType.SUNSET, run, false));

        List<Object[]> rows = repository.countTideAlignedByTargetType(date);
        Map<TargetType, Long> counts = rows.stream()
                .collect(Collectors.toMap(r -> (TargetType) r[0], r -> (Long) r[1]));

        assertThat(counts.get(TargetType.SUNRISE)).isEqualTo(2);
        assertThat(counts.get(TargetType.SUNSET)).isEqualTo(1);
    }

    @Test
    @DisplayName("countTideAlignedByTargetType excludes inland locations")
    void countTideAlignedByTargetType_excludesInlandLocations() {
        LocationEntity inland = locationRepository.save(LocationEntity.builder()
                .name("Durham Hills").lat(54.77).lon(-1.58)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());

        LocalDate date = LocalDate.of(2026, 4, 20);
        repository.save(buildEvaluation(inland, date, TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 18, 6, 0), true));

        List<Object[]> rows = repository.countTideAlignedByTargetType(date);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("countTideAlignedByTargetType excludes evaluations on different dates")
    void countTideAlignedByTargetType_excludesDifferentDates() {
        LocationEntity coastal = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.HIGH))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());

        LocalDate targetDate = LocalDate.of(2026, 4, 20);
        LocalDate otherDate = LocalDate.of(2026, 4, 21);
        LocalDateTime run = LocalDateTime.of(2026, 4, 18, 6, 0);

        repository.save(buildEvaluation(coastal, otherDate, TargetType.SUNRISE, run, true));

        List<Object[]> rows = repository.countTideAlignedByTargetType(targetDate);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("countTideAlignedByTargetType counts distinct locations, not duplicate evaluations")
    void countTideAlignedByTargetType_countsDistinctLocations() {
        LocationEntity coastal = locationRepository.save(LocationEntity.builder()
                .name("Bamburgh").lat(55.61).lon(-1.71)
                .tideType(Set.of(TideType.HIGH))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());

        LocalDate date = LocalDate.of(2026, 4, 20);
        // Two evaluations for the same location (e.g. Haiku then Sonnet run)
        repository.save(buildEvaluation(coastal, date, TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 18, 6, 0), true));
        repository.save(buildEvaluation(coastal, date, TargetType.SUNRISE,
                LocalDateTime.of(2026, 4, 19, 6, 0), true));

        List<Object[]> rows = repository.countTideAlignedByTargetType(date);
        Map<TargetType, Long> counts = rows.stream()
                .collect(Collectors.toMap(r -> (TargetType) r[0], r -> (Long) r[1]));

        assertThat(counts.get(TargetType.SUNRISE)).isEqualTo(1);
    }

    @Test
    @DisplayName("countTideAlignedByTargetType returns empty list when no aligned evaluations exist")
    void countTideAlignedByTargetType_noAligned_returnsEmpty() {
        List<Object[]> rows = repository.countTideAlignedByTargetType(
                LocalDate.of(2026, 4, 20));

        assertThat(rows).isEmpty();
    }

    private ForecastEvaluationEntity buildEvaluation(LocationEntity location,
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt,
            boolean tideAligned) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(location)
                .targetDate(targetDate)
                .targetType(targetType)
                .forecastRunAt(forecastRunAt)
                .daysAhead(2)
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
                .tide(TideDetails.builder().aligned(tideAligned).build())
                .summary("Test evaluation.")
                .build();
    }

    private ForecastEvaluationEntity buildHourlyEvaluation(LocationEntity location,
            LocalDate targetDate, LocalDateTime solarEventTime, LocalDateTime forecastRunAt) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(location)
                .targetDate(targetDate)
                .targetType(TargetType.HOURLY)
                .solarEventTime(solarEventTime)
                .forecastRunAt(forecastRunAt)
                .daysAhead(0)
                .evaluationModel(EvaluationModel.HAIKU)
                .summary("Hourly comfort row.")
                .build();
    }

    private ForecastEvaluationEntity buildSonnetEvaluation(LocationEntity location,
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt, int daysAhead) {
        // Solar rows in production always carry a non-null solar event time; set a realistic
        // per-type value so the dedup tests exercise the same data shape as production.
        LocalDateTime solarEventTime =
                targetDate.atTime(targetType == TargetType.SUNRISE ? 6 : 18, 0);
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(location)
                .targetDate(targetDate)
                .targetType(targetType)
                .solarEventTime(solarEventTime)
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

    private ForecastEvaluationEntity buildSolarRun(LocationEntity location, LocalDate targetDate,
            TargetType targetType, LocalDateTime solarEventTime, LocalDateTime forecastRunAt,
            EvaluationModel model) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(location)
                .targetDate(targetDate)
                .targetType(targetType)
                .solarEventTime(solarEventTime)
                .forecastRunAt(forecastRunAt)
                .daysAhead(1)
                .evaluationModel(model)
                .fierySkyPotential(65)
                .goldenHourPotential(72)
                .summary("Explicit solar-event-time run.")
                .build();
    }

    private ForecastEvaluationEntity buildHaikuEvaluation(LocationEntity location,
            LocalDate targetDate, TargetType targetType, LocalDateTime forecastRunAt, int daysAhead) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(location)
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
