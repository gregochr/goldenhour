package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.IncompleteHourlyDataException;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OpenMeteoResponseParser#extractAtmosphericData}'s handling of the REQUIRED
 * hourly series.
 *
 * <p>Every optional series already falls back to {@code null} via bounds-aware accessors
 * ({@code getDoubleValue}/{@code getIntegerValue}). The required series that back
 * {@code CloudData}, the primitive fields of {@code WeatherData}, and
 * {@code AerosolData.boundaryLayerHeightMetres} used to read the resolved index directly, so a
 * short array threw {@code IndexOutOfBoundsException} and a null element threw
 * {@code NullPointerException} — both surfaced identically to the callers' generic
 * {@code catch (Exception e)} as a misleading "Open-Meteo API failure". This pins that every
 * required field now throws {@link IncompleteHourlyDataException} naming itself, instead.
 */
class OpenMeteoResponseParserAtmosphericDataTest {

    private static final int HOUR_COUNT = 5;
    private static final LocalDateTime SOLAR_EVENT_TIME = LocalDateTime.of(2026, 3, 11, 21, 0);
    private static final TargetType TARGET_TYPE = TargetType.SUNSET;

    /** {@code findBestIndex} resolves 21:00 sunset against {@link #times()} to this index. */
    private static final int RESOLVED_IDX = 2;

    @Test
    @DisplayName("a fully populated hourly response parses without throwing")
    void fullyPopulatedHourly_parsesSuccessfully() {
        OpenMeteoForecastResponse forecast = new OpenMeteoForecastResponse();
        forecast.setHourly(fullHourly());

        var data = OpenMeteoResponseParser.extractAtmosphericData(
                forecast, emptyAirQuality(), "Test Location", SOLAR_EVENT_TIME, TARGET_TYPE);

        assertThat(data.cloud().lowCloudPercent()).isEqualTo(20);
        assertThat(data.weather().visibilityMetres()).isEqualTo(20000);
        assertThat(data.aerosol().boundaryLayerHeightMetres()).isEqualTo(800);
    }

    @ParameterizedTest(name = "{0} truncated before the resolved index throws naming the field")
    @MethodSource("intFields")
    void requiredIntField_truncated_throwsNamingField(RequiredIntField field) {
        OpenMeteoForecastResponse.Hourly h = fullHourly();
        field.setter().accept(h, intList(1).subList(0, RESOLVED_IDX));

        assertThrowsIncomplete(h, field.name());
    }

    @ParameterizedTest(name = "{0} null at the resolved index throws naming the field")
    @MethodSource("intFields")
    void requiredIntField_nullAtIndex_throwsNamingField(RequiredIntField field) {
        OpenMeteoForecastResponse.Hourly h = fullHourly();
        List<Integer> values = new ArrayList<>(intList(1));
        values.set(RESOLVED_IDX, null);
        field.setter().accept(h, values);

        assertThrowsIncomplete(h, field.name());
    }

    @ParameterizedTest(name = "{0} truncated before the resolved index throws naming the field")
    @MethodSource("doubleFields")
    void requiredDoubleField_truncated_throwsNamingField(RequiredDoubleField field) {
        OpenMeteoForecastResponse.Hourly h = fullHourly();
        field.setter().accept(h, doubleList(1.0).subList(0, RESOLVED_IDX));

        assertThrowsIncomplete(h, field.name());
    }

    @ParameterizedTest(name = "{0} null at the resolved index throws naming the field")
    @MethodSource("doubleFields")
    void requiredDoubleField_nullAtIndex_throwsNamingField(RequiredDoubleField field) {
        OpenMeteoForecastResponse.Hourly h = fullHourly();
        List<Double> values = new ArrayList<>(doubleList(1.0));
        values.set(RESOLVED_IDX, null);
        field.setter().accept(h, values);

        assertThrowsIncomplete(h, field.name());
    }

    static Stream<RequiredIntField> intFields() {
        return Stream.of(
                new RequiredIntField("cloudCoverLow",
                        OpenMeteoForecastResponse.Hourly::setCloudCoverLow),
                new RequiredIntField("cloudCoverMid",
                        OpenMeteoForecastResponse.Hourly::setCloudCoverMid),
                new RequiredIntField("cloudCoverHigh",
                        OpenMeteoForecastResponse.Hourly::setCloudCoverHigh),
                new RequiredIntField("windDirection10m",
                        OpenMeteoForecastResponse.Hourly::setWindDirection10m),
                new RequiredIntField("relativeHumidity2m",
                        OpenMeteoForecastResponse.Hourly::setRelativeHumidity2m),
                new RequiredIntField("weatherCode",
                        OpenMeteoForecastResponse.Hourly::setWeatherCode));
    }

    static Stream<RequiredDoubleField> doubleFields() {
        return Stream.of(
                new RequiredDoubleField("visibility",
                        OpenMeteoForecastResponse.Hourly::setVisibility),
                new RequiredDoubleField("windSpeed10m",
                        OpenMeteoForecastResponse.Hourly::setWindSpeed10m),
                new RequiredDoubleField("precipitation",
                        OpenMeteoForecastResponse.Hourly::setPrecipitation),
                new RequiredDoubleField("shortwaveRadiation",
                        OpenMeteoForecastResponse.Hourly::setShortwaveRadiation),
                new RequiredDoubleField("boundaryLayerHeight",
                        OpenMeteoForecastResponse.Hourly::setBoundaryLayerHeight));
    }

    private void assertThrowsIncomplete(OpenMeteoForecastResponse.Hourly h, String expectedField) {
        OpenMeteoForecastResponse forecast = new OpenMeteoForecastResponse();
        forecast.setHourly(h);

        assertThatThrownBy(() -> OpenMeteoResponseParser.extractAtmosphericData(
                forecast, emptyAirQuality(), "Test Location", SOLAR_EVENT_TIME, TARGET_TYPE))
                .isInstanceOf(IncompleteHourlyDataException.class)
                .satisfies(ex -> {
                    IncompleteHourlyDataException incomplete = (IncompleteHourlyDataException) ex;
                    assertThat(incomplete.getFieldName()).isEqualTo(expectedField);
                    assertThat(incomplete.getIndex()).isEqualTo(RESOLVED_IDX);
                });
    }

    private static List<String> times() {
        return List.of(
                "2026-03-11T19:00", "2026-03-11T20:00", "2026-03-11T21:00",
                "2026-03-11T22:00", "2026-03-11T23:00");
    }

    private static OpenMeteoForecastResponse.Hourly fullHourly() {
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(times());
        h.setCloudCoverLow(intList(20));
        h.setCloudCoverMid(intList(30));
        h.setCloudCoverHigh(intList(10));
        h.setVisibility(doubleList(20000.0));
        h.setWindSpeed10m(doubleList(5.0));
        h.setWindDirection10m(intList(220));
        h.setPrecipitation(doubleList(0.0));
        h.setWeatherCode(intList(1));
        h.setRelativeHumidity2m(intList(65));
        h.setShortwaveRadiation(doubleList(100.0));
        h.setBoundaryLayerHeight(doubleList(800.0));
        return h;
    }

    private static List<Integer> intList(int value) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < HOUR_COUNT; i++) {
            list.add(value);
        }
        return list;
    }

    private static List<Double> doubleList(double value) {
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < HOUR_COUNT; i++) {
            list.add(value);
        }
        return list;
    }

    private static OpenMeteoAirQualityResponse emptyAirQuality() {
        OpenMeteoAirQualityResponse aq = new OpenMeteoAirQualityResponse();
        aq.setHourly(new OpenMeteoAirQualityResponse.Hourly());
        return aq;
    }

    private record RequiredIntField(String name,
            BiConsumer<OpenMeteoForecastResponse.Hourly, List<Integer>> setter) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record RequiredDoubleField(String name,
            BiConsumer<OpenMeteoForecastResponse.Hourly, List<Double>> setter) {
        @Override
        public String toString() {
            return name;
        }
    }
}
