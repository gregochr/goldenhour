package com.gregochr.goldenhour.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything one scheduled pipeline cycle recorded about one place, kept per lane so the
 * auto-disable rule can compare a failure only with LIKE evidence (see
 * {@code LocationFailureService}).
 *
 * <p>The facts are raw: whether the pipeline triaged the place (looked and answered without a
 * Claude call), whether collection errored for it, and in which result lanes (sky, bluebell,
 * woodland) it received a successful or a failed Claude result. Everything else, "got through",
 * "failed" and "attempted", is derived from them.
 *
 * @param triaged          a {@code SKIPPED_TRIAGED} disposition exists for the place this cycle
 * @param collectionFailed a {@code SKIPPED_ERROR} disposition exists: collection threw for a slot
 * @param succeededLanes   lanes in which the place received a usable Claude result
 * @param failedLanes      lanes in which the place received an errored, truncated or unusable result
 */
public record CyclePlaceEvidence(boolean triaged, boolean collectionFailed,
        Set<Lane> succeededLanes, Set<Lane> failedLanes) {

    /** The three result lanes a Claude batch result can belong to, told apart by custom-id prefix. */
    public enum Lane {
        /** Sky prompt: {@code fc-} ids (and the manual {@code jf-}/force-submit ids). */
        SKY,
        /** Bluebell prompt: {@code bb-} ids. */
        BLUEBELL,
        /** Woodland prompt: {@code wd-} ids. */
        WOODLAND
    }

    /**
     * Why a place failed, as a fixed phrase. Deliberately a closed set so a raw exception message
     * (which can carry URLs, keys or stack text) can never reach a stored reason or an email.
     */
    public enum FailureKind {
        /**
         * Collection threw for the place. {@code SKIPPED_ERROR} is the collector's catch-all (any
         * exception in its loop, a weather fetch, a cloud-cache miss or a database error), so the
         * phrase claims no more than "collection".
         */
        COLLECTION("data could not be collected"),
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

    /** Canonical constructor: defensive copies, so a record is never changed after it is built. */
    public CyclePlaceEvidence {
        succeededLanes = succeededLanes.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(succeededLanes));
        failedLanes = failedLanes.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(failedLanes));
    }

    /**
     * Builds evidence for a place the pipeline triaged away.
     *
     * @return evidence with only {@code triaged} set
     */
    public static CyclePlaceEvidence triagedOnly() {
        return new CyclePlaceEvidence(true, false, Set.of(), Set.of());
    }

    /**
     * Builds evidence for a place that scored in the given lanes and failed in none.
     *
     * @param lanes the lanes with a usable result
     * @return the evidence
     */
    public static CyclePlaceEvidence scoredIn(Lane... lanes) {
        return new CyclePlaceEvidence(false, false, setOf(lanes), Set.of());
    }

    /**
     * Builds evidence for a place whose results failed in the given lanes and succeeded in none.
     *
     * @param lanes the lanes with a failed result
     * @return the evidence
     */
    public static CyclePlaceEvidence failedIn(Lane... lanes) {
        return new CyclePlaceEvidence(false, false, Set.of(), setOf(lanes));
    }

    /**
     * Builds evidence for a place whose collection threw and that has nothing else recorded.
     *
     * @return the evidence
     */
    public static CyclePlaceEvidence collectionError() {
        return new CyclePlaceEvidence(false, true, Set.of(), Set.of());
    }

    /**
     * Builds evidence for a place with nothing recorded either way (queued but no result,
     * skipped, never submitted).
     *
     * @return the evidence
     */
    public static CyclePlaceEvidence nothing() {
        return new CyclePlaceEvidence(false, false, Set.of(), Set.of());
    }

    private static Set<Lane> setOf(Lane... lanes) {
        Set<Lane> set = EnumSet.noneOf(Lane.class);
        set.addAll(java.util.Arrays.asList(lanes));
        return set;
    }

    /**
     * Whether the pipeline looked at the place and answered: triaged, or at least one usable
     * Claude result in any lane. Any one such answer in the cycle is enough, so a place with a
     * failure and a success is "got through".
     *
     * @return true if the place got through
     */
    public boolean gotThrough() {
        return triaged || !succeededLanes.isEmpty();
    }

    /**
     * Whether the place failed: it did not get through and either collection threw or a Claude
     * result failed.
     *
     * @return true if the place failed
     */
    public boolean failed() {
        return !gotThrough() && (collectionFailed || !failedLanes.isEmpty());
    }

    /**
     * Whether the cycle got far enough with the place to say something either way.
     *
     * @return true if the place got through or failed
     */
    public boolean attempted() {
        return gotThrough() || failed();
    }

    /**
     * Whether the place received a Claude result, good or bad, in the lane.
     *
     * @param lane the lane
     * @return true if a result was recorded
     */
    public boolean hasResultIn(Lane lane) {
        return succeededLanes.contains(lane) || failedLanes.contains(lane);
    }

    /**
     * Whether the place received a usable Claude result in the lane.
     *
     * @param lane the lane
     * @return true if a success was recorded
     */
    public boolean succeededIn(Lane lane) {
        return succeededLanes.contains(lane);
    }
}
