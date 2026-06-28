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
 * @param name        short identifier used in reports and assertion messages
 * @param description what the fixture represents and where its band comes from
 * @param band        the expected star-rating band (ground truth)
 * @param resource    classpath resource path, relative to {@code /eval/fixtures/}
 */
public record SkyRatingEvalFixture(String name, String description, RatingBand band, String resource) {
}
