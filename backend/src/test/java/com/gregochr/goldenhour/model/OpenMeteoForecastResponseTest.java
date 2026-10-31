package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenMeteoForecastResponse} deserialization.
 */
class OpenMeteoForecastResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Deserializes latitude and longitude from response JSON")
    void deserialize_withLatLon() throws Exception {
        String json = """
                {
                  "latitude": 55.125,
                  "longitude": -1.625,
                  "hourly": {
                    "time": ["2026-04-03T00:00"],
                    "cloud_cover_low": [20]
                  }
                }
                """;

        OpenMeteoForecastResponse response = mapper.readValue(json, OpenMeteoForecastResponse.class);

        assertThat(response.getLatitude()).isEqualTo(55.125);
        assertThat(response.getLongitude()).isEqualTo(-1.625);
        assertThat(response.getHourly()).isNotNull();
        assertThat(response.getHourly().getCloudCoverLow()).containsExactly(20);
    }

    @Test
    @DisplayName("Deserializes with null latitude and longitude when omitted")
    void deserialize_missingLatLon_nulls() throws Exception {
        String json = """
                {
                  "hourly": {
                    "time": ["2026-04-03T00:00"]
                  }
                }
                """;

        OpenMeteoForecastResponse response = mapper.readValue(json, OpenMeteoForecastResponse.class);

        assertThat(response.getLatitude()).isNull();
        assertThat(response.getLongitude()).isNull();
        assertThat(response.getHourly()).isNotNull();
    }

    @Test
    @DisplayName("Deserializes negative coordinates (southern/western hemisphere)")
    void deserialize_negativeCoordinates() throws Exception {
        String json = """
                {
                  "latitude": -34.625,
                  "longitude": -58.375,
                  "hourly": { "time": [] }
                }
                """;

        OpenMeteoForecastResponse response = mapper.readValue(json, OpenMeteoForecastResponse.class);

        assertThat(response.getLatitude()).isEqualTo(-34.625);
        assertThat(response.getLongitude()).isEqualTo(-58.375);
    }

    @Test
    @DisplayName("Ignores unknown top-level fields without error")
    void deserialize_unknownFields_ignored() throws Exception {
        String json = """
                {
                  "latitude": 55.0,
                  "longitude": -1.0,
                  "utc_offset_seconds": 0,
                  "timezone": "GMT",
                  "generationtime_ms": 0.5,
                  "hourly": { "time": [] }
                }
                """;

        OpenMeteoForecastResponse response = mapper.readValue(json, OpenMeteoForecastResponse.class);

        assertThat(response.getLatitude()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("Deserializes zero coordinates (equator/prime meridian)")
    void deserialize_zeroCoordinates() throws Exception {
        String json = """
                {
                  "latitude": 0.0,
                  "longitude": 0.0,
                  "hourly": { "time": [] }
                }
                """;

        OpenMeteoForecastResponse response = mapper.readValue(json, OpenMeteoForecastResponse.class);

        assertThat(response.getLatitude()).isEqualTo(0.0);
        assertThat(response.getLongitude()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Deserializes the eight pressure-level cloud series into their own lists")
    void deserialize_pressureLevelCloud_eightLists() throws Exception {
        String json = """
                {
                  "hourly": {
                    "time": ["2026-04-03T00:00"],
                    "cloud_cover_850hPa": [11],
                    "cloud_cover_700hPa": [22],
                    "cloud_cover_600hPa": [33],
                    "cloud_cover_500hPa": [44],
                    "cloud_cover_400hPa": [55],
                    "cloud_cover_300hPa": [66],
                    "cloud_cover_250hPa": [77],
                    "cloud_cover_200hPa": [88]
                  }
                }
                """;

        OpenMeteoForecastResponse.Hourly hourly =
                mapper.readValue(json, OpenMeteoForecastResponse.class).getHourly();

        assertThat(hourly.getCloudCover850hPa()).containsExactly(11);
        assertThat(hourly.getCloudCover700hPa()).containsExactly(22);
        assertThat(hourly.getCloudCover600hPa()).containsExactly(33);
        assertThat(hourly.getCloudCover500hPa()).containsExactly(44);
        assertThat(hourly.getCloudCover400hPa()).containsExactly(55);
        assertThat(hourly.getCloudCover300hPa()).containsExactly(66);
        assertThat(hourly.getCloudCover250hPa()).containsExactly(77);
        assertThat(hourly.getCloudCover200hPa()).containsExactly(88);
        assertThat(hourly.getCloudCoverLow()).isNull();
    }
}
