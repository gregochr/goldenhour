package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mixed-location branches of {@link NlcClarityService#refresh}: a dark-sky location the transect
 * sampler returned nothing for, several clear locations (the first is the representative), and a
 * clear location with no region.
 */
@ExtendWith(MockitoExtension.class)
class NlcClarityServiceMixedLocationsTest {

    private static final LocalDate IN_SEASON = LocalDate.of(2026, 6, 17);
    private static final List<String> HOUR_KEYS = List.of("2026-06-18T00:00");

    @Mock
    private NorthwardTransectSampler transectSampler;
    @Mock
    private LocationRepository locationRepository;

    private NlcClarityService service;

    @BeforeEach
    void setUp() {
        service = new NlcClarityService(transectSampler, locationRepository,
                new NlcTwilightWindowCalculator(new SolarService()));
    }

    private static LocationEntity location(long id, String name, String regionName) {
        RegionEntity region = null;
        if (regionName != null) {
            region = new RegionEntity();
            region.setName(regionName);
        }
        return LocationEntity.builder().id(id).name(name).lat(55).lon(-2).bortleClass(3).region(region).build();
    }

    @Test
    @DisplayName("unsampled locations do not count; clear ones count with distinct regions; no region adds none")
    void refresh_mixedLocations() {
        LocationEntity unsampled = location(1L, "Unsampled", "Borders");
        LocationEntity clearA = location(2L, "Kielder", "Northumberland");
        LocationEntity clearB = location(3L, "Cheviot", "Northumberland");
        LocationEntity clearNoRegion = location(4L, "Nowhere", null);
        LocationEntity overcast = location(5L, "Cloudy", "Lakes");
        List<LocationEntity> darkSky = List.of(unsampled, clearA, clearB, clearNoRegion, overcast);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSky);
        when(transectSampler.sample(darkSky, HOUR_KEYS, NorthwardTransectSampler.LayerCombiner.MAX_LAYER))
                .thenReturn(Map.of(
                        clearA, new int[]{20},
                        clearB, new int[]{74},
                        clearNoRegion, new int[]{0},
                        overcast, new int[]{75}));

        service.refresh(List.of(IN_SEASON));

        NlcNightClarity.ClearNight night = service.getCached().clearNights().get(0);
        assertThat(night.date()).isEqualTo(IN_SEASON);
        assertThat(night.clearLocationCount()).isEqualTo(3);
        assertThat(night.totalDarkSkyCount()).isEqualTo(5);
        assertThat(night.regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("when every sampled location is overcast or unsampled there is no clear night")
    void refresh_nothingClear() {
        LocationEntity unsampled = location(1L, "Unsampled", "Borders");
        LocationEntity overcast = location(5L, "Cloudy", "Lakes");
        List<LocationEntity> darkSky = List.of(unsampled, overcast);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(darkSky);
        when(transectSampler.sample(darkSky, HOUR_KEYS, NorthwardTransectSampler.LayerCombiner.MAX_LAYER))
                .thenReturn(Map.of(overcast, new int[]{90}));

        service.refresh(List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }
}
