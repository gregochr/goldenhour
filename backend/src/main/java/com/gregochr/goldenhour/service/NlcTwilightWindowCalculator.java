package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.NlcWindow;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Computes the two nightly noctilucent-cloud (NLC) twilight windows for a location.
 *
 * <p>NLC are only visible while the sun sits between <strong>6° and 16° below the horizon</strong>:
 * high enough to still light the mesosphere (~80&nbsp;km), low enough that the sky near the horizon
 * has darkened. That geometry occurs twice a night — low in the <strong>NW after dusk</strong> and
 * low in the <strong>NE before dawn</strong>. This is exact solar geometry, the honest "when to
 * look" signal; it never implies NLC will actually appear.
 *
 * <p>{@code solar-utils} only exposes civil twilight (−6°), so this calculator computes the sun's
 * altitude directly (standard NOAA low-precision algorithm, accurate to a fraction of a degree —
 * ample for minute-level twilight timing) and bisects for the −6° and −16° crossings within each
 * monotonic half of the night. Either window may be absent on a deep-summer night when the sun
 * never drops to −16° (only a partial window exists) or never even reaches −6° (a white night).
 */
@Component
public class NlcTwilightWindowCalculator {

    /** Upper twilight bound: sun 6° below the horizon (mesosphere lit, lower sky darkening). */
    private static final double UPPER_DEPRESSION_DEG = -6.0;

    /** Lower twilight bound: sun 16° below the horizon (sky too dark below this). */
    private static final double LOWER_DEPRESSION_DEG = -16.0;

    /** Bisection convergence tolerance in seconds — one minute is finer than we display. */
    private static final long BISECTION_TOLERANCE_SECONDS = 30;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final SolarService solarService;

    /**
     * Constructs the calculator.
     *
     * @param solarService sunrise/sunset provider used to bound each night
     */
    public NlcTwilightWindowCalculator(SolarService solarService) {
        this.solarService = solarService;
    }

    /**
     * Computes the evening (NW) and morning (NE) NLC windows for the night beginning on the given
     * evening date at the given location.
     *
     * @param lat         latitude in decimal degrees
     * @param lon         longitude in decimal degrees
     * @param eveningDate the evening's date; the night runs into the following morning
     * @return the two windows (either may be null); never null itself
     */
    public NlcWindows compute(double lat, double lon, LocalDate eveningDate) {
        LocalDateTime sunset = solarService.sunsetUtc(lat, lon, eveningDate);
        LocalDateTime sunrise = solarService.sunriseUtc(lat, lon, eveningDate.plusDays(1));
        if (sunset == null || sunrise == null) {
            return new NlcWindows(null, null); // polar day/night — no ordinary twilight
        }
        Instant duskStart = sunset.toInstant(ZoneOffset.UTC);
        Instant dawnEnd = sunrise.toInstant(ZoneOffset.UTC);
        if (!duskStart.isBefore(dawnEnd)) {
            return new NlcWindows(null, null);
        }
        // Solar midnight ≈ the midpoint between sunset and the next sunrise: the darkest instant,
        // and the boundary between the descending (evening) and ascending (morning) halves.
        Instant solarMidnight = duskStart.plus(java.time.Duration.between(duskStart, dawnEnd).dividedBy(2));

        NlcWindow evening = eveningWindow(lat, lon, duskStart, solarMidnight);
        NlcWindow morning = morningWindow(lat, lon, solarMidnight, dawnEnd);
        return new NlcWindows(evening, morning);
    }

    /**
     * Evening window (NW): between sunset and solar midnight the sun's altitude falls monotonically.
     * The window opens as it passes −6° and closes at −16° (or at solar midnight if it never gets
     * that low).
     */
    private NlcWindow eveningWindow(double lat, double lon, Instant duskStart, Instant solarMidnight) {
        Instant start = crossing(lat, lon, duskStart, solarMidnight, UPPER_DEPRESSION_DEG);
        if (start == null) {
            return null; // sun never drops to −6° — a white night, no window
        }
        Instant end = crossing(lat, lon, duskStart, solarMidnight, LOWER_DEPRESSION_DEG);
        if (end == null) {
            end = solarMidnight; // never reaches −16° — window runs to the darkest point
        }
        return start.isBefore(end) ? window(start, end, "NW") : null;
    }

    /**
     * Morning window (NE): between solar midnight and sunrise the sun's altitude rises monotonically.
     * The window opens at −16° (or at solar midnight if the sun never got that low) and closes as it
     * passes −6°.
     */
    private NlcWindow morningWindow(double lat, double lon, Instant solarMidnight, Instant dawnEnd) {
        Instant end = crossing(lat, lon, solarMidnight, dawnEnd, UPPER_DEPRESSION_DEG);
        if (end == null) {
            return null; // sun never climbs back above −6° before this bound — no window
        }
        Instant start = crossing(lat, lon, solarMidnight, dawnEnd, LOWER_DEPRESSION_DEG);
        if (start == null) {
            start = solarMidnight; // never dropped below −16° — window opens at the darkest point
        }
        return start.isBefore(end) ? window(start, end, "NE") : null;
    }

    /**
     * Bisects for the instant in {@code [from, to]} where the sun's altitude equals {@code target},
     * assuming altitude is monotonic across the interval. Returns null when the target is not
     * crossed (both endpoints on the same side of it).
     */
    private Instant crossing(double lat, double lon, Instant from, Instant to, double target) {
        double altFrom = solarAltitudeDeg(lat, lon, from) - target;
        double altTo = solarAltitudeDeg(lat, lon, to) - target;
        if (altFrom == 0) {
            return from;
        }
        if (altTo == 0) {
            return to;
        }
        if ((altFrom > 0) == (altTo > 0)) {
            return null; // target not crossed within the interval
        }
        Instant lo = from;
        Instant hi = to;
        while (java.time.Duration.between(lo, hi).getSeconds() > BISECTION_TOLERANCE_SECONDS) {
            Instant mid = lo.plus(java.time.Duration.between(lo, hi).dividedBy(2));
            double altMid = solarAltitudeDeg(lat, lon, mid) - target;
            if ((altMid > 0) == (altFrom > 0)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo.plus(java.time.Duration.between(lo, hi).dividedBy(2));
    }

    private static NlcWindow window(Instant start, Instant end, String azimuth) {
        return new NlcWindow(toLondon(start), toLondon(end), azimuth);
    }

    private static String toLondon(Instant instant) {
        return instant.atZone(LONDON).toLocalTime().format(HH_MM);
    }

    /**
     * Returns the sun's altitude (degrees above the horizon; negative below) at the given instant,
     * using the standard NOAA low-precision solar-position algorithm.
     *
     * <p>Package-private and static so the astronomy can be unit-tested directly and cross-checked
     * against {@code solar-utils} civil twilight (altitude ≈ −6° at civil dawn/dusk).
     *
     * @param lat     latitude in decimal degrees
     * @param lon     longitude in decimal degrees (east positive)
     * @param instant the UTC instant
     * @return the solar altitude in degrees
     */
    static double solarAltitudeDeg(double lat, double lon, Instant instant) {
        double jd = instant.getEpochSecond() / 86400.0 + 2440587.5;
        double n = jd - 2451545.0;

        double meanLongitude = normalizeDeg(280.460 + 0.9856474 * n);
        double meanAnomaly = Math.toRadians(normalizeDeg(357.528 + 0.9856003 * n));
        double eclipticLongitude = Math.toRadians(meanLongitude
                + 1.915 * Math.sin(meanAnomaly)
                + 0.020 * Math.sin(2 * meanAnomaly));
        double obliquity = Math.toRadians(23.439 - 0.0000004 * n);

        double declination = Math.asin(Math.sin(obliquity) * Math.sin(eclipticLongitude));
        double rightAscension = Math.atan2(
                Math.cos(obliquity) * Math.sin(eclipticLongitude), Math.cos(eclipticLongitude));

        double gmstHours = normalizeHours(18.697374558 + 24.06570982441908 * n);
        double localSiderealDeg = normalizeDeg(gmstHours * 15.0 + lon);
        double hourAngle = Math.toRadians(normalizeToSigned(localSiderealDeg - Math.toDegrees(rightAscension)));

        double latRad = Math.toRadians(lat);
        double sinAlt = Math.sin(latRad) * Math.sin(declination)
                + Math.cos(latRad) * Math.cos(declination) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))));
    }

    private static double normalizeDeg(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static double normalizeHours(double hours) {
        double h = hours % 24.0;
        return h < 0 ? h + 24.0 : h;
    }

    /** Normalises degrees to the signed range (−180, 180]. */
    private static double normalizeToSigned(double deg) {
        double d = normalizeDeg(deg);
        return d > 180.0 ? d - 360.0 : d;
    }

    /**
     * The pair of NLC twilight windows for one night. Either field may be null when the
     * corresponding geometry does not exist that night.
     *
     * @param evening the after-dusk NW window, or null
     * @param morning the before-dawn NE window, or null
     */
    public record NlcWindows(NlcWindow evening, NlcWindow morning) {

        /**
         * Whether at least one window exists — the "geometry available" half of the emit gate.
         *
         * @return true if either window is present
         */
        public boolean hasAny() {
            return evening != null || morning != null;
        }
    }
}
