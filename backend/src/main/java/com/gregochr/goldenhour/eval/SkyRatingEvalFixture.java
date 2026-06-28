package com.gregochr.goldenhour.eval;

/**
 * A registry entry for one sky-rating eval fixture: a frozen, fully-augmented
 * {@code AtmosphericData} JSON resource paired with the rating band its real-world ground truth
 * warrants.
 *
 * <p>The {@code resource} is a path under {@code /eval/fixtures/} on the test classpath holding a
 * pure {@code AtmosphericData} JSON document. Pure (un-wrapped) JSON is deliberate: it lets a
 * captured-real export from {@code prompt_test_result.atmospheric_data_json} drop straight in as a
 * fixture with no editing — the band and metadata live here in code, not in the JSON.
 *
 * <p><b>Gated vs monitored.</b> A {@code gated} fixture (the default) hard-asserts its band: a
 * single-session pass^k miss fails the manual {@code SkyRatingEvalTest} run. A <em>monitored</em>
 * fixture ({@code gated = false}) is still scored and reported, but does not fail the build — used
 * for a fixture empirically shown to sit on a rating boundary, where the scorer's score clusters
 * within a session (correlated samples) and flips between sessions, so a single run's pass^k result
 * is one effective sample, not a verdict. Its band is unchanged (still the ground truth); only the
 * gate is relaxed, and the weekly recorder still trends it.
 *
 * @param name        short identifier used in reports and assertion messages
 * @param description what the fixture represents and where its band comes from
 * @param band        the expected star-rating band (ground truth)
 * @param resource    classpath resource path, relative to {@code /eval/fixtures/}
 * @param gated       whether the harness hard-asserts this fixture's band (true) or only records
 *                    and reports it (false, monitored)
 */
public record SkyRatingEvalFixture(String name, String description, RatingBand band, String resource,
        boolean gated) {

    /**
     * Convenience constructor for a gated fixture — the default. Use the canonical 5-argument form
     * with {@code gated = false} to register a monitored (trend-only) fixture.
     *
     * @param name        short identifier
     * @param description what the fixture represents
     * @param band        the expected star-rating band
     * @param resource    classpath resource path
     */
    public SkyRatingEvalFixture(String name, String description, RatingBand band, String resource) {
        this(name, description, band, resource, true);
    }
}
