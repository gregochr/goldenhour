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

    /** A response of {@code hours} hourly steps from {@code start} (UTC), with mid and high cloud 10 and 20. */
    private static OpenMeteoForecastResponse longResponse(LocalDateTime start, int hours) {
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setElevation(80.0);
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(IntStream.range(0, hours).mapToObj(i -> start.plusHours(i).toString()).toList());
        h.setCloudCoverMid(IntStream.range(0, hours).mapToObj(i -> 10).toList());
        h.setCloudCoverHigh(IntStream.range(0, hours).mapToObj(i -> 20).toList());
        r.setHourly(h);
        return r;
    }

    private static List<LocalDateTime> nightTimes(InversionProfile slice) {
        return slice.nightHours().stream().map(InversionProfile.Hour::time).toList();
    }

    @Test
    @DisplayName("from() reads mid and high cloud per hour, and the seven-argument Hour has neither")
    void midAndHighCloudAreRead() {
        InversionProfile p = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 15, 0, 0), 3), 80);

        assertThat(p.hours().get(1).midCloudPercent()).isEqualTo(10);
        assertThat(p.hours().get(1).highCloudPercent()).isEqualTo(20);
        assertThat(p.nightHours()).isEmpty();
        InversionProfile.Hour old = new InversionProfile.Hour(LocalDateTime.of(2025, 10, 15, 0, 0), 1.0, 0.5, 30,
                9000.0, 2.0, List.of());
        assertThat(old.midCloudPercent()).isNull();
        assertThat(old.highCloudPercent()).isNull();
    }

    @Test
    @DisplayName("a BST sunrise: night hours are 21:00 BST (20:00 UTC) to 05:00 BST (04:00 UTC), nine hours")
    void bstNight() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 14, 0, 0), 48), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 6, 41), TargetType.SUNRISE);

        assertThat(nightTimes(slice)).hasSize(9)
                .startsWith(LocalDateTime.of(2025, 10, 14, 20, 0))
                .endsWith(LocalDateTime.of(2025, 10, 15, 4, 0));
        // hours() is still the event window only: the walk must not see the night.
        assertThat(slice.hours()).extracting(InversionProfile.Hour::time).containsExactly(
                LocalDateTime.of(2025, 10, 15, 3, 0), LocalDateTime.of(2025, 10, 15, 4, 0),
                LocalDateTime.of(2025, 10, 15, 5, 0), LocalDateTime.of(2025, 10, 15, 6, 0),
                LocalDateTime.of(2025, 10, 15, 7, 0));
        assertThat(slice.nightHours().get(0).midCloudPercent()).isEqualTo(10);
    }

    @Test
    @DisplayName("the October fall-back night (26 Oct 2025) has ten hours: 20:00 UTC to 05:00 UTC")
    void fallBackNightHasTenHours() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 25, 0, 0), 48), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 26, 6, 50), TargetType.SUNRISE);

        assertThat(nightTimes(slice)).hasSize(10)
                .startsWith(LocalDateTime.of(2025, 10, 25, 20, 0))
                .endsWith(LocalDateTime.of(2025, 10, 26, 5, 0));
    }

    @Test
    @DisplayName("the March spring-forward night (30 Mar 2025) has eight hours: 21:00 UTC to 04:00 UTC")
    void springForwardNightHasEightHours() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 3, 29, 0, 0), 48), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 3, 30, 5, 30), TargetType.SUNRISE);

        assertThat(nightTimes(slice)).hasSize(8)
                .startsWith(LocalDateTime.of(2025, 3, 29, 21, 0))
                .endsWith(LocalDateTime.of(2025, 3, 30, 4, 0));
    }

    @Test
    @DisplayName("an ordinary GMT winter night (15 Jan) is 21:00 to 05:00 UTC, nine hours")
    void gmtNight() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 1, 14, 0, 0), 48), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 1, 15, 8, 15), TargetType.SUNRISE);

        assertThat(nightTimes(slice)).hasSize(9)
                .startsWith(LocalDateTime.of(2025, 1, 14, 21, 0))
                .endsWith(LocalDateTime.of(2025, 1, 15, 5, 0));
    }

    @Test
    @DisplayName("a slot earlier than 05:00 local ends the night at the slot, inclusive")
    void slotBeforeFiveEndsTheNight() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 14, 0, 0), 48), 80);

        // Sunrise 02:20 UTC (03:20 BST) picks the 03:00 UTC slot, which is 04:00 BST.
        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 2, 20), TargetType.SUNRISE);

        assertThat(slice.slotTime()).isEqualTo(LocalDateTime.of(2025, 10, 15, 3, 0));
        assertThat(nightTimes(slice)).hasSize(8).endsWith(LocalDateTime.of(2025, 10, 15, 3, 0));
    }

    @Test
    @DisplayName("a profile that starts mid-evening keeps only the night hours it has")
    void shortPastKeepsWhatExists() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 15, 0, 0), 24), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 6, 41), TargetType.SUNRISE);

        assertThat(nightTimes(slice)).hasSize(5).startsWith(LocalDateTime.of(2025, 10, 15, 0, 0));
    }

    @Test
    @DisplayName("a sunset has no night side, and neither has a whole-forecast profile")
    void sunsetAndWholeProfileHaveNoNight() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 10, 14, 0, 0), 48), 80);

        InversionProfile sunset = whole.sliceForEvent(LocalDateTime.of(2025, 10, 15, 17, 8), TargetType.SUNSET);

        assertThat(sunset.nightHours()).isEmpty();
        assertThat(whole.nightHours()).isEmpty();
    }

    @Test
    @DisplayName("the event date is the UK civil date: an event at 23:30 UTC in summer is already the next UK day")
    void eventDateIsTheUkCivilDate() {
        InversionProfile whole = InversionProfile.from(longResponse(LocalDateTime.of(2025, 6, 29, 0, 0), 96), 80);

        InversionProfile slice = whole.sliceForEvent(LocalDateTime.of(2025, 7, 1, 23, 30), TargetType.SUNRISE);

        // 23:30 UTC on 1 Jul is 00:30 BST on 2 Jul, so the night opens at 21:00 BST on 1 Jul (20:00 UTC) and
        // ends at the 00:00 UTC slot (01:00 BST): five hours. Reading the UTC date would open it a day early.
        assertThat(slice.slotTime()).isEqualTo(LocalDateTime.of(2025, 7, 2, 0, 0));
        assertThat(nightTimes(slice)).hasSize(5).startsWith(LocalDateTime.of(2025, 7, 1, 20, 0))
                .endsWith(LocalDateTime.of(2025, 7, 2, 0, 0));
    }
}
