package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
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
}
