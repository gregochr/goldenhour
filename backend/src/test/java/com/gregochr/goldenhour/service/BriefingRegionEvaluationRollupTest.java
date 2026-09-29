package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingRegionEvaluationRollup#enrichSlot}, isolated from
 * {@link EvaluationViewService} — the resolver here is a plain lambda the test controls directly,
 * so these pin the rollup's OWN reaction to what a resolver hands it, independent of how
 * {@code EvaluationViewService} computes that map.
 *
 * <p>This is the other half of the stale-rating stability-skip fix. {@code
 * EvaluationViewServiceTest}'s {@code stability-skip retraction} tests prove the resolver returns
 * {@link BriefingEvaluationResult#retracted} rather than a bare absent entry; these prove
 * {@link #enrichSlot} actually acts on that marker — clearing a slot's EMBEDDED rating (the shape a
 * persisted {@code BriefingDay} tree carries from whichever build last rated it) rather than
 * leaving it untouched the way a genuine absent entry correctly would.
 */
class BriefingRegionEvaluationRollupTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 10, 1, 6, 30);
    private static final String LOCATION = "Angel of the North";
    private static final BriefingSlot.WeatherConditions WEATHER =
            new BriefingSlot.WeatherConditions(
                    20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0, BigDecimal.ONE, 30, 40);

    private BriefingRegionEvaluationRollup rollup;

    @BeforeEach
    void setUp() {
        // Fixed at the slot's own date so the confidence-horizon maths this class also runs
        // (daysAhead = 0) stays out of the way of what these tests actually assert.
        rollup = new BriefingRegionEvaluationRollup(
                Clock.fixed(DATE.atStartOfDay(LONDON).toInstant(), LONDON));
    }

    /**
     * A slot as it would be read back from {@code daily_briefing_cache} after an earlier build
     * rated it 4★ — {@code claudeRating}, {@code skyRating}, both potentials, the summary and the
     * headline are all populated, exactly what a persisted, previously-scored slot carries.
     */
    private static BriefingSlot ratedSlot() {
        return new BriefingSlot(LOCATION, EVENT_TIME, Verdict.GO, WEATHER,
                BriefingSlot.TideInfo.NONE, List.of("Clear"), null)
                .withClaudeScores(4, 4, 75, 60, "Great sky", "Fiery dawn over the Angel");
    }

    private List<BriefingDay> daysWith(BriefingSlot slot) {
        return daysWith(List.of(slot));
    }

    private List<BriefingDay> daysWith(List<BriefingSlot> slots) {
        return daysWith(slots, Verdict.GO);
    }

    /**
     * As {@link #daysWith(List)}, with the region's own triage {@code verdict} — the fallback the
     * verdict-minimum-sample rule resolves to when the sample is insufficient — supplied
     * explicitly, so a test can pick a fallback value distinguishable from whatever the rated
     * average would otherwise produce.
     */
    private List<BriefingDay> daysWith(List<BriefingSlot> slots, Verdict triageFallback) {
        BriefingRegion region = new BriefingRegion("Tyne and Wear", triageFallback, "Clear skies",
                List.of(), slots, 12.0, 11.0, 3.0, 0, null, null,
                DisplayVerdict.resolve(null, triageFallback), 1);
        BriefingEventSummary summary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        return List.of(new BriefingDay(DATE, List.of(summary)));
    }

    private BriefingSlot enrichedSlot(BriefingSlot slot, RegionScoreResolver resolver) {
        List<BriefingDay> enriched = rollup.enrich(daysWith(slot), resolver);
        return enriched.getFirst().eventSummaries().getFirst().regions().getFirst().slots()
                .getFirst();
    }

    /** Enriches a whole roster of slots and returns the resulting region. */
    private BriefingRegion enrichedRegion(List<BriefingSlot> slots, RegionScoreResolver resolver) {
        return enrichedRegion(slots, Verdict.GO, resolver);
    }

    /** As above, with the region's own triage fallback verdict supplied explicitly. */
    private BriefingRegion enrichedRegion(List<BriefingSlot> slots, Verdict triageFallback,
            RegionScoreResolver resolver) {
        List<BriefingDay> enriched = rollup.enrich(daysWith(slots, triageFallback), resolver);
        return enriched.getFirst().eventSummaries().getFirst().regions().getFirst();
    }

    @Test
    @DisplayName("a retracted marker clears an EMBEDDED rating — the slot reads as never-rated")
    void retractedMarker_clearsEmbeddedRating() {
        BriefingSlot persisted = ratedSlot();
        assertThat(persisted.claudeRating()).isEqualTo(4); // sanity: the fixture really is rated

        RegionScoreResolver resolver = (regionName, date, targetType) ->
                Map.of(LOCATION, BriefingEvaluationResult.retracted(LOCATION));

        BriefingSlot result = enrichedSlot(persisted, resolver);

        assertThat(result.claudeRating()).isNull();
        assertThat(result.skyRating()).isNull();
        assertThat(result.fierySkyPotential()).isNull();
        assertThat(result.goldenHourPotential()).isNull();
        assertThat(result.claudeSummary()).isNull();
        assertThat(result.claudeHeadline()).isNull();
    }

    @Test
    @DisplayName("a retracted slot's verdict fields match a never-rated slot at the same triage state")
    void retractedMarker_verdictMatchesNeverRatedSlot() {
        // Two slots that started identical (same weather triage verdict, same standdownReason:
        // null) — one persisted with an embedded rating that gets retracted, the other never
        // enriched with anything at all. Both must land on the SAME displayVerdict/standdownReason,
        // proving retraction reads as "never rated", never as a weather stand-down.
        BriefingSlot ratedThenRetracted = enrichedSlot(ratedSlot(),
                (regionName, date, targetType) -> Map.of(LOCATION,
                        BriefingEvaluationResult.retracted(LOCATION)));
        BriefingSlot neverTouched = enrichedSlot(
                new BriefingSlot(LOCATION, EVENT_TIME, Verdict.GO, WEATHER,
                        BriefingSlot.TideInfo.NONE, List.of("Clear"), null),
                (regionName, date, targetType) -> Map.of());

        assertThat(ratedThenRetracted.displayVerdict()).isEqualTo(neverTouched.displayVerdict());
        assertThat(ratedThenRetracted.verdict()).isEqualTo(neverTouched.verdict());
        assertThat(ratedThenRetracted.standdownReason()).isEqualTo(neverTouched.standdownReason());
        assertThat(ratedThenRetracted.claudeRating()).isEqualTo(neverTouched.claudeRating());
    }

    @Test
    @DisplayName("retraction never sets a triage reason — it is not read as a weather stand-down")
    void retractedMarker_neverSetsATriageReason() {
        BriefingSlot result = enrichedSlot(ratedSlot(),
                (regionName, date, targetType) -> Map.of(LOCATION,
                        BriefingEvaluationResult.retracted(LOCATION)));

        // The slot's own GO triage verdict survives untouched — retraction clears Claude's fields
        // only, never substitutes a stand-down reason of its own.
        assertThat(result.verdict()).isEqualTo(Verdict.GO);
        assertThat(result.standdownReason()).isNull();
        assertThat(result.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
    }

    @Test
    @DisplayName("a rating OLDER than a skip's fixture is retracted, but a resolver with no entry "
            + "for the slot leaves an embedded rating untouched")
    void absentEntry_leavesEmbeddedRatingUntouched() {
        // The pre-existing, still-correct half of enrichSlot's contract: a slot outside the
        // resolver's coverage (e.g. a date/region it does not answer for) must not be blanked just
        // because nothing new was said about it. Only a resolved retraction — never a bare absence
        // — may clear an embedded rating.
        BriefingSlot persisted = ratedSlot();

        BriefingSlot result = enrichedSlot(persisted, (regionName, date, targetType) -> Map.of());

        assertThat(result.claudeRating()).isEqualTo(4);
        assertThat(result.claudeSummary()).isEqualTo("Great sky");
        assertThat(result).isEqualTo(persisted);
    }

    @Test
    @DisplayName("a normal (non-retracted) resolved rating still updates the slot as before")
    void normalRating_stillUpdatesSlot() {
        BriefingSlot persisted = ratedSlot();
        BriefingEvaluationResult fresh = new BriefingEvaluationResult(
                LOCATION, 5, 90, 85, "Even better now", null, null, "Blazing dawn",
                Instant.now(), 5);

        BriefingSlot result = enrichedSlot(persisted,
                (regionName, date, targetType) -> Map.of(LOCATION, fresh));

        assertThat(result.claudeRating()).isEqualTo(5);
        assertThat(result.claudeSummary()).isEqualTo("Even better now");
        assertThat(result.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
    }

    /**
     * The verdict-minimum-sample rule end to end ({@code docs/engineering/plan-verdict-consolidation-plan.md},
     * {@link VerdictSampleGate}, owner decision 2026-09-29 for the force-evaluation exemption).
     * These drive the real {@link BriefingRegionEvaluationRollup#enrich} over a whole roster of
     * slots — not a single one, as the rest of this file does — because the rule is a claim about
     * the REGION's sample, not about any one slot.
     */
    @Nested
    @DisplayName("verdict-minimum-sample rule")
    class VerdictMinimumSample {

        /** A rated, non-canopy voting slot — GO triage, a Claude rating already embedded. */
        private BriefingSlot rated(String name, int rating) {
            return new BriefingSlot(name, EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of(), null)
                    .withClaudeScores(rating, 75, 60, "summary");
        }

        /** A voting slot the weather triage stood down — examined, but never rated. */
        private BriefingSlot triaged(String name) {
            return new BriefingSlot(name, EVENT_TIME, Verdict.STANDDOWN, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of(), "Grey ceiling");
        }

        /** A voting slot nobody has looked at this cycle — beyond Gate 4's horizon, unforced. */
        private BriefingSlot untouched(String name) {
            return new BriefingSlot(name, EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of(), null);
        }

        /** {@code count} untouched slots, named {@code prefix0..count-1}. */
        private List<BriefingSlot> untouchedSlots(String prefix, int count) {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                slots.add(untouched(prefix + i));
            }
            return slots;
        }

        /** A resolver that reports nothing new for any slot — the embedded ratings stand as is. */
        private RegionScoreResolver noOpResolver() {
            return (regionName, date, targetType) -> Map.of();
        }

        /**
         * A resolver that marks specific locations' CURRENT rating as force-evaluated, reporting
         * nothing for every other name (so their embedded ratings are read as unforced — the
         * resolver "has nothing new to say" for them, which must never grant the exemption).
         */
        private RegionScoreResolver forcedResolver(Map<String, Integer> forcedNameToRating) {
            Map<String, BriefingEvaluationResult> results = new HashMap<>();
            forcedNameToRating.forEach((name, rating) -> results.put(name,
                    new BriefingEvaluationResult(name, rating, 75, 60, "summary",
                            null, null, null, Instant.now(), null).withForced(true)));
            return (regionName, date, targetType) -> results;
        }

        @Test
        @DisplayName("4 rated of 5 voting (1 untouched) — full coverage, but below MIN_RATED: "
                + "insufficient, falls back to the triage verdict")
        void fourRatedOfFive_belowMinRated_fallsBackToTriage() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                slots.add(rated("R" + i, 5));
            }
            slots.add(untouched("Untouched"));

            // Triage fallback deliberately different from what the 5-star average would say
            // (WORTH_IT), so a pass-through bug cannot hide behind an accidentally matching value.
            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
            assertThat(region.confidence()).isEqualTo(Confidence.LOW);
            // Stars are never touched by the sample gate.
            assertThat(region.meanRating()).isEqualTo(5.0);
            assertThat(region.bestRating()).isEqualTo(5);
        }

        @Test
        @DisplayName("5 rated of 5 voting — meets MIN_RATED with full coverage: sufficient, the "
                + "rated average sets the verdict")
        void fiveRatedOfFive_sufficient() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                slots.add(rated("R" + i, 5));
            }

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            assertThat(region.sampleSufficient()).isTrue();
            assertThat(region.forcedSample()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.meanRating()).isEqualTo(5.0);
            assertThat(region.bestRating()).isEqualTo(5);
        }

        @Test
        @DisplayName("the poor-weather near window: 35 triaged + 15 rated of 50 voting keeps "
                + "today's verdict — the region HAS been examined in full")
        void poorWeatherNearWindow_thirtyFiveTriagedFifteenRated_keepsVerdict() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                slots.add(triaged("Triaged" + i));
            }
            for (int i = 0; i < 15; i++) {
                slots.add(rated("Rated" + i, 4));
            }

            BriefingRegion region = enrichedRegion(slots, Verdict.STANDDOWN, noOpResolver());

            assertThat(region.sampleSufficient()).isTrue();
            assertThat(region.forcedSample()).isFalse();
            // 15 locations all rated 4 -> mean 4.0 -> WORTH_IT, exactly as it would read before
            // this gate existed. The gate must not touch a region that has been looked at in full.
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.meanRating()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("one ORDINARY (non-forced) 4-star rating in a voting roster of fifty falls "
                + "back to the triage verdict and is not sufficient")
        void oneOrdinaryRatingInFifty_fallsBackToTriage() {
            List<BriefingSlot> slots = new ArrayList<>();
            slots.add(rated("Solo", 4));
            slots.addAll(untouchedSlots("Untouched", 49));

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
            // The star is unaffected: the one rated location still reports its own 4.
            assertThat(region.meanRating()).isEqualTo(4.0);
            assertThat(region.bestRating()).isEqualTo(4);
        }

        @Test
        @DisplayName("six force-evaluated 4-star ratings in a voting roster of fifty are exempt: "
                + "WORTH_IT stands and the region remains pick-eligible")
        void sixForcedRatingsInFifty_exempt() {
            List<BriefingSlot> slots = new ArrayList<>();
            Map<String, Integer> forced = new HashMap<>();
            for (int i = 0; i < 6; i++) {
                String name = "Forced" + i;
                slots.add(rated(name, 4));
                forced.put(name, 4);
            }
            slots.addAll(untouchedSlots("Untouched", 44));

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, forcedResolver(forced));

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isTrue();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.meanRating()).isEqualTo(4.0);
            assertThat(region.bestRating()).isEqualTo(4);
        }

        @Test
        @DisplayName("the same six ratings with EVALUATED (not forced) dispositions fall back to "
                + "the triage verdict")
        void sixOrdinaryRatingsInFifty_notExempt_fallsBackToTriage() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                slots.add(rated("Ordinary" + i, 4));
            }
            slots.addAll(untouchedSlots("Untouched", 44));

            // noOpResolver: the resolver reports nothing new (as an ordinary EVALUATED disposition
            // that merely reaffirms an already-embedded rating would), so none of the six reads
            // forced.
            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("one forced rating among four ordinary ones (5 rated, thin coverage) exempts "
                + "the whole region")
        void oneForcedAmongFourOrdinary_exemptsRegion() {
            List<BriefingSlot> slots = new ArrayList<>();
            slots.add(rated("Forced", 4));
            for (int i = 0; i < 4; i++) {
                slots.add(rated("Ordinary" + i, 4));
            }
            // 45 more untouched so coverage (5 of 50) is thin — the raw gate would refuse this
            // region even though MIN_RATED (5) is met, isolating the exemption's own effect.
            slots.addAll(untouchedSlots("Untouched", 45));

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL,
                    forcedResolver(Map.of("Forced", 4)));

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isTrue();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("a resolver reporting nothing for a rated slot never grants the exemption — "
                + "unknown reads as not forced")
        void resolverReportsNothing_neverGrantsExemption() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                slots.add(rated("R" + i, 4));
            }
            slots.addAll(untouchedSlots("Untouched", 44));

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            assertThat(region.forcedSample()).isFalse();
            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("the all-canopy fallback roster votes as one, and the sample gate reads its "
                + "own (fallback) roster size — five rated woods are sufficient")
        void allCanopyFallbackRoster_gateReadsFallbackRoster() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                slots.add(BriefingSlot.canopySlot("Wood" + i, EVENT_TIME, Verdict.GO, WEATHER,
                                List.of(), null)
                        .withClaudeScores(4, 75, 60, "summary"));
            }

            BriefingRegion region = enrichedRegion(slots, Verdict.MARGINAL, noOpResolver());

            // All-canopy: BriefingSlot.votingSlots falls back to the full (woodland) list, so the
            // gate's roster is 5, not 0 — five rated woods clear MIN_RATED and full coverage.
            assertThat(region.sampleSufficient()).isTrue();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.meanRating()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("a region with zero rated voting slots behaves exactly as before this rule — "
                + "the triage fallback, and null confidence (nothing at all is scored)")
        void zeroRated_behavesAsBefore() {
            BriefingRegion region = enrichedRegion(untouchedSlots("Untouched", 10),
                    Verdict.GO, noOpResolver());

            assertThat(region.sampleSufficient()).isFalse();
            assertThat(region.forcedSample()).isFalse();
            assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(region.confidence()).isNull();
            assertThat(region.meanRating()).isNull();
            assertThat(region.bestRating()).isNull();
        }

        @Test
        @DisplayName("build path and serve path agree for one fixture — the same resolver output "
                + "yields the same region either way")
        void buildAndServePathsAgree() {
            List<BriefingSlot> slots = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                slots.add(rated("R" + i, 5));
            }
            slots.add(untouched("Untouched"));

            // The build path hands the rollup a per-region resolver
            // (EvaluationViewService::getScoresForEnrichment); the serve path hands it one backed
            // by a bulk load (EvaluationViewService::getScoresForEnrichmentBulk /
            // ServedBriefingAssembler). BriefingRegionEvaluationRollup.enrich cannot tell which
            // shape produced the map it is handed — both are exercised here as the identical
            // no-op resolver lambda, and the two calls must produce identical regions.
            RegionScoreResolver buildPathResolver = noOpResolver();
            RegionScoreResolver servePathResolver = noOpResolver();

            BriefingRegion fromBuildPath = enrichedRegion(slots, Verdict.MARGINAL, buildPathResolver);
            BriefingRegion fromServePath = enrichedRegion(slots, Verdict.MARGINAL, servePathResolver);

            assertThat(fromBuildPath.sampleSufficient()).isEqualTo(fromServePath.sampleSufficient());
            assertThat(fromBuildPath.forcedSample()).isEqualTo(fromServePath.forcedSample());
            assertThat(fromBuildPath.displayVerdict()).isEqualTo(fromServePath.displayVerdict());
            assertThat(fromBuildPath.confidence()).isEqualTo(fromServePath.confidence());
            assertThat(fromBuildPath.meanRating()).isEqualTo(fromServePath.meanRating());
        }
    }
}
