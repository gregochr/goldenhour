package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HourlyComfort;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenMeteoResponseParser#extractComfortHours}: the tolerant, comfort-only
 * extraction the wildlife hourly refresh uses in place of the strict colour extraction.
 */
class OpenMeteoResponseParserComfortTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Test
    @DisplayName("returns one reading per full hour from 'from' to 'to' inclusive, truncating both ends")
    void returnsEveryHourInclusive() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);

        List<HourlyComfort> hours = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 6, 41), LocalDateTime.of(2026, 10, 2, 17, 28));

        // 06:41 floors to 06:00 and 17:28 floors to 17:00 — hours 6..17 inclusive is twelve.
        assertThat(hours).hasSize(12);
        assertThat(hours.getFirst().hour()).isEqualTo(LocalDateTime.of(2026, 10, 2, 6, 0));
        assertThat(hours.getLast().hour()).isEqualTo(LocalDateTime.of(2026, 10, 2, 17, 0));
    }

    @Test
    @DisplayName("maps all six comfort fields, rounding wind speed and precipitation HALF_UP to two places")
    void mapsEveryComfortField() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);

        HourlyComfort nine = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 9, 0)).getFirst();

        // Index 9: temperature 14.0, feels-like 12.0, probability 9, wind 9.125 -> 9.13,
        // direction 90, precipitation 1.125 -> 1.13 (both exact ties, so HALF_UP is the only way to 13).
        assertThat(nine.hour()).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 0));
        assertThat(nine.temperatureCelsius()).isEqualTo(14.0);
        assertThat(nine.apparentTemperatureCelsius()).isEqualTo(12.0);
        assertThat(nine.precipitationProbability()).isEqualTo(9);
        assertThat(nine.windSpeedMs()).isEqualTo(new BigDecimal("9.13"));
        assertThat(nine.windDirectionDegrees()).isEqualTo(90);
        assertThat(nine.precipitationMm()).isEqualTo(new BigDecimal("1.13"));
    }

    @Test
    @DisplayName("reads a later day's hours from the same response by exact timestamp")
    void matchesTheHourByExactTimestampAcrossDays() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 3);

        HourlyComfort hour = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 4, 7, 0), LocalDateTime.of(2026, 10, 4, 7, 0)).getFirst();

        // Index = 2 days * 24 + 7 = 55.
        assertThat(hour.temperatureCelsius()).isEqualTo(60.0);
        assertThat(hour.windDirectionDegrees()).isEqualTo(190);
    }

    @Test
    @DisplayName("an hour with no cloud, visibility, humidity or air-quality data still yields its comfort row")
    void colourSeriesHolesDoNotCostTheHour() {
        // The fixture carries none of the colour series at all, which is exactly the shape that
        // makes extractAtmosphericData throw IncompleteHourlyDataException for every hour.
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);
        assertThat(forecast.getHourly().getCloudCoverLow()).isNull();
        assertThat(forecast.getHourly().getVisibility()).isNull();

        List<HourlyComfort> hours = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 9, 0));

        assertThat(hours).extracting(HourlyComfort::temperatureCelsius).containsExactly(13.0, 14.0);
    }

    @Test
    @DisplayName("a null-temperature hour is skipped and its neighbours are kept")
    void nullTemperatureHourIsSkipped() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);
        forecast.getHourly().getTemperature2m().set(9, null);

        List<HourlyComfort> hours = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 10, 0));

        assertThat(hours).extracting(HourlyComfort::hour).containsExactly(
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 10, 0));
    }

    @Test
    @DisplayName("null readings other than temperature stay null on a kept hour")
    void otherNullReadingsStayNull() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);
        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        h.getApparentTemperature().set(9, null);
        h.getPrecipitationProbability().set(9, null);
        h.getWindSpeed10m().set(9, null);
        h.getWindDirection10m().set(9, null);
        h.getPrecipitation().set(9, null);

        HourlyComfort nine = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 9, 0)).getFirst();

        assertThat(nine.temperatureCelsius()).isEqualTo(14.0);
        assertThat(nine.apparentTemperatureCelsius()).isNull();
        assertThat(nine.precipitationProbability()).isNull();
        assertThat(nine.windSpeedMs()).isNull();
        assertThat(nine.windDirectionDegrees()).isNull();
        assertThat(nine.precipitationMm()).isNull();
    }

    @Test
    @DisplayName("an absent comfort series (null list or too short) yields null readings, not a failure")
    void absentSeriesYieldsNulls() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);
        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        h.setApparentTemperature(null);
        h.setWindSpeed10m(new ArrayList<>(List.of(1.0)));

        HourlyComfort nine = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 9, 0)).getFirst();

        assertThat(nine.apparentTemperatureCelsius()).isNull();
        assertThat(nine.windSpeedMs()).isNull();
        assertThat(nine.temperatureCelsius()).isEqualTo(14.0);
    }

    @Test
    @DisplayName("hours the response does not contain are skipped")
    void hoursOutsideTheResponseAreSkipped() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);

        List<HourlyComfort> hours = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 22, 0), LocalDateTime.of(2026, 10, 3, 2, 0));

        assertThat(hours).extracting(HourlyComfort::hour).containsExactly(
                LocalDateTime.of(2026, 10, 2, 22, 0), LocalDateTime.of(2026, 10, 2, 23, 0));
    }

    @Test
    @DisplayName("an unparseable timestamp is an unmatchable hour, not a failure")
    void unparseableTimestampIsSkipped() {
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(DAY, 1);
        forecast.getHourly().getTime().set(9, "not-a-time");

        List<HourlyComfort> hours = OpenMeteoResponseParser.extractComfortHours(forecast,
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 10, 0));

        assertThat(hours).extracting(HourlyComfort::hour).containsExactly(
                LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 2, 10, 0));
    }

    @Test
    @DisplayName("a null response, a null hourly block or a null time list yields no hours")
    void nullStructureYieldsNothing() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 2, 8, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 2, 10, 0);

        assertThat(OpenMeteoResponseParser.extractComfortHours(null, from, to)).isEmpty();
        assertThat(OpenMeteoResponseParser.extractComfortHours(
                new OpenMeteoForecastResponse(), from, to)).isEmpty();
        OpenMeteoForecastResponse noTimes = new OpenMeteoForecastResponse();
        noTimes.setHourly(new OpenMeteoForecastResponse.Hourly());
        assertThat(OpenMeteoResponseParser.extractComfortHours(noTimes, from, to)).isEmpty();
    }
}
