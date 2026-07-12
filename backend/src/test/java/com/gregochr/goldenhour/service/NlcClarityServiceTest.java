package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.NlcNightClarity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NlcClarityService}.
 *
 * <p>Verifies the clarity scan gates on the NLC season, reads dark-sky locations from the
 * repository, samples the northward transect at each in-season night's deep-night hour, and
 * records only nights with a clear dark-sky location.
 */
@ExtendWith(MockitoExtension.class)
class NlcClarityServiceTest {

    /** 2026-06-17 is in NLC season (25 May – 10 Aug). */
    private static final LocalDate IN_SEASON = LocalDate.of(2026, 6, 17);
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 1, 10);

    @Mock
    private NorthwardTransectSampler transectSampler;

    @Mock
    private LocationRepository locationRepository;

    private NlcClarityService service;

    @BeforeEach
    void setUp() {
        // Real solar geometry — a lat-55 in-season night has genuine twilight windows.
        NlcTwilightWindowCalculator windowCalculator =
                new NlcTwilightWindowCalculator(new SolarService());
        service = new NlcClarityService(transectSampler, locationRepository, windowCalculator);
    }

    private static LocationEntity darkSky(String name, String regionName, int bortle) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        return LocationEntity.builder()
                .id(1L).name(name).lat(55).lon(-2).bortleClass(bortle).region(region).build();
    }

    @Test
    @DisplayName("no refresh yet: cache is null")
    void getCached_beforeRefresh_null() {
        assertThat(service.getCached()).isNull();
    }

    @Test
    @DisplayName("clear northern transect on an in-season night records a clear night")
    void refresh_clearTransect_recordsClearNight() {
        LocationEntity loc = darkSky("Kielder", "Northumberland", 3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(loc, new int[]{20}));

        service.refresh(List.of(IN_SEASON));

        NlcNightClarity clarity = service.getCached();
        assertThat(clarity.hasClearNight()).isTrue();
        NlcNightClarity.ClearNight night = clarity.clearNights().get(0);
        assertThat(night.date()).isEqualTo(IN_SEASON);
        assertThat(night.clearLocationCount()).isEqualTo(1);
        assertThat(night.regions()).containsExactly("Northumberland");
        // A lat-55 mid-June night has real twilight geometry — at least one window is computed.
        assertThat(night.eveningWindow() != null || night.morningWindow() != null).isTrue();
    }

    @Test
    @DisplayName("clear-night record carries the dark-sky total — the 'of Y' denominator")
    void refresh_clearNight_carriesDarkSkyTotal() {
        LocationEntity clear = darkSky("Clear", "Northumberland", 3);
        LocationEntity cloudy = darkSky("Cloudy", "Cumbria", 3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(clear, cloudy));
        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(clear, new int[]{20}, cloudy, new int[]{90}));

        service.refresh(List.of(IN_SEASON));

        NlcNightClarity.ClearNight night = service.getCached().clearNights().get(0);
        assertThat(night.clearLocationCount()).isEqualTo(1);   // only the clear location counts
        assertThat(night.totalDarkSkyCount()).isEqualTo(2);    // both scanned — the "of 2" denominator
    }

    @Test
    @DisplayName("overcast northern transect yields no clear night")
    void refresh_overcastTransect_noClearNight() {
        LocationEntity loc = darkSky("Kielder", "Northumberland", 3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(loc, new int[]{85}));

        service.refresh(List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("threshold boundary: 74 is clear, 75 is not")
    void refresh_thresholdBoundary() {
        LocationEntity clearLoc = darkSky("Clear", "Northumberland", 3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(clearLoc));

        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(clearLoc, new int[]{74}));
        service.refresh(List.of(IN_SEASON));
        assertThat(service.getCached().hasClearNight()).isTrue();

        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(clearLoc, new int[]{75}));
        service.refresh(List.of(IN_SEASON));
        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("multiple nights: only the clear one is recorded, dated correctly")
    void refresh_multipleNights_recordsOnlyClear() {
        LocationEntity loc = darkSky("Kielder", "Northumberland", 3);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of(loc));
        // Night 0 overcast, night 1 clear (array aligned to the two in-season night hour keys).
        when(transectSampler.sample(any(), any(), any()))
                .thenReturn(Map.of(loc, new int[]{90, 20}));

        service.refresh(List.of(IN_SEASON, IN_SEASON.plusDays(1)));

        NlcNightClarity clarity = service.getCached();
        assertThat(clarity.clearNights()).hasSize(1);
        assertThat(clarity.clearNights().get(0).date()).isEqualTo(IN_SEASON.plusDays(1));
    }

    @Test
    @DisplayName("out-of-season nights are skipped — no sampling, empty clarity")
    void refresh_outOfSeason_skipped() {
        service.refresh(List.of(OUT_OF_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
        verifyNoInteractions(transectSampler);
        verify(locationRepository, never()).findByBortleClassIsNotNullAndEnabledTrue();
    }

    @Test
    @DisplayName("no dark-sky locations: empty clarity, no sampling")
    void refresh_noDarkSkyLocations_empty() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(List.of());

        service.refresh(List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
        verifyNoInteractions(transectSampler);
    }
}
