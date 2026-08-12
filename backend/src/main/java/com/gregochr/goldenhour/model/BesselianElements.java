package com.gregochr.goldenhour.model;

import java.time.LocalDate;

/**
 * The Besselian elements of one solar eclipse — the published ephemeris table from which every
 * local circumstance of that eclipse is derived.
 *
 * <p>Besselian elements describe the Moon's shadow in a coordinate system whose origin is the
 * centre of the Earth and whose z-axis points along the shadow axis toward the Sun. Six quantities,
 * each a cubic polynomial in {@code t} (hours of Terrestrial Dynamical Time from {@link #t0Hour}),
 * fix the shadow's position and size for the whole eclipse. They are published to seven decimal
 * places for every eclipse through 3000&nbsp;AD in NASA's Five Millennium Canon of Solar Eclipses,
 * and they are <em>the</em> standard interchange format for eclipse prediction.
 *
 * <p><b>This is seeded data, never computed.</b> Deriving the elements themselves would need a
 * full solar and lunar ephemeris (VSOP87/ELP2000-82) at an accuracy the abridged series in
 * {@code solar-utils} cannot reach — its ~10 arcminute lunar error is a third of the Moon's own
 * diameter, which would put contact times tens of minutes out. Seeding the elements and deriving
 * everything else from them is what makes a contact time trustworthy to the second.
 *
 * <p>What the elements are <em>not</em> is per-location: one table serves every observer on Earth.
 * {@link com.gregochr.goldenhour.util.EclipseCalculator} turns them into what a given latitude and
 * longitude actually sees.
 *
 * @param designation  human-readable identity, e.g. {@code "2026 Aug 12"} — for logs and tests
 * @param t0Date       the UTC date the eclipse falls on, to which {@code t0Hour} is relative
 * @param t0Hour       the reference hour in Terrestrial Dynamical Time, decimal hours from
 *                     midnight on {@code t0Date}; NASA publishes this as the whole hour nearest
 *                     greatest eclipse, and every {@code t} in this record is measured from it
 * @param deltaTSeconds TDT − UT for this eclipse, the correction that turns dynamical time into
 *                     the clock time an observer reads. Published alongside the elements because
 *                     it is an observed quantity, not a computed one
 * @param x            the shadow-axis x coordinate, in Earth radii, as a polynomial in {@code t}
 * @param y            the shadow-axis y coordinate, in Earth radii
 * @param d            the declination of the shadow axis, in degrees — within a few arcseconds of
 *                     the Sun's own apparent declination, which is what lets the Sun's altitude and
 *                     azimuth be read straight off the same elements as the contact times
 * @param l1           the radius of the penumbral cone in the fundamental plane, in Earth radii
 * @param l2           the radius of the umbral cone, negative for a total eclipse
 * @param mu           the ephemeris hour angle of the shadow axis, in degrees, increasing at very
 *                     nearly 15°/hour
 * @param tanF1        tangent of the penumbral cone's half-angle
 * @param tanF2        tangent of the umbral cone's half-angle
 */
public record BesselianElements(
        String designation,
        LocalDate t0Date,
        double t0Hour,
        double deltaTSeconds,
        Poly x,
        Poly y,
        Poly d,
        Poly l1,
        Poly l2,
        Poly mu,
        double tanF1,
        double tanF2) {

    /**
     * One Besselian element as its published cubic in {@code t}.
     *
     * <p>A record of four named doubles rather than a {@code double[]}: an array component would be
     * compared by identity, which this project has already been bitten by once (CLAUDE.md's rule
     * that a cached series must be a {@code List<Double>} and never a {@code double[]}). Four named
     * coefficients also read in the same order NASA prints them.
     *
     * @param c0 the constant term — the element's value at {@code t = 0}
     * @param c1 the coefficient of {@code t}
     * @param c2 the coefficient of {@code t²}
     * @param c3 the coefficient of {@code t³}
     */
    public record Poly(double c0, double c1, double c2, double c3) {

        /**
         * Evaluates the cubic at {@code t}, by Horner's method.
         *
         * @param t hours of Terrestrial Dynamical Time from the elements' reference hour
         * @return the element's value at {@code t}
         */
        public double at(double t) {
            return c0 + t * (c1 + t * (c2 + t * c3));
        }
    }
}
