package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of {@link OpenMeteoResponseParser}'s trend and wind extraction: missing, short and null-holding
 * arrays.
 */
class OpenMeteoResponseParserEdgeTest {

    private static final List<String> TIMES = List.of("2026-06-17T05:00", "2026-06-17T06:00", "2026-06-17T07:00",
            "2026-06-17T08:00");
    private static final LocalDateTime EIGHT = LocalDateTime.of(2026, 6, 17, 8, 0);

    private static OpenMeteoForecastResponse.Hourly hourly() {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(TIMES);
        return hourly;
    }

    // ── preceding precipitation ──────────────────────────────────────────────

    @Test
    @DisplayName("preceding precipitation is unknown with no precipitation series or no preceding hours")
    void precedingPrecipitation_unknownCases() {
        OpenMeteoForecastResponse.Hourly none = hourly();
        OpenMeteoForecastResponse.Hourly empty = hourly();
        empty.setPrecipitation(List.of());
        OpenMeteoForecastResponse.Hourly some = hourly();
        some.setPrecipitation(List.of(1.0, 2.0));

        assertThat(OpenMeteoResponseParser.extractPrecedingPrecipitationMm(none, 3)).isNull();
        assertThat(OpenMeteoResponseParser.extractPrecedingPrecipitationMm(empty, 3)).isNull();
        assertThat(OpenMeteoResponseParser.extractPrecedingPrecipitationMm(some, 0)).isNull();
    }

    @Test
    @DisplayName("preceding precipitation sums the available preceding hours, skipping gaps and a short series")
    void precedingPrecipitation_sumsAvailableHours() {
        OpenMeteoForecastResponse.Hourly gaps = hourly();
        gaps.setPrecipitation(Arrays.asList(1.0, null, 2.5));
        OpenMeteoForecastResponse.Hourly shortSeries = hourly();
        shortSeries.setPrecipitation(List.of(1.0, 2.0));

        assertThat(OpenMeteoResponseParser.extractPrecedingPrecipitationMm(gaps, 3)).isEqualTo(3.5);
        // Event index 10 looks back six hours (4-9) but the series ends at index 1: nothing to add.
        assertThat(OpenMeteoResponseParser.extractPrecedingPrecipitationMm(shortSeries, 10)).isEqualTo(0.0);
    }

    // ── mist trend ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("mist trend skips hours with no dew point or temperature, and hours beyond a short series")
    void mistTrend_skipsIncompleteHours() {
        OpenMeteoForecastResponse.Hourly h = hourly();
        h.setVisibility(Arrays.asList(5000.0, 4000.0, 3000.0, 2000.0));
        h.setDewPoint2m(Arrays.asList(8.0, null, 9.0, 9.5));
        h.setTemperature2m(Arrays.asList(9.0, 10.0, null, 10.5));

        MistTrend trend = OpenMeteoResponseParser.extractMistTrend(h, 2);

        // Eventidx 2 spans offsets -3..+2 = indexes -1..4: only indexes 0 and 3 are complete.
        assertThat(trend.slots()).extracting(MistTrend.MistSlot::hoursRelativeToEvent).containsExactly(-2, 1);
        assertThat(trend.slots()).extracting(MistTrend.MistSlot::visibilityMetres).containsExactly(5000, 2000);
    }

    @Test
    @DisplayName("mist trend stops at the shortest of the visibility, dew point and temperature series")
    void mistTrend_shortestSeriesBounds() {
        OpenMeteoForecastResponse.Hourly h = hourly();
        h.setVisibility(Arrays.asList(5000.0, 4000.0, 3000.0, 2000.0));
        h.setDewPoint2m(Arrays.asList(8.0, 8.5, 9.0, 9.5));
        h.setTemperature2m(Arrays.asList(9.0, 10.0));

        MistTrend trend = OpenMeteoResponseParser.extractMistTrend(h, 2);

        assertThat(trend.slots()).extracting(MistTrend.MistSlot::hoursRelativeToEvent).containsExactly(-2, -1);
    }

    @Test
    @DisplayName("mist trend is unknown when no hour is complete or a series is missing")
    void mistTrend_unknownCases() {
        OpenMeteoForecastResponse.Hourly noneComplete = hourly();
        noneComplete.setVisibility(Arrays.asList(5000.0, 4000.0));
        noneComplete.setDewPoint2m(Arrays.asList(null, null));
        noneComplete.setTemperature2m(Arrays.asList(9.0, 10.0));
        OpenMeteoForecastResponse.Hourly missing = hourly();
        missing.setVisibility(List.of(5000.0));

        assertThat(OpenMeteoResponseParser.extractMistTrend(noneComplete, 1)).isNull();
        assertThat(OpenMeteoResponseParser.extractMistTrend(missing, 0)).isNull();
    }

    // ── solar cloud trend ────────────────────────────────────────────────────

    private static OpenMeteoForecastResponse forecast(OpenMeteoForecastResponse.Hourly hourly) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("solar trend leaves mid and high cloud null where their series are missing or shorter than low")
    void solarTrend_missingAndShortLayers() {
        OpenMeteoForecastResponse.Hourly h = hourly();
        h.setCloudCoverLow(List.of(10, 20, 30, 40));
        h.setCloudCoverMid(List.of(1, 2));
        h.setCloudCoverHigh(null);

        SolarCloudTrend trend = OpenMeteoResponseParser.extractSolarTrend(forecast(h), EIGHT, TargetType.SUNRISE);

        assertThat(trend.slots()).extracting(SolarCloudTrend.SolarCloudSlot::hoursBeforeEvent)
                .containsExactly(3, 2, 1, 0);
        assertThat(trend.slots()).extracting(SolarCloudTrend.SolarCloudSlot::midCloudPercent)
                .containsExactly(1, 2, null, null);
        assertThat(trend.slots()).extracting(SolarCloudTrend.SolarCloudSlot::highCloudPercent)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("solar trend keeps high cloud where present and nulls it past a shorter series")
    void solarTrend_shortHighSeries() {
        OpenMeteoForecastResponse.Hourly h = hourly();
        h.setCloudCoverLow(List.of(10, 20, 30, 40));
        h.setCloudCoverMid(List.of(1, 2, 3, 4));
        h.setCloudCoverHigh(List.of(7, 8));

        SolarCloudTrend trend = OpenMeteoResponseParser.extractSolarTrend(forecast(h), EIGHT, TargetType.SUNRISE);

        assertThat(trend.slots()).extracting(SolarCloudTrend.SolarCloudSlot::highCloudPercent)
                .containsExactly(7, 8, null, null);
    }

    // ── event wind ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("event wind is unknown without an hourly block, a time axis or either wind series")
    void eventWind_missingSeries_null() {
        OpenMeteoForecastResponse.Hourly noTime = new OpenMeteoForecastResponse.Hourly();
        noTime.setWindDirection10m(List.of(90));
        noTime.setWindSpeed10m(List.of(5.0));
        OpenMeteoForecastResponse.Hourly noDirection = hourly();
        noDirection.setWindSpeed10m(List.of(5.0));
        OpenMeteoForecastResponse.Hourly noSpeed = hourly();
        noSpeed.setWindDirection10m(List.of(90));

        assertThat(OpenMeteoResponseParser.resolveEventWind(null, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(noTime, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(noDirection, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(noSpeed, EIGHT, TargetType.SUNRISE)).isNull();
    }

    @Test
    @DisplayName("event wind is unknown when the event slot is beyond a short wind series or holds a null reading")
    void eventWind_shortOrNullReading_null() {
        OpenMeteoForecastResponse.Hourly shortDirection = hourly();
        shortDirection.setWindDirection10m(List.of(90, 100));
        shortDirection.setWindSpeed10m(List.of(5.0, 5.0, 5.0, 5.0));
        OpenMeteoForecastResponse.Hourly shortSpeed = hourly();
        shortSpeed.setWindDirection10m(List.of(90, 100, 110, 120));
        shortSpeed.setWindSpeed10m(List.of(5.0, 5.0));
        OpenMeteoForecastResponse.Hourly nullDirection = hourly();
        nullDirection.setWindDirection10m(Arrays.asList(90, 100, 110, null));
        nullDirection.setWindSpeed10m(List.of(5.0, 5.0, 5.0, 5.0));
        OpenMeteoForecastResponse.Hourly nullSpeed = hourly();
        nullSpeed.setWindDirection10m(List.of(90, 100, 110, 120));
        nullSpeed.setWindSpeed10m(Arrays.asList(5.0, 5.0, 5.0, null));

        assertThat(OpenMeteoResponseParser.resolveEventWind(shortDirection, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(shortSpeed, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(nullDirection, EIGHT, TargetType.SUNRISE)).isNull();
        assertThat(OpenMeteoResponseParser.resolveEventWind(nullSpeed, EIGHT, TargetType.SUNRISE)).isNull();
    }

    @Test
    @DisplayName("event wind is the direction and speed at the event's slot")
    void eventWind_present() {
        OpenMeteoForecastResponse.Hourly h = hourly();
        h.setWindDirection10m(List.of(90, 100, 110, 120));
        h.setWindSpeed10m(List.of(1.0, 2.0, 3.0, 4.0));

        OpenMeteoResponseParser.EventWind wind = OpenMeteoResponseParser.resolveEventWind(h, EIGHT,
                TargetType.SUNRISE);

        assertThat(wind).isEqualTo(new OpenMeteoResponseParser.EventWind(120, 4.0));
    }
}
