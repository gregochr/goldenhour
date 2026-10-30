package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InversionProfile}: building from a response with nullable series, and slicing to the event slot and
 * its night side.
 */
class InversionProfileTest {

    private static OpenMeteoForecastResponse response(int hours) {
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setLatitude(54.575);
        r.setLongitude(-3.152);
        r.setElevation(80.0);
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        for (int i = 0; i < hours; i++) {
            times.add(LocalDateTime.of(2025, 10, 15, 0, 0).plusHours(i).toString());
            temps.add(5.0 + i);
        }
        h.setTime(times);
        h.setTemperature2m(temps);
        h.setTemperature975hPa(IntStream.range(0, hours).mapToObj(i -> 4.0 + i).toList());
        h.setGeopotentialHeight975hPa(IntStream.range(0, hours).mapToObj(i -> 450.0).toList());
        h.setCloudCoverLow(IntStream.range(0, hours).mapToObj(i -> 10 * i).toList());
        r.setHourly(h);
        return r;
    }

    @Test
    @DisplayName("from() reads each series by hour and leaves absent series null, never throwing")
    void fromReadsNullableSeries() {
        InversionProfile p = InversionProfile.from(response(3), 80);

        assertThat(p.valleyFloorMetres()).isEqualTo(80);
        assertThat(p.cellElevationMetres()).isEqualTo(80.0);
        assertThat(p.cellLatitude()).isEqualTo(54.575);
        assertThat(p.hours()).hasSize(3);
        InversionProfile.Hour second = p.hours().get(1);
        assertThat(second.temperatureCelsius()).isEqualTo(6.0);
        assertThat(second.lowCloudPercent()).isEqualTo(10);
        assertThat(second.dewPointCelsius()).isNull();
        assertThat(second.visibilityMetres()).isNull();
        assertThat(second.windSpeedMs()).isNull();
        assertThat(second.levels()).hasSize(5);
        assertThat(second.levels().get(0).pressureHpa()).isEqualTo(975);
        assertThat(second.levels().get(0).temperatureCelsius()).isEqualTo(5.0);
        assertThat(second.levels().get(0).heightMetres()).isEqualTo(450.0);
        assertThat(second.levels().get(1).temperatureCelsius()).isNull();
        assertThat(second.levels().get(4).pressureHpa()).isEqualTo(850);
    }

    @Test
    @DisplayName("from() is null for a missing response, hourly block, time list or an empty one")
    void fromNullForUnusableResponses() {
        assertThat(InversionProfile.from(null, 80)).isNull();
        assertThat(InversionProfile.from(new OpenMeteoForecastResponse(), 80)).isNull();
        OpenMeteoForecastResponse noTimes = new OpenMeteoForecastResponse();
        noTimes.setHourly(new OpenMeteoForecastResponse.Hourly());
        assertThat(InversionProfile.from(noTimes, 80)).isNull();
        OpenMeteoForecastResponse empty = response(0);
        assertThat(InversionProfile.from(empty, 80)).isNull();
    }

    @Test
    @DisplayName("a series shorter than the time list reads null beyond its end")
    void shortSeriesReadsNull() {
        OpenMeteoForecastResponse r = response(4);
        r.getHourly().setTemperature2m(List.of(5.0, 6.0));

        InversionProfile p = InversionProfile.from(r, 80);

        assertThat(p.hours().get(1).temperatureCelsius()).isEqualTo(6.0);
        assertThat(p.hours().get(3).temperatureCelsius()).isNull();
    }

    @Test
    @DisplayName("sunrise slice: the slot (earliest hour at or after the event) and the four hours before it")
    void sunriseSlice() {
        InversionProfile whole = InversionProfile.from(response(24), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 6, 41), TargetType.SUNRISE);

        assertThat(slice.slotTime()).isEqualTo(LocalDateTime.of(2025, 10, 15, 7, 0));
        assertThat(slice.hours()).extracting(InversionProfile.Hour::time).containsExactly(
                LocalDateTime.of(2025, 10, 15, 3, 0), LocalDateTime.of(2025, 10, 15, 4, 0),
                LocalDateTime.of(2025, 10, 15, 5, 0), LocalDateTime.of(2025, 10, 15, 6, 0),
                LocalDateTime.of(2025, 10, 15, 7, 0));
        assertThat(slice.slotHour().time()).isEqualTo(LocalDateTime.of(2025, 10, 15, 7, 0));
        assertThat(slice.valleyFloorMetres()).isEqualTo(80);
    }

    @Test
    @DisplayName("sunset slice: the slot (latest hour at or before the event) and the two hours after it")
    void sunsetSlice() {
        InversionProfile whole = InversionProfile.from(response(24), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 17, 8), TargetType.SUNSET);

        assertThat(slice.slotTime()).isEqualTo(LocalDateTime.of(2025, 10, 15, 17, 0));
        assertThat(slice.hours()).extracting(InversionProfile.Hour::time).containsExactly(
                LocalDateTime.of(2025, 10, 15, 17, 0), LocalDateTime.of(2025, 10, 15, 18, 0),
                LocalDateTime.of(2025, 10, 15, 19, 0));
    }

    @Test
    @DisplayName("a slice near the start of the data keeps what exists")
    void sliceAtTheStartOfTheData() {
        InversionProfile whole = InversionProfile.from(response(24), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 1, 20), TargetType.SUNRISE);

        assertThat(slice.hours()).extracting(h -> h.time().getHour()).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("an event the profile does not cover, or a non-solar target, is no slice")
    void uncoveredEventIsNull() {
        InversionProfile whole = InversionProfile.from(response(24), 80);

        assertThat(whole.sliceForEvent(LocalDateTime.of(2025, 10, 20, 7, 0), TargetType.SUNRISE)).isNull();
        assertThat(whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 7, 0), TargetType.HOURLY)).isNull();
    }

    @Test
    @DisplayName("a whole-forecast profile has no slot hour")
    void wholeProfileHasNoSlotHour() {
        assertThat(InversionProfile.from(response(5), 80).slotHour()).isNull();
    }
}
