package com.gregochr.goldenhour.util;

import java.util.List;
import java.util.Optional;

/**
 * A point snowpack, walked hour by hour from the place's own weather (snow-topics-truth-plan §3.1).
 *
 * <p>Open-Meteo's {@code snow_depth} is a 9 km grid-cell value, not the place's. This models the pack from the
 * place's own downscaled temperature and humidity and its precipitation instead, over a fixed
 * {@value #LOOKBACK_HOURS}-hour lookback ending at the slot hour, so the same slot always sees the same window
 * whatever time the run ran.
 *
 * <ol>
 *   <li><b>Wet-bulb</b> {@code Tw} from T2m and RH (Stull 2011), dew point {@code Td} (Magnus).</li>
 *   <li><b>Snow fraction</b> {@code f}: 1 at {@code Tw <=} {@value #SNOW_FULL_TW_C} C, 0 at
 *       {@code Tw >=} {@value #SNOW_NONE_TW_C} C, linear between.</li>
 *   <li><b>Settling gate</b>: the hour's snow water {@code s = precip x f} is added only if
 *       {@code T2m <=} {@value #SETTLE_ALWAYS_MAX_C} C, or if {@code T2m <=} {@value #SETTLE_HEAVY_MAX_C} C
 *       and {@code s >=} {@value #SETTLE_HEAVY_MIN_MM} mm. Never above {@value #SETTLE_HEAVY_MAX_C} C.</li>
 *   <li><b>Melt</b> when {@code T2m > 0}: {@code swe -= DDF / 24 x T2m}, with {@code DDF} 3 mm/C/day; 6 when
 *       {@code Td > 0} and wind is at least 5 m/s; at least 5 when shortwave exceeds 200 W/m2. Floored at 0; no
 *       refreeze.</li>
 *   <li><b>Outputs</b> at the slot hour: the water equivalent and the snow added in the 24 hours ending at the
 *       slot hour (the slot hour included) times {@value #CM_PER_MM}.</li>
 * </ol>
 *
 * <p>The result is empty (never zero) when the lookback is not covered by the series or more than 10 % of its
 * hours lack an input; "unknown" never fires a topic. Pure and stateless.
 */
public final class PointSnowpack {

    /** Hours walked, ending at (and including) the slot hour. */
    public static final int LOOKBACK_HOURS = 168;

    /** Hours of fresh snow reported in {@link Result#snowfall24hCm()}, ending at (and including) the slot hour. */
    public static final int FRESH_WINDOW_HOURS = 24;

    /**
     * Most hours of the lookback that may lack an input: 10 % of {@value #LOOKBACK_HOURS} rounded down (16.8 is 16),
     * so 16 missing hours still compute and 17 do not.
     */
    public static final int MAX_MISSING_HOURS = LOOKBACK_HOURS / 10;

    /** Water equivalent (mm) at which snow is lying at a place: about 2 cm fresh. */
    public static final double LYING_MM = 3.0;

    /** Water equivalent (mm) at which a summit reads as white; 2 cm does not whiten heather and tussock. */
    public static final double SUMMIT_WHITE_MM = 5.0;

    /** Centimetres of fresh snow per millimetre of water (7 cm per 10 mm). */
    public static final double CM_PER_MM = 0.7;

    /** Wet-bulb (C) at or below which all precipitation is snow. */
    static final double SNOW_FULL_TW_C = 0.5;

    /** Wet-bulb (C) at or above which no precipitation is snow. */
    static final double SNOW_NONE_TW_C = 1.5;

    /** Air temperature (C) up to which any snow settles. */
    static final double SETTLE_ALWAYS_MAX_C = 1.0;

    /** Air temperature (C) above which no snow settles; between the two it must be heavy. */
    static final double SETTLE_HEAVY_MAX_C = 2.0;

    /** Snow water (mm in the hour) at which snow settles between the two temperatures. */
    static final double SETTLE_HEAVY_MIN_MM = 2.0;

    /** Base melt, mm of water per degree C per day. */
    static final double DDF_BASE = 3.0;

    /** Melt with condensation onto the snow (dew point above 0 and a breeze), mm per degree C per day. */
    static final double DDF_CONDENSING = 6.0;

    /** Melt floor under strong sun, mm per degree C per day. */
    static final double DDF_SUNNY_FLOOR = 5.0;

    /** Wind (m/s) at which condensation melt applies. */
    static final double CONDENSING_WIND_MS = 5.0;

    /** Shortwave radiation (W/m2) above which the sunny melt floor applies. */
    static final double SUNNY_SHORTWAVE_WM2 = 200.0;

    private static final double HOURS_PER_DAY = 24.0;

    /** Lowest humidity (%) fed to the wet-bulb and dew point formulas; zero would break the logarithm. */
    private static final double MIN_RH_PCT = 1.0;

    private static final double MAX_RH_PCT = 100.0;

    // Stull (2011), J. Appl. Meteorol. Climatol. 50, 2267: wet-bulb from T (C) and RH (%).
    private static final double STULL_A = 0.151977;
    private static final double STULL_B = 8.313659;
    private static final double STULL_C = 1.676331;
    private static final double STULL_D = 0.00391838;
    private static final double STULL_E = 0.023101;
    private static final double STULL_F = 4.686035;
    private static final double STULL_RH_POWER = 1.5;

    // Magnus formula (Sonntag 1990 coefficients) for the dew point.
    private static final double MAGNUS_A = 17.62;
    private static final double MAGNUS_B = 243.12;

    private PointSnowpack() {
    }

    /**
     * One hour of the place's weather. Any field may be null (a missing reading).
     *
     * @param precipitationMm  precipitation in the hour, mm
     * @param temperatureC     2 m air temperature, degrees C
     * @param relativeHumidity 2 m relative humidity, percent
     * @param windMs           10 m wind speed, metres per second (not km/h)
     * @param shortwaveWm2     shortwave radiation, W/m2
     */
    public record Hour(Double precipitationMm, Double temperatureC, Double relativeHumidity, Double windMs,
            Double shortwaveWm2) {

        /**
         * Whether any input is missing.
         *
         * @return true if one of the five readings is null or not a number
         */
        boolean lacksAnInput() {
            return isMissing(precipitationMm) || isMissing(temperatureC) || isMissing(relativeHumidity)
                    || isMissing(windMs) || isMissing(shortwaveWm2);
        }

        private static boolean isMissing(Double value) {
            return value == null || value.isNaN();
        }
    }

    /**
     * The pack at the slot hour.
     *
     * @param snowWaterMm   modelled snow water equivalent, mm
     * @param snowfall24hCm snow added in the 24 hours ending at the slot hour, cm (fresh-snow ratio)
     */
    public record Result(double snowWaterMm, double snowfall24hCm) {
    }

    /**
     * Walks the {@value #LOOKBACK_HOURS} hours ending at {@code slotIndex}, inclusive.
     *
     * @param series    consecutive hourly readings, oldest first; an entry may be null for a missing hour
     * @param slotIndex index in {@code series} of the slot hour
     * @return the pack, or empty when the lookback is not covered or more than 10 % of its hours lack an input
     */
    public static Optional<Result> compute(List<Hour> series, int slotIndex) {
        int start = slotIndex - (LOOKBACK_HOURS - 1);
        if (series == null || start < 0 || slotIndex >= series.size()) {
            return Optional.empty();
        }
        int missing = 0;
        for (int i = start; i <= slotIndex; i++) {
            Hour hour = series.get(i);
            if (hour == null || hour.lacksAnInput()) {
                missing++;
            }
        }
        if (missing > MAX_MISSING_HOURS) {
            return Optional.empty();
        }
        double water = 0.0;
        double added24h = 0.0;
        int freshFrom = slotIndex - (FRESH_WINDOW_HOURS - 1);
        for (int i = start; i <= slotIndex; i++) {
            Hour hour = series.get(i);
            if (hour == null || hour.lacksAnInput()) {
                continue;
            }
            double temperature = hour.temperatureC();
            double humidity = clampHumidity(hour.relativeHumidity());
            double snowWater = Math.max(0.0, hour.precipitationMm())
                    * snowFraction(wetBulbC(temperature, humidity));
            if (settles(temperature, snowWater)) {
                water += snowWater;
                if (i >= freshFrom) {
                    added24h += snowWater;
                }
            }
            if (temperature > 0.0) {
                double ddf = degreeDayFactor(dewPointC(temperature, humidity), hour.windMs(), hour.shortwaveWm2());
                water = Math.max(0.0, water - ddf / HOURS_PER_DAY * temperature);
            }
        }
        return Optional.of(new Result(water, added24h * CM_PER_MM));
    }

    /**
     * Wet-bulb temperature (Stull 2011).
     *
     * @param temperatureC     air temperature, degrees C
     * @param relativeHumidity relative humidity, percent (clamped to 1 to 100)
     * @return the wet-bulb temperature, degrees C
     */
    public static double wetBulbC(double temperatureC, double relativeHumidity) {
        double rh = clampHumidity(relativeHumidity);
        return temperatureC * Math.atan(STULL_A * Math.sqrt(rh + STULL_B))
                + Math.atan(temperatureC + rh)
                - Math.atan(rh - STULL_C)
                + STULL_D * Math.pow(rh, STULL_RH_POWER) * Math.atan(STULL_E * rh)
                - STULL_F;
    }

    /**
     * Dew point (Magnus).
     *
     * @param temperatureC     air temperature, degrees C
     * @param relativeHumidity relative humidity, percent (clamped to 1 to 100)
     * @return the dew point, degrees C
     */
    public static double dewPointC(double temperatureC, double relativeHumidity) {
        double rh = clampHumidity(relativeHumidity);
        double gamma = Math.log(rh / MAX_RH_PCT) + MAGNUS_A * temperatureC / (MAGNUS_B + temperatureC);
        return MAGNUS_B * gamma / (MAGNUS_A - gamma);
    }

    /**
     * The share of precipitation that falls as snow: 1 at a wet-bulb of 0.5 C or below, 0 at 1.5 C or above,
     * linear between.
     *
     * @param wetBulbC the wet-bulb temperature, degrees C
     * @return the snow fraction, 0 to 1
     */
    public static double snowFraction(double wetBulbC) {
        if (wetBulbC <= SNOW_FULL_TW_C) {
            return 1.0;
        }
        if (wetBulbC >= SNOW_NONE_TW_C) {
            return 0.0;
        }
        return (SNOW_NONE_TW_C - wetBulbC) / (SNOW_NONE_TW_C - SNOW_FULL_TW_C);
    }

    /**
     * The settling gate: falling is not settling.
     *
     * @param temperatureC air temperature, degrees C
     * @param snowWaterMm  the hour's snow water, mm
     * @return true when the hour's snow is added to the pack
     */
    static boolean settles(double temperatureC, double snowWaterMm) {
        if (temperatureC <= SETTLE_ALWAYS_MAX_C) {
            return true;
        }
        return temperatureC <= SETTLE_HEAVY_MAX_C && snowWaterMm >= SETTLE_HEAVY_MIN_MM;
    }

    /**
     * The degree-day melt factor for an hour above freezing.
     *
     * @param dewPointC    dew point, degrees C
     * @param windMs       wind speed, m/s
     * @param shortwaveWm2 shortwave radiation, W/m2
     * @return mm of water per degree C per day
     */
    static double degreeDayFactor(double dewPointC, double windMs, double shortwaveWm2) {
        double ddf = DDF_BASE;
        if (dewPointC > 0.0 && windMs >= CONDENSING_WIND_MS) {
            ddf = DDF_CONDENSING;
        }
        if (shortwaveWm2 > SUNNY_SHORTWAVE_WM2) {
            ddf = Math.max(ddf, DDF_SUNNY_FLOOR);
        }
        return ddf;
    }

    private static double clampHumidity(double relativeHumidity) {
        return Math.min(MAX_RH_PCT, Math.max(MIN_RH_PCT, relativeHumidity));
    }
}
