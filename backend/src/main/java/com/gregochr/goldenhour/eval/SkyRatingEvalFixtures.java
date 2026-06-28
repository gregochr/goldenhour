package com.gregochr.goldenhour.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.model.AtmosphericData;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The sky-rating eval fixture set and the loader that deserialises a frozen JSON fixture into an
 * {@link AtmosphericData}.
 *
 * <p><b>Loader fidelity.</b> The mapper here mirrors the production replay engine
 * ({@code PromptTestService}): a Jackson 2 {@code ObjectMapper} with {@link JavaTimeModule}, so
 * {@code LocalDateTime} round-trips and a captured-real export written by that engine deserialises
 * byte-for-byte. Unknown properties are ignored so a captured fixture survives later
 * {@code AtmosphericData} schema additions.
 *
 * <p><b>The fixture set.</b> Both halves of the range are present — strong (proves the scorer does
 * not under-rate good conditions) and flat/middling (proves it is not always-high) — because the
 * pass rate is only meaningful if both halves pass.
 *
 * <p><b>Real days, real bands.</b> Seven of the eight fixtures are real days ported verbatim from
 * {@code PromptRegressionTest}: real Open-Meteo / production input <em>and</em> the band Chris
 * actually observed at that location. Real provenance is the most credible foundation, so
 * augmentation is taken as the day's reality, not forced — some real days carry full augmentation
 * (directional cloud + cloud-approach trend), others only what was persisted that run (Angel of the
 * North is observer-point only). {@code SkyRatingEvalFixturesTest} therefore verifies each fixture
 * deserialises <em>faithfully</em> (no silent field-name defaults), not that every one is fully
 * augmented. The lone exception is {@code middling-hazy-mixed}, hand-authored to cover a low-middle
 * band ({2,3}) no real day supplies. Two fixtures carry tide and exercise the
 * {@code CoastalPromptBuilder} path.
 *
 * <p>A new captured-real day slots in by dropping its exported {@code atmospheric_data_json} here
 * and adding a registry line — e.g. a fully-augmented strong day from current prod would be the
 * ideal upgrade over the observer-only Angel anchor.
 */
public final class SkyRatingEvalFixtures {

    private static final String FIXTURE_ROOT = "/eval/fixtures/";

    /**
     * The registered fixtures. See class Javadoc for the sourcing policy.
     */
    public static final List<SkyRatingEvalFixture> ALL = List.of(
            new SkyRatingEvalFixture(
                    "copt-hill-5mar-washout",
                    "Real day, ported from PromptRegressionTest#coptHill_5Mar2026_sunset_blocked"
                            + "SolarHorizon (input + observed band verbatim). Near-clear observer point "
                            + "but the solar horizon is blocked (67% low / 100% high) — observed a total "
                            + "washout. MONITORED (gated=false), not because the band is soft but because "
                            + "67% low cloud sits right on the 60% block line: the scorer clusters within "
                            + "a session and flips between sessions (seen at 6/8, 0/8, 8/8, 0/8), so a "
                            + "single pass^k run is one effective sample, not a verdict. Band stays {1,2} "
                            + "(the day was a washout — a 3 would be a real over-rating); the weekly "
                            + "recorder still trends it. A decisively-blocked day (90%+ low) would be a "
                            + "stable gated monitor — capture one when convenient.",
                    RatingBand.atMost(2),
                    "copt-hill-5mar-washout.json",
                    false),
            new SkyRatingEvalFixture(
                    "angel-of-the-north-2mar-spectacular",
                    "Real day, ported from PromptRegressionTest#angelOfTheNorth_2Mar2026_sunset_"
                            + "spectacular (prod record 3284; input + observed band verbatim). High-cloud "
                            + "canvas overhead, observed spectacular. Observer-point only — no directional "
                            + "or cloud-approach was persisted for that run.",
                    RatingBand.atLeast(4),
                    "angel-of-the-north-2mar-spectacular.json"),
            new SkyRatingEvalFixture(
                    "st-marys-10mar-moderate",
                    "Real day, ported from PromptRegressionTest#stMarysLighthouse_10Mar2026_sunrise_"
                            + "moderate (prod forecast_evaluation). Clear low cloud under a thick "
                            + "mid/high canvas, observed 4★ — and the prompt's own rule caps this "
                            + "scenario at 4 (never 5), so the band is the exact observed {4}. Coastal "
                            + "(tide) — exercises CoastalPromptBuilder.",
                    RatingBand.exactly(4),
                    "st-marys-10mar-moderate.json"),
            new SkyRatingEvalFixture(
                    "copt-hill-11mar-false-positive",
                    "Real day, ported from PromptRegressionTest#coptHill_11Mar2026_sunset_cloud"
                            + "ApproachFalsePositive (input + observed band verbatim). Clear event-time "
                            + "snapshot but a cloud bank approaching upwind — must not be fooled into a "
                            + "high score. Fully augmented (directional + cloud-approach).",
                    RatingBand.atMost(2),
                    "copt-hill-11mar-false-positive.json"),
            new SkyRatingEvalFixture(
                    "copt-hill-15mar-overcast",
                    "Real day, ported from PromptRegressionTest#coptHill_15Mar2026_sunset_total"
                            + "OvercastWithApproach (input + observed band verbatim). Total overcast with "
                            + "a building trend and an upwind bank the model under-called — observed a "
                            + "non-event. Fully augmented.",
                    RatingBand.atMost(2),
                    "copt-hill-15mar-overcast.json"),
            new SkyRatingEvalFixture(
                    "copt-hill-16mar-horizon-strip",
                    "Real day, ported from PromptRegressionTest#coptHill_16Mar2026_horizonStrip "
                            + "(input + observed band verbatim). Thin solar-horizon strip (far-field "
                            + "clears) under a high-cloud canvas — graded middling, not a washout, not "
                            + "spectacular. Fully augmented.",
                    new RatingBand(3, 4),
                    "copt-hill-16mar-horizon-strip.json"),
            new SkyRatingEvalFixture(
                    "st-marys-7apr-clear-sky-cap",
                    "Real day, ported from PromptRegressionTest#stMarysLighthouse_7Apr2026_sunrise_"
                            + "clearSkyNoCanvas (input + observed band verbatim). A flawless clear dawn "
                            + "with no canvas — clean light but no subject in the sky, observed 3★. Guards "
                            + "the clearing-to-bald-blue failure mode. Coastal (tide).",
                    RatingBand.atMost(3),
                    "st-marys-7apr-clear-sky-cap.json"),
            new SkyRatingEvalFixture(
                    "middling-hazy-mixed",
                    "Hand-authored middling sunset (the one synthetic fixture, kept for low-middle "
                            + "coverage no real day supplies): moderate mixed cloud, modest canvas, "
                            + "slightly hazy air. Proves a graded (non-bimodal) response — the scorer "
                            + "can return the middle of the range.",
                    new RatingBand(2, 3),
                    "middling-hazy-mixed.json"));

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
