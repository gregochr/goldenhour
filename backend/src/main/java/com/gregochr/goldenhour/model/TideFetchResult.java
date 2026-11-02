package com.gregochr.goldenhour.model;

/**
 * What one WorldTides fetch for one location amounted to, so the scheduled tide refresh can tell a stored
 * fetch from "already covered" and from a swallowed failure (scheduler-redesign-plan.md S2).
 *
 * @param status the outcome
 * @param change the source change the fetch detected (only for {@link Status#STORED}), or {@code null}
 */
public record TideFetchResult(Status status, TideSourceChange change) {

    /** The outcome of a fetch. */
    public enum Status {
        /** Extremes were fetched and merged. */
        STORED,
        /** Stored coverage already reached the horizon, so no fetch was made. */
        UNCHANGED,
        /** The API call failed, returned no usable data, or the merge threw; stored data is preserved. */
        FAILED,
        /** The WorldTides API key is not configured, so nothing was attempted. */
        NOT_CONFIGURED
    }

    /**
     * A stored fetch.
     *
     * @param change the source change detected, or {@code null}
     * @return the result
     */
    public static TideFetchResult stored(TideSourceChange change) {
        return new TideFetchResult(Status.STORED, change);
    }

    /**
     * A fetch that was not needed.
     *
     * @return the result
     */
    public static TideFetchResult unchanged() {
        return new TideFetchResult(Status.UNCHANGED, null);
    }

    /**
     * A failed fetch.
     *
     * @return the result
     */
    public static TideFetchResult failed() {
        return new TideFetchResult(Status.FAILED, null);
    }

    /**
     * A fetch that was not attempted because no key is configured.
     *
     * @return the result
     */
    public static TideFetchResult notConfigured() {
        return new TideFetchResult(Status.NOT_CONFIGURED, null);
    }
}
