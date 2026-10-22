package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.AuroraViewlineResponse.ViewlinePoint;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import com.gregochr.goldenhour.service.aurora.AuroraViewlineRule;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link NoaaSwpcClient} JSON parsing logic.
 */
@ExtendWith(MockitoExtension.class)
class NoaaSwpcClientTest {

    @Mock
    private RestClient restClient;

    private NoaaSwpcClient client;

    /** Mutable clock so tests can age the cache past its TTL and reach the fail-open branch. */
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-17T12:00:00Z"));
        client = new NoaaSwpcClient(restClient, new AuroraProperties(), new ObjectMapper(), clock);
    }

    /** A Clock whose instant the test advances by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long minutes) {
            now = now.plus(Duration.ofMinutes(minutes));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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

    private static String magRow(String time, String source, boolean active, String bz) {
        return "{\"time_tag\":\"" + time + "\",\"active\":" + active + ",\"source\":\"" + source
                + "\",\"bt\":4.4,\"bz_gsm\":" + bz + "}";
    }

    private static String windRow(String time, String source, boolean active, String speed, String density) {
        return "{\"time_tag\":\"" + time + "\",\"active\":" + active + ",\"source\":\"" + source
                + "\",\"proton_speed\":" + speed + ",\"proton_density\":" + density + "}";
    }

    @Test
    @DisplayName("parseSolarWind merges active mag and wind rows by minute, ignoring the inactive spacecraft")
    void parseSolarWind_mergesMagAndPlasma() throws Exception {
        String magJson = "[" + magRow("2026-10-19T12:01:01", "IMAP", false, "9.9") + ","
                + magRow("2026-10-19T12:01:00", "SOLAR1", true, "-3.0") + ","
                + magRow("2026-10-19T12:00:00", "SOLAR1", true, "-5.21") + "]";
        // Wind seconds differ from mag seconds: the merge key is the minute.
        String plasmaJson = "[" + windRow("2026-10-19T12:01:03", "IMAP", false, "999.0", "99.0") + ","
                + windRow("2026-10-19T12:01:30", "SOLAR1", true, "420.0", "4.8") + ","
                + windRow("2026-10-19T12:00:30", "SOLAR1", true, "450.0", "5.2") + "]";

        List<SolarWindReading> readings = client.parseSolarWind(magJson, plasmaJson);

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).bzNanoTesla()).isEqualTo(-5.21);
        assertThat(readings.get(0).speedKmPerSec()).isEqualTo(450.0);
        assertThat(readings.get(0).densityPerCm3()).isEqualTo(5.2);
        assertThat(readings.get(1).bzNanoTesla()).isEqualTo(-3.0);
        assertThat(readings.get(1).speedKmPerSec()).isEqualTo(420.0);
        assertThat(readings.get(1).densityPerCm3()).isEqualTo(4.8);
    }

    @Test
    @DisplayName("parseSolarWind falls back to every row when no row is flagged active")
    void parseSolarWind_noActiveRows_usesAllRows() throws Exception {
        String magJson = "[" + magRow("2026-10-19T12:00:00", "IMAP", false, "-4.0") + "]";
        String plasmaJson = "[" + windRow("2026-10-19T12:00:00", "IMAP", false, "400.0", "5.0") + "]";

        List<SolarWindReading> readings = client.parseSolarWind(magJson, plasmaJson);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).bzNanoTesla()).isEqualTo(-4.0);
    }

    @Test
    @DisplayName("parseSolarWind skips null Bz values")
    void parseSolarWind_skipsNaN() throws Exception {
        String magJson = "[" + magRow("2026-10-19T12:00:00", "SOLAR1", true, "null") + "]";

        List<SolarWindReading> readings = client.parseSolarWind(magJson, "[]");

        assertThat(readings).isEmpty();
    }

    @Test
    @DisplayName("parseSolarWind limits to most recent 60 readings")
    void parseSolarWind_limitsTo60Readings() throws Exception {
        StringBuilder magSb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                magSb.append(',');
            }
            magSb.append(magRow(String.format("2026-10-19T%02d:%02d:00", i / 60, i % 60), "SOLAR1", true, "-1.0"));
        }
        magSb.append("]");

        List<SolarWindReading> readings = client.parseSolarWind(magSb.toString(), "[]");

        assertThat(readings).hasSize(60);
        assertThat(readings.get(59).timestamp().toString()).isEqualTo("2026-10-19T01:39Z");
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
    void fetchKp_fetchesAndParses() {
        String json = """
                [
                  ["time_tag","Kp","Kp_fraction","Kp_int"],
                  ["2025-08-01 00:00:00","3.33","3.333","3"]
                ]
                """;
        RestClientMocks.stubGet(restClient, String.class, json);

        List<KpReading> readings = client.fetchKp();

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).kp()).isEqualTo(3.33);
    }

    @Test
    @DisplayName("fetchKp returns empty list when RestClient throws")
    void fetchKp_exceptionFallback_returnsEmpty() {
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("timeout"));

        assertThat(client.fetchKp()).isEmpty();
    }

    @Test
    @DisplayName("fetchAlerts fetches and parses alert objects via RestClient")
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
        RestClientMocks.stubGet(restClient, String.class, json);

        List<SpaceWeatherAlert> alerts = client.fetchAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).messageType()).isEqualTo("A");
    }

    @Test
    @DisplayName("fetchAll returns a SpaceWeatherData with all fields")
    void fetchAll_returnsSpaceWeatherData() {
        // Stub all 5 HTTP calls with appropriate JSON payloads
        String kpJson = "[[\"time_tag\",\"Kp\",\"Kp_fraction\",\"Kp_int\"],"
                + "[\"2025-08-01 00:00:00\",\"2.0\",\"2.0\",\"2\"]]";
        String forecastJson = "[[\"time_tag\",\"Kp\",\"observed\",\"noaa_scale\"],"
                + "[\"2025-08-01 00:00:00\",\"2.0\",\"observed\",\"None\"]]";
        String ovationJson = "{\"Forecast Time\":\"2025-08-01T12:00:00Z\",\"coordinates\":[[0,55,10]]}";
        String magJson = "[" + magRow("2025-08-01T00:00:00", "SOLAR1", true, "-2.0") + "]";
        String plasmaJson = "[" + windRow("2025-08-01T00:00:00", "SOLAR1", true, "400.0", "5.0") + "]";
        String alertsJson = "[]";

        RestClientMocks.stubGetSequence(
                restClient, String.class, kpJson, forecastJson, ovationJson, magJson, plasmaJson, alertsJson);

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
        assertThat(result.isForecast()).isFalse();
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
    // Viewline threshold tests (using VIEWLINE_THRESHOLD = 5)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Kp 6 profile: viewline extends to ~54°N (northern England)")
    void parseViewline_moderateStorm_viewlineReachesNorthernEngland() throws Exception {
        // Probabilities taper realistically for a Kp 6 event
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 60, 30],
                    [355, 56, 15],
                    [355, 54, 8],
                    [355, 50, 3],
                    [0, 60, 30],
                    [0, 56, 15],
                    [0, 54, 8],
                    [0, 50, 3]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, NoaaSwpcClient.VIEWLINE_THRESHOLD);

        assertThat(result.active()).isTrue();
        // 54°N has 8% (above 5%), so viewline extends to northern England
        assertThat(result.southernmostLatitude()).isLessThanOrEqualTo(55.0);
        assertThat(result.summary()).contains("northern England");
    }

    @Test
    @DisplayName("Kp 8+ profile: viewline reaches ~50°N (whole UK)")
    void parseViewline_strongStorm_viewlineReachesWholeUk() throws Exception {
        // Higher probabilities reach further south
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 60, 60],
                    [355, 56, 40],
                    [355, 52, 15],
                    [355, 50, 8],
                    [0, 60, 60],
                    [0, 56, 40],
                    [0, 52, 15],
                    [0, 50, 8]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, NoaaSwpcClient.VIEWLINE_THRESHOLD);

        assertThat(result.active()).isTrue();
        // 50°N has 8% (above 5%), viewline extends across the whole UK
        assertThat(result.southernmostLatitude()).isLessThanOrEqualTo(51.0);
        assertThat(result.summary()).contains("whole of the UK");
    }

    @Test
    @DisplayName("All probabilities below 5% threshold → inactive")
    void parseViewline_allBelowThreshold_inactive() throws Exception {
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 60, 4],
                    [355, 56, 3],
                    [0, 60, 4],
                    [0, 56, 2]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, NoaaSwpcClient.VIEWLINE_THRESHOLD);

        assertThat(result.active()).isFalse();
        assertThat(result.points()).isEmpty();
    }

    @Test
    @DisplayName("Max UK probability 4% → inactive (below 5% threshold)")
    void parseViewline_maxProbability4pct_inactive() throws Exception {
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [355, 60, 4],
                    [355, 56, 3],
                    [0, 60, 4],
                    [0, 56, 1]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, NoaaSwpcClient.VIEWLINE_THRESHOLD);

        assertThat(result.active()).isFalse();
        assertThat(result.points()).isEmpty();
    }

    @Test
    @DisplayName("Exactly 5% probability is included; 4% is excluded")
    void parseViewline_exactBoundary_5pctIncluded_4pctExcluded() throws Exception {
        String json = """
                {
                  "Forecast Time": "2026-04-01T22:00:00Z",
                  "coordinates": [
                    [0, 56, 5],
                    [0, 54, 4],
                    [0, 60, 30]
                  ]
                }
                """;

        AuroraViewlineResponse result = client.parseViewline(json, NoaaSwpcClient.VIEWLINE_THRESHOLD);

        assertThat(result.active()).isTrue();
        // 56°N at exactly 5% should be included; 54°N at 4% should not
        assertThat(result.southernmostLatitude()).isGreaterThanOrEqualTo(56.0);
        assertThat(result.southernmostLatitude()).isLessThanOrEqualTo(56.5);
    }

    // -------------------------------------------------------------------------
    // Glow latitude and the forecast viewline — both read AuroraViewlineRule. The Kp-to-latitude
    // table and its applyKpCap_* / getGlowLatitudeCap_* pins are gone with the code: they pinned
    // the defect (geomagnetic figures read as geographic, Kp truncated, the live line clamped).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getGlowLatitudeCap is the viewline rule at the UK centre longitude, continuous in Kp")
    void getGlowLatitudeCap_isTheRuleAtMinusTwoDegrees() {
        assertThat(client.getGlowLatitudeCap(6.67))
                .isEqualTo(AuroraViewlineRule.geographicLatitudeFor(6.67, -2.0))
                .isBetween(46.2, 47.2);
        assertThat(client.getGlowLatitudeCap(6.0)).isGreaterThan(client.getGlowLatitudeCap(7.0));
    }

    @Test
    @DisplayName("buildForecastViewline Kp 6.67 is a 17-point curve, about 46.7N at -2 and further south to the west")
    void buildForecastViewline_kp667_seventeenPointCurve() {
        AuroraViewlineResponse result = client.buildForecastViewline(6.67);

        assertThat(result.points()).hasSize(17);
        assertThat(result.points().get(0).longitude()).isEqualTo(NoaaSwpcClient.VIEWLINE_LON_WEST);
        assertThat(result.points().get(16).longitude()).isEqualTo(NoaaSwpcClient.VIEWLINE_LON_EAST);
        assertThat(result.points().get(10).longitude()).isEqualTo(-2.0);
        assertThat(result.points().get(10).latitude()).isBetween(46.2, 47.2);
        // southernmost is the rule at the reference longitude (-2), NOT the curve's west-end minimum.
        assertThat(result.southernmostLatitude()).isEqualTo(result.points().get(10).latitude());
        assertThat(result.southernmostLatitude())
                .isGreaterThan(result.points().stream().mapToDouble(ViewlinePoint::latitude).min().orElseThrow());
        assertThat(result.points().get(0).latitude()).isLessThan(result.points().get(16).latitude());
    }

    @Test
    @DisplayName("buildForecastViewline carries isForecast, the Kp it was drawn for and when")
    void buildForecastViewline_carriesProvenance() {
        AuroraViewlineResponse result = client.buildForecastViewline(6.67);

        assertThat(result.isForecast()).isTrue();
        assertThat(result.active()).isTrue();
        assertThat(result.kp()).isEqualTo(6.67);
        assertThat(result.asOf()).isEqualTo(clock.instant().atZone(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("the forecast summary band and the Plan card's glow figure agree for the same Kp")
    void buildForecastViewline_summaryAgreesWithGlow() {
        for (double kp : new double[] {0.0, 3.0, 4.0, 5.0, 6.67, 9.0}) {
            AuroraViewlineResponse result = client.buildForecastViewline(kp);

            assertThat(result.southernmostLatitude()).as("Kp %.2f", kp).isEqualTo(client.getGlowLatitudeCap(kp));
            assertThat(result.summary()).as("Kp %.2f", kp)
                    .isEqualTo(client.viewlineSummary(client.getGlowLatitudeCap(kp)));
        }
        // The band the Map names at Kp 4 is the one the card's ~52N sits in, not the west end's 50.4.
        assertThat(client.buildForecastViewline(4.0).summary()).isEqualTo("Visible as far south as the Midlands");
    }

    @Test
    @DisplayName("buildForecastViewline summary band follows the southernmost latitude")
    void buildForecastViewline_summaryMatchesLatitude() {
        assertThat(client.buildForecastViewline(9.0).summary()).contains("whole of the UK");
        AuroraViewlineResponse quiet = client.buildForecastViewline(0.0);
        assertThat(quiet.summary()).isEqualTo(client.viewlineSummary(quiet.southernmostLatitude()));
        assertThat(quiet.summary()).isNotEqualTo(client.buildForecastViewline(9.0).summary());
    }

    // -------------------------------------------------------------------------
    // Cache + fail-open characterization
    //
    // Every fetch documents "retaining cached" on failure — the branch that keeps the aurora
    // banner alive through a NOAA outage. It had no coverage: reaching it needs an EXPIRED
    // cache, which was impossible while isFresh read the wall clock. These pin that contract,
    // and the caching that keeps the ~900 KB OVATION grid off the wire.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fetchOvation serves the cache within its TTL — no second HTTP call")
    void fetchOvation_withinTtl_doesNotRefetch() {
        String json = "{\"Forecast Time\":\"2026-07-17T12:00:00Z\",\"coordinates\":[[0,55,10]]}";
        RestClientMocks.stubGet(restClient, String.class, json);

        OvationReading first = client.fetchOvation();
        clock.advanceMinutes(4);
        OvationReading second = client.fetchOvation();

        assertThat(second).isSameAs(first);
        verify(restClient, times(1)).get();
    }

    @Test
    @DisplayName("fetchOvation refetches once its 5-minute TTL expires")
    void fetchOvation_afterTtl_refetches() {
        String json = "{\"Forecast Time\":\"2026-07-17T12:00:00Z\",\"coordinates\":[[0,55,10]]}";
        RestClientMocks.stubGet(restClient, String.class, json);

        client.fetchOvation();
        clock.advanceMinutes(6);
        client.fetchOvation();

        verify(restClient, times(2)).get();
    }

    @Test
    @DisplayName("fetchOvation fails open — a NOAA outage after a good fetch serves the stale value")
    void fetchOvation_failureAfterSuccess_retainsStale() {
        String json = "{\"Forecast Time\":\"2026-07-17T12:00:00Z\",\"coordinates\":[[0,55,10]]}";
        RestClientMocks.stubGet(restClient, String.class, json);
        OvationReading fresh = client.fetchOvation();

        clock.advanceMinutes(6);
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));

        assertThat(client.fetchOvation()).isSameAs(fresh);
    }

    @Test
    @DisplayName("fetchKp fails open — outage after a good fetch serves the stale readings")
    void fetchKp_failureAfterSuccess_retainsStale() {
        String json = "[[\"time_tag\",\"Kp\",\"Kp_fraction\",\"Kp_int\"],"
                + "[\"2026-07-17 00:00:00\",\"3.33\",\"3.333\",\"3\"]]";
        RestClientMocks.stubGet(restClient, String.class, json);
        List<KpReading> fresh = client.fetchKp();

        clock.advanceMinutes(16);
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));

        assertThat(client.fetchKp()).isSameAs(fresh);
    }

    @Test
    @DisplayName("fetchAlerts fails open — outage after a good fetch serves the stale alerts")
    void fetchAlerts_failureAfterSuccess_retainsStale() {
        String json = "[{\"message_type\":\"A\",\"message_id\":\"1\","
                + "\"issue_datetime\":\"2026-07-17 12:00:00\",\"message\":\"G2 storm\"}]";
        RestClientMocks.stubGet(restClient, String.class, json);
        List<SpaceWeatherAlert> fresh = client.fetchAlerts();

        clock.advanceMinutes(6);
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));

        assertThat(client.fetchAlerts()).isSameAs(fresh);
    }

    // -------------------------------------------------------------------------
    // Viewline caching — this endpoint is user-facing and re-downloaded the ~900 KB OVATION
    // grid on EVERY authenticated page load, unbounded in user traffic.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fetchViewline serves the cache within its TTL — no ~900 KB re-download per page load")
    void fetchViewline_withinTtl_doesNotRefetch() {
        RestClientMocks.stubGet(restClient, String.class, viewlineJson());

        client.fetchViewline();
        clock.advanceMinutes(4);
        client.fetchViewline();

        verify(restClient, times(1)).get();
    }

    @Test
    @DisplayName("fetchViewline serves the OVATION 5% contour unchanged: 49N stays 49N, on a fetch and on a cache hit")
    void fetchViewline_neverClampsTheContour() {
        RestClientMocks.stubGet(restClient, String.class, viewlineJsonFrom(49));

        AuroraViewlineResponse fetched = client.fetchViewline();
        clock.advanceMinutes(1);
        AuroraViewlineResponse cached = client.fetchViewline();

        verify(restClient, times(1)).get();
        assertThat(fetched.southernmostLatitude()).isEqualTo(49.0);
        assertThat(cached.southernmostLatitude()).isEqualTo(49.0);
        assertThat(cached.points()).allSatisfy(p -> assertThat(p.latitude()).isEqualTo(49.0));
        assertThat(cached.isForecast()).isFalse();
        assertThat(cached.kp()).isNull();
        assertThat(cached.asOf()).isEqualTo(ZonedDateTime.parse("2026-07-17T12:00:00Z"));
    }

    @Test
    @DisplayName("fetchViewline keeps serving the unclamped contour when NOAA fails after a good fetch")
    void fetchViewline_staleOnFailure_isUnclamped() {
        RestClientMocks.stubGet(restClient, String.class, viewlineJsonFrom(49));
        client.fetchViewline();

        clock.advanceMinutes(6);
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));

        assertThat(client.fetchViewline().southernmostLatitude()).isEqualTo(49.0);
    }

    @Test
    @DisplayName("fetchViewline refetches once its TTL expires")
    void fetchViewline_afterTtl_refetches() {
        RestClientMocks.stubGet(restClient, String.class, viewlineJson());

        client.fetchViewline();
        clock.advanceMinutes(6);
        client.fetchViewline();

        verify(restClient, times(2)).get();
    }

    @Test
    @DisplayName("fetchViewline fails open — an outage keeps the overlay rather than blanking it")
    void fetchViewline_failureAfterSuccess_retainsStale() {
        RestClientMocks.stubGet(restClient, String.class, viewlineJson());
        AuroraViewlineResponse fresh = client.fetchViewline();
        assertThat(fresh.active()).isTrue();

        clock.advanceMinutes(6);
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));
        AuroraViewlineResponse stale = client.fetchViewline();

        assertThat(stale.active()).isTrue();
        assertThat(stale.southernmostLatitude()).isEqualTo(fresh.southernmostLatitude());
    }

    @Test
    @DisplayName("fetchViewline with no cache and a failing NOAA returns an inactive response")
    void fetchViewline_failureWhenCold_returnsInactive() {
        RestClientMocks.stubGetThrows(restClient, String.class, new RestClientException("NOAA down"));

        AuroraViewlineResponse response = client.fetchViewline();

        assertThat(response.active()).isFalse();
        assertThat(response.summary()).isEqualTo("Aurora viewline unavailable");
    }

    /** OVATION grid with a UK-longitude band well above the viewline threshold. */
    private static String viewlineJson() {
        return viewlineJsonFrom(50);
    }

    /** OVATION grid whose 60% band starts at {@code southLat} for longitudes 0..3. */
    private static String viewlineJsonFrom(int southLat) {
        StringBuilder coords = new StringBuilder();
        for (int lon = 0; lon <= 3; lon++) {
            for (int lat = southLat; lat <= 70; lat++) {
                if (coords.length() > 0) {
                    coords.append(',');
                }
                coords.append('[').append(lon).append(',').append(lat).append(",60]");
            }
        }
        return "{\"Forecast Time\":\"2026-07-17T12:00:00Z\",\"coordinates\":["
                + coords + "]}";
    }
}
