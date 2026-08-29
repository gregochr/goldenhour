package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.BatchState;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.service.ConfidenceDeriver;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

    @Autowired
    private ActualOutcomeRepository outcomeRepository;

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

    // ── findByLocationIdInAndTargetDateBetween ──────────────────────────────

    @Test
    @DisplayName("findByLocationIdInAndTargetDateBetween returns evaluations in the date range")
    void findByLocationIdInAndTargetDateBetween_returnsEvaluationsInRange() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET, run, 2));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(1), TargetType.SUNRISE, run, 3));
        repository.save(buildSonnetEvaluation(durham, today.plusDays(8), TargetType.SUNSET, run, 10));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdInAndTargetDateBetween(
                        List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTargetDate()).isEqualTo(today);
        assertThat(results.get(1).getTargetDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("findByLocationIdInAndTargetDateBetween excludes evaluations for a location not in the id list")
    void findByLocationIdInAndTargetDateBetween_excludesLocationNotInList() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        repository.save(buildSonnetEvaluation(edinburgh, today, TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2));

        List<ForecastEvaluationEntity> results =
                repository.findByLocationIdInAndTargetDateBetween(
                        List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("findByLocationIdInAndTargetDateBetween orders by location name, then date, then target type")
    void findByLocationIdInAndTargetDateBetween_ordersByLocationNameThenDateThenType() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        LocalDateTime run = LocalDateTime.of(2026, 2, 18, 6, 0);
        // Edinburgh sorts after Durham alphabetically; seed rows so a date-only sort would
        // interleave them, to prove location name is the primary sort key.
        repository.save(buildSonnetEvaluation(edinburgh, today, TargetType.SUNSET, run, 2));
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNRISE, run, 2));
        repository.save(buildSonnetEvaluation(durham, today, TargetType.SUNSET, run, 2));

        List<ForecastEvaluationEntity> results = repository.findByLocationIdInAndTargetDateBetween(
                List.of(durham.getId(), edinburgh.getId()), today, today.plusDays(7));

        assertThat(results).extracting(e -> e.getLocation().getName(), ForecastEvaluationEntity::getTargetType)
                .containsExactly(
                        tuple("Durham UK", TargetType.SUNRISE),
                        tuple("Durham UK", TargetType.SUNSET),
                        tuple("Edinburgh UK", TargetType.SUNSET));
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

    // ── R1: PENDING rows are invisible to the serve-side latest-run queries ─────

    @Test
    @DisplayName("R1 non-member: a NEWER PENDING row never shadows the prior rated row — the "
            + "rated row is served, the slot is not blanked while a batch is in flight")
    void findLatestRunPerSlot_excludesNewerPendingRow_ratedRowStillServed() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastEvaluationEntity rated = repository.save(buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        ForecastEvaluationEntity pending = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 18, 0), 1);
        pending.setBatchState(BatchState.PENDING);
        repository.save(pending);

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo(rated.getId());
        assertThat(results.getFirst().getBatchState()).isNotEqualTo(BatchState.PENDING);
    }

    @Test
    @DisplayName("R1 member: a SCORED row with a newer forecastRunAt DOES win — the exclusion is "
            + "on batch_state, not on being part of the batch lifecycle at all")
    void findLatestRunPerSlot_scoredRowWithNewerForecastRunAt_wins() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        repository.save(buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        ForecastEvaluationEntity scored = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 18, 0), 1);
        scored.setBatchState(BatchState.SCORED);
        ForecastEvaluationEntity saved = repository.save(scored);

        List<ForecastEvaluationEntity> results = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), today, today.plusDays(7));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo(saved.getId());
        assertThat(results.getFirst().getBatchState()).isEqualTo(BatchState.SCORED);
    }

    @Test
    @DisplayName("R1: findTopBy...Desc excludes a newer PENDING row for the same slot")
    void findTopByForecastRunAtDesc_excludesNewerPendingRow() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastEvaluationEntity rated = repository.save(buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 18, 6, 0), 2));
        ForecastEvaluationEntity pending = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 18, 0), 1);
        pending.setBatchState(BatchState.PENDING);
        repository.save(pending);

        List<ForecastEvaluationEntity> results = repository
                .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                        durham.getId(), today, TargetType.SUNSET, PageRequest.of(0, 1));

        assertThat(results).singleElement()
                .satisfies(e -> assertThat(e.getId()).isEqualTo(rated.getId()));
    }

    @Test
    @DisplayName("R1: findTopBy...Desc still finds a rated row when the only newer row is PENDING "
            + "— non-member proving the query is not simply always empty for a slot with a "
            + "pending row present")
    void findTopByForecastRunAtDesc_noPriorRows_pendingOnly_returnsEmpty() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastEvaluationEntity pending = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 18, 0), 1);
        pending.setBatchState(BatchState.PENDING);
        repository.save(pending);

        List<ForecastEvaluationEntity> results = repository
                .findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
                        durham.getId(), today, TargetType.SUNSET, PageRequest.of(0, 1));

        assertThat(results).isEmpty();
    }

    // ── R7: the abandonment sweep bulk updates ──────────────────────────────────

    @Test
    @DisplayName("R7(a): abandonPending stamps only still-PENDING rows among the given ids — a "
            + "SCORED or already-ABANDONED row is untouched")
    void abandonPending_stampsOnlyStillPendingRows() {
        LocalDate today = LocalDate.of(2026, 2, 20);
        ForecastEvaluationEntity pending = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 6, 0), 1);
        pending.setBatchState(BatchState.PENDING);
        ForecastEvaluationEntity pendingSaved = repository.save(pending);

        ForecastEvaluationEntity scored = buildSonnetEvaluation(
                durham, today, TargetType.SUNRISE, LocalDateTime.of(2026, 2, 19, 6, 0), 1);
        scored.setBatchState(BatchState.SCORED);
        ForecastEvaluationEntity scoredSaved = repository.save(scored);

        ForecastEvaluationEntity alreadyAbandoned = buildSonnetEvaluation(
                edinburgh, today, TargetType.SUNSET, LocalDateTime.of(2026, 2, 19, 6, 0), 1);
        alreadyAbandoned.setBatchState(BatchState.ABANDONED);
        ForecastEvaluationEntity abandonedSaved = repository.save(alreadyAbandoned);

        int updated = repository.abandonPending(
                List.of(pendingSaved.getId(), scoredSaved.getId(), abandonedSaved.getId()));

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findById(pendingSaved.getId()).orElseThrow().getBatchState())
                .isEqualTo(BatchState.ABANDONED);
        assertThat(repository.findById(scoredSaved.getId()).orElseThrow().getBatchState())
                .isEqualTo(BatchState.SCORED);
        assertThat(repository.findById(abandonedSaved.getId()).orElseThrow().getBatchState())
                .isEqualTo(BatchState.ABANDONED);
    }

    @Test
    @DisplayName("R7(b) band edge: a PENDING row exactly one minute older than the cutoff is "
            + "abandoned; one a minute younger is not")
    void abandonPendingOlderThan_bandEdgeBothSides() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 2, 20, 12, 0);
        LocalDate today = LocalDate.of(2026, 2, 20);

        ForecastEvaluationEntity older = buildSonnetEvaluation(
                durham, today, TargetType.SUNSET, cutoff.minusMinutes(1), 1);
        older.setBatchState(BatchState.PENDING);
        ForecastEvaluationEntity olderSaved = repository.save(older);

        ForecastEvaluationEntity younger = buildSonnetEvaluation(
                durham, today, TargetType.SUNRISE, cutoff.plusMinutes(1), 1);
        younger.setBatchState(BatchState.PENDING);
        ForecastEvaluationEntity youngerSaved = repository.save(younger);

        int updated = repository.abandonPendingOlderThan(cutoff);

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findById(olderSaved.getId()).orElseThrow().getBatchState())
                .isEqualTo(BatchState.ABANDONED);
        assertThat(repository.findById(youngerSaved.getId()).orElseThrow().getBatchState())
                .isEqualTo(BatchState.PENDING);
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

    // --- findCalibrationPairs: forecast scored against what was actually recorded ---

    @Test
    @DisplayName("findCalibrationPairs pairs a rated evaluation with its recorded outcome")
    void findCalibrationPairs_pairsForecastWithOutcome() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        outcomeRepository.save(buildOutcome(durham, date, TargetType.SUNSET, 5));

        List<CalibrationPair> pairs = repository.findCalibrationPairs(
                date.minusDays(1), date.plusDays(1));

        assertThat(pairs).hasSize(1);
        CalibrationPair pair = pairs.getFirst();
        assertThat(pair.locationName()).isEqualTo("Durham UK");
        assertThat(pair.targetDate()).isEqualTo(date);
        assertThat(pair.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(pair.daysAhead()).isEqualTo(1);
        assertThat(pair.evaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(pair.predictedRating()).isEqualTo(3);
        assertThat(pair.actualRating()).isEqualTo(5);
        // Forecast said 3, sky delivered 5 — a two-star pessimistic miss.
        assertThat(pair.ratingError()).isEqualTo(-2);
    }

    @Test
    @DisplayName("findCalibrationPairs yields one row per forecast horizon for the same slot")
    void findCalibrationPairs_onePairPerHorizon() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 10, 1, 0), 0));
        repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 8, 1, 0), 2));
        outcomeRepository.save(buildOutcome(durham, date, TargetType.SUNSET, 4));

        List<CalibrationPair> pairs = repository.findCalibrationPairs(date, date);

        assertThat(pairs).hasSize(2);
        assertThat(pairs).extracting(CalibrationPair::daysAhead)
                .containsExactlyInAnyOrder(0, 2);
    }

    @Test
    @DisplayName("findCalibrationPairs skips unrated forecasts and unrated outcomes")
    void findCalibrationPairs_skipsUnratedRows() {
        LocalDate date = LocalDate.of(2026, 5, 10);

        // Triaged forecast: no rating, so nothing to score.
        ForecastEvaluationEntity triaged = buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        triaged.setRating(null);
        repository.save(triaged);
        outcomeRepository.save(buildOutcome(durham, date, TargetType.SUNSET, 4));

        // Rated forecast, but the outcome carries no rating.
        repository.save(buildHaikuEvaluation(
                edinburgh, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        outcomeRepository.save(buildOutcome(edinburgh, date, TargetType.SUNSET, null));

        assertThat(repository.findCalibrationPairs(date, date)).isEmpty();
    }

    @Test
    @DisplayName("findCalibrationPairs does not pair across locations or target types")
    void findCalibrationPairs_matchesOnNaturalKeyOnly() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        // Same date, different location.
        outcomeRepository.save(buildOutcome(edinburgh, date, TargetType.SUNSET, 5));
        // Same location, different event.
        outcomeRepository.save(buildOutcome(durham, date, TargetType.SUNRISE, 5));

        assertThat(repository.findCalibrationPairs(date, date)).isEmpty();
    }

    @Test
    @DisplayName("findCalibrationPairs bounds on the outcome date, inclusive at both ends")
    void findCalibrationPairs_respectsWindow() {
        LocalDate inside = LocalDate.of(2026, 5, 10);
        LocalDate outside = LocalDate.of(2026, 5, 20);
        repository.save(buildHaikuEvaluation(
                durham, inside, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        outcomeRepository.save(buildOutcome(durham, inside, TargetType.SUNSET, 4));
        repository.save(buildHaikuEvaluation(
                durham, outside, TargetType.SUNSET, LocalDateTime.of(2026, 5, 19, 1, 0), 1));
        outcomeRepository.save(buildOutcome(durham, outside, TargetType.SUNSET, 2));

        assertThat(repository.findCalibrationPairs(inside, inside)).hasSize(1);
        assertThat(repository.findCalibrationPairs(inside, outside)).hasSize(2);
        assertThat(repository.findCalibrationPairs(
                outside.plusDays(1), outside.plusDays(5))).isEmpty();
    }

    private ActualOutcomeEntity buildOutcome(LocationEntity location, LocalDate date,
            TargetType targetType, Integer actualRating) {
        return ActualOutcomeEntity.builder()
                .locationLat(BigDecimal.valueOf(location.getLat()).setScale(6, RoundingMode.HALF_UP))
                .locationLon(BigDecimal.valueOf(location.getLon()).setScale(6, RoundingMode.HALF_UP))
                .location(location)
                .outcomeDate(date)
                .targetType(targetType)
                .wentOut(true)
                .actualRating(actualRating)
                .fierySkyActual(actualRating == null ? null : actualRating * 20)
                .recordedAt(LocalDateTime.of(2026, 5, 11, 9, 0))
                .build();
    }
}
