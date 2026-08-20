package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RegionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegionDriveTimeRefreshJob}.
 *
 * <p>The behaviours worth pinning are the ones that decide whether the shared matrix silently
 * goes stale: which regions are selected, and that one region's ORS failure does not abandon the
 * rest of the sweep.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegionDriveTimeRefreshJob")
class RegionDriveTimeRefreshJobTest {

    @Mock
    private RegionService regionService;

    @Mock
    private RegionDriveDurationService regionDriveDurationService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    @InjectMocks
    private RegionDriveTimeRefreshJob job;

    private static RegionEntity region(long id, String name, String base) {
        RegionEntity r = new RegionEntity();
        r.setId(id);
        r.setName(name);
        if (base != null) {
            r.setBaseName(base);
            r.setBaseLat(54.6);
            r.setBaseLon(-3.1);
        }
        return r;
    }

    @Test
    @DisplayName("registers the sweep itself against the job key V145 seeds — not a no-op")
    void registerJob_registersTheSweepUnderTheSeededKey() {
        // ⚠️ The captured Runnable is RUN. `verify(..., any())` would pass against
        // `registerJobTarget(JOB_KEY, () -> { })`: the 03:10 cron would fire nightly and do
        // nothing, the shared matrix would never refresh after the first base save, and a location
        // added later would have no row from any base indefinitely — which is the stated reason the
        // job exists. The seven direct `runScheduled()` tests below cannot see that.
        RegionEntity lakes = region(1L, "Lake District", "Keswick");
        when(regionService.findAll()).thenReturn(List.of(lakes));
        when(regionDriveDurationService.refreshForRegion(lakes)).thenReturn(61);

        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService).registerJobTarget(eq("region_drive_time_refresh"),
                target.capture());
        target.getValue().run();

        verify(regionDriveDurationService).refreshForRegion(lakes);
    }

    @Test
    @DisplayName("refreshes only the regions that have a base town")
    void runScheduled_skipsBaselessRegions() {
        RegionEntity lakes = region(1L, "Lake District", "Keswick");
        RegionEntity none = region(2L, "North East", null);
        when(regionService.findAll()).thenReturn(List.of(lakes, none));
        when(regionDriveDurationService.refreshForRegion(lakes)).thenReturn(61);

        job.runScheduled();

        verify(regionDriveDurationService).refreshForRegion(lakes);
        verify(regionDriveDurationService, never()).refreshForRegion(none);
    }

    @Test
    @DisplayName("does nothing at all when no region has a base")
    void runScheduled_noBases_doesNothing() {
        when(regionService.findAll()).thenReturn(List.of(region(2L, "North East", null)));

        job.runScheduled();

        verifyNoInteractions(regionDriveDurationService);
    }

    @Test
    @DisplayName("a disabled region with a base is still refreshed — hidden from dropdowns is not "
            + "the same as unplannable")
    void runScheduled_disabledRegionWithBase_isRefreshed() {
        RegionEntity lakes = region(1L, "Lake District", "Keswick");
        lakes.setEnabled(false);
        when(regionService.findAll()).thenReturn(List.of(lakes));
        when(regionDriveDurationService.refreshForRegion(lakes)).thenReturn(61);

        job.runScheduled();

        verify(regionDriveDurationService).refreshForRegion(lakes);
    }

    @Test
    @DisplayName("one region's failure does not abandon the sweep")
    void runScheduled_oneFailure_stillRefreshesTheRest() {
        RegionEntity lakes = region(1L, "Lake District", "Keswick");
        RegionEntity peak = region(2L, "Peak District", "Bakewell");
        RegionEntity borders = region(3L, "Borders", "Kelso");
        when(regionService.findAll()).thenReturn(List.of(lakes, peak, borders));
        when(regionDriveDurationService.refreshForRegion(lakes)).thenReturn(61);
        when(regionDriveDurationService.refreshForRegion(peak))
                .thenThrow(new IllegalStateException("ORS 429"));
        when(regionDriveDurationService.refreshForRegion(borders)).thenReturn(61);

        job.runScheduled();

        verify(regionDriveDurationService).refreshForRegion(lakes);
        verify(regionDriveDurationService).refreshForRegion(peak);
        verify(regionDriveDurationService).refreshForRegion(borders);
    }

    @Test
    @DisplayName("a region writing zero rows does not stop the ones after it")
    void runScheduled_zeroRows_stillContinues() {
        RegionEntity lakes = region(1L, "Lake District", "Keswick");
        RegionEntity peak = region(2L, "Peak District", "Bakewell");
        when(regionService.findAll()).thenReturn(List.of(lakes, peak));
        when(regionDriveDurationService.refreshForRegion(lakes)).thenReturn(0);
        when(regionDriveDurationService.refreshForRegion(peak)).thenReturn(61);

        job.runScheduled();

        verify(regionDriveDurationService).refreshForRegion(peak);
    }

    @Test
    @DisplayName("a base whose name is present but whose coordinates are not is not swept")
    void runScheduled_partialBase_isSkipped() {
        RegionEntity partial = region(1L, "Lake District", "Keswick");
        partial.setBaseLon(null);
        when(regionService.findAll()).thenReturn(List.of(partial));

        job.runScheduled();

        verifyNoInteractions(regionDriveDurationService);
    }

    @Test
    @DisplayName("an empty region list is not an error")
    void runScheduled_noRegions_doesNothing() {
        when(regionService.findAll()).thenReturn(List.of());

        job.runScheduled();

        verifyNoInteractions(regionDriveDurationService);
    }
}
