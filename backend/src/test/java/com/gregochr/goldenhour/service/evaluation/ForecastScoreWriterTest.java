package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.service.evaluation.visitor.ComponentScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastScoreWriter} — the Pass 2 dual-write.
 *
 * <p>Verifies exactly which {@code forecast_score} rows are written for a scored evaluation
 * (the SKY/TIDAL combiner components plus the FIERY_SKY/GOLDEN_HOUR display products), the
 * UPSERT-on-existing behaviour, provenance threading, the {@code HOURLY} exclusion, and the
 * feature-flag no-op. The repository is mocked; the real persistence (unique-key upsert against
 * the schema) is proven by the testcontainers integration test.
 */
@ExtendWith(MockitoExtension.class)
class ForecastScoreWriterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final Instant FIXED = Instant.parse("2026-06-21T17:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long LOCATION_ID = 7L;
    private static final Long PIPELINE_RUN_ID = 555L;

    @Mock
    private ForecastScoreRepository repository;

    private ForecastScoreWriter writer(boolean enabled) {
        return new ForecastScoreWriter(repository, CLOCK, enabled);
    }

    private static LocationEntity location() {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Berwick-Upon-Tweed");
        return location;
    }

    private static SunsetEvaluation eval() {
        return new SunsetEvaluation(3, 72, 68, "Clearing western sky");
    }

    /** Stubs the always-written component lookups (SKY + the two display products) as absent. */
    private void stubCoreComponentsAbsent() {
        when(repository.findComponent(eq(ForecastType.SKY), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
        when(repository.findComponent(
                eq(ForecastType.FIERY_SKY), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
        when(repository.findComponent(
                eq(ForecastType.GOLDEN_HOUR), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("flag off → writes nothing (whole-pass rollback path)")
    void flagOff_writesNothing() {
        ForecastScoreWriter writer = writer(false);
        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 3, "prose")), PIPELINE_RUN_ID);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("HOURLY event → writes nothing (wildlife comfort is never colour-evaluated)")
    void hourly_writesNothing() {
        ForecastScoreWriter writer = writer(true);
        writer.write(location(), DATE, TargetType.HOURLY, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 3, "prose")), PIPELINE_RUN_ID);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("inland: writes SKY + FIERY_SKY + GOLDEN_HOUR (no TIDAL); correct scores/summaries")
    void inland_writesSkyFieryGolden() {
        stubCoreComponentsAbsent();
        ForecastScoreWriter writer = writer(true);

        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 3, "Clearing western sky")),
                PIPELINE_RUN_ID);

        List<ForecastScoreEntity> saved = captureSaves(3);
        assertThat(saved).extracting(ForecastScoreEntity::getForecastType)
                .containsExactlyInAnyOrder(
                        ForecastType.SKY, ForecastType.FIERY_SKY, ForecastType.GOLDEN_HOUR);
        assertThat(saved).noneMatch(row -> row.getForecastType() == ForecastType.TIDAL);
        assertRow(saved, ForecastType.SKY, 3, "Clearing western sky");
        assertRow(saved, ForecastType.FIERY_SKY, 72, null);
        assertRow(saved, ForecastType.GOLDEN_HOUR, 68, null);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getEvaluationDate()).isEqualTo(DATE);
            assertThat(row.getEventType()).isEqualTo(SUNSET);
            assertThat(row.getLocation().getId()).isEqualTo(LOCATION_ID);
            assertThat(row.getPipelineRunId()).isEqualTo(PIPELINE_RUN_ID);
            assertThat(row.getEvaluatedAt()).isEqualTo(FIXED);
        });
    }

    @Test
    @DisplayName("coastal: writes the TIDAL row too, carrying its deterministic state clause")
    void coastal_writesTidalRowWithClause() {
        stubCoreComponentsAbsent();
        when(repository.findComponent(eq(ForecastType.TIDAL), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
        ForecastScoreWriter writer = writer(true);

        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 3, "Clearing western sky"),
                        new ComponentScore(ForecastType.TIDAL, 5,
                                "Spring tide aligns with the event — strong water movement")),
                PIPELINE_RUN_ID);

        List<ForecastScoreEntity> saved = captureSaves(4);
        assertThat(saved).extracting(ForecastScoreEntity::getForecastType)
                .containsExactlyInAnyOrder(ForecastType.SKY, ForecastType.TIDAL,
                        ForecastType.FIERY_SKY, ForecastType.GOLDEN_HOUR);
        ForecastScoreEntity tidal = saved.stream()
                .filter(row -> row.getForecastType() == ForecastType.TIDAL)
                .findFirst().orElseThrow();
        assertThat(tidal.getScore()).isEqualTo(5);
        assertThat(tidal.getSummary()).contains("Spring tide");
    }

    @Test
    @DisplayName("abstaining tide: no TIDAL component supplied → no TIDAL row")
    void abstainingTide_noTidalRow() {
        stubCoreComponentsAbsent();
        ForecastScoreWriter writer = writer(true);

        // The combiner already excluded the abstaining tide, so only SKY is in the components.
        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 4, "Settled high pressure")),
                PIPELINE_RUN_ID);

        List<ForecastScoreEntity> saved = captureSaves(3);
        assertThat(saved).noneMatch(row -> row.getForecastType() == ForecastType.TIDAL);
    }

    @Test
    @DisplayName("upsert: an existing component row is updated in place (latest score wins)")
    void upsert_updatesExistingRow() {
        ForecastScoreEntity existingSky = new ForecastScoreEntity();
        existingSky.setForecastType(ForecastType.SKY);
        existingSky.setLocation(location());
        existingSky.setEvaluationDate(DATE);
        existingSky.setEventType(SUNSET);
        existingSky.setScore(2);                 // a stale prior score
        existingSky.setSummary("stale prose");
        when(repository.findComponent(eq(ForecastType.SKY), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.of(existingSky));
        when(repository.findComponent(
                eq(ForecastType.FIERY_SKY), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
        when(repository.findComponent(
                eq(ForecastType.GOLDEN_HOUR), eq(LOCATION_ID), eq(DATE), eq(SUNSET)))
                .thenReturn(Optional.empty());
        ForecastScoreWriter writer = writer(true);

        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 5, "fresh prose")), PIPELINE_RUN_ID);

        // The SAME entity instance is re-saved with the new score/summary — an upsert, not a duplicate.
        verify(repository).save(existingSky);
        assertThat(existingSky.getScore()).isEqualTo(5);
        assertThat(existingSky.getSummary()).isEqualTo("fresh prose");
        assertThat(existingSky.getEvaluatedAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("sync/admin path: null pipeline_run_id is threaded onto every row")
    void nullPipelineRunId_threaded() {
        stubCoreComponentsAbsent();
        ForecastScoreWriter writer = writer(true);

        writer.write(location(), DATE, SUNSET, eval(),
                List.of(new ComponentScore(ForecastType.SKY, 3, "Clearing western sky")), null);

        List<ForecastScoreEntity> saved = captureSaves(3);
        assertThat(saved).allSatisfy(row -> assertThat(row.getPipelineRunId()).isNull());
    }

    private List<ForecastScoreEntity> captureSaves(int expected) {
        ArgumentCaptor<ForecastScoreEntity> captor =
                ArgumentCaptor.forClass(ForecastScoreEntity.class);
        verify(repository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private static void assertRow(List<ForecastScoreEntity> rows, ForecastType type,
            int score, String summary) {
        ForecastScoreEntity row = rows.stream()
                .filter(r -> r.getForecastType() == type)
                .findFirst().orElseThrow();
        assertThat(row.getScore()).isEqualTo(score);
        assertThat(row.getSummary()).isEqualTo(summary);
    }
}
