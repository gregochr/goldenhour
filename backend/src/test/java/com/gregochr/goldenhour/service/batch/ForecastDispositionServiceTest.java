package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.DispositionBreakdownResponse;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastDispositionService}. The service is a thin
 * persistence/retention adapter on top of {@link ForecastRunDispositionRepository}
 * — the interesting behaviour is the per-row mapping done by {@link
 * ForecastDispositionService#persist} and the no-op short-circuits.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDispositionServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Mock
    private ForecastRunDispositionRepository repository;

    private ForecastDispositionService service;

    @BeforeEach
    void setUp() {
        service = new ForecastDispositionService(repository);
    }

    @Test
    @DisplayName("persist: maps every CandidateDisposition field onto the entity")
    void persist_mapsAllFieldsCorrectly() {
        CandidateDisposition d = new CandidateDisposition(
                42L, "Durham UK", TODAY.plusDays(1), TargetType.SUNRISE, 1,
                DispositionCategory.EVALUATED, null);

        service.persist(7L, List.of(d));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastRunDispositionEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<ForecastRunDispositionEntity> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        ForecastRunDispositionEntity e = saved.get(0);
        assertThat(e.getJobRunId()).isEqualTo(7L);
        assertThat(e.getLocationId()).isEqualTo(42L);
        assertThat(e.getLocationName()).isEqualTo("Durham UK");
        assertThat(e.getEvaluationDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(e.getEventType()).isEqualTo("SUNRISE");
        assertThat(e.getDaysAhead()).isEqualTo(1);
        assertThat(e.getDisposition()).isEqualTo("EVALUATED");
        assertThat(e.getDetail()).isNull();
    }

    @Test
    @DisplayName("persist: skip detail is truncated to 500 chars to fit VARCHAR(500)")
    void persist_truncatesLongDetail() {
        String longDetail = "x".repeat(600);
        CandidateDisposition d = new CandidateDisposition(
                42L, "Loc", TODAY, TargetType.SUNRISE, 0,
                DispositionCategory.SKIPPED_TRIAGED, longDetail);

        service.persist(1L, List.of(d));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastRunDispositionEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getDetail()).hasSize(500);
    }

    @Test
    @DisplayName("persist: null jobRunId → no-op (no repo interaction)")
    void persist_nullJobRunId_noOp() {
        CandidateDisposition d = new CandidateDisposition(
                42L, "Loc", TODAY, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);

        service.persist(null, List.of(d));

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("persist: empty list → no-op (no repo interaction)")
    void persist_emptyList_noOp() {
        service.persist(7L, List.of());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("persist: null list → no-op (no repo interaction)")
    void persist_nullList_noOp() {
        service.persist(7L, null);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("persist: every disposition row carries the same jobRunId")
    void persist_allRowsCarrySameJobRunId() {
        CandidateDisposition a = new CandidateDisposition(
                42L, "A", TODAY, TargetType.SUNRISE, 0,
                DispositionCategory.EVALUATED, null);
        CandidateDisposition b = new CandidateDisposition(
                43L, "B", TODAY, TargetType.SUNSET, 0,
                DispositionCategory.SKIPPED_TRIAGED, "cloud");
        CandidateDisposition c = new CandidateDisposition(
                null, "C", TODAY.minusDays(1), TargetType.SUNRISE, -1,
                DispositionCategory.SKIPPED_PAST_DATE, "Date in past");

        service.persist(99L, List.of(a, b, c));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastRunDispositionEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(ForecastRunDispositionEntity::getJobRunId)
                .containsOnly(99L);
    }

    @Test
    @DisplayName("pruneStale: deletes rows with createdAt older than retention window")
    void pruneStale_deletesByCutoff() {
        when(repository.deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(42);

        service.pruneStale();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByCreatedAtBefore(captor.capture());
        Instant cutoff = captor.getValue();
        long actualDaysAgo = java.time.Duration.between(cutoff, Instant.now()).toDays();
        // Allow ±1 day slack for clock granularity / test timing
        assertThat(actualDaysAgo)
                .isBetween((long) ForecastDispositionService.RETENTION_DAYS - 1L,
                        (long) ForecastDispositionService.RETENTION_DAYS + 1L);
    }

    @Test
    @DisplayName("pruneStale: zero matching rows still calls repository (no special-case)")
    void pruneStale_zeroDeletes_stillCalled() {
        when(repository.deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        service.pruneStale();

        verify(repository).deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("persist: never calls saveAll when nothing to save")
    void persist_neverCallsSaveAllWhenEmpty() {
        service.persist(null, null);
        service.persist(1L, List.of());

        verify(repository, never()).saveAll(anyList());
    }

    // ── getBreakdownForJobRun (read API backing Job Run detail UI) ────────────

    @Test
    @DisplayName("getBreakdownForJobRun: groups by disposition, totals across rows, "
            + "exposes every field of each entry")
    void getBreakdownForJobRun_groupsAndExposesAllFields() {
        ForecastRunDispositionEntity evaluated = ForecastRunDispositionEntity.builder()
                .id(1L).jobRunId(348L).locationId(42L).locationName("Durham UK")
                .evaluationDate(TODAY.plusDays(1)).eventType("SUNRISE").daysAhead(1)
                .disposition("EVALUATED").detail(null).build();
        ForecastRunDispositionEntity triaged = ForecastRunDispositionEntity.builder()
                .id(2L).jobRunId(348L).locationId(43L).locationName("Newcastle")
                .evaluationDate(TODAY).eventType("SUNRISE").daysAhead(0)
                .disposition("SKIPPED_TRIAGED")
                .detail("Solar horizon low cloud 94% — sun blocked").build();
        ForecastRunDispositionEntity triaged2 = ForecastRunDispositionEntity.builder()
                .id(3L).jobRunId(348L).locationId(44L).locationName("Whitby")
                .evaluationDate(TODAY).eventType("SUNRISE").daysAhead(0)
                .disposition("SKIPPED_TRIAGED").detail("Heavy cloud").build();
        when(repository.findByJobRunIdOrderByDispositionAscLocationNameAsc(348L))
                .thenReturn(List.of(evaluated, triaged, triaged2));

        DispositionBreakdownResponse response = service.getBreakdownForJobRun(348L);

        assertThat(response.jobRunId()).isEqualTo(348L);
        assertThat(response.totalCount()).isEqualTo(3);
        assertThat(response.countsByDisposition())
                .containsEntry("EVALUATED", 1L)
                .containsEntry("SKIPPED_TRIAGED", 2L);
        assertThat(response.entries()).hasSize(3);
        DispositionBreakdownResponse.DispositionEntry firstEntry = response.entries().get(0);
        assertThat(firstEntry.locationName()).isEqualTo("Durham UK");
        assertThat(firstEntry.locationId()).isEqualTo(42L);
        assertThat(firstEntry.disposition()).isEqualTo("EVALUATED");
        assertThat(firstEntry.detail()).isNull();
        assertThat(firstEntry.daysAhead()).isEqualTo(1);
        DispositionBreakdownResponse.DispositionEntry secondEntry = response.entries().get(1);
        assertThat(secondEntry.disposition()).isEqualTo("SKIPPED_TRIAGED");
        assertThat(secondEntry.detail()).isEqualTo("Solar horizon low cloud 94% — sun blocked");
    }

    @Test
    @DisplayName("getBreakdownForJobRun: no rows → empty-but-well-formed response, "
            + "not null")
    void getBreakdownForJobRun_noRows_returnsEmptyResponse() {
        // Non-batch job runs (e.g. tide refresh, weather refresh) and the cycle's
        // 2nd/3rd/4th bucket job runs have no disposition rows. The UI uses the
        // zero count to decide whether to render the section — so the response
        // must be well-formed, not null/404.
        when(repository.findByJobRunIdOrderByDispositionAscLocationNameAsc(999L))
                .thenReturn(List.of());

        DispositionBreakdownResponse response = service.getBreakdownForJobRun(999L);

        assertThat(response).isNotNull();
        assertThat(response.jobRunId()).isEqualTo(999L);
        assertThat(response.totalCount()).isZero();
        assertThat(response.countsByDisposition()).isEmpty();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    @DisplayName("getBreakdownForJobRun: tolerates unknown future disposition values")
    void getBreakdownForJobRun_unknownDisposition_passesThrough() {
        // Forward-compat: a newer deployment writes "SKIPPED_NO_REFRESH_NEEDED"
        // (or any other future value). The read path must surface it as a String
        // in the response without crashing — the UI can show it under an
        // "Unknown" bucket.
        ForecastRunDispositionEntity futureRow = ForecastRunDispositionEntity.builder()
                .id(1L).jobRunId(500L).locationId(42L).locationName("Durham UK")
                .evaluationDate(TODAY).eventType("SUNRISE").daysAhead(0)
                .disposition("SKIPPED_NO_REFRESH_NEEDED")
                .detail("cached evaluation still fresh under intraday threshold").build();
        when(repository.findByJobRunIdOrderByDispositionAscLocationNameAsc(500L))
                .thenReturn(List.of(futureRow));

        DispositionBreakdownResponse response = service.getBreakdownForJobRun(500L);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.countsByDisposition())
                .containsEntry("SKIPPED_NO_REFRESH_NEEDED", 1L);
        assertThat(response.entries().get(0).disposition())
                .isEqualTo("SKIPPED_NO_REFRESH_NEEDED");
    }
}
