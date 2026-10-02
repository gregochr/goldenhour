package com.gregochr.goldenhour.model;

/**
 * What one scheduled pipeline cycle recorded about one place, reduced to the three answers the
 * auto-disable rule needs.
 *
 * <p>A place is {@link Status#GOT_THROUGH} when the cycle either scored it or triaged it (the
 * pipeline looked and answered), {@link Status#FAILED} when it errored with no success for the
 * same place in the same cycle, and {@link Status#NOT_ATTEMPTED} when nothing was sent or learned
 * about it (skipped as cached, past, travel day, hard constraint or stability, never submitted, or
 * submitted with no result recorded).
 *
 * @param status      the place's outcome for the cycle
 * @param failureKind why it failed, or {@code null} unless {@code status} is {@code FAILED}
 */
public record CyclePlaceOutcome(Status status, FailureKind failureKind) {

    /** The cycle's verdict on one place. */
    public enum Status {
        /** Scored by Claude, or triaged: the pipeline looked at the place and answered. */
        GOT_THROUGH,
        /** Errored at collection or at the batch result, with no success for it in the cycle. */
        FAILED,
        /** Not sent and not judged this cycle: neither a success nor a failure. */
        NOT_ATTEMPTED
    }

    /**
     * Why a place failed, as a fixed phrase. Deliberately a closed set so a raw exception message
     * (which can carry URLs, keys or stack text) can never reach a stored reason or an email.
     */
    public enum FailureKind {
        /** Collection raised an error while assembling the place's weather data. */
        WEATHER_DATA("weather data could not be fetched"),
        /** The place's Claude batch request came back errored, truncated or unusable. */
        EVALUATION("the Claude evaluation request failed");

        private final String phrase;

        FailureKind(String phrase) {
            this.phrase = phrase;
        }

        /**
         * Returns the fixed phrase used in the stored disabled reason.
         *
         * @return the phrase
         */
        public String phrase() {
            return phrase;
        }
    }

    /**
     * Builds the outcome of a place the pipeline looked at and answered.
     *
     * @return a {@code GOT_THROUGH} outcome
     */
    public static CyclePlaceOutcome gotThrough() {
        return new CyclePlaceOutcome(Status.GOT_THROUGH, null);
    }

    /**
     * Builds the outcome of a place that errored.
     *
     * @param kind why the place failed
     * @return a {@code FAILED} outcome
     */
    public static CyclePlaceOutcome failed(FailureKind kind) {
        return new CyclePlaceOutcome(Status.FAILED, kind);
    }

    /**
     * Builds the outcome of a place the cycle neither sent nor judged.
     *
     * @return a {@code NOT_ATTEMPTED} outcome
     */
    public static CyclePlaceOutcome notAttempted() {
        return new CyclePlaceOutcome(Status.NOT_ATTEMPTED, null);
    }
}
