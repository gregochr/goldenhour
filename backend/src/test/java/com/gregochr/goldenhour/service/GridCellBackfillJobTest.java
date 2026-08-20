package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GridCellBackfillJob}.
 */
@ExtendWith(MockitoExtension.class)
class GridCellBackfillJobTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationEnrichmentService locationEnrichmentService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private GridCellBackfillJob job;

    @BeforeEach
    void setUp() {
        job = new GridCellBackfillJob(locationRepository, locationEnrichmentService,
                dynamicSchedulerService);
    }

    @Test
    @DisplayName("registers itself with the dynamic scheduler under the key V146 seeds")
    void registerJob_registersUnderTheSeededKey() {
        job.registerJob();

        // The literal is asserted, not GridCellBackfillJob.JOB_KEY, because the key is the join
        // between this class and the scheduler_job_config row V146 seeds. Asserting the constant
        // against itself would pass after a rename that leaves the seeded schedule pointing at
        // nothing — and a schedule with no target fires silently forever, with no error.
        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService)
                .registerJobTarget(eq("grid_cell_backfill"), target.capture());

        // A method reference is a fresh object every time, so equality proves nothing. Run what
        // was actually registered and confirm it is this job rather than some other lambda.
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(List.of());
        target.getValue().run();
        verify(locationRepository).findByGridLatIsNullOrGridLngIsNullOrderByNameAsc();
    }

    @Test
    @DisplayName("fills the cell for every location missing one")
    void runScheduled_fillsMissingCells() {
        LocationEntity penshaw = entity("Penshaw Monument", 54.8830, -1.4809);
        LocationEntity roker = entity("Roker Pier & Lighthouse", 54.9210, -1.3620);
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(new ArrayList<>(List.of(penshaw, roker)));
        when(locationEnrichmentService.fetchGridCell(54.8830, -1.4809))
                .thenReturn(new double[]{54.9, -1.5});
        when(locationEnrichmentService.fetchGridCell(54.9210, -1.3620))
                .thenReturn(new double[]{54.95, -1.35});

        job.runScheduled();

        assertThat(penshaw.getGridLat()).isEqualTo(54.9);
        assertThat(penshaw.getGridLng()).isEqualTo(-1.5);
        assertThat(roker.getGridLat()).isEqualTo(54.95);
        assertThat(roker.getGridLng()).isEqualTo(-1.35);
        verify(locationRepository).save(penshaw);
        verify(locationRepository).save(roker);
    }

    @Test
    @DisplayName("makes no Open-Meteo call when every location already has a cell")
    void runScheduled_nothingPending_makesNoCall() {
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(List.of());

        job.runScheduled();

        // The steady state. A nightly job that is normally a no-op must actually cost nothing.
        verifyNoInteractions(locationEnrichmentService);
        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("steps over a single failure and still repairs the rest of the backlog")
    void runScheduled_oneFailure_continues() {
        LocationEntity bad = entity("Bad Coordinates", 1.0, 1.0);
        LocationEntity good = entity("Penshaw Monument", 54.8830, -1.4809);
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(new ArrayList<>(List.of(bad, good)));
        when(locationEnrichmentService.fetchGridCell(1.0, 1.0)).thenReturn(null);
        when(locationEnrichmentService.fetchGridCell(54.8830, -1.4809))
                .thenReturn(new double[]{54.9, -1.5});

        job.runScheduled();

        assertThat(bad.hasGridCell()).isFalse();
        assertThat(good.getGridLat()).isEqualTo(54.9);
        verify(locationRepository, never()).save(bad);
        verify(locationRepository).save(good);
    }

    @Test
    @DisplayName("a thrown lookup is treated as a failure, not an aborted run")
    void runScheduled_lookupThrows_isContained() {
        LocationEntity throwing = entity("Throws", 1.0, 1.0);
        LocationEntity good = entity("Penshaw Monument", 54.8830, -1.4809);
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(new ArrayList<>(List.of(throwing, good)));
        when(locationEnrichmentService.fetchGridCell(1.0, 1.0))
                .thenThrow(new RuntimeException("connection reset"));
        when(locationEnrichmentService.fetchGridCell(54.8830, -1.4809))
                .thenReturn(new double[]{54.9, -1.5});

        job.runScheduled();

        // fetchGridCell already swallows its own exceptions, so this guards the contract rather
        // than today's implementation — an unchecked throw must not take the scheduler thread down
        // or strand the rest of the backlog.
        assertThat(good.getGridLat()).isEqualTo(54.9);
        verify(locationRepository).save(good);
    }

    @Test
    @DisplayName("abandons the run after five consecutive failures, keeping what it filled")
    void runScheduled_outage_abortsAfterConsecutiveFailures() {
        LocationEntity first = entity("A First", 54.0, -1.0);
        List<LocationEntity> pending = new ArrayList<>();
        pending.add(first);
        IntStream.range(0, 8).forEach(i -> pending.add(entity("Down " + i, 55.0 + i, -2.0)));
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(pending);
        when(locationEnrichmentService.fetchGridCell(54.0, -1.0))
                .thenReturn(new double[]{54.1, -1.1});
        when(locationEnrichmentService.fetchGridCell(anyDouble(), eq(-2.0)))
                .thenReturn(null);

        job.runScheduled();

        // The one success before the outage is kept...
        verify(locationRepository).save(first);
        // ...and the run stops after the 5th consecutive failure rather than working through the
        // remaining 3, which during an outage is three more pointless calls per night.
        verify(locationEnrichmentService, times(6)).fetchGridCell(anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("a success between failures resets the consecutive counter")
    void runScheduled_intermittentFailures_doNotAbort() {
        List<LocationEntity> pending = new ArrayList<>();
        // fail, fail, fail, fail, SUCCESS, fail, fail, fail, fail — nine attempts, never five in a
        // row. Holds "nine failures-or-not" constant against the previous test and varies only
        // their adjacency, so the test pins the CONSECUTIVE clause rather than a total count.
        for (int i = 0; i < 4; i++) {
            pending.add(entity("Fail " + i, 55.0 + i, -2.0));
        }
        LocationEntity success = entity("Works", 54.0, -1.0);
        pending.add(success);
        for (int i = 4; i < 8; i++) {
            pending.add(entity("Fail " + i, 55.0 + i, -2.0));
        }
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(pending);
        when(locationEnrichmentService.fetchGridCell(54.0, -1.0))
                .thenReturn(new double[]{54.1, -1.1});
        when(locationEnrichmentService.fetchGridCell(anyDouble(), eq(-2.0)))
                .thenReturn(null);

        job.runScheduled();

        verify(locationEnrichmentService, times(9)).fetchGridCell(anyDouble(), anyDouble());
        verify(locationRepository).save(success);
    }

    @Test
    @DisplayName("caps the work per run and leaves the remainder for the next tick")
    void runScheduled_hugeBacklog_isCapped() {
        List<LocationEntity> pending = new ArrayList<>();
        IntStream.range(0, GridCellBackfillJob.MAX_PER_RUN + 25)
                .forEach(i -> pending.add(entity("Loc " + i, 54.0, -1.0)));
        when(locationRepository.findByGridLatIsNullOrGridLngIsNullOrderByNameAsc())
                .thenReturn(pending);
        when(locationEnrichmentService.fetchGridCell(54.0, -1.0))
                .thenReturn(new double[]{54.1, -1.1});

        job.runScheduled();

        ArgumentCaptor<LocationEntity> saved = ArgumentCaptor.forClass(LocationEntity.class);
        verify(locationRepository, times(GridCellBackfillJob.MAX_PER_RUN)).save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(GridCellBackfillJob.MAX_PER_RUN);
        // The tail is untouched, not silently dropped — it is still missing a cell, so the next
        // run's query returns it again.
        assertThat(pending.get(GridCellBackfillJob.MAX_PER_RUN).hasGridCell()).isFalse();
    }

    private LocationEntity entity(String name, double lat, double lon) {
        return LocationEntity.builder().name(name).lat(lat).lon(lon).build();
    }
}
