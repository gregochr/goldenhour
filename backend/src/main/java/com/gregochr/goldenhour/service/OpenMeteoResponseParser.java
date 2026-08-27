package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.exception.IncompleteHourlyDataException;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PressureTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.util.TimeSlotUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure parsing of raw Open-Meteo forecast/air-quality responses into the domain records
 * ({@link AtmosphericData}, {@link MistTrend}, {@link PressureTrend}, {@link SolarCloudTrend},
 * {@link UpwindCloudSample}) at the hourly slot nearest the solar event.
 *
 * <p>Stateless — all methods are pure functions of their arguments (nearest-slot selection,
 * HALF_UP rounding, null/short-list guarding). Extracted from {@code OpenMeteoService} so the
 * parsing rules can be tested in isolation from HTTP orchestration and sampling geometry.
 */
public final class OpenMeteoResponseParser {

    private static final int WIND_SPEED_SCALE = 2;
    private static final int PRECIP_SCALE = 2;
    private static final int RADIATION_SCALE = 2;
    private static final int AOD_SCALE = 3;

    /** Number of hours before the event to sample for the solar trend. */
    private static final int TREND_HOURS_BACK = 3;

    /** Number of hours before the event to include in the mist/visibility trend. */
    private static final int MIST_TREND_HOURS_BACK = 3;

    /** Number of hours after the event to include in the mist/visibility trend. */
    private static final int MIST_TREND_HOURS_FORWARD = 2;

    /** Number of hours before and after the event to sample for pressure tendency. */
    private static final int PRESSURE_TREND_HOURS = 3;

    /** Hours before the event slot summed for "did it rain in the run-up?". */
    private static final int PRECEDING_PRECIP_HOURS = 6;

    private OpenMeteoResponseParser() {
    }

    /**
     * Extracts the forecast values nearest to the solar event time from the API responses.
     *
     * @param forecast       the Open-Meteo forecast response
     * @param airQuality     the Open-Meteo air quality response
     * @param locationName   human-readable location name
     * @param solarEventTime UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return pre-processed atmospheric data for the closest forecast slot
     */
    public static AtmosphericData extractAtmosphericData(OpenMeteoForecastResponse forecast,
            OpenMeteoAirQualityResponse airQuality, String locationName,
            LocalDateTime solarEventTime, TargetType targetType) {
        List<String> times = forecast.getHourly().getTime();
        int idx = TimeSlotUtils.findBestIndex(times, solarEventTime, targetType);

        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        OpenMeteoAirQualityResponse.Hourly aq = airQuality.getHourly();

        Double pm25Raw = getAirQualityValue(aq.getPm25(), idx);
        Double dustRaw = getAirQualityValue(aq.getDust(), idx);
        Double aodRaw = getAirQualityValue(aq.getAerosolOpticalDepth(), idx);

        CloudData cloud = new CloudData(
                requireInt(h.getCloudCoverLow(), idx, "cloudCoverLow"),
                requireInt(h.getCloudCoverMid(), idx, "cloudCoverMid"),
                requireInt(h.getCloudCoverHigh(), idx, "cloudCoverHigh"));

        WeatherData weather = new WeatherData(
                (int) requireDouble(h.getVisibility(), idx, "visibility"),
                BigDecimal.valueOf(requireDouble(h.getWindSpeed10m(), idx, "windSpeed10m"))
                        .setScale(WIND_SPEED_SCALE, RoundingMode.HALF_UP),
                requireInt(h.getWindDirection10m(), idx, "windDirection10m"),
                BigDecimal.valueOf(requireDouble(h.getPrecipitation(), idx, "precipitation"))
                        .setScale(PRECIP_SCALE, RoundingMode.HALF_UP),
                requireInt(h.getRelativeHumidity2m(), idx, "relativeHumidity2m"),
                requireInt(h.getWeatherCode(), idx, "weatherCode"),
                BigDecimal.valueOf(requireDouble(h.getShortwaveRadiation(), idx, "shortwaveRadiation"))
                        .setScale(RADIATION_SCALE, RoundingMode.HALF_UP),
                getDoubleValue(h.getDewPoint2m(), idx),
                getDoubleValue(h.getSurfacePressure(), idx),
                getDoubleValue(h.getSnowfall(), idx),
                getDoubleValue(h.getSnowDepth(), idx),
                getDoubleValue(h.getFreezingLevelHeight(), idx),
                getDoubleValue(h.getTemperature925hPa(), idx),
                getDoubleValue(h.getTemperature850hPa(), idx),
                extractPrecedingPrecipitationMm(h, idx));

        AerosolData aerosol = new AerosolData(
                toDecimal(pm25Raw, PRECIP_SCALE),
                toDecimal(dustRaw, PRECIP_SCALE),
                toDecimal(aodRaw, AOD_SCALE),
                (int) requireDouble(h.getBoundaryLayerHeight(), idx, "boundaryLayerHeight"));

        ComfortData comfort = new ComfortData(
                getDoubleValue(h.getTemperature2m(), idx),
                getDoubleValue(h.getApparentTemperature(), idx),
                getIntegerValue(h.getPrecipitationProbability(), idx));

        MistTrend mistTrend = extractMistTrend(h, idx);
        PressureTrend pressureTrend = extractPressureTrend(h, idx);

        return new AtmosphericData(
                locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort,
                null,  // directionalCloud — populated later for colour locations
                null,  // tide — populated later for coastal locations
                null,  // cloudApproach — populated later if directional data available
                mistTrend, pressureTrend);
    }

    /**
     * Sums precipitation over the {@value #PRECEDING_PRECIP_HOURS} hours before the event slot.
     *
     * <p>Answers a different question from the event-slot {@code precipitation} reading: "did it
     * rain in the run-up?" rather than "is it raining now?". Bluebell scoring wants both — rain
     * earlier freshens the flowers and ground cover, dry at event time makes it shootable — and
     * a single instantaneous value cannot express that combination, since a slot cannot be both
     * wet and dry. The event hour itself is excluded so the two readings stay independent.
     *
     * @param h        the hourly forecast arrays from Open-Meteo
     * @param eventIdx the slot index corresponding to the solar event time
     * @return total mm over the preceding window, or {@code null} if precipitation is unavailable
     *         or the event slot is the first hour in the array (no preceding hours to sum)
     */
    static Double extractPrecedingPrecipitationMm(OpenMeteoForecastResponse.Hourly h,
            int eventIdx) {
        List<Double> precip = h.getPrecipitation();
        if (precip == null || precip.isEmpty()) {
            return null;
        }
        int from = Math.max(0, eventIdx - PRECEDING_PRECIP_HOURS);
        if (from >= eventIdx) {
            return null;
        }
        double total = 0.0;
        for (int i = from; i < eventIdx && i < precip.size(); i++) {
            Double value = precip.get(i);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    /**
     * Extracts an hourly visibility and dew point trend from T-3h through T+2h.
     *
     * <p>Data is sourced from the already-fetched main forecast response — no additional
     * API call required. Returns {@code null} if dew point or visibility data is absent.
     *
     * @param h        the hourly forecast arrays from Open-Meteo
     * @param eventIdx the slot index corresponding to the solar event time
     * @return the trend, or {@code null} if data is insufficient
     */
    public static MistTrend extractMistTrend(OpenMeteoForecastResponse.Hourly h, int eventIdx) {
        List<Double> vis = h.getVisibility();
        List<Double> dew = h.getDewPoint2m();
        List<Double> temp = h.getTemperature2m();

        if (vis == null || dew == null || temp == null) {
            return null;
        }

        List<MistTrend.MistSlot> slots = new ArrayList<>();
        for (int offset = -MIST_TREND_HOURS_BACK; offset <= MIST_TREND_HOURS_FORWARD; offset++) {
            int idx = eventIdx + offset;
            if (idx >= 0 && idx < vis.size() && idx < dew.size() && idx < temp.size()) {
                Double dewVal = dew.get(idx);
                Double tempVal = temp.get(idx);
                if (dewVal != null && tempVal != null) {
                    slots.add(new MistTrend.MistSlot(
                            offset,
                            vis.get(idx).intValue(),
                            dewVal,
                            tempVal));
                }
            }
        }

        return slots.isEmpty() ? null : new MistTrend(slots);
    }

    /**
     * Extracts a pressure tendency trend from T-3h through T+3h using mean sea level pressure.
     *
     * <p>Boundary handling: if T-3 or T+3 falls outside the array, uses the nearest available
     * index. Returns {@code null} only if the pressureMsl list is null or empty.
     *
     * @param h        the hourly forecast arrays from Open-Meteo
     * @param eventIdx the slot index corresponding to the solar event time
     * @return the trend, or {@code null} if pressure data is unavailable
     */
    public static PressureTrend extractPressureTrend(OpenMeteoForecastResponse.Hourly h,
            int eventIdx) {
        List<Double> pressureMsl = h.getPressureMsl();
        if (pressureMsl == null || pressureMsl.isEmpty()) {
            return null;
        }

        List<Double> values = new ArrayList<>();
        for (int offset = -PRESSURE_TREND_HOURS; offset <= PRESSURE_TREND_HOURS; offset++) {
            int idx = Math.max(0, Math.min(eventIdx + offset, pressureMsl.size() - 1));
            Double val = pressureMsl.get(idx);
            if (val != null) {
                values.add(val);
            }
        }

        if (values.isEmpty()) {
            return null;
        }

        double tendencyHpa6h = values.getLast() - values.getFirst();
        String label = PressureTrend.labelFromTendency(tendencyHpa6h);
        return new PressureTrend(values, tendencyHpa6h, label);
    }

    /**
     * Extracts the solar horizon cloud trend from T-3h through T, capturing the low cloud blocker
     * plus the mid and high canvas layers at each hour.
     *
     * <p>The mid/high series lets {@link SolarCloudTrend#isClearing()} distinguish a dramatic
     * clearance (blocker drops, canvas survives) from a wholesale clear (everything falling toward
     * bald blue). Mid/high come from the same forecast response as the low cloud — the cloud-only
     * batch already requests all three layers — so no additional fetch is needed.
     *
     * @param forecast       the Open-Meteo forecast for the solar horizon point
     * @param eventTime      UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return the trend, or {@code null} if no valid slots found
     */
    public static SolarCloudTrend extractSolarTrend(OpenMeteoForecastResponse forecast,
            LocalDateTime eventTime, TargetType targetType) {
        List<String> times = forecast.getHourly().getTime();
        int eventIdx = TimeSlotUtils.findBestIndex(times, eventTime, targetType);

        List<SolarCloudTrend.SolarCloudSlot> slots = new ArrayList<>();
        List<Integer> lowCloud = forecast.getHourly().getCloudCoverLow();
        List<Integer> midCloud = forecast.getHourly().getCloudCoverMid();
        List<Integer> highCloud = forecast.getHourly().getCloudCoverHigh();

        for (int h = TREND_HOURS_BACK; h >= 0; h--) {
            int idx = eventIdx - h;
            if (idx >= 0 && idx < lowCloud.size()) {
                Integer mid = midCloud != null && idx < midCloud.size() ? midCloud.get(idx) : null;
                Integer high = highCloud != null && idx < highCloud.size()
                        ? highCloud.get(idx) : null;
                slots.add(new SolarCloudTrend.SolarCloudSlot(h, lowCloud.get(idx), mid, high));
            }
        }

        return slots.isEmpty() ? null : new SolarCloudTrend(slots);
    }

    /**
     * Extracts low cloud at the upwind point for both current time and event time.
     *
     * @param forecast        the Open-Meteo forecast for the upwind point
     * @param eventTime       UTC time of the solar event
     * @param currentTime     current UTC time
     * @param targetType      SUNRISE or SUNSET
     * @param distanceKm      distance to the upwind point in km
     * @param windFromBearing wind-from bearing in degrees
     * @return the upwind sample
     */
    public static UpwindCloudSample extractUpwindSample(OpenMeteoForecastResponse forecast,
            LocalDateTime eventTime, LocalDateTime currentTime, TargetType targetType,
            int distanceKm, int windFromBearing) {
        List<String> times = forecast.getHourly().getTime();
        List<Integer> lowCloud = forecast.getHourly().getCloudCoverLow();

        int eventIdx = TimeSlotUtils.findBestIndex(times, eventTime, targetType);
        int currentIdx = findNearestIndex(times, currentTime);

        int eventLowCloud = lowCloud.get(eventIdx);
        int currentLowCloud = lowCloud.get(currentIdx);

        return new UpwindCloudSample(distanceKm, windFromBearing,
                currentLowCloud, eventLowCloud);
    }

    private static Double getAirQualityValue(List<Double> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private static Double getDoubleValue(List<Double> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private static Integer getIntegerValue(List<Integer> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    /**
     * Reads a required integer hourly value, throwing rather than returning a default when the
     * series is absent, too short, or holds a null at the resolved index.
     *
     * @param values    the hourly series
     * @param idx       the resolved slot index
     * @param fieldName the field name to report if the value is missing
     * @return the value at {@code idx}
     * @throws IncompleteHourlyDataException if the value is missing
     */
    private static int requireInt(List<Integer> values, int idx, String fieldName) {
        Integer value = getIntegerValue(values, idx);
        if (value == null) {
            throw new IncompleteHourlyDataException(fieldName, idx);
        }
        return value;
    }

    /**
     * Reads a required double hourly value, throwing rather than returning a default when the
     * series is absent, too short, or holds a null at the resolved index.
     *
     * @param values    the hourly series
     * @param idx       the resolved slot index
     * @param fieldName the field name to report if the value is missing
     * @return the value at {@code idx}
     * @throws IncompleteHourlyDataException if the value is missing
     */
    private static double requireDouble(List<Double> values, int idx, String fieldName) {
        Double value = getDoubleValue(values, idx);
        if (value == null) {
            throw new IncompleteHourlyDataException(fieldName, idx);
        }
        return value;
    }

    /**
     * Scales an air-quality reading, preserving absence as {@code null}.
     *
     * <p>⚠️ <b>An absent reading must never become zero.</b> This returned
     * {@link BigDecimal#ZERO} for null until 2026-08-27, which is not a neutral default for these
     * three fields — it is a specific and flattering claim. The system prompt tells Claude
     * {@code AOD thresholds: 0.05-0.15 clean (baseline)}, so a fabricated {@code AOD: 0.000} reads
     * as <em>cleaner than clean</em> rather than as unknown, and {@code PM2.5: 0.00} alongside it
     * completes a picture of pristine air nobody measured.
     *
     * <p>It was not rare. Measured against the live API on 2026-08-27, the forecast array runs 168
     * hours while air quality runs 120, with a null tail from index 109 — so usable aerosol data
     * ends around T+4 13:00 UTC and every later slot was being handed a full set of zeros.
     * {@link #getAirQualityValue} already returns null for both a short array and a null element;
     * this method was discarding that distinction on the very next line.
     *
     * <p>Every consumer was already null-safe — {@code PromptBuilder.isDustElevated} and
     * {@code DustHotTopicStrategy.isDustEnhanced} both guard explicitly, and the persistence
     * columns are nullable. The zero had been <em>defeating</em> those guards rather than
     * satisfying them: a zero passes the null check and then fails the threshold, so the dust
     * signal could never fire on a slot with no data.
     *
     * @param value the raw reading, or {@code null} when absent
     * @param scale decimal scale to apply
     * @return the scaled value, or {@code null} if the reading was absent
     */
    private static BigDecimal toDecimal(Double value, int scale) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Finds the index of the time slot nearest to the target time (absolute nearest, no
     * direction preference).
     *
     * @param times      list of ISO-8601 time strings
     * @param targetTime the target time
     * @return the index of the nearest slot
     */
    private static int findNearestIndex(List<String> times, LocalDateTime targetTime) {
        int bestIdx = 0;
        long minDiff = Long.MAX_VALUE;
        for (int i = 0; i < times.size(); i++) {
            long diff = Math.abs(ChronoUnit.SECONDS.between(
                    LocalDateTime.parse(times.get(i)), targetTime));
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * The event-time steering wind used to place the upwind cloud-approach sample.
     *
     * @param windFromDeg wind-from bearing in degrees
     * @param windSpeedMs wind speed in metres per second
     */
    public record EventWind(int windFromDeg, double windSpeedMs) {
    }

    /**
     * Resolves the event-time wind that positions the upwind cloud-approach sample.
     *
     * <p><strong>This must be the single decider of that wind.</strong> The upwind point is
     * computed twice in the pipeline — once by a prefetcher, to decide which coordinate to fetch
     * into {@code CloudPointCache}, and again by {@code CloudPointCacheReader}, to look that
     * coordinate back up. The lookup is a grid-snapped exact match, so if the two computations
     * start from different wind values they produce different coordinates, the lookup misses, the
     * upwind sample comes back {@code null}, and the cloud-approach veto silently loses one of its
     * two triggers — with no error and no log line.
     *
     * <p>That is why this uses {@link TimeSlotUtils#findBestIndex} rather than
     * {@code findNearestIndex}: the reader's wind arrives via
     * {@link #extractAtmosphericData}, which selects its slot with {@code findBestIndex}. The two
     * differ for roughly half of all events — for a 21:37 sunset {@code findBestIndex} takes the
     * 21:00 slot (latest at or before the event, since the post-sunset slot is useless) while
     * {@code findNearestIndex} takes 22:00 — and at the 200 km cap a bearing difference of about
     * 3 degrees is enough to land in a different 0.1-degree cache cell.
     *
     * @param hourly     the hourly block from the forecast response
     * @param eventTime  UTC time of the solar event
     * @param targetType SUNRISE or SUNSET
     * @return the steering wind, or {@code null} if unavailable at the resolved slot
     */
    public static EventWind resolveEventWind(OpenMeteoForecastResponse.Hourly hourly,
            LocalDateTime eventTime, TargetType targetType) {
        if (hourly == null || hourly.getTime() == null
                || hourly.getWindDirection10m() == null || hourly.getWindSpeed10m() == null) {
            return null;
        }
        int idx = TimeSlotUtils.findBestIndex(hourly.getTime(), eventTime, targetType);
        if (idx < 0 || idx >= hourly.getWindDirection10m().size()
                || idx >= hourly.getWindSpeed10m().size()) {
            return null;
        }
        Integer windDir = hourly.getWindDirection10m().get(idx);
        Double windSpeed = hourly.getWindSpeed10m().get(idx);
        if (windDir == null || windSpeed == null) {
            return null;
        }
        return new EventWind(windDir, windSpeed);
    }
}
