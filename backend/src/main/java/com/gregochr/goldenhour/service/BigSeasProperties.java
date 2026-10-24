package com.gregochr.goldenhour.service;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to {@code photocast.big-seas} (docs/engineering/big-seas-plan.md §3 B2).
 *
 * <p>Every key is optional and every default lives here in Java, because the host
 * {@code application.yml} is gitignored. Each setter enforces its range, so an out-of-range value
 * fails startup (the binder wraps the {@link IllegalArgumentException}) exactly as
 * {@link NeighbourGuardProperties} does. The thresholds are read at serve time from the numbers
 * {@code marine_high_water} stores, so retuning one needs no refetch; only {@code halfWindowMinutes}
 * shapes what is stored, and a change to it affects later refreshes only.
 */
@Component
@ConfigurationProperties(prefix = "photocast.big-seas")
@Getter
public class BigSeasProperties implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(BigSeasProperties.class);

    /** Smallest wave-height threshold accepted, in metres. */
    public static final double MIN_WAVE_HEIGHT_M = 0.5;
    /** Largest wave-height threshold accepted, in metres. */
    public static final double MAX_WAVE_HEIGHT_M = 15.0;
    /** Smallest onshore angle accepted, in degrees. */
    public static final int MIN_ONSHORE_ANGLE_DEG = 0;
    /** Largest onshore angle accepted, in degrees. */
    public static final int MAX_ONSHORE_ANGLE_DEG = 180;
    /** Smallest wave-period threshold accepted, in seconds. */
    public static final double MIN_WAVE_PERIOD_S = 1.0;
    /** Largest wave-period threshold accepted, in seconds. */
    public static final double MAX_WAVE_PERIOD_S = 30.0;
    /** Smallest high-water half-window accepted, in minutes. */
    public static final int MIN_HALF_WINDOW_MINUTES = 15;
    /** Largest high-water half-window accepted, in minutes. */
    public static final int MAX_HALF_WINDOW_MINUTES = 180;
    /** Smallest sample age bound accepted, in hours. */
    public static final int MIN_SAMPLE_AGE_HOURS = 1;
    /** Largest sample age bound accepted, in hours. */
    public static final int MAX_SAMPLE_AGE_HOURS = 168;

    private double minWaveHeightM = 3.0;
    private int maxOnshoreAngleDeg = 60;
    private double minWavePeriodS = 8.0;
    private int halfWindowMinutes = 90;
    private int maxSampleAgeHours = 30;

    /**
     * Sets the smallest wave height that counts as a big sea.
     *
     * @param minWaveHeightM 0.5 to 15 metres
     * @throws IllegalArgumentException if out of range or not a number
     */
    public void setMinWaveHeightM(double minWaveHeightM) {
        this.minWaveHeightM = within("min-wave-height-m", minWaveHeightM, MIN_WAVE_HEIGHT_M, MAX_WAVE_HEIGHT_M);
    }

    /**
     * Sets the widest angle between the wave direction and the way a structure faces.
     *
     * @param maxOnshoreAngleDeg 0 to 180 degrees
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxOnshoreAngleDeg(int maxOnshoreAngleDeg) {
        this.maxOnshoreAngleDeg = within("max-onshore-angle-deg", maxOnshoreAngleDeg,
                MIN_ONSHORE_ANGLE_DEG, MAX_ONSHORE_ANGLE_DEG);
    }

    /**
     * Sets the shortest wave period that counts as a big sea.
     *
     * @param minWavePeriodS 1 to 30 seconds
     * @throws IllegalArgumentException if out of range or not a number
     */
    public void setMinWavePeriodS(double minWavePeriodS) {
        this.minWavePeriodS = within("min-wave-period-s", minWavePeriodS, MIN_WAVE_PERIOD_S, MAX_WAVE_PERIOD_S);
    }

    /**
     * Sets the half-width of the window sampled around each high water.
     *
     * @param halfWindowMinutes 15 to 180 minutes
     * @throws IllegalArgumentException if out of range
     */
    public void setHalfWindowMinutes(int halfWindowMinutes) {
        this.halfWindowMinutes = within("half-window-minutes", halfWindowMinutes,
                MIN_HALF_WINDOW_MINUTES, MAX_HALF_WINDOW_MINUTES);
    }

    /**
     * Sets how old a stored sample may be before it is no longer served.
     *
     * @param maxSampleAgeHours 1 to 168 hours
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxSampleAgeHours(int maxSampleAgeHours) {
        this.maxSampleAgeHours = within("max-sample-age-hours", maxSampleAgeHours,
                MIN_SAMPLE_AGE_HOURS, MAX_SAMPLE_AGE_HOURS);
    }

    /** Logs the effective configuration once, as bound. */
    @Override
    public void afterPropertiesSet() {
        LOG.info("[BIG SEAS] minWaveHeightM={} maxOnshoreAngleDeg={} minWavePeriodS={} halfWindowMinutes={}"
                + " maxSampleAgeHours={}", minWaveHeightM, maxOnshoreAngleDeg, minWavePeriodS,
                halfWindowMinutes, maxSampleAgeHours);
    }

    private static int within(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("photocast.big-seas." + key + " must be " + min
                    + ".." + max + " but was " + value);
        }
        return value;
    }

    private static double within(String key, double value, double min, double max) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException("photocast.big-seas." + key + " must be " + min
                    + ".." + max + " but was " + value);
        }
        return value;
    }
}
