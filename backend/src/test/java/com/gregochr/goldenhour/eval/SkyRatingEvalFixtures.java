package com.gregochr.goldenhour.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.model.AtmosphericData;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The sky-rating eval fixture set and the loader that deserialises a frozen JSON fixture into a
 * fully-augmented {@link AtmosphericData}.
 *
 * <p><b>Loader fidelity.</b> The mapper here mirrors the production replay engine
 * ({@code PromptTestService}): a Jackson 2 {@code ObjectMapper} with {@link JavaTimeModule}, so
 * {@code LocalDateTime} round-trips and a captured-real export written by that engine deserialises
 * byte-for-byte. Unknown properties are ignored so a captured fixture survives later
 * {@code AtmosphericData} schema additions.
 *
 * <p><b>The fixture set.</b> Both halves of the range are present — strong (proves the scorer does
 * not under-rate good conditions) and flat/middling (proves it is not always-high) — because the
 * pass rate is only meaningful if both halves pass. Every fixture is at <em>full augmentation</em>:
 * it carries the always-block plus {@code directionalCloud} and {@code cloudApproach.solarTrend},
 * the blocks the prompt actually reasons over for colour. A partial fixture (always-block only)
 * under-exercises the prompt and is rejected by {@code SkyRatingEvalFixturesTest}.
 *
 * <p>Provenance is noted per fixture: <em>ported</em> fixtures carry the exact input and band of a
 * {@code PromptRegressionTest} case (re-homed, not altered); <em>hand-authored</em> fixtures are
 * constructed at full augmentation to fill bands no ported case covers. Captured-real anchors
 * (a genuine strong/flat day exported from prod) slot in by dropping a JSON file here and adding a
 * registry line — they are the most faithful seed and supersede the hand-authored strong/flat
 * placeholders once available.
 */
public final class SkyRatingEvalFixtures {

    private static final String FIXTURE_ROOT = "/eval/fixtures/";

    /**
     * The registered fixtures. See class Javadoc for the sourcing policy.
     */
    public static final List<SkyRatingEvalFixture> ALL = List.of(
            new SkyRatingEvalFixture(
                    "strong-clearing-canvas",
                    "Hand-authored textbook-strong sunset: clear solar-horizon blocker with a "
                            + "surviving high/mid canvas and a clearing-into-event trend. Proves the "
                            + "scorer does not UNDER-rate good conditions. Placeholder for a "
                            + "captured-real 4–5 day.",
                    RatingBand.atLeast(4),
                    "strong-clearing-canvas.json"),
            new SkyRatingEvalFixture(
                    "flat-grey-overcast",
                    "Hand-authored flat-grey washout: total low/mid overcast at observer and solar "
                            + "horizon, building trend, no canvas. Proves the scorer is not "
                            + "always-high. Placeholder for a captured-real 1–2 day.",
                    RatingBand.atMost(2),
                    "flat-grey-overcast.json"),
            new SkyRatingEvalFixture(
                    "copt-hill-11mar-false-positive",
                    "Ported from PromptRegressionTest#coptHill_11Mar2026_sunset_cloudApproachFalse"
                            + "Positive (band re-homed, input verbatim). Clear event-time snapshot but "
                            + "a cloud bank approaching upwind — must not be fooled into a high score.",
                    RatingBand.atMost(2),
                    "copt-hill-11mar-false-positive.json"),
            new SkyRatingEvalFixture(
                    "copt-hill-16mar-horizon-strip",
                    "Ported from PromptRegressionTest#coptHill_16Mar2026_horizonStrip (band re-homed, "
                            + "input verbatim). Thin solar-horizon strip (far-field clears) under a "
                            + "high-cloud canvas — graded middling, not a washout, not spectacular.",
                    new RatingBand(3, 4),
                    "copt-hill-16mar-horizon-strip.json"),
            new SkyRatingEvalFixture(
                    "middling-hazy-mixed",
                    "Hand-authored middling sunset: moderate mixed cloud, modest canvas, slightly "
                            + "hazy air — decent but undramatic. Proves a graded (non-bimodal) "
                            + "response: the scorer can return the middle of the range.",
                    new RatingBand(2, 3),
                    "middling-hazy-mixed.json"),
            new SkyRatingEvalFixture(
                    "clear-sky-no-canvas-cap3",
                    "Hand-authored CLEAR-SKY-CAP edge: a flawless clear dawn with no mid/high canvas. "
                            + "Clean golden light but no subject in the sky — must cap at 3, never "
                            + "score high. Guards the clearing-to-bald-blue failure mode.",
                    RatingBand.atMost(3),
                    "clear-sky-no-canvas-cap3.json"));

    private SkyRatingEvalFixtures() {
    }

    /**
     * Builds the fixture-loading {@link ObjectMapper} — Jackson 2 + {@link JavaTimeModule}, with
     * unknown properties ignored. Matches the production replay engine so captured-real exports
     * deserialise faithfully.
     *
     * @return a configured mapper
     */
    public static ObjectMapper fixtureMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Loads a fixture's frozen JSON into a fully-augmented {@link AtmosphericData}.
     *
     * @param fixture the fixture to load
     * @return the deserialised atmospheric data
     * @throws IllegalStateException if the resource is missing
     * @throws UncheckedIOException  if the JSON cannot be parsed
     */
    public static AtmosphericData load(SkyRatingEvalFixture fixture) {
        String path = FIXTURE_ROOT + fixture.resource();
        try (InputStream in = SkyRatingEvalFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Eval fixture resource not found on classpath: " + path);
            }
            return fixtureMapper().readValue(in, AtmosphericData.class);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Failed to parse eval fixture: " + path, e);
        }
    }
}
