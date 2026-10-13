package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CloudPointCacheReader#fetchDirectionalCloudDataFromCache}.
 */
class CloudPointCacheReaderTest {

    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;
    private static final int AZIMUTH = 245;
    private static final List<String> TIMES = List.of("2026-06-21T19:00", "2026-06-21T20:00",
            "2026-06-21T21:00");
    // Sunset 20:47 -> best slot is 20:00 (index 1); the dark-side hour is 21:00 (index 2).
    private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 21, 20, 47);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 6, 21, 19, 30);

    @Test
    @DisplayName("fills the far mid/high at the event slot and low/mid/high at the dark-side hour")
    void sunset_fillsFarValuesFromFarResponse() {
        DirectionalCloudData data = read(response(List.of(1, 40, 41), List.of(2, 50, 51),
                List.of(3, 60, 61)), TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarLowCloudPercent()).isEqualTo(40);
        assertThat(data.farSolarMidCloudPercent()).isEqualTo(50);
        assertThat(data.farSolarHighCloudPercent()).isEqualTo(60);
        assertThat(data.farSolarLowCloudPercentDarkHour()).isEqualTo(41);
        assertThat(data.farSolarMidCloudPercentDarkHour()).isEqualTo(51);
        assertThat(data.farSolarHighCloudPercentDarkHour()).isEqualTo(61);
    }

    @Test
    @DisplayName("a sunrise reads the dark-side hour from the slot before the stored one")
    void sunrise_darkSideIsPreviousSlot() {
        // Sunrise 19:30 -> best slot is 20:00 (index 1); dark side is 19:00 (index 0).
        DirectionalCloudData data = read(response(List.of(11, 40, 41), List.of(12, 50, 51),
                List.of(13, 60, 61)), TargetType.SUNRISE, SUNRISE);

        assertThat(data.farSolarLowCloudPercent()).isEqualTo(40);
        assertThat(data.farSolarLowCloudPercentDarkHour()).isEqualTo(11);
        assertThat(data.farSolarMidCloudPercentDarkHour()).isEqualTo(12);
        assertThat(data.farSolarHighCloudPercentDarkHour()).isEqualTo(13);
    }

    @Test
    @DisplayName("null mid and high lists leave only those values null and the rest unchanged")
    void nullMidAndHighLists_onlyThoseValuesNull() {
        DirectionalCloudData data = read(response(List.of(1, 40, 41), null, null),
                TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarLowCloudPercent()).isEqualTo(40);
        assertThat(data.farSolarLowCloudPercentDarkHour()).isEqualTo(41);
        assertThat(data.farSolarMidCloudPercent()).isNull();
        assertThat(data.farSolarHighCloudPercent()).isNull();
        assertThat(data.farSolarMidCloudPercentDarkHour()).isNull();
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
        assertThat(data.solarLowCloudPercent()).isEqualTo(10);
        assertThat(data.antisolarHighCloudPercent()).isEqualTo(30);
    }

    @Test
    @DisplayName("null elements at the event or dark-side slot leave just those values null")
    void nullElements_onlyThoseValuesNull() {
        DirectionalCloudData data = read(response(List.of(1, 40, 41),
                Arrays.asList(2, null, 51), Arrays.asList(3, 60, null)), TargetType.SUNSET, SUNSET);

        assertThat(data.farSolarMidCloudPercent()).isNull();
        assertThat(data.farSolarHighCloudPercent()).isEqualTo(60);
        assertThat(data.farSolarMidCloudPercentDarkHour()).isEqualTo(51);
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
    }

    @Test
    @DisplayName("a dark-side index outside the array leaves the dark-hour values null")
    void darkIndexOutsideArray_darkHourValuesNull() {
        // Sunset 21:30 -> best slot is 21:00 (index 2, the last); dark side index 3 is out of range.
        DirectionalCloudData data = read(response(List.of(1, 40, 41), List.of(2, 50, 51),
                List.of(3, 60, 61)), TargetType.SUNSET, LocalDateTime.of(2026, 6, 21, 21, 30));

        assertThat(data.farSolarLowCloudPercent()).isEqualTo(41);
        assertThat(data.farSolarMidCloudPercent()).isEqualTo(51);
        assertThat(data.farSolarHighCloudPercent()).isEqualTo(61);
        assertThat(data.farSolarLowCloudPercentDarkHour()).isNull();
        assertThat(data.farSolarMidCloudPercentDarkHour()).isNull();
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
    }

    @Test
    @DisplayName("a missing far cell leaves every far field null and returns the rest, as before")
    void missingFarCell_farFieldsNullRestReturned() {
        DirectionalCloudData data = read(null, TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.solarLowCloudPercent()).isEqualTo(10);
        assertThat(data.antisolarLowCloudPercent()).isEqualTo(10);
        assertThat(data.farSolarLowCloudPercent()).isNull();
        assertThat(data.farSolarMidCloudPercent()).isNull();
        assertThat(data.farSolarHighCloudPercent()).isNull();
        assertThat(data.farSolarLowCloudPercentDarkHour()).isNull();
        assertThat(data.farSolarMidCloudPercentDarkHour()).isNull();
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
    }

    @Test
    @DisplayName("DELIBERATE: a far response whose LOW list is null still nulls the WHOLE result")
    void farLowListNull_nullsWholeResult_deliberatelyUnchanged() {
        // This outcome removes the prompt's directional block and switches triage's cloud source,
        // so it is a rating input and must not be softened to "keep the rest".
        DirectionalCloudData data = read(response(null, List.of(2, 50, 51), List.of(3, 60, 61)),
                TargetType.SUNSET, SUNSET);

        assertThat(data).isNull();
    }

    private static DirectionalCloudData read(OpenMeteoForecastResponse far, TargetType type,
            LocalDateTime eventTime) {
        List<double[]> points = DirectionalSamplingGeometry.computeDirectionalCloudPoints(
                LAT, LON, AZIMUTH);
        Map<String, OpenMeteoForecastResponse> cells = new HashMap<>();
        for (int i = 0; i < DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX; i++) {
            cells.put(CloudPointCache.gridKey(points.get(i)[0], points.get(i)[1]),
                    response(List.of(10, 10, 10), List.of(20, 20, 20), List.of(30, 30, 30)));
        }
        double[] farPoint = points.get(DirectionalSamplingGeometry.FAR_SOLAR_POINT_INDEX);
        if (far != null) {
            cells.put(CloudPointCache.gridKey(farPoint[0], farPoint[1]), far);
        }
        return CloudPointCacheReader.fetchDirectionalCloudDataFromCache(
                LAT, LON, AZIMUTH, eventTime, type, new CloudPointCache(cells));
    }

    private static OpenMeteoForecastResponse response(List<Integer> low, List<Integer> mid,
            List<Integer> high) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(TIMES);
        hourly.setCloudCoverLow(low);
        hourly.setCloudCoverMid(mid);
        hourly.setCloudCoverHigh(high);
        response.setHourly(hourly);
        return response;
    }
}
