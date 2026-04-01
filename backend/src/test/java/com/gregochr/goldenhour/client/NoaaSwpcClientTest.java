package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import com.gregochr.goldenhour.model.SolarWindReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NoaaSwpcClient} JSON parsing logic.
 */
@ExtendWith(MockitoExtension.class)
class NoaaSwpcClientTest {

    @Mock
    private RestClient restClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private RestClient.RequestHeadersSpec headersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private NoaaSwpcClient client;

    @BeforeEach
    void setUp() {
        client = new NoaaSwpcClient(restClient, new AuroraProperties(), new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // Kp index parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseKpReadings parses standard NOAA array-of-arrays format")
    void parseKpReadings_standardFormat() throws Exception {
        String json = """
                [
                  ["time_tag","Kp","Kp_fraction","Kp_int"],
                  ["2025-08-01 00:00:00","1.33","1.333","1"],
                  ["2025-08-01 03:00:00","2.67","2.667","3"]
                ]
                """;

        List<KpReading> readings = client.parseKpReadings(json);

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).kp()).isEqualTo(1.33);
        assertThat(readings.get(1).kp()).isEqualTo(2.67);
    }

    @Test
    @DisplayName("parseKpReadings skips header row and NaN values")
    void parseKpReadings_skipsHeaderAndNaN() throws Exception {
        String json = """
                [
                  ["time_tag","Kp","Kp_fraction","Kp_int"],
                  ["2025-08-01 00:00:00","-9999.9","0.0","0"],
                  ["2025-08-01 03:00:00","4.00","4.0","4"]
                ]
                """;

        List<KpReading> readings = client.parseKpReadings(json);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("parseKpReadings returns empty list for empty array")
    void parseKpReadings_emptyArray() throws Exception {
        List<KpReading> readings = client.parseKpReadings("[]");
        assertThat(readings).isEmpty();
    }

    @Test
    @DisplayName("parseKpReadings parses new NOAA object format")
    void parseKpReadings_objectFormat() throws Exception {
        String json = """
                [
                  {"time_tag": "2026-03-24T00:00:00", "Kp": 2.67, "a_running": 12, "station_count": 8},
                  {"time_tag": "2026-03-24T03:00:00", "Kp": 3.67, "a_running": 22, "station_count": 8}
                ]
                """;

        List<KpReading> readings = client.parseKpReadings(json);

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).kp()).isEqualTo(2.67);
        assertThat(readings.get(1).kp()).isEqualTo(3.67);
    }

    // -------------------------------------------------------------------------
    // Kp forecast parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseKpForecasts sets 3-hour windows correctly")
    void parseKpForecasts_threeHourWindows() throws Exception {
        String json = """
                [
                  ["time_tag","Kp","observed","noaa_scale"],
                  ["2025-08-01 00:00:00","3.00","observed","None"],
                  ["2025-08-01 03:00:00","5.00","predicted","G1"]
                ]
                """;

        List<KpForecast> forecasts = client.parseKpForecasts(json);

        assertThat(forecasts).hasSize(2);
        KpForecast first = forecasts.get(0);
        assertThat(first.kp()).isEqualTo(3.0);
        assertThat(first.to()).isEqualTo(first.from().plusHours(3));
    }

    @Test
    @DisplayName("parseKpForecasts parses new NOAA object format with lowercase kp")
    void parseKpForecasts_objectFormat() throws Exception {
        String json = """
                [
                  {"time_tag": "2026-03-24T00:00:00", "kp": 2.67, "observed": "observed", "noaa_scale": null},
                  {"time_tag": "2026-03-24T03:00:00", "kp": 5.00, "observed": "predicted", "noaa_scale": "G1"}
                ]
                """;

        List<KpForecast> forecasts = client.parseKpForecasts(json);

        assertThat(forecasts).hasSize(2);
        assertThat(forecasts.get(0).kp()).isEqualTo(2.67);
        assertThat(forecasts.get(1).kp()).isEqualTo(5.0);
        assertThat(forecasts.get(0).to()).isEqualTo(forecasts.get(0).from().plusHours(3));
    }

    // -------------------------------------------------------------------------
    // OVATION parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseOvation averages probability across all longitudes at target latitude")
    void parseOvation_averagesAtTargetLat() throws Exception {
        String json = """
                {
                  "Forecast Time": "2025-08-01T12:00:00Z",
                  "coordinates": [
                    [0, 55, 20],
                    [1, 55, 40],
                    [2, 55, 60],
                    [0, 56, 100]
                  ]
                }
                """;

        OvationReading reading = client.parseOvation(json, 55.0);

        assertThat(reading.probabilityAtLatitude()).isEqualTo(40.0); // (20+40+60)/3
        assertThat(reading.latitude()).isEqualTo(55.0);
        assertThat(reading.forecastTime()).isNotNull();
    }

    @Test
    @DisplayName("parseOvation returns 0 probability when no data at target latitude")
    void parseOvation_noDataAtLat_returnsZero() throws Exception {
        String json = """
                {
                  "Forecast Time": "2025-08-01T12:00:00Z",
                  "coordinates": [
                    [0, 60, 50],
                    [1, 60, 30]
                  ]
                }
                """;

        OvationReading reading = client.parseOvation(json, 55.0);

        assertThat(reading.probabilityAtLatitude()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Solar wind parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseSolarWind merges mag and plasma data by timestamp")
    void parseSolarWind_mergesMagAndPlasma() throws Exception {
        String magJson = """
                [
                  ["time_tag","bx_gsm","by_gsm","bz_gsm","lon_gsm","lat_gsm","bt"],
                  ["2025-08-01 12:00:00.000","0.82","0.66","-5.21","38.82","4.88","5.26"],
                  ["2025-08-01 12:01:00.000","1.00","1.00","-3.00","40.00","5.00","4.00"]
                ]
                """;
        String plasmaJson = """
                [
                  ["time_tag","density","speed","temperature"],
                  ["2025-08-01 12:00:00.000","5.2","450.0","50000"],
                  ["2025-08-01 12:01:00.000","4.8","420.0","48000"]
                ]
                """;

        List<SolarWindReading> readings = client.parseSolarWind(magJson, plasmaJson);

        assertThat(readings).hasSize(2);
        // Check Bz values (sorted oldest first)
        assertThat(readings).anyMatch(r -> r.bzNanoTesla() == -5.21 && r.speedKmPerSec() == 450.0);
        assertThat(readings).anyMatch(r -> r.bzNanoTesla() == -3.0 && r.speedKmPerSec() == 420.0);
    }

    @Test
    @DisplayName("parseSolarWind skips NaN Bz values")
    void parseSolarWind_skipsNaN() throws Exception {
        String magJson = """
                [
                  ["time_tag","bx_gsm","by_gsm","bz_gsm","lon_gsm","lat_gsm","bt"],
                  ["2025-08-01 12:00:00.000","0.82","0.66","nan","38.82","4.88","5.26"]
                ]
                """;
        String plasmaJson = "[[\"time_tag\",\"density\",\"speed\",\"temperature\"]]";

        List<SolarWindReading> readings = client.parseSolarWind(magJson, plasmaJson);

        assertThat(readings).isEmpty();
    }

    @Test
    @DisplayName("parseSolarWind limits to most recent 60 readings")
    void parseSolarWind_limitsTo60Readings() throws Exception {
        StringBuilder magSb = new StringBuilder("[[\"time_tag\",\"bx\",\"by\",\"bz\",\"lon\",\"lat\",\"bt\"]");
        for (int i = 0; i < 100; i++) {
            String row = String.format(
                    ",[\"%04d-01-01 00:%02d:00.000\",\"0\",\"0\",\"-1.0\",\"0\",\"0\",\"1\"]", 2025, i % 60);
            magSb.append(row);
        }
        magSb.append("]");

        List<SolarWindReading> readings = client.parseSolarWind(magSb.toString(),
                "[[\"time_tag\",\"density\",\"speed\",\"temperature\"]]");

        assertThat(readings.size()).isLessThanOrEqualTo(60);
    }

    // -------------------------------------------------------------------------
    // Alerts parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseAlerts parses alert objects correctly")
    void parseAlerts_parsesCorrectly() throws Exception {
        String json = """
                [
                  {
                    "message_type": "A",
                    "message_id": "20250801-AL-001",
                    "issue_datetime": "2025-08-01 12:00:00",
                    "message": "Space Weather Alert: G2 storm in progress"
                  }
                ]
                """;

        List<SpaceWeatherAlert> alerts = client.parseAlerts(json);

        assertThat(alerts).hasSize(1);
        SpaceWeatherAlert alert = alerts.get(0);
        assertThat(alert.messageType()).isEqualTo("A");
        assertThat(alert.messageId()).isEqualTo("20250801-AL-001");
        assertThat(alert.message()).contains("G2 storm");
    }

    @Test
    @DisplayName("parseAlerts returns empty list for empty array")
    void parseAlerts_emptyArray() throws Exception {
        assertThat(client.parseAlerts("[]")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // parseDouble
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseDouble returns NaN for null, -9999.9, and 'nan'")
    void parseDouble_sentinelValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var nullNode = mapper.readTree("null");
        var sentinelNode = mapper.readTree("\"-9999.9\"");
        var nanNode = mapper.readTree("\"nan\"");

        assertThat(client.parseDouble(null)).isNaN();
        assertThat(client.parseDouble(nullNode)).isNaN();
        assertThat(client.parseDouble(sentinelNode)).isNaN();
        assertThat(client.parseDouble(nanNode)).isNaN();
    }

    @Test
    @DisplayName("parseDouble parses valid numeric strings")
    void parseDouble_validValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree("\"-5.21\"");
        assertThat(client.parseDouble(node)).isEqualTo(-5.21);
    }

    // -------------------------------------------------------------------------
    // parseUtcDateTime
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseUtcDateTime handles space-separated NOAA format")
    void parseUtcDateTime_spaceSeparated() {
        var result = client.parseUtcDateTime("2025-08-01 12:00:00");
        assertThat(result).isPresent();
        assertThat(result.get().getYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("parseUtcDateTime handles fractional seconds")
    void parseUtcDateTime_fractionalSeconds() {
        var result = client.parseUtcDateTime("2025-08-01 12:00:00.000");
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("parseUtcDateTime handles ISO-8601 with Z")
    void parseUtcDateTime_iso8601() {
        var result = client.parseUtcDateTime("2025-08-01T12:00:00Z");
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("parseUtcDateTime returns empty for blank input")
    void parseUtcDateTime_blank_empty() {
        assertThat(client.parseUtcDateTime("")).isEmpty();
        assertThat(client.parseUtcDateTime(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Fetch methods — RestClient integration (cache + error fallback)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fetchKp fetches and parses Kp readings via RestClient")
    @SuppressWarnings("unchecked")
    void fetchKp_fetchesAndParses() {
        String json = """
                [
                  ["time_tag","Kp","Kp_fraction","Kp_int"],
                  ["2025-08-01 00:00:00","3.33","3.333","3"]
                ]
                """;
        stubRestClientBody(json);

        List<KpReading> readings = client.fetchKp();

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(3.33);
    }

    @Test
    @DisplayName("fetchKp returns empty list when RestClient throws")
    @SuppressWarnings("unchecked")
    void fetchKp_exceptionFallback_returnsEmpty() {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RestClientException("timeout"));

        assertThat(client.fetchKp()).isEmpty();
    }

    @Test
    @DisplayName("fetchAlerts fetches and parses alert objects via RestClient")
    @SuppressWarnings("unchecked")
    void fetchAlerts_fetchesAndParses() {
        String json = """
                [
                  {
                    "message_type": "A",
                    "message_id": "20250801-AL-001",
                    "issue_datetime": "2025-08-01 12:00:00",
                    "message": "G2 storm in progress"
                  }
                ]
                """;
        stubRestClientBody(json);

        List<SpaceWeatherAlert> alerts = client.fetchAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).messageType()).isEqualTo("A");
    }

    @Test
    @DisplayName("fetchAll returns a SpaceWeatherData with all fields")
    @SuppressWarnings("unchecked")
    void fetchAll_returnsSpaceWeatherData() {
        // Stub all 5 HTTP calls with appropriate JSON payloads
        String kpJson = "[[\"time_tag\",\"Kp\",\"Kp_fraction\",\"Kp_int\"],"
                + "[\"2025-08-01 00:00:00\",\"2.0\",\"2.0\",\"2\"]]";
        String forecastJson = "[[\"time_tag\",\"Kp\",\"observed\",\"noaa_scale\"],"
                + "[\"2025-08-01 00:00:00\",\"2.0\",\"observed\",\"None\"]]";
        String ovationJson = "{\"Forecast Time\":\"2025-08-01T12:00:00Z\",\"coordinates\":[[0,55,10]]}";
        String magJson = "[[\"time_tag\",\"bx\",\"by\",\"bz\",\"lon\",\"lat\",\"bt\"],"
                + "[\"2025-08-01 00:00:00.000\",\"0\",\"0\",\"-2.0\",\"0\",\"0\",\"2\"]]";
        String plasmaJson = "[[\"time_tag\",\"density\",\"speed\",\"temperature\"],"
                + "[\"2025-08-01 00:00:00.000\",\"5.0\",\"400.0\",\"50000\"]]";
        String alertsJson = "[]";

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class))
                .thenReturn(kpJson)
                .thenReturn(forecastJson)
                .thenReturn(ovationJson)
                .thenReturn(magJson)
                .thenReturn(plasmaJson)
                .thenReturn(alertsJson);

        var data = client.fetchAll();

        assertThat(data.recentKp()).hasSize(1);
        assertThat(data.kpForecast()).hasSize(1);
        assertThat(data.ovation()).isNotNull();
        assertThat(data.recentSolarWind()).hasSize(1);
        assertThat(data.activeAlerts()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Viewline parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseViewline extracts southernmost latitude from UK longitude range")
    void parseViewline_extractsSouthernmostLatitude() throws Exception {
        // Coordinates at UK longitudes: lon=-5 has aurora at lat 54, lon=0 has aurora at lat 56
        // OVATION uses 0-360 longitude, so -5° = 355°
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 54, 10],
                    [355, 55, 15],
                    [355, 60, 30],
                    [0, 56, 8],
                    [0, 57, 12],
                    [0, 60, 25]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isTrue();
        assertThat(result.southernmostLatitude()).isLessThanOrEqualTo(55.0);
        assertThat(result.points()).isNotEmpty();
        assertThat(result.forecastTime().getYear()).isEqualTo(2026);
    }

    @Test
    @DisplayName("parseViewline returns inactive when no aurora in UK range")
    void parseViewline_returnsInactive_whenNoAuroraInUkRange() throws Exception {
        // Only coordinates at longitudes outside the UK range
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [100, 55, 20],
                    [101, 56, 15],
                    [200, 60, 40]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isFalse();
        assertThat(result.points()).isEmpty();
    }

    @Test
    @DisplayName("parseViewline filters to UK longitude range only")
    void parseViewline_filtersToUkLongitudeRange() throws Exception {
        // lon=355 (-5°) is in UK range; lon=100 (100°E) is not
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 54, 10],
                    [100, 40, 80]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isTrue();
        // The point at lon 100 (40°N, high probability) should be excluded
        assertThat(result.southernmostLatitude()).isGreaterThanOrEqualTo(54.0);
    }

    @Test
    @DisplayName("parseViewline smooths noisy data with moving average")
    void parseViewline_smoothsNoisyData() throws Exception {
        // Five adjacent longitudes with an outlier spike at lon=358 (-2°)
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [356, 56, 10],
                    [357, 56, 10],
                    [358, 50, 10],
                    [359, 56, 10],
                    [0, 56, 10]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isTrue();
        // The outlier at 50° should be smoothed — the point at lon -2 should be > 50
        var lonMinus2 = result.points().stream()
                .filter(p -> p.longitude() == -2)
                .findFirst();
        assertThat(lonMinus2).isPresent();
        assertThat(lonMinus2.get().latitude()).isGreaterThan(50.0);
    }

    @Test
    @DisplayName("parseViewline summary text matches latitude bands")
    void parseViewline_summaryMatchesLatitudeBands() throws Exception {
        assertThat(client.viewlineSummary(50.0)).contains("whole of the UK");
        assertThat(client.viewlineSummary(52.0)).contains("Midlands");
        assertThat(client.viewlineSummary(54.0)).contains("northern England");
        assertThat(client.viewlineSummary(56.0)).contains("central Scotland");
        assertThat(client.viewlineSummary(58.0)).contains("northern Scotland");
        assertThat(client.viewlineSummary(62.0)).contains("far north Scotland");
    }

    @Test
    @DisplayName("parseViewline handles empty coordinates gracefully")
    void parseViewline_handlesEmptyCoordinates() throws Exception {
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": []
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isFalse();
        assertThat(result.points()).isEmpty();
    }

    @Test
    @DisplayName("parseViewline returns inactive when all probabilities below threshold")
    void parseViewline_belowThreshold_returnsInactive() throws Exception {
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 55, 2],
                    [356, 56, 3],
                    [0, 57, 4]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, 5);

        assertThat(result.active()).isFalse();
        assertThat(result.points()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubRestClientBody(String body) {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(body);
    }
}
