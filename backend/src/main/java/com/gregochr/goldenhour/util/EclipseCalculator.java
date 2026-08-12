package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.model.BesselianElements;
import com.gregochr.goldenhour.model.EclipseCircumstances;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Turns one eclipse's published {@link BesselianElements} into what a given observer sees.
 *
 * <p>This is the standard local-circumstances reduction (Explanatory Supplement to the
 * Astronomical Almanac; Meeus, <i>Astronomical Algorithms</i> ch. 54). The observer's geocentric
 * position is projected onto the fundamental plane — the plane through the Earth's centre
 * perpendicular to the shadow axis — giving (ξ, η, ζ). The distance from the observer's projection
 * to the shadow axis, compared against the penumbral radius at the observer's own distance from
 * that plane, is the whole of the geometry.
 *
 * <h2>Why the times are found numerically</h2>
 *
 * <p>Meeus solves for maximum and for the contacts by Newton iteration on the derivatives of the
 * elements. This class instead scans and then brackets: a one-minute sweep to locate the minimum
 * separation, a ternary search to refine it, and bisection either side for the contacts. The result
 * agrees with the published London circumstances to within a second, needs no derivative
 * polynomials, and cannot diverge — an iteration that fails to converge on a grazing eclipse is a
 * real failure mode of the analytic form, and the one place a photography app would rather be slow
 * than wrong. The cost is microseconds, once, for a table of seeded eclipses.
 *
 * <h2>What is deliberately not modelled</h2>
 *
 * <p><b>Observer height.</b> Elevation shifts contact times by well under a second at UK altitudes
 * and the field is null for every location in the local dataset, so it is omitted rather than
 * plumbed through as a parameter nothing supplies.
 *
 * <p><b>Refraction.</b> {@link EclipseCircumstances#sunAltitudeDeg()} is geometric. See that
 * record's own note.
 *
 * <p><b>Lunar limb profile.</b> Contact times assume a smooth Moon. The real limb's mountains and
 * valleys move the instant of contact by a second or two, and matter only for grazing eclipses and
 * Baily's beads — neither of which this app reports.
 */
public final class EclipseCalculator {

    /**
     * Flattening ratio of the Earth's ellipsoid, {@code b/a} — the constant Besselian reductions
     * use to turn a geodetic latitude into a geocentric one.
     */
    private static final double FLATTENING = 0.99664719;

    /** Hours either side of the reference hour searched for the eclipse. */
    private static final double SEARCH_SPAN_HOURS = 6.0;

    /** Step of the coarse sweep that locates the minimum separation, in hours (one minute). */
    private static final double COARSE_STEP_HOURS = 1.0 / 60.0;

    /** Ternary-search iterations refining the time of maximum — converges well below a second. */
    private static final int MAXIMUM_REFINEMENTS = 60;

    /** Bisection iterations locating each contact — converges well below a second. */
    private static final int CONTACT_REFINEMENTS = 60;

    /**
     * The Sun's geometric altitude at sunrise and sunset, in degrees — negative because the Sun is
     * geometrically below the horizon when its upper limb appears to touch it, by roughly 34' of
     * atmospheric refraction plus its own 16' semidiameter.
     *
     * <p><b>This value is not chosen here; it is copied.</b> {@code solar-utils} computes sunrise and
     * sunset at a zenith of 90.833°, which is this altitude, and {@code SolarService} is what the
     * eclipse surfaces call to print "sun sets 20:52". Clamping at a geometric zero instead — the
     * obvious choice, and the one this method shipped first — put the two definitions 15 minutes
     * apart on the 2028 eclipse: the clamped maximum came out at 16:30 while the pill's own sunset
     * chip said 16:45, so the pill implied the eclipse peaked before sunset when in fact the Sun was
     * still up and the eclipse still deepening. Sharing the horizon makes them agree by construction.
     */
    private static final double SUNSET_ALTITUDE_DEG = -0.833;

    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final int NANOS_PER_SECOND = 1_000_000_000;
    private static final double DEGREES_PER_CIRCLE = 360.0;
    private static final double HALF = 2.0;

    private EclipseCalculator() {
    }

    /**
     * The eclipse as seen from one place, or empty when nothing of it can be seen there.
     *
     * <p><b>The maximum reported is the greatest eclipse VISIBLE from here, which is not always the
     * greatest eclipse.</b> An eclipse can peak while the Sun is below the observer's horizon: on
     * 2028 January 26 the deepest coverage over London falls at 16:49 UT with the Sun already
     * 2.4° set, and on 2029 June 12 the peak is before sunrise almost everywhere in Britain. Taking
     * the astronomical maximum regardless would have this method report a coverage, a bearing and an
     * altitude for an instant nobody can watch — and, worse, would have the roster name whichever
     * town happened to be deepest under a set Sun as the best place to stand.
     *
     * <p>So the search is constrained to the interval in which the Sun is actually up — up by the
     * same definition {@code SolarService} uses for sunset, see {@link #SUNSET_ALTITUDE_DEG}. Where that
     * clips the peak, the reported instant is the horizon crossing: the last moment of the eclipse
     * that is visible, which for a setting Sun is exactly the picture worth having. Where the Sun is
     * up throughout — every UK location for the 2026 eclipse — the result is unchanged.
     *
     * <p>Contact times remain the TRUE astronomical contacts, not clipped. They are real events, the
     * caller may want the eclipse's full extent, and the surfaces that print them already state the
     * Sun's relation to sunset alongside.
     *
     * @param elements  the eclipse's published Besselian elements
     * @param latitude  observer geodetic latitude in degrees, north positive
     * @param longitude observer longitude in degrees, <b>east positive</b> — so a UK location is
     *                  negative, matching {@code LocationEntity.lon}
     * @return the local circumstances, or {@link Optional#empty()} when the Sun is never even
     *     partly covered here, or is covered only while it is below the horizon
     */
    public static Optional<EclipseCircumstances> circumstances(
            BesselianElements elements, double latitude, double longitude) {
        double maximumT = findMaximumT(elements, latitude, longitude);
        Separation atMaximum = separationAt(elements, latitude, longitude, maximumT);

        // No contact at all: the observer's projection never comes within the penumbral radius.
        if (atMaximum.gap() >= 0) {
            return Optional.empty();
        }

        double firstT = bisectContact(elements, latitude, longitude, maximumT - SEARCH_SPAN_HOURS, maximumT);
        double lastT = bisectContact(elements, latitude, longitude, maximumT + SEARCH_SPAN_HOURS, maximumT);

        double visibleT = visibleMaximumT(elements, latitude, longitude, firstT, lastT, maximumT);
        if (Double.isNaN(visibleT)) {
            return Optional.empty();
        }
        Separation visible = separationAt(elements, latitude, longitude, visibleT);

        return Optional.of(new EclipseCircumstances(
                magnitude(visible),
                obscuration(visible),
                utcAt(elements, firstT),
                utcAt(elements, visibleT),
                utcAt(elements, lastT),
                visible.sunAltitudeDeg(),
                visible.sunAzimuthDeg()));
    }

    /**
     * The instant of greatest coverage at which the Sun is above the horizon, or {@code NaN} when
     * there is none.
     *
     * <p>Returns the astronomical maximum untouched in the ordinary case, so a location that sees
     * the whole eclipse in daylight pays one extra altitude evaluation and nothing else.
     *
     * <p>Otherwise the peak is hidden and the best visible moment lies at a horizon crossing, because
     * coverage falls away monotonically on both sides of the peak: whichever end of the visible
     * stretch is nearer the peak is the deepest thing that can be watched. The coarse sweep finds
     * which end that is, and the bisection puts the crossing to well under a second — the same shape
     * as {@link #bisectContact}, on altitude rather than on separation.
     */
    private static double visibleMaximumT(BesselianElements elements, double latitude, double longitude,
            double firstT, double lastT, double maximumT) {
        if (separationAt(elements, latitude, longitude, maximumT).sunAltitudeDeg() > SUNSET_ALTITUDE_DEG) {
            return maximumT;
        }

        double bestT = Double.NaN;
        double bestSeparation = Double.MAX_VALUE;
        for (double t = firstT; t <= lastT; t += COARSE_STEP_HOURS) {
            Separation candidate = separationAt(elements, latitude, longitude, t);
            if (candidate.sunAltitudeDeg() > SUNSET_ALTITUDE_DEG && candidate.m() < bestSeparation) {
                bestSeparation = candidate.m();
                bestT = t;
            }
        }
        if (Double.isNaN(bestT)) {
            return Double.NaN;
        }

        // Step toward the hidden peak until the Sun goes under, then bisect for the crossing. When
        // that step leaves the eclipse entirely the Sun was already up at that end, so the sweep's
        // answer is the boundary itself and there is nothing to refine.
        double towardPeak = maximumT > bestT ? COARSE_STEP_HOURS : -COARSE_STEP_HOURS;
        double below = bestT + towardPeak;
        if (below < firstT || below > lastT) {
            return bestT;
        }

        double above = bestT;
        for (int i = 0; i < CONTACT_REFINEMENTS; i++) {
            double mid = (above + below) / HALF;
            if (separationAt(elements, latitude, longitude, mid).sunAltitudeDeg() > SUNSET_ALTITUDE_DEG) {
                above = mid;
            } else {
                below = mid;
            }
        }
        return above;
    }

    /**
     * The observer's separation from the shadow axis at one instant, with the two cone radii at the
     * observer's own distance from the fundamental plane and the Sun's local horizontal position.
     *
     * @param m              distance from the observer's projection to the shadow axis, Earth radii
     * @param penumbralRadius the penumbral radius at the observer's ζ, Earth radii (Meeus's L1′)
     * @param umbralRadius   the umbral radius at the observer's ζ, Earth radii (Meeus's L2′);
     *                       negative inside a total eclipse's path
     * @param sunAltitudeDeg the Sun's geometric altitude, degrees
     * @param sunAzimuthDeg  the Sun's azimuth, degrees clockwise from true north
     */
    private record Separation(
            double m,
            double penumbralRadius,
            double umbralRadius,
            double sunAltitudeDeg,
            double sunAzimuthDeg) {

        /** Negative while any part of the Sun is covered; zero exactly at a contact. */
        double gap() {
            return m - penumbralRadius;
        }
    }

    /**
     * Projects the observer onto the fundamental plane and reads off the geometry at time {@code t}.
     *
     * <p>The Sun's altitude and azimuth come from the elements' own {@code d} and {@code mu} rather
     * than from a separate solar-position routine. That is not a shortcut: {@code d} <em>is</em> the
     * declination of the shadow axis, which points at the Sun, so taking both from one source is
     * what guarantees the altitude quoted beside a contact time cannot disagree with it.
     */
    private static Separation separationAt(
            BesselianElements elements, double latitude, double longitude, double t) {

        double x = elements.x().at(t);
        double y = elements.y().at(t);
        double declination = Math.toRadians(elements.d().at(t));
        double hourAngle = Math.toRadians(elements.mu().at(t) + longitude);

        double latitudeRad = Math.toRadians(latitude);
        double reducedLatitude = Math.atan(FLATTENING * Math.tan(latitudeRad));
        double rhoSinPhi = FLATTENING * Math.sin(reducedLatitude);
        double rhoCosPhi = Math.cos(reducedLatitude);

        double xi = rhoCosPhi * Math.sin(hourAngle);
        double eta = rhoSinPhi * Math.cos(declination)
                - rhoCosPhi * Math.cos(hourAngle) * Math.sin(declination);
        double zeta = rhoSinPhi * Math.sin(declination)
                + rhoCosPhi * Math.cos(hourAngle) * Math.cos(declination);

        double m = Math.hypot(x - xi, y - eta);
        double penumbral = elements.l1().at(t) - zeta * elements.tanF1();
        double umbral = elements.l2().at(t) - zeta * elements.tanF2();

        double sinAltitude = Math.sin(latitudeRad) * Math.sin(declination)
                + Math.cos(latitudeRad) * Math.cos(declination) * Math.cos(hourAngle);
        double altitude = Math.toDegrees(Math.asin(clamp(sinAltitude)));
        double azimuth = Math.toDegrees(Math.atan2(
                -Math.sin(hourAngle) * Math.cos(declination),
                Math.cos(latitudeRad) * Math.sin(declination)
                        - Math.sin(latitudeRad) * Math.cos(declination) * Math.cos(hourAngle)));

        return new Separation(m, penumbral, umbral, altitude, normaliseDegrees(azimuth));
    }

    /**
     * Fraction of the Sun's diameter covered.
     *
     * <p>{@code (L1′ − m) / (L1′ + L2′)}. The denominator is twice the Sun's apparent radius in
     * these units — the Sun's radius is {@code (L1′ + L2′) / 2} and the Moon's is
     * {@code (L1′ − L2′) / 2}, which is the way round that makes the Moon the larger body during a
     * total eclipse. Getting those two the other way round produces a plausible-looking number that
     * is wrong by several percent, and silently turns every total eclipse annular.
     */
    private static double magnitude(Separation s) {
        return (s.penumbralRadius() - s.m()) / (s.penumbralRadius() + s.umbralRadius());
    }

    /**
     * Fraction of the Sun's area covered — the area of the intersection of two circles, over the
     * area of the solar disc.
     */
    private static double obscuration(Separation s) {
        double sunRadius = (s.penumbralRadius() + s.umbralRadius()) / HALF;
        double moonRadius = (s.penumbralRadius() - s.umbralRadius()) / HALF;
        double separation = s.m();

        if (separation >= sunRadius + moonRadius) {
            return 0.0;
        }
        // One disc wholly inside the other: either total (Moon covers the Sun) or annular.
        if (separation <= Math.abs(moonRadius - sunRadius)) {
            double ratio = moonRadius / sunRadius;
            return moonRadius >= sunRadius ? 1.0 : ratio * ratio;
        }

        double sunAngle = Math.acos(clamp(
                (sunRadius * sunRadius + separation * separation - moonRadius * moonRadius)
                        / (HALF * sunRadius * separation)));
        double moonAngle = Math.acos(clamp(
                (moonRadius * moonRadius + separation * separation - sunRadius * sunRadius)
                        / (HALF * moonRadius * separation)));
        double overlap = sunRadius * sunRadius * (sunAngle - Math.sin(HALF * sunAngle) / HALF)
                + moonRadius * moonRadius * (moonAngle - Math.sin(HALF * moonAngle) / HALF);
        return overlap / (Math.PI * sunRadius * sunRadius);
    }

    /** Sweeps for the minimum separation, then refines it by ternary search. */
    private static double findMaximumT(BesselianElements elements, double latitude, double longitude) {
        double bestT = -SEARCH_SPAN_HOURS;
        double bestM = Double.MAX_VALUE;
        for (double t = -SEARCH_SPAN_HOURS; t <= SEARCH_SPAN_HOURS; t += COARSE_STEP_HOURS) {
            double m = separationAt(elements, latitude, longitude, t).m();
            if (m < bestM) {
                bestM = m;
                bestT = t;
            }
        }

        double low = bestT - COARSE_STEP_HOURS;
        double high = bestT + COARSE_STEP_HOURS;
        for (int i = 0; i < MAXIMUM_REFINEMENTS; i++) {
            double third = (high - low) / 3.0;
            double leftProbe = low + third;
            double rightProbe = high - third;
            if (separationAt(elements, latitude, longitude, leftProbe).m()
                    < separationAt(elements, latitude, longitude, rightProbe).m()) {
                high = rightProbe;
            } else {
                low = leftProbe;
            }
        }
        return (low + high) / HALF;
    }

    /**
     * Bisects for the instant of contact between an uneclipsed bracket and the eclipsed maximum.
     *
     * @param outside a time at which the Sun is certainly uncovered
     * @param inside  the time of maximum, at which it is certainly covered
     */
    private static double bisectContact(BesselianElements elements, double latitude, double longitude,
            double outside, double inside) {
        double low = outside;
        double high = inside;
        for (int i = 0; i < CONTACT_REFINEMENTS; i++) {
            double mid = (low + high) / HALF;
            if (separationAt(elements, latitude, longitude, mid).gap() > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / HALF;
    }

    /**
     * Converts an offset in Terrestrial Dynamical Time hours into the UTC instant an observer's
     * clock reads, by subtracting the eclipse's own published ΔT.
     */
    private static LocalDateTime utcAt(BesselianElements elements, double t) {
        double hoursFromMidnight = elements.t0Hour() + t - elements.deltaTSeconds() / SECONDS_PER_HOUR;
        long nanos = Math.round(hoursFromMidnight * SECONDS_PER_HOUR * NANOS_PER_SECOND);
        return elements.t0Date().atStartOfDay().plusNanos(nanos);
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double normaliseDegrees(double degrees) {
        return (degrees % DEGREES_PER_CIRCLE + DEGREES_PER_CIRCLE) % DEGREES_PER_CIRCLE;
    }
}
