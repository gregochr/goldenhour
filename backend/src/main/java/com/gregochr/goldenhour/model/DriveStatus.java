package com.gregochr.goldenhour.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * What a reader's drive to one location is: measured, not measured yet, or confirmed to have no route
 * (reach-scoped-card-plan D1).
 *
 * <p>There used to be two kinds, "has a figure" and "unknown", and a finite reach tier passed every unknown. That
 * was right for a reader with no drive times, but it also let a place OpenRouteService could not route to (a
 * summit with no road) pass every tier forever, and as a high-rated mountain it then won the Best row of a card
 * that said "within 45 min". The third kind separates the two causes of a missing row:
 *
 * <ul>
 *   <li>{@link #MEASURED}: a row exists.</li>
 *   <li>{@link #PENDING}: no row, and the origin's last full measurement is null or does not yet cover the
 *       location's current routing point. Still unknown, so a tier keeps it.</li>
 *   <li>{@link #UNROUTABLE}: no row, and the origin was fully measured after the location's routing point last
 *       changed, so ORS answered and had no route. A finite tier drops it.</li>
 * </ul>
 *
 * <p>The origin is a home (stamp {@code app_user.drive_times_calculated_at}) or a region base (stamp
 * {@code regions.drives_measured_at}); the rule is identical for both. The frontend repeats it for the away
 * origin in {@code planOrigin.js}, with the same margin.
 */
public enum DriveStatus {

    /** A drive time is stored. */
    MEASURED,

    /** No drive time is stored, and none has been measured that covers this location yet. */
    PENDING,

    /** No drive time is stored although the origin was fully measured after this location last moved. */
    UNROUTABLE;

    /**
     * Classifies one (origin, location) drive.
     *
     * <p>The stamp is compared to {@code targetChangedAt} with the same widening the nightly job uses to decide a
     * roster has grown ({@code DriveTimeRefreshJob.ROSTER_VISIBILITY_MARGIN}, passed in): a location whose routing
     * point changed within {@code margin} <em>before</em> the stamp may not have been visible to the roster read
     * that produced it, so it reads PENDING, never UNROUTABLE. The cost of the margin is bounded and benign:
     * PENDING is kept by every tier.
     *
     * @param hasRow          whether a drive time row exists for this origin and location
     * @param measuredAt      the origin's last full-measurement stamp, or null if it has none
     * @param targetChangedAt the location's {@code drive_target_changed_at} (UTC wall clock), or null if unknown
     * @param margin          the roster-visibility margin to widen the comparison by
     * @return the drive's status
     */
    public static DriveStatus of(boolean hasRow, Instant measuredAt, LocalDateTime targetChangedAt,
            Duration margin) {
        if (hasRow) {
            return MEASURED;
        }
        if (measuredAt == null || targetChangedAt == null) {
            return PENDING;
        }
        Instant changed = targetChangedAt.toInstant(ZoneOffset.UTC);
        return changed.isBefore(measuredAt.minus(margin)) ? UNROUTABLE : PENDING;
    }
}
