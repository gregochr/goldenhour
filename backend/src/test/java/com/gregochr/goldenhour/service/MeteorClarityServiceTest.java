package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeteorClarityService} — the per-briefing overhead-clarity scan that only
 * fetches on shower-peak nights and counts how many dark-sky locations are clear overhead.
 */
@ExtendWith(MockitoExtension.class)
class MeteorClarityServiceTest {

    private static final LocalDate PERSEIDS_PEAK = LocalDate.of(2026, 8, 12);
    /** The deep-night sample hour: solar-midnight UTC on the morning after the peak evening. */
    private static final String PEAK_HOUR = "2026-08-13T00:00";

    @Mock
    private OverheadCloudSampler overheadCloudSampler;

    @Mock
    private LocationRepository locationRepository;

    private MeteorClarityService service;

    @BeforeEach
    void setUp() {
        service = new MeteorClarityService(overheadCloudSampler, locationRepository);
    }

    private static LocationEntity darkSky(long id) {
        return LocationEntity.builder().id(id).name("L" + id).build();
    }

    @Test
    @DisplayName("no shower peak in the window: caches empty and makes no fetch")
    void refresh_noPeakInWindow_emptyNoFetch() {
        service.refresh(List.of(
                LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19)));

        assertThat(service.getCached().byNight()).isEmpty();
        verifyNoInteractions(overheadCloudSampler, locationRepository);
    }

    @Test
    @DisplayName("shower peak in window: counts dark-sky locations clear overhead at the peak hour")
    void refresh_showerPeak_countsClearOverhead() {
        LocationEntity clear1 = darkSky(1);
        LocationEntity cloudy = darkSky(2);
        LocationEntity clear2 = darkSky(3);
        List<LocationEntity> darkSky = List.of(clear1, cloudy, clear2);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSky);
        // Below 75 = clear; the eq() on the hour key pins that we sample the peak night's deep hour.
        when(overheadCloudSampler.sample(eq(darkSky), eq(List.of(PEAK_HOUR)))).thenReturn(Map.of(
                clear1, new int[]{40}, cloudy, new int[]{80}, clear2, new int[]{20}));

        service.refresh(List.of(LocalDate.of(2026, 8, 11), PERSEIDS_PEAK, LocalDate.of(2026, 8, 13)));

        MeteorClarity.NightClarity night = service.getCached().forNight(PERSEIDS_PEAK).orElseThrow();
        assertThat(night.clearLocationCount()).isEqualTo(2);   // 40 and 20 are below the 75 threshold
        assertThat(night.totalDarkSkyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("exactly at the clear threshold (75) is not clear (boundary)")
    void refresh_thresholdBoundary() {
        LocationEntity at75 = darkSky(1);
        LocationEntity just74 = darkSky(2);
        List<LocationEntity> darkSky = List.of(at75, just74);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSky);
        when(overheadCloudSampler.sample(eq(darkSky), eq(List.of(PEAK_HOUR)))).thenReturn(Map.of(
                at75, new int[]{75}, just74, new int[]{74}));

        service.refresh(List.of(PERSEIDS_PEAK));

        assertThat(service.getCached().forNight(PERSEIDS_PEAK).orElseThrow().clearLocationCount())
                .isEqualTo(1);   // only the 74 counts; 75 is not below the threshold
    }

    @Test
    @DisplayName("shower peak but no dark-sky locations: caches empty, no fetch")
    void refresh_peakButNoDarkSky_empty() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(List.of());

        service.refresh(List.of(PERSEIDS_PEAK));

        assertThat(service.getCached().byNight()).isEmpty();
        verifyNoInteractions(overheadCloudSampler);
    }

    @Test
    @DisplayName("getCached is EMPTY before any refresh")
    void getCached_beforeRefresh_empty() {
        assertThat(service.getCached()).isSameAs(MeteorClarity.EMPTY);
    }
}
