package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.util.TimeSlotUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The valley-floor profile behind the v2 inversion score (inversion-v2-plan Phase 1b): the Open-Meteo cell
 * requested at a location's stored valley-floor point with {@code elevation=<valley floor>}, as hourly
 * valley readings and five pressure levels.
 *
 * <p>Every reading is nullable (a model without 975 or 950 hPa, a short forecast, a failed field). One
 * instance per location holds the whole forecast; {@link #sliceForEvent} cuts it to the event slot and its
 * night side, which is what {@link InversionV2Result} carries on an {@link AtmosphericData}.
 *
 * @param cellLatitude         latitude of the model cell the response resolved to, or null
 * @param cellLongitude        longitude of the model cell, or null
 * @param cellElevationMetres  surface height the response refers to (with {@code elevation=} it echoes the
 *                             request), or null
 * @param valleyFloorMetres    the location's stored valley floor in metres, the walk's reference height h0
 * @param slotTime             the event slot's hour on a sliced profile; null on a whole-forecast profile
 * @param hours                the hourly readings in time order
 */
public record InversionProfile(
        Double cellLatitude,
        Double cellLongitude,
        Double cellElevationMetres,
        int valleyFloorMetres,
        LocalDateTime slotTime,
        List<Hour> hours) {

    /** Hours of the night side examined before a sunrise slot. */
    public static final int SUNRISE_HOURS_BEFORE = 4;

    /** Hours of the night side examined after a sunset slot. */
    public static final int SUNSET_HOURS_AFTER = 2;

    /** The pressure levels read, lowest first. */
    private static final int[] PRESSURE_LEVELS_HPA = {975, 950, 925, 900, 850};

    /** The most the event slot may sit from the event time before the profile is judged not to cover it. */
    private static final Duration MAX_SLOT_DISTANCE = Duration.ofHours(1);

    /**
     * One pressure level at one hour.
     *
     * @param pressureHpa        the level in hPa
     * @param temperatureCelsius temperature at the level, or null
     * @param heightMetres       geopotential height of the level, or null
     */
    public record Level(int pressureHpa, Double temperatureCelsius, Double heightMetres) {
    }

    /**
     * One hour of the valley cell.
     *
     * @param time               the UTC hour
     * @param temperatureCelsius 2 m temperature, or null
     * @param dewPointCelsius    2 m dew point, or null
     * @param lowCloudPercent    low cloud cover, or null
     * @param visibilityMetres   visibility, or null
     * @param windSpeedMs        10 m wind speed in m/s, or null
     * @param levels             the pressure levels, lowest first (975, 950, 925, 900, 850 hPa)
     */
    public record Hour(
            LocalDateTime time,
            Double temperatureCelsius,
            Double dewPointCelsius,
            Integer lowCloudPercent,
            Double visibilityMetres,
            Double windSpeedMs,
            List<Level> levels) {
    }

    /**
     * Builds a whole-forecast profile from a valley-floor response.
     *
     * @param response          the Open-Meteo response for the valley-floor point, or null
     * @param valleyFloorMetres the stored valley floor in metres
     * @return the profile, or null when the response carries no hourly series
     */
    public static InversionProfile from(OpenMeteoForecastResponse response, int valleyFloorMetres) {
        if (response == null || response.getHourly() == null || response.getHourly().getTime() == null
                || response.getHourly().getTime().isEmpty()) {
            return null;
        }
        OpenMeteoForecastResponse.Hourly h = response.getHourly();
        List<List<Double>> temperatures = List.of(
                orEmpty(h.getTemperature975hPa()), orEmpty(h.getTemperature950hPa()),
                orEmpty(h.getTemperature925hPa()), orEmpty(h.getTemperature900hPa()),
                orEmpty(h.getTemperature850hPa()));
        List<List<Double>> heights = List.of(
                orEmpty(h.getGeopotentialHeight975hPa()), orEmpty(h.getGeopotentialHeight950hPa()),
                orEmpty(h.getGeopotentialHeight925hPa()), orEmpty(h.getGeopotentialHeight900hPa()),
                orEmpty(h.getGeopotentialHeight850hPa()));
        List<Hour> hours = new ArrayList<>(h.getTime().size());
        for (int i = 0; i < h.getTime().size(); i++) {
            List<Level> levels = new ArrayList<>(PRESSURE_LEVELS_HPA.length);
            for (int l = 0; l < PRESSURE_LEVELS_HPA.length; l++) {
                levels.add(new Level(PRESSURE_LEVELS_HPA[l], at(temperatures.get(l), i), at(heights.get(l), i)));
            }
            hours.add(new Hour(
                    LocalDateTime.parse(h.getTime().get(i)),
                    at(h.getTemperature2m(), i),
                    at(h.getDewPoint2m(), i),
                    at(h.getCloudCoverLow(), i),
                    at(h.getVisibility(), i),
                    at(h.getWindSpeed10m(), i),
                    List.copyOf(levels)));
        }
        return new InversionProfile(response.getLatitude(), response.getLongitude(), response.getElevation(),
                valleyFloorMetres, null, List.copyOf(hours));
    }

    /**
     * Cuts the profile to the event slot and its night side: the {@value #SUNRISE_HOURS_BEFORE} hours up to
     * a sunrise slot, or the {@value #SUNSET_HOURS_AFTER} hours from a sunset slot. The slot is chosen by
     * the same rule as the main forecast ({@link TimeSlotUtils#findBestIndex}).
     *
     * @param eventTime  UTC time of the solar event
     * @param targetType SUNRISE or SUNSET
     * @return the sliced profile, or null when the profile does not cover the event or the target is not a
     *         solar event
     */
    public InversionProfile sliceForEvent(LocalDateTime eventTime, TargetType targetType) {
        if (hours.isEmpty() || (targetType != TargetType.SUNRISE && targetType != TargetType.SUNSET)) {
            return null;
        }
        List<String> times = hours.stream().map(hour -> hour.time().toString()).toList();
        int slotIndex = TimeSlotUtils.findBestIndex(times, eventTime, targetType);
        LocalDateTime slot = hours.get(slotIndex).time();
        if (Duration.between(slot, eventTime).abs().compareTo(MAX_SLOT_DISTANCE) > 0) {
            return null;
        }
        LocalDateTime from = targetType == TargetType.SUNRISE ? slot.minusHours(SUNRISE_HOURS_BEFORE) : slot;
        LocalDateTime to = targetType == TargetType.SUNRISE ? slot : slot.plusHours(SUNSET_HOURS_AFTER);
        List<Hour> window = hours.stream()
                .filter(hour -> !hour.time().isBefore(from) && !hour.time().isAfter(to))
                .toList();
        return new InversionProfile(cellLatitude, cellLongitude, cellElevationMetres, valleyFloorMetres, slot,
                window);
    }

    /**
     * Returns the hour at {@link #slotTime()}.
     *
     * @return the event slot's hour, or null on a whole-forecast profile or when the slice lacks it
     */
    public Hour slotHour() {
        if (slotTime == null) {
            return null;
        }
        return hours.stream().filter(hour -> hour.time().equals(slotTime)).findFirst().orElse(null);
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> T at(List<T> values, int index) {
        return values == null || index < 0 || index >= values.size() ? null : values.get(index);
    }
}
