package com.gregochr.goldenhour.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * What one observer, at one latitude and longitude, actually sees of a solar eclipse.
 *
 * <p>Every field here is derived from that eclipse's {@link BesselianElements} for that observer —
 * nothing is seeded per location and nothing is interpolated between locations. Two places forty
 * miles apart get genuinely different contact times and a genuinely different magnitude, which is
 * the whole reason this is computed rather than tabulated: a "94% at 19:13" quoted from London is
 * simply wrong on the Northumberland coast, where it is 91% at 19:06.
 *
 * <h2>Magnitude and obscuration are different quantities and must not be swapped</h2>
 *
 * <p><b>Magnitude</b> is the fraction of the Sun's <em>diameter</em> covered. <b>Obscuration</b> is
 * the fraction of its <em>area</em>. They differ substantially — for the 2026 eclipse over London,
 * 0.924 against 0.912 — and published sources quote both, often without saying which. Popular
 * coverage of this eclipse ("91.4% partial") is quoting obscuration; NASA's canon tabulates
 * magnitude. Whichever a surface prints, it must label it as what it is, because "94% of the sun
 * goes" reads as area to anyone who is not an eclipse chaser.
 *
 * @param magnitude       fraction of the Sun's diameter covered at maximum, 0–1 (values above 1
 *                        mean the observer is inside the path of totality)
 * @param obscuration     fraction of the Sun's area covered at maximum, 0–1
 * @param firstContactUtc when the Moon's limb first touches the Sun's, in UTC
 * @param maximumUtc      when the covered fraction peaks, in UTC
 * @param lastContactUtc  when the limbs separate again, in UTC
 * @param sunAltitudeDeg  the Sun's geometric altitude above the true horizon at maximum, in
 *                        degrees. <b>Geometric</b>: no correction for atmospheric refraction, which
 *                        lifts the apparent Sun by roughly 0.1° at these altitudes — immaterial to
 *                        a figure printed as a whole number of degrees, and stated rather than
 *                        silently applied
 * @param sunAzimuthDeg   the Sun's azimuth at maximum, in degrees clockwise from true north
 */
public record EclipseCircumstances(
        double magnitude,
        double obscuration,
        LocalDateTime firstContactUtc,
        LocalDateTime maximumUtc,
        LocalDateTime lastContactUtc,
        double sunAltitudeDeg,
        double sunAzimuthDeg) {

    /** Magnitude at or above which the observer is inside the path of totality. */
    private static final double TOTAL_MAGNITUDE = 1.0;

    /**
     * Whether the observer is inside the path of totality rather than seeing a partial eclipse.
     *
     * @return true when the Sun's diameter is wholly covered at maximum
     */
    public boolean isTotal() {
        return magnitude >= TOTAL_MAGNITUDE;
    }

    /**
     * How long the Sun is partly covered — first contact to last.
     *
     * @return the duration of the eclipse at this location
     */
    public Duration duration() {
        return Duration.between(firstContactUtc, lastContactUtc);
    }

    /**
     * The Sun's altitude at maximum, rounded to whole degrees for display.
     *
     * @return the rounded altitude in degrees
     */
    public int sunAltitudeRounded() {
        return (int) Math.round(sunAltitudeDeg);
    }

    /**
     * The magnitude as a whole-number percentage, for display.
     *
     * @return magnitude × 100, rounded
     */
    public int magnitudePct() {
        return (int) Math.round(magnitude * 100);
    }

    /**
     * The obscuration as a whole-number percentage, for display.
     *
     * @return obscuration × 100, rounded
     */
    public int obscurationPct() {
        return (int) Math.round(obscuration * 100);
    }
}
