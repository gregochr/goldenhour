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

    @Test
    @DisplayName("fills the eight pressure levels from the level response at the event slot")
    void pressureLevels_filledFromLevelResponse() {
        DirectionalCloudData data = readWithLevels(farResponse(), levelResponse(TIMES),
                TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarCloud850hPa()).isEqualTo(81);
        assertThat(data.farSolarCloud700hPa()).isEqualTo(71);
        assertThat(data.farSolarCloud600hPa()).isEqualTo(61);
        assertThat(data.farSolarCloud500hPa()).isEqualTo(51);
        assertThat(data.farSolarCloud400hPa()).isEqualTo(41);
        assertThat(data.farSolarCloud300hPa()).isEqualTo(31);
        assertThat(data.farSolarCloud250hPa()).isEqualTo(21);
        assertThat(data.farSolarCloud200hPa()).isEqualTo(11);
    }

    @Test
    @DisplayName("a level response missing the 250 and 200 hPa series nulls only those two")
    void pressureLevels_missingCirrusSeries_nullsOnlyLevelsSixAndSeven() {
        OpenMeteoForecastResponse noCirrus = levelResponse(TIMES);
        noCirrus.getHourly().setCloudCover250hPa(null);
        noCirrus.getHourly().setCloudCover200hPa(null);

        DirectionalCloudData data = readWithLevels(farResponse(), noCirrus, TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarCloud850hPa()).isEqualTo(81);
        assertThat(data.farSolarCloud700hPa()).isEqualTo(71);
        assertThat(data.farSolarCloud600hPa()).isEqualTo(61);
        assertThat(data.farSolarCloud500hPa()).isEqualTo(51);
        assertThat(data.farSolarCloud400hPa()).isEqualTo(41);
        assertThat(data.farSolarCloud300hPa()).isEqualTo(31);
        assertThat(data.farSolarCloud250hPa()).isNull();
        assertThat(data.farSolarCloud200hPa()).isNull();
    }

    @Test
    @DisplayName("readPressureLevels returns eight nulls on the all-null failure path")
    void readPressureLevels_failurePath_returnsEightNulls() {
        OpenMeteoForecastResponse badTime = levelResponse(TIMES);
        badTime.getHourly().setTime(List.of("not-a-time", "also-not", "nope"));

        Integer[] fromBadTime = CloudPointCacheReader.readPressureLevels(badTime, SUNSET, TargetType.SUNSET);
        Integer[] fromNull = CloudPointCacheReader.readPressureLevels(null, SUNSET, TargetType.SUNSET);

        assertThat(fromBadTime).hasSize(8).containsOnlyNulls();
        assertThat(fromNull).hasSize(8).containsOnlyNulls();
    }

    @Test
    @DisplayName("pressure levels are null when no level response exists, other fields unchanged")
    void pressureLevels_nullWhenNoLevelResponse() {
        DirectionalCloudData data = readWithLevels(farResponse(), null, TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarCloud850hPa()).isNull();
        assertThat(data.farSolarCloud300hPa()).isNull();
        assertThat(data.farSolarLowCloudPercent()).isEqualTo(40);
    }

    @Test
    @DisplayName("a malformed level response nulls only the eight levels, never another field or the result")
    void pressureLevels_malformedResponse_leavesEverythingElseIdentical() {
        DirectionalCloudData baseline = readWithLevels(farResponse(), null, TargetType.SUNSET, SUNSET);

        OpenMeteoForecastResponse nullTimes = levelResponse(TIMES);
        nullTimes.getHourly().setTime(null);
        OpenMeteoForecastResponse badTime = levelResponse(TIMES);
        badTime.getHourly().setTime(List.of("not-a-time", "also-not", "nope"));
        OpenMeteoForecastResponse noHourly = new OpenMeteoForecastResponse();

        for (OpenMeteoForecastResponse malformed : List.of(nullTimes, badTime, noHourly)) {
            DirectionalCloudData data = readWithLevels(farResponse(), malformed,
                    TargetType.SUNSET, SUNSET);

            assertThat(data).isNotNull();
            assertThat(data).isEqualTo(baseline);
            assertThat(data.farSolarCloud850hPa()).isNull();
            assertThat(data.farSolarCloud300hPa()).isNull();
            assertThat(data.farSolarCloud250hPa()).isNull();
            assertThat(data.farSolarCloud200hPa()).isNull();
        }
    }

    @Test
    @DisplayName("levels are still read when the far cloud cell is absent, far cloud fields null")
    void pressureLevels_readWithoutFarCloudCell() {
        DirectionalCloudData data = readWithLevels(null, levelResponse(TIMES), TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarCloud850hPa()).isEqualTo(81);
        assertThat(data.farSolarCloud300hPa()).isEqualTo(31);
        assertThat(data.farSolarLowCloudPercent()).isNull();
        assertThat(data.farSolarMidCloudPercent()).isNull();
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
    }

    @Test
    @DisplayName("no levels and no far cloud cell leaves every far and level field null")
    void pressureLevels_absentWithoutFarCloudCell_allNull() {
        DirectionalCloudData data = readWithLevels(null, null, TargetType.SUNSET, SUNSET);

        assertThat(data).isNotNull();
        assertThat(data.farSolarLowCloudPercent()).isNull();
        assertThat(data.farSolarCloud850hPa()).isNull();
        assertThat(data.farSolarCloud300hPa()).isNull();
    }

    private static OpenMeteoForecastResponse farResponse() {
        return response(List.of(1, 40, 41), List.of(2, 50, 51), List.of(3, 60, 61));
    }

    private static OpenMeteoForecastResponse levelResponse(List<String> times) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(times);
        hourly.setCloudCover850hPa(List.of(80, 81, 82));
        hourly.setCloudCover700hPa(List.of(70, 71, 72));
        hourly.setCloudCover600hPa(List.of(60, 61, 62));
        hourly.setCloudCover500hPa(List.of(50, 51, 52));
        hourly.setCloudCover400hPa(List.of(40, 41, 42));
        hourly.setCloudCover300hPa(List.of(30, 31, 32));
        hourly.setCloudCover250hPa(List.of(20, 21, 22));
        hourly.setCloudCover200hPa(List.of(10, 11, 12));
        response.setHourly(hourly);
        return response;
    }

    private static DirectionalCloudData readWithLevels(OpenMeteoForecastResponse far,
            OpenMeteoForecastResponse levels, TargetType type, LocalDateTime eventTime) {
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
        Map<String, OpenMeteoForecastResponse> levelCells = new HashMap<>();
        if (levels != null) {
            levelCells.put(CloudPointCache.gridKey(farPoint[0], farPoint[1]), levels);
        }
        return CloudPointCacheReader.fetchDirectionalCloudDataFromCache(
                LAT, LON, AZIMUTH, eventTime, type, new CloudPointCache(cells, levelCells));
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
