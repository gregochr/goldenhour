package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * What one observer, at one latitude and longitude, sees of a lunar eclipse.
 *
 * <p>Unlike a solar eclipse — where the Moon's shadow sweeps a narrow path and magnitude varies
 * sharply between nearby sites — a lunar eclipse's depth is the same for every observer who can see
 * the Moon at all: the whole Earth stands between the Moon and the Sun, not a small cone of shadow
 * on the ground. So there is nothing here comparable to {@link EclipseCircumstances}'s per-location
 * magnitude or obscuration. The only thing that varies by site is geometry — is the Moon above the
 * horizon, and for how much of the umbral span — which is exactly what this record carries.
 *
 * <p><b>Nothing here reads a horizon profile.</b> "Clear to N°" is not a field: this codebase has no
 * terrain data for any location (see {@code lunar-eclipse-plan.md} §1 row 3), so altitude and
 * bearing are reported against the astronomical horizon only, the same simplification the solar
 * eclipse's {@code EclipseCalculator} already makes.
 *
 * @param moonAltAtMax     the Moon's altitude above the astronomical horizon at greatest eclipse,
 *                         degrees, rounded to the nearest whole degree. Negative when the Moon is
 *                         below the horizon at that instant
 * @param moonAzAtMax      the Moon's azimuth at greatest eclipse, degrees clockwise from true north,
 *                         rounded to the nearest whole degree
 * @param moonAzCardinal   {@link #moonAzAtMax} as a 16-point compass cardinal (e.g. "WSW")
 * @param moonset          the Moon's setting time nearest the umbral span, London local time —
 *                         null only in the practical non-occurrence that neither of the two civil
 *                         dates spanning the umbral phase has a moonset (the Moon is a once-daily
 *                         riser/setter at these latitudes, so this is exceedingly rare)
 * @param moonrise         the Moon's rising time nearest the umbral span, London local time —
 *                         nullable on the same basis as {@link #moonset}
 * @param setsInShadow     true when {@link #moonset} falls inside the umbral span {@code [u1, u4]}
 *                         — the Moon sets while still eclipsed
 * @param risesInShadow    true when {@link #moonrise} falls inside the umbral span — the eclipse is
 *                         already under way when the Moon comes up
 * @param visibleUmbraStart the start of the umbral span actually watchable from here, London local
 *                         time — {@link #moonrise} when {@link #risesInShadow}, else the eclipse's
 *                         own U1
 * @param visibleUmbraEnd  the end of the umbral span actually watchable from here, London local
 *                         time — {@link #moonset} when {@link #setsInShadow}, else the eclipse's
 *                         own U4
 * @param visible          whether this location can see any of the umbral phase at all — the
 *                         eligibility rule {@code LunarEclipseCalculator} applies: the Moon's
 *                         altitude at greatest eclipse is at least 3°, or its altitude is at least
 *                         3° for a contiguous 30 minutes somewhere inside {@code [u1, u4]}
 */
public record LunarEclipseSight(
        int moonAltAtMax,
        int moonAzAtMax,
        String moonAzCardinal,
        LocalDateTime moonset,
        LocalDateTime moonrise,
        boolean setsInShadow,
        boolean risesInShadow,
        LocalDateTime visibleUmbraStart,
        LocalDateTime visibleUmbraEnd,
        boolean visible) {
}
