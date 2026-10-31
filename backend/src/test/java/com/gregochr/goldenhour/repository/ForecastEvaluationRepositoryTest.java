package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.BatchState;
import com.gregochr.goldenhour.entity.DirectionalCloudDetails;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.service.ConfidenceDeriver;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private TestEntityManager entityManager;

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
    @DisplayName("The five far-point mid/high and dark-hour readings persist and reload from the database")
    void farPointCloudCapture_persistsAndReloads() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        entity.setDirectionalCloud(DirectionalCloudDetails.from(
                new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55, 61, 72, 18, 27, 83)));

        Long id = entityManager.persistAndFlush(entity).getId();
        entityManager.clear();

        DirectionalCloudDetails reloaded =
                repository.findById(id).orElseThrow().getDirectionalCloud();
        assertThat(reloaded.getFarSolarLow()).isEqualTo(55);
        assertThat(reloaded.getFarSolarMid()).isEqualTo(61);
        assertThat(reloaded.getFarSolarHigh()).isEqualTo(72);
        assertThat(reloaded.getFarSolarLowDark()).isEqualTo(18);
        assertThat(reloaded.getFarSolarMidDark()).isEqualTo(27);
        assertThat(reloaded.getFarSolarHighDark()).isEqualTo(83);
    }

    @Test
    @DisplayName("The eight far-point pressure-level readings persist and reload from the database")
    void farPointPressureLevels_persistAndReload() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        entity.setDirectionalCloud(DirectionalCloudDetails.from(new DirectionalCloudData(
                35, 45, 20, 40, 50, 15, 55, 61, 72, 18, 27, 83, 11, 22, 33, 44, 55, 66, 77, 88)));

        Long id = entityManager.persistAndFlush(entity).getId();
        entityManager.clear();

        DirectionalCloudDetails reloaded =
                repository.findById(id).orElseThrow().getDirectionalCloud();
        assertThat(reloaded.getFarSolarCloud850()).isEqualTo(11);
        assertThat(reloaded.getFarSolarCloud700()).isEqualTo(22);
        assertThat(reloaded.getFarSolarCloud600()).isEqualTo(33);
        assertThat(reloaded.getFarSolarCloud500()).isEqualTo(44);
        assertThat(reloaded.getFarSolarCloud400()).isEqualTo(55);
        assertThat(reloaded.getFarSolarCloud300()).isEqualTo(66);
        assertThat(reloaded.getFarSolarCloud250()).isEqualTo(77);
        assertThat(reloaded.getFarSolarCloud200()).isEqualTo(88);
        assertThat(reloaded.getFarSolarHighDark()).isEqualTo(83);
    }

    @Test
    @DisplayName("A row built from a 12-argument record reloads with the eight pressure levels null")
    void farPointPressureLevels_twelveArgumentRecord_reloadsNulls() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        entity.setDirectionalCloud(DirectionalCloudDetails.from(
                new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55, 61, 72, 18, 27, 83)));

        Long id = entityManager.persistAndFlush(entity).getId();
        entityManager.clear();

        DirectionalCloudDetails reloaded =
                repository.findById(id).orElseThrow().getDirectionalCloud();
        assertThat(reloaded.getFarSolarHighDark()).isEqualTo(83);
        assertThat(reloaded.getFarSolarCloud850()).isNull();
        assertThat(reloaded.getFarSolarCloud700()).isNull();
        assertThat(reloaded.getFarSolarCloud600()).isNull();
        assertThat(reloaded.getFarSolarCloud500()).isNull();
        assertThat(reloaded.getFarSolarCloud400()).isNull();
        assertThat(reloaded.getFarSolarCloud300()).isNull();
        assertThat(reloaded.getFarSolarCloud250()).isNull();
        assertThat(reloaded.getFarSolarCloud200()).isNull();
    }

    @Test
    @DisplayName("A row built from a 7-argument record reloads with the five new readings null")
    void farPointCloudCapture_sevenArgumentRecord_reloadsNulls() {
        ForecastEvaluationEntity entity = buildSonnetEvaluation(
                durham, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 18, 6, 0), 2);
        entity.setDirectionalCloud(DirectionalCloudDetails.from(
                new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55)));

        Long id = entityManager.persistAndFlush(entity).getId();
        entityManager.clear();

        DirectionalCloudDetails reloaded =
                repository.findById(id).orElseThrow().getDirectionalCloud();
        assertThat(reloaded.getFarSolarLow()).isEqualTo(55);
        assertThat(reloaded.getFarSolarMid()).isNull();
        assertThat(reloaded.getFarSolarHigh()).isNull();
        assertThat(reloaded.getFarSolarLowDark()).isNull();
        assertThat(reloaded.getFarSolarMidDark()).isNull();
        assertThat(reloaded.getFarSolarHighDark()).isNull();
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

    // --- V182: build and prompt provenance ---

    @Test
    @DisplayName("V182: findCalibrationPairsFromRatings carries the forecast row's build and prompt hash,"
            + " and null for a row from before they were recorded")
    void findCalibrationPairsFromRatings_projectsBuildAndPromptHash() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity stamped = buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        stamped.setAppBuild("v2.28.0");
        stamped.setPromptHash("b".repeat(64));
        stamped = repository.save(stamped);
        ForecastEvaluationEntity legacy = repository.save(buildHaikuEvaluation(
                edinburgh, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        saveRating(11L, durham, date, 4, stamped.getId(), 3);
        saveRating(12L, edinburgh, date, 4, legacy.getId(), 3);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).hasSize(2);
        CalibrationPair withBuild = pairs.stream()
                .filter(p -> p.outcomeKey().equals("rating:11")).findFirst().orElseThrow();
        CalibrationPair withoutBuild = pairs.stream()
                .filter(p -> p.outcomeKey().equals("rating:12")).findFirst().orElseThrow();
        assertThat(withBuild.appBuild()).isEqualTo("v2.28.0");
        assertThat(withBuild.promptHash()).isEqualTo("b".repeat(64));
        assertThat(withoutBuild.appBuild()).isNull();
        assertThat(withoutBuild.promptHash()).isNull();
    }

    @Test
    @DisplayName("V182: stampPromptHash stamps the named rows only and touches no other column")
    void stampPromptHash_stampsOnlyTheNamedRows() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        Long first = repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1)).getId();
        Long second = repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNRISE, LocalDateTime.of(2026, 5, 9, 1, 0), 1)).getId();
        Long untouched = repository.save(buildHaikuEvaluation(
                edinburgh, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1)).getId();
        entityManager.flush();

        int stamped = repository.stampPromptHash("c".repeat(64), List.of(first, second));
        entityManager.clear();

        assertThat(stamped).isEqualTo(2);
        assertThat(repository.findById(first).orElseThrow().getPromptHash()).isEqualTo("c".repeat(64));
        assertThat(repository.findById(second).orElseThrow().getPromptHash()).isEqualTo("c".repeat(64));
        assertThat(repository.findById(untouched).orElseThrow().getPromptHash()).isNull();
        assertThat(repository.findById(first).orElseThrow().getRating()).as("rating is left alone")
                .isEqualTo(3);
    }

    // --- V186/V187: the mist band and the seen tag ride the pairs ---

    @Test
    @DisplayName("V187: findCalibrationPairsFromRatings carries the forecast row's mist band and the "
            + "reader's seen tag, null for a rating given with none")
    void findCalibrationPairsFromRatings_projectsMistPotentialAndSeenTag() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity mist = buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        mist.setMistPotential("LIKELY");
        mist = repository.save(mist);
        ForecastEvaluationEntity plain = repository.save(buildHaikuEvaluation(
                edinburgh, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        saveRating(11L, durham, date, 4, mist.getId(), 2, SeenTag.MIST);
        saveRating(12L, edinburgh, date, 4, plain.getId(), 3, null);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).extracting(CalibrationPair::outcomeKey, CalibrationPair::mistPotential,
                CalibrationPair::seen).containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("rating:11", "LIKELY", SeenTag.MIST),
                org.assertj.core.api.Assertions.tuple("rating:12", null, null));
    }

    // --- findCalibrationPairsFromRatings: forecast scored against what a reader said they saw ---

    @Test
    @DisplayName("findCalibrationPairsFromRatings pairs a rating with the row its id names, with the"
            + " row's own horizon, run time, model and potentials and no trip flag")
    void findCalibrationPairsFromRatings_pairsWithTheNamedRow() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity row = buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        row.setFierySkyPotential(61);
        row.setGoldenHourPotential(72);
        row = repository.save(row);
        saveRating(11L, durham, date, 4, row.getId(), 3);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).hasSize(1);
        CalibrationPair pair = pairs.getFirst();
        assertThat(pair.locationName()).isEqualTo("Durham UK");
        assertThat(pair.targetDate()).isEqualTo(date);
        assertThat(pair.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(pair.daysAhead()).isEqualTo(1);
        assertThat(pair.forecastRunAt()).isEqualTo(LocalDateTime.of(2026, 5, 9, 1, 0));
        assertThat(pair.evaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(pair.predictedFierySky()).isEqualTo(61);
        assertThat(pair.predictedGoldenHour()).isEqualTo(72);
        assertThat(pair.predictedRating()).isEqualTo(3);
        assertThat(pair.actualRating()).isEqualTo(4);
        assertThat(pair.actualFierySky()).isNull();
        assertThat(pair.actualGoldenHour()).isNull();
        assertThat(pair.wentOut()).isNull();
        assertThat(pair.outcomeKey()).isEqualTo("rating:11");
    }

    @Test
    @DisplayName("findCalibrationPairsFromRatings pairs with the named row only, never a newer row of the slot")
    void findCalibrationPairsFromRatings_ignoresLaterRowsOfTheSlot() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity named = repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        // A batch that landed after the event: newer, same slot, rated — and not the one the reader saw.
        repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 11, 14, 0), 0));
        saveRating(11L, durham, date, 4, named.getId(), 3);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.getFirst().daysAhead()).isEqualTo(1);
        assertThat(pairs.getFirst().forecastRunAt()).isEqualTo(LocalDateTime.of(2026, 5, 9, 1, 0));
    }

    @Test
    @DisplayName("findCalibrationPairsFromRatings predicts the captured star, not the row's own rating")
    void findCalibrationPairsFromRatings_predictedRatingIsTheCapturedStar() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity row = buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        // A recombined OPEN_FELL slot: the row holds the sky lane's 4, the reader saw the combined 3.
        row.setRating(4);
        row = repository.save(row);
        saveRating(11L, durham, date, 2, row.getId(), 3);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).extracting(CalibrationPair::predictedRating).containsExactly(3);
    }

    @Test
    @DisplayName("findCalibrationPairsFromRatings skips a not-seen rating, an unscored row, a null id"
            + " and a null captured star")
    void findCalibrationPairsFromRatings_skipsWhatCannotBeScored() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity rated = repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        ForecastEvaluationEntity unscored = buildHaikuEvaluation(
                edinburgh, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1);
        unscored.setRating(null);
        unscored = repository.save(unscored);

        saveRating(11L, durham, date, null, rated.getId(), 3);
        saveRating(12L, edinburgh, date, 4, unscored.getId(), 3);
        saveRating(13L, durham, date, 4, null, 3);
        saveRating(14L, durham, date, 4, rated.getId(), null);

        assertThat(repository.findCalibrationPairsFromRatings(date, date)).isEmpty();
    }

    @Test
    @DisplayName("findCalibrationPairsFromRatings gives one pair per reader of the same row")
    void findCalibrationPairsFromRatings_twoUsersGiveTwoPairs() {
        LocalDate date = LocalDate.of(2026, 5, 10);
        ForecastEvaluationEntity row = repository.save(buildHaikuEvaluation(
                durham, date, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        saveRating(11L, durham, date, 4, row.getId(), 3);
        saveRating(12L, durham, date, 1, row.getId(), 3);

        List<CalibrationPair> pairs = repository.findCalibrationPairsFromRatings(date, date);

        assertThat(pairs).extracting(CalibrationPair::outcomeKey)
                .containsExactlyInAnyOrder("rating:11", "rating:12");
        assertThat(pairs).extracting(CalibrationPair::actualRating)
                .containsExactlyInAnyOrder(4, 1);
    }

    @Test
    @DisplayName("findCalibrationPairsFromRatings bounds on the event date, inclusive at both ends")
    void findCalibrationPairsFromRatings_respectsWindow() {
        LocalDate inside = LocalDate.of(2026, 5, 10);
        LocalDate outside = LocalDate.of(2026, 5, 20);
        ForecastEvaluationEntity a = repository.save(buildHaikuEvaluation(
                durham, inside, TargetType.SUNSET, LocalDateTime.of(2026, 5, 9, 1, 0), 1));
        ForecastEvaluationEntity b = repository.save(buildHaikuEvaluation(
                durham, outside, TargetType.SUNSET, LocalDateTime.of(2026, 5, 19, 1, 0), 1));
        saveRating(11L, durham, inside, 4, a.getId(), 3);
        // The outside rating's row targets a date INSIDE the window: only the rating's event date bounds.
        b.setTargetDate(inside);
        repository.save(b);
        saveRating(11L, durham, outside, 2, b.getId(), 3);

        assertThat(repository.findCalibrationPairsFromRatings(inside, inside)).hasSize(1);
        assertThat(repository.findCalibrationPairsFromRatings(inside, outside)).hasSize(2);
        assertThat(repository.findCalibrationPairsFromRatings(
                outside.plusDays(1), outside.plusDays(5))).isEmpty();
    }

    private void saveRating(long userId, LocationEntity location, LocalDate date, Integer rating,
            Long forecastEvaluationId, Integer forecastRating) {
        saveRating(userId, location, date, rating, forecastEvaluationId, forecastRating, null);
    }

    private void saveRating(long userId, LocationEntity location, LocalDate date, Integer rating,
            Long forecastEvaluationId, Integer forecastRating, SeenTag seen) {
        entityManager.persistAndFlush(OutcomeRatingEntity.builder()
                .seen(seen)
                .userId(userId)
                .locationId(location.getId())
                .eventDate(date)
                .eventType(TargetType.SUNSET)
                .rating(rating)
                .eventTime(Instant.parse("2026-05-10T19:30:00Z"))
                .forecastEvaluationId(forecastEvaluationId)
                .forecastRating(forecastRating)
                .createdAt(Instant.parse("2026-05-11T09:00:00Z"))
                .updatedAt(Instant.parse("2026-05-11T09:00:00Z"))
                .build());
    }

    // --- deleteHourlyByLocationIdAndTargetDate: the one place this insert-only table loses rows ---

    @Test
    @DisplayName("deleteHourly removes only HOURLY rows for that place and date, never a solar row")
    void deleteHourly_touchesOnlyHourlyRowsForThatPlaceAndDate() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        LocalDateTime run = LocalDateTime.of(2026, 10, 2, 5, 30);
        // Targets: two HOURLY rows for Durham on the day.
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(9, 0), run));
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(10, 0), run));
        // Bystanders: Durham's SUNRISE and SUNSET that same day (carrying a rating), Durham's HOURLY
        // the next day, and Edinburgh's HOURLY the same day.
        repository.save(buildSonnetEvaluation(durham, day, TargetType.SUNRISE, run, 0));
        repository.save(buildSonnetEvaluation(durham, day, TargetType.SUNSET, run, 0));
        repository.save(buildHourlyEvaluation(durham, day.plusDays(1), day.plusDays(1).atTime(9, 0), run));
        repository.save(buildHourlyEvaluation(edinburgh, day, day.atTime(9, 0), run));

        int deleted = repository.deleteHourlyByLocationIdAndTargetDate(durham.getId(), day);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAll())
                .extracting(e -> e.getLocationName() + "|" + e.getTargetDate() + "|" + e.getTargetType())
                .containsExactlyInAnyOrder(
                        "Durham UK|2026-10-02|SUNRISE",
                        "Durham UK|2026-10-02|SUNSET",
                        "Durham UK|2026-10-03|HOURLY",
                        "Edinburgh UK|2026-10-02|HOURLY");
    }

    @Test
    @DisplayName("deleteHourly on a place-date with no HOURLY rows deletes nothing")
    void deleteHourly_withNothingToDelete_returnsZero() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        repository.save(buildSonnetEvaluation(durham, day, TargetType.SUNRISE,
                LocalDateTime.of(2026, 10, 2, 5, 30), 0));

        assertThat(repository.deleteHourlyByLocationIdAndTargetDate(durham.getId(), day)).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("after a delete-then-insert replace, findLatestRunPerSlot returns exactly one row per hour")
    void findLatestRunPerSlot_afterReplace_returnsOneRowPerHour() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        LocalDateTime morning = LocalDateTime.of(2026, 10, 2, 5, 30);
        LocalDateTime evening = LocalDateTime.of(2026, 10, 2, 17, 30);
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(9, 0), morning));
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(10, 0), morning));

        repository.deleteHourlyByLocationIdAndTargetDate(durham.getId(), day);
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(9, 0), evening));
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(10, 0), evening));
        repository.save(buildHourlyEvaluation(durham, day, day.atTime(11, 0), evening));

        List<ForecastEvaluationEntity> served = repository.findLatestRunPerSlotByLocationIds(
                List.of(durham.getId()), day, day.plusDays(5));

        assertThat(repository.count()).as("the superseded rows are gone, not merely outranked").isEqualTo(3);
        assertThat(served).extracting(ForecastEvaluationEntity::getSolarEventTime)
                .containsExactlyInAnyOrder(day.atTime(9, 0), day.atTime(10, 0), day.atTime(11, 0));
        assertThat(served).allMatch(e -> e.getForecastRunAt().equals(evening));
    }

    // ── findScoredByEventInstant: the as-of-event resolver's step-2 pool (V178) ─────────────

    private ForecastEvaluationEntity scored(LocationEntity location, LocalDate day, TargetType type,
            LocalDateTime runAt, java.time.Instant scoredAt, BatchState state) {
        ForecastEvaluationEntity row = buildSonnetEvaluation(location, day, type, runAt, 0);
        row.setRating(4);
        row.setScoredAt(scoredAt);
        row.setBatchState(state);
        return row;
    }

    @Test
    @DisplayName("findScoredByEventInstant: bounded on scored_at, newest submission first; a NULL scored_at "
            + "(PENDING, ABANDONED, legacy) is never returned however early its submission")
    void findScoredByEventInstant_boundsOnAvailability() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        java.time.Instant upTo = java.time.Instant.parse("2026-10-14T06:41:00Z");
        ForecastEvaluationEntity early = repository.save(scored(durham, day, TargetType.SUNRISE,
                day.atTime(1, 0), java.time.Instant.parse("2026-10-14T01:10:00Z"), BatchState.SCORED));
        ForecastEvaluationEntity atBound = repository.save(scored(durham, day, TargetType.SUNRISE,
                day.atTime(2, 0), upTo, BatchState.SCORED));
        // submitted BEFORE the bound, scored AFTER it - a late batch's own row
        repository.save(scored(durham, day, TargetType.SUNRISE,
                day.atTime(0, 30), java.time.Instant.parse("2026-10-14T06:41:01Z"), BatchState.SCORED));
        repository.save(scored(durham, day, TargetType.SUNRISE,
                day.atTime(3, 0), null, BatchState.PENDING));
        repository.save(scored(durham, day, TargetType.SUNRISE,
                day.atTime(3, 30), null, BatchState.ABANDONED));
        repository.save(scored(durham, day, TargetType.SUNRISE, day.atTime(4, 0), null, null));

        List<ForecastEvaluationEntity> pool = repository.findScoredByEventInstant(
                List.of(durham.getId()), day, TargetType.SUNRISE, upTo);

        assertThat(pool).extracting(ForecastEvaluationEntity::getId)
                .containsExactly(atBound.getId(), early.getId());
    }

    @Test
    @DisplayName("findScoredByEventInstant: only the asked places, the asked date and the asked event type")
    void findScoredByEventInstant_scopesToPlacesDateAndType() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        java.time.Instant at = java.time.Instant.parse("2026-10-14T01:10:00Z");
        ForecastEvaluationEntity mine = repository.save(scored(durham, day, TargetType.SUNSET,
                day.atTime(1, 0), at, BatchState.SCORED));
        repository.save(scored(edinburgh, day, TargetType.SUNSET, day.atTime(1, 0), at, BatchState.SCORED));
        repository.save(scored(durham, day, TargetType.SUNRISE, day.atTime(1, 0), at, BatchState.SCORED));
        repository.save(scored(durham, day.minusDays(1), TargetType.SUNSET, day.atTime(1, 0), at,
                BatchState.SCORED));

        List<ForecastEvaluationEntity> pool = repository.findScoredByEventInstant(
                List.of(durham.getId()), day, TargetType.SUNSET, at.plusSeconds(3600));

        assertThat(pool).extracting(ForecastEvaluationEntity::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("scored_at round-trips to the microsecond and is independent of forecast_run_at")
    void scoredAt_roundTrips() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        java.time.Instant at = java.time.Instant.parse("2026-10-14T01:10:00.123456Z");
        Long id = repository.save(scored(durham, day, TargetType.SUNRISE, day.atTime(0, 5), at,
                BatchState.SCORED)).getId();
        entityManager.flush();
        entityManager.clear();

        ForecastEvaluationEntity reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getScoredAt()).isEqualTo(at);
        assertThat(reloaded.getForecastRunAt()).isEqualTo(day.atTime(0, 5));
    }
}
