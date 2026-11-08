package com.gregochr.goldenhour.eval;

/**
 * The judged result of one scorer run against one sky-rating fixture: the rating's direction against
 * the band, the Fiery Sky and Golden Hour directions against the fixture's {@link RegressionBounds}
 * (where it has any), and whether the response was malformed (a null score).
 *
 * <p>A run passes only when every check holds. {@link MissDirection#IN_BAND} is used both for "within
 * its bound" and for "no bound to check", so an eval-only fixture's bounds checks always read as held.
 *
 * @param rating          the star rating returned, or {@code null} if the response was malformed
 * @param ratingDirection the rating against the band (and the bounds' rating band, where present)
 * @param fieryDirection  Fiery Sky potential against {@code fieryMin}/{@code fieryMax}
 * @param goldenDirection Golden Hour potential against {@code goldenMin}/{@code goldenMax}
 * @param malformed       true if the rating, either potential or the summary came back null
 */
public record RunOutcome(Integer rating, MissDirection ratingDirection, MissDirection fieryDirection,
        MissDirection goldenDirection, boolean malformed) {

    /**
     * Builds the outcome for a response with a null score. It fails every assertion and is counted
     * separately from rating and bounds misses.
     *
     * @param rating the rating that did arrive, or {@code null}
     * @return a malformed outcome
     */
    public static RunOutcome malformedRun(Integer rating) {
        return new RunOutcome(rating, MissDirection.IN_BAND, MissDirection.IN_BAND, MissDirection.IN_BAND, true);
    }

    /**
     * @return true only if the response was well-formed and every check held
     */
    public boolean passed() {
        return !malformed
                && ratingDirection == MissDirection.IN_BAND
                && fieryDirection == MissDirection.IN_BAND
                && goldenDirection == MissDirection.IN_BAND;
    }

    /**
     * @return true if the response was well-formed but Fiery Sky or Golden Hour broke a bound
     */
    public boolean boundMissed() {
        return !malformed
                && (fieryDirection != MissDirection.IN_BAND || goldenDirection != MissDirection.IN_BAND);
    }

    /**
     * Renders a short, human-readable reason for the run, for the per-run log line.
     *
     * @return {@code ok}, {@code MALFORMED}, or the list of failed checks with their directions
     */
    public String describe() {
        if (malformed) {
            return "MALFORMED (null score)";
        }
        if (passed()) {
            return "ok";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "rating", ratingDirection);
        append(sb, "fiery", fieryDirection);
        append(sb, "golden", goldenDirection);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String check, MissDirection direction) {
        if (direction == MissDirection.IN_BAND) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(check).append(direction == MissDirection.BELOW ? " BELOW" : " ABOVE");
    }
}
