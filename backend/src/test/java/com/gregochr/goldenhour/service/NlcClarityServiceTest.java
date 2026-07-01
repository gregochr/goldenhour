package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NlcClarityService}.
 *
 * <p>Verifies that the nightly clarity scan reads deep-night cloud (00:00 UTC of the following
 * day) from the briefing forecast, gates on the NLC season, only considers dark-sky locations,
 * and treats the densest cloud layer as the clarity ceiling.
 */
class NlcClarityServiceTest {

    /** 2026-06-17 is in NLC season (25 May – 10 Aug); the night runs into 2026-06-18T00:00 UTC. */
    private static final LocalDate IN_SEASON = LocalDate.of(2026, 6, 17);
    private static final String IN_SEASON_HOUR = "2026-06-18T00:00";

    private NlcClarityService service;

    @BeforeEach
    void setUp() {
        service = new NlcClarityService();
    }

    @Test
    @DisplayName("no refresh yet: cache is null")
    void getCached_beforeRefresh_null() {
        assertThat(service.getCached()).isNull();
    }

    @Test
    @DisplayName("clear dark-sky location on an in-season night is a clear night")
    void refresh_clearDarkSky_recordsClearNight() {
        service.refresh(List.of(darkSky("Kielder", "Northumberland",
                forecast(IN_SEASON_HOUR, 10, 5, 20))), List.of(IN_SEASON));

        NlcNightClarity clarity = service.getCached();
        assertThat(clarity.hasClearNight()).isTrue();
        assertThat(clarity.clearNights()).hasSize(1);
        NlcNightClarity.ClearNight night = clarity.clearNights().get(0);
        assertThat(night.date()).isEqualTo(IN_SEASON);
        assertThat(night.clearLocationCount()).isEqualTo(1);
        assertThat(night.regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("overcast dark-sky location yields no clear night")
    void refresh_overcast_noClearNight() {
        service.refresh(List.of(darkSky("Kielder", "Northumberland",
                forecast(IN_SEASON_HOUR, 90, 80, 85))), List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("the densest cloud layer governs: high cloud alone blocks NLC")
    void refresh_denseHighCloud_notClear() {
        // Low and mid clear, but high cloud at 80% (>= 75) hides the high-altitude NLC.
        service.refresh(List.of(darkSky("Kielder", "Northumberland",
                forecast(IN_SEASON_HOUR, 5, 10, 80))), List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("non-dark-sky locations are ignored")
    void refresh_nonDarkSky_ignored() {
        LocationEntity noBortle = LocationEntity.builder().name("Coast").lat(55).lon(-1).build();
        service.refresh(List.of(new BriefingSlotBuilder.LocationWeather(
                noBortle, forecast(IN_SEASON_HOUR, 0, 0, 0))), List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("out-of-season nights are skipped even when clear")
    void refresh_outOfSeason_skipped() {
        LocalDate winter = LocalDate.of(2026, 1, 10);
        service.refresh(List.of(darkSky("Kielder", "Northumberland",
                forecast("2026-01-11T00:00", 0, 0, 0))), List.of(winter));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    @Test
    @DisplayName("missing deep-night hour in the forecast is treated as unknown, not clear")
    void refresh_hourMissing_notClear() {
        service.refresh(List.of(darkSky("Kielder", "Northumberland",
                forecast("2026-06-18T12:00", 0, 0, 0))), List.of(IN_SEASON));

        assertThat(service.getCached().hasClearNight()).isFalse();
    }

    private static BriefingSlotBuilder.LocationWeather darkSky(String name, String regionName,
            OpenMeteoForecastResponse forecast) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = LocationEntity.builder()
                .name(name).lat(55).lon(-1).bortleClass(3).region(region).build();
        return new BriefingSlotBuilder.LocationWeather(location, forecast);
    }

    private static OpenMeteoForecastResponse forecast(String hour, int low, int mid, int high) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of(hour));
        hourly.setCloudCoverLow(List.of(low));
        hourly.setCloudCoverMid(List.of(mid));
        hourly.setCloudCoverHigh(List.of(high));
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }
}
