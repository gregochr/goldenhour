package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.StabilitySnapshotEntity;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.repository.StabilitySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StabilitySnapshotProvider}. Covers the read path
 * (in-memory hit, DB fallback, staleness guard, cache warm, error tolerance)
 * and the write path ({@link StabilitySnapshotProvider#update update}'s
 * insert/upsert behaviour, in-memory publication, and DB-failure tolerance).
 */
@ExtendWith(MockitoExtension.class)
class StabilitySnapshotProviderTest {

    @Mock
    private StabilitySnapshotRepository repository;

    private StabilitySnapshotProvider provider;

    private static final String CELL_KEY = "54.7500,-1.6250";

    @BeforeEach
    void setUp() {
        provider = new StabilitySnapshotProvider(repository);
    }

    private static StabilitySummaryResponse buildSummary(Instant generatedAt) {
        var cell = new StabilitySummaryResponse.GridCellDetail(
                CELL_KEY, 54.75, -1.625,
                ForecastStability.SETTLED, "high pressure dominant", 3,
                List.of("Durham UK", "Penshaw Monument"));
        return new StabilitySummaryResponse(
                generatedAt, 1,
                Map.of(ForecastStability.SETTLED, 1L),
                List.of(cell));
    }

    private static StabilitySnapshotEntity buildDbEntity(
            String key, ForecastStability level, Instant classifiedAt) {
        var entity = new StabilitySnapshotEntity();
        entity.setId(1L);
        entity.setGridCellKey(key);
        entity.setGridLat(54.75);
        entity.setGridLng(-1.625);
        entity.setStabilityLevel(level);
        entity.setReason("high pressure dominant");
        entity.setEvaluationWindowDays(level.evaluationWindowDays());
        entity.setLocationNames("Durham UK,Penshaw Monument");
        entity.setClassifiedAt(classifiedAt);
        entity.setUpdatedAt(classifiedAt);
        return entity;
    }

    // -------------------------------------------------------------------------
    // Read path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getLatestStabilitySummary")
    class ReadPath {

        @Test
        @DisplayName("Returns in-memory value when set (no DB query)")
        void returnsInMemoryWhenSet() {
            // Warm in-memory by going through the DB fallback first.
            when(repository.findByClassifiedAtAfter(any()))
                    .thenReturn(List.of(buildDbEntity(CELL_KEY,
                            ForecastStability.SETTLED, Instant.now())));

            StabilitySummaryResponse first = provider.getLatestStabilitySummary();
            assertThat(first).isNotNull();

            // Second call should not hit the DB again.
            Mockito.clearInvocations(repository);
            StabilitySummaryResponse second = provider.getLatestStabilitySummary();
            assertThat(second).isNotNull();
            verify(repository, never()).findByClassifiedAtAfter(any());
        }

        @Test
        @DisplayName("Returns DB snapshot when in-memory is null and DB snapshot is fresh")
        void returnsDbSnapshotWhenFresh() {
            var entity = buildDbEntity(CELL_KEY,
                    ForecastStability.SETTLED, Instant.now().minus(2, ChronoUnit.HOURS));
            when(repository.findByClassifiedAtAfter(any())).thenReturn(List.of(entity));

            StabilitySummaryResponse result = provider.getLatestStabilitySummary();

            assertThat(result).isNotNull();
            assertThat(result.cells()).hasSize(1);
            assertThat(result.cells().get(0).stability()).isEqualTo(ForecastStability.SETTLED);
            assertThat(result.cells().get(0).locationNames())
                    .containsExactly("Durham UK", "Penshaw Monument");
        }

        @Test
        @DisplayName("Returns null when in-memory is null and DB has no rows")
        void returnsNullWhenNoDbRows() {
            when(repository.findByClassifiedAtAfter(any())).thenReturn(List.of());

            assertThat(provider.getLatestStabilitySummary()).isNull();
        }

        @Test
        @DisplayName("Returns null when in-memory is null and DB snapshot is stale (>24h)")
        void returnsNullWhenDbStale() {
            // The 24h staleness guard is enforced server-side via findByClassifiedAtAfter,
            // which returns no rows for stale data.
            when(repository.findByClassifiedAtAfter(any())).thenReturn(List.of());

            assertThat(provider.getLatestStabilitySummary()).isNull();
        }

        @Test
        @DisplayName("DB load warms in-memory cache for subsequent calls")
        void dbLoadWarmsCacheForSubsequentCalls() {
            var entity = buildDbEntity(CELL_KEY,
                    ForecastStability.TRANSITIONAL, Instant.now());
            when(repository.findByClassifiedAtAfter(any())).thenReturn(List.of(entity));

            StabilitySummaryResponse first = provider.getLatestStabilitySummary();
            assertThat(first).isNotNull();
            verify(repository, times(1)).findByClassifiedAtAfter(any());

            Mockito.clearInvocations(repository);
            StabilitySummaryResponse second = provider.getLatestStabilitySummary();
            assertThat(second).isSameAs(first);
            verify(repository, never()).findByClassifiedAtAfter(any());
        }

        @Test
        @DisplayName("DB read failure returns null gracefully")
        void dbReadFailureReturnsNull() {
            when(repository.findByClassifiedAtAfter(any()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThat(provider.getLatestStabilitySummary()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Write path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class WritePath {

        @Test
        @DisplayName("Saves one entity per grid cell with correct fields when row is new")
        void savesEntityPerGridCellWithCorrectFields() {
            when(repository.findByGridCellKey(CELL_KEY)).thenReturn(Optional.empty());

            provider.update(buildSummary(Instant.now()));

            ArgumentCaptor<StabilitySnapshotEntity> captor =
                    ArgumentCaptor.forClass(StabilitySnapshotEntity.class);
            verify(repository).save(captor.capture());

            StabilitySnapshotEntity saved = captor.getValue();
            assertThat(saved.getGridCellKey()).isEqualTo(CELL_KEY);
            assertThat(saved.getStabilityLevel()).isEqualTo(ForecastStability.SETTLED);
            assertThat(saved.getGridLat()).isEqualTo(54.75);
            assertThat(saved.getGridLng()).isEqualTo(-1.625);
            assertThat(saved.getReason()).isEqualTo("high pressure dominant");
            assertThat(saved.getLocationNames()).isEqualTo("Durham UK,Penshaw Monument");
            assertThat(saved.getClassifiedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
            assertThat(saved.getEvaluationWindowDays()).isEqualTo(3);
        }

        @Test
        @DisplayName("Upserts existing entity rather than creating a new one")
        void updatesExistingEntity() {
            StabilitySnapshotEntity existing = new StabilitySnapshotEntity();
            existing.setId(42L);
            existing.setGridCellKey(CELL_KEY);
            existing.setStabilityLevel(ForecastStability.SETTLED);
            existing.setReason("Old reason");
            when(repository.findByGridCellKey(CELL_KEY)).thenReturn(Optional.of(existing));

            // New classification overwrites the existing row.
            var cell = new StabilitySummaryResponse.GridCellDetail(
                    CELL_KEY, 54.75, -1.625,
                    ForecastStability.TRANSITIONAL, "Frontal passage", 1,
                    List.of("Durham UK"));
            provider.update(new StabilitySummaryResponse(
                    Instant.now(), 1,
                    Map.of(ForecastStability.TRANSITIONAL, 1L),
                    List.of(cell)));

            ArgumentCaptor<StabilitySnapshotEntity> captor =
                    ArgumentCaptor.forClass(StabilitySnapshotEntity.class);
            verify(repository).save(captor.capture());

            StabilitySnapshotEntity saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(42L);
            assertThat(saved.getStabilityLevel()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(saved.getReason()).isEqualTo("Frontal passage");
            assertThat(saved.getEvaluationWindowDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("DB persist failure does not propagate — non-fatal")
        void persistFailureIsNonFatal() {
            when(repository.findByGridCellKey(CELL_KEY)).thenReturn(Optional.empty());
            when(repository.save(any())).thenThrow(new RuntimeException("DB write failure"));

            assertThatCode(() -> provider.update(buildSummary(Instant.now())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("In-memory cache is published before DB persist (readers see fresh value "
                + "even if persist fails)")
        void inMemoryPublishedEvenIfPersistFails() {
            when(repository.findByGridCellKey(CELL_KEY)).thenReturn(Optional.empty());
            when(repository.save(any())).thenThrow(new RuntimeException("DB write failure"));

            StabilitySummaryResponse summary = buildSummary(Instant.now());
            provider.update(summary);

            // Subsequent read returns the in-memory value without falling through to DB.
            Mockito.clearInvocations(repository);
            StabilitySummaryResponse cached = provider.getLatestStabilitySummary();
            assertThat(cached).isSameAs(summary);
            verify(repository, never()).findByClassifiedAtAfter(any());
        }

        @Test
        @DisplayName("Null summary is a no-op — neither in-memory nor DB are touched")
        void nullSummaryIsNoOp() {
            provider.update(null);

            verify(repository, never()).findByGridCellKey(any());
            verify(repository, never()).save(any());
            assertThat(provider.getLatestStabilitySummary()).isNull();
        }

        @Test
        @DisplayName("Persists every cell when summary contains multiple grid cells")
        void persistsEveryCellInSummary() {
            var cellA = new StabilitySummaryResponse.GridCellDetail(
                    "54.7500,-1.6250", 54.75, -1.625,
                    ForecastStability.SETTLED, "high pressure dominant", 3,
                    List.of("Durham UK"));
            var cellB = new StabilitySummaryResponse.GridCellDetail(
                    "55.0000,-1.6250", 55.0, -1.625,
                    ForecastStability.TRANSITIONAL, "Frontal passage approaching", 1,
                    List.of("Whitley Bay"));
            when(repository.findByGridCellKey(any())).thenReturn(Optional.empty());

            provider.update(new StabilitySummaryResponse(
                    Instant.now(), 2,
                    Map.of(ForecastStability.SETTLED, 1L,
                            ForecastStability.TRANSITIONAL, 1L),
                    List.of(cellA, cellB)));

            verify(repository, times(2)).save(any());
        }
    }
}
