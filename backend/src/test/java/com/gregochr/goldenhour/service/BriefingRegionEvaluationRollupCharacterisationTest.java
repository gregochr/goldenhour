package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation of the per-region arithmetic inside {@link BriefingRegionEvaluationRollup#enrich}: the
 * verdict, mean, best, confidence, sample flags and gloss rule it produces over realistic region shapes.
 *
 * <p>Written BEFORE that arithmetic was extracted into a method the reach-scoped headline re-runs on a pruned
 * tree (reach-scoped-card-plan R2 step 2), and kept after it: it pins the outputs, not the structure, so the
 * extraction is proven a pure move when this class is green on both sides of it. Every expectation is the
 * value the unextracted code produced.
 */
class BriefingRegionEvaluationRollupCharacterisationTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 10, 1, 6, 30);
    private static final BriefingSlot.WeatherConditions WEATHER = new BriefingSlot.WeatherConditions(
            20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0, BigDecimal.ONE, 30, 40);

    private BriefingRegionEvaluationRollup rollup;

    @BeforeEach
    void setUp() {
        rollup = new BriefingRegionEvaluationRollup(Clock.fixed(TODAY.atStartOfDay(LONDON).toInstant(), LONDON));
    }

    private static BriefingSlot slot(int index, boolean canopy) {
        long id = 100L + index;
        String name = "Place " + index;
        return canopy
                ? BriefingSlot.canopySlot(id, name, EVENT_TIME, Verdict.GO, WEATHER, List.of())
                : new BriefingSlot(id, name, EVENT_TIME, Verdict.GO, WEATHER, BriefingSlot.TideInfo.NONE, List.of());
    }

    private static List<BriefingSlot> sky(int count) {
        List<BriefingSlot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            slots.add(slot(i, false));
        }
        return slots;
    }

    private static BriefingEvaluationResult rated(int index, int rating, boolean forced) {
        BriefingEvaluationResult result = new BriefingEvaluationResult("Place " + index, rating, 70, 60, "why");
        return forced ? result.withForced(true) : result;
    }

    private static RegionScoreResolver resolving(Map<String, BriefingEvaluationResult> results) {
        return (regionName, date, targetType) -> results;
    }

    /** Ratings by slot index, in order, with no forced mark. */
    private static Map<String, BriefingEvaluationResult> ratings(int... byIndex) {
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        for (int i = 0; i < byIndex.length; i++) {
            if (byIndex[i] > 0) {
                results.put("Place " + i, rated(i, byIndex[i], false));
            }
        }
        return results;
    }

    private BriefingRegion enriched(List<BriefingSlot> slots, Verdict weather, String gloss,
            DisplayVerdict previous, RegionScoreResolver resolver, TriagedByBatchResolver triaged) {
        BriefingRegion region = new BriefingRegion("Lakes", weather, "summary", List.of(), slots,
                12.0, 11.0, 3.0, 0, gloss, gloss == null ? null : "detail", previous, 0);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of());
        List<BriefingDay> out = rollup.enrich(List.of(new BriefingDay(TODAY, List.of(summary))), resolver, triaged);
        return out.getFirst().eventSummaries().getFirst().regions().getFirst();
    }

    private BriefingRegion enriched(List<BriefingSlot> slots, Verdict weather, RegionScoreResolver resolver) {
        return enriched(slots, weather, null, DisplayVerdict.resolve(null, weather), resolver,
                (regionName, date, targetType) -> Set.of());
    }

    @Test
    @DisplayName("a well-sampled region (12 of 12 rated, mean 3.9, range 2): Worth it, best 5, sufficient, MEDIUM")
    void wellSampledRegion() {
        BriefingRegion region = enriched(sky(12), Verdict.GO,
                resolving(ratings(4, 4, 4, 3, 5, 4, 3, 4, 4, 5, 4, 3)));

        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(region.meanRating()).isEqualTo(3.9);
        assertThat(region.bestRating()).isEqualTo(5);
        assertThat(region.scoredLocationCount()).isEqualTo(12);
        assertThat(region.sampleSufficient()).isTrue();
        assertThat(region.forcedSample()).isFalse();
        assertThat(region.verdictEligible()).isTrue();
        assertThat(region.pickEligible()).isTrue();
        // Today (daysAhead 0) is HIGH, downgraded one band by the 5-vs-3 spread.
        assertThat(region.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("a tight cluster (12 rated, all 3 or 4): Worth it at HIGH, since nothing downgrades it")
    void tightClusterIsHigh() {
        BriefingRegion region = enriched(sky(12), Verdict.GO,
                resolving(ratings(4, 4, 4, 4, 4, 4, 3, 4, 4, 4, 4, 4)));

        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(region.meanRating()).isEqualTo(3.9);
        assertThat(region.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("a mid-band mean (2.5 to 3.4) reads Maybe and a low one reads Poor (STAND_DOWN)")
    void middleAndLowBands() {
        BriefingRegion maybe = enriched(sky(6), Verdict.GO, resolving(ratings(3, 3, 3, 3, 3, 2)));
        BriefingRegion poor = enriched(sky(6), Verdict.GO, resolving(ratings(2, 2, 2, 1, 2, 2)));

        assertThat(maybe.displayVerdict()).isEqualTo(DisplayVerdict.MAYBE);
        assertThat(maybe.meanRating()).isEqualTo(2.8);
        assertThat(poor.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(poor.meanRating()).isEqualTo(1.8);
    }

    @Test
    @DisplayName("a thin sample (3 rated of 10, no force): UNRATED from the weather fallback, LOW, mean still served")
    void thinSampleIsUnrated() {
        BriefingRegion region = enriched(sky(10), Verdict.GO, resolving(ratings(5, 5, 4)));

        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(region.meanRating()).isEqualTo(4.7);
        assertThat(region.bestRating()).isEqualTo(5);
        assertThat(region.scoredLocationCount()).isEqualTo(3);
        assertThat(region.sampleSufficient()).isFalse();
        assertThat(region.forcedSample()).isFalse();
        assertThat(region.verdictEligible()).isFalse();
        assertThat(region.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("a thin sample under a STANDDOWN weather call falls back to STAND_DOWN, not UNRATED")
    void thinSampleUnderStanddownWeather() {
        BriefingRegion region = enriched(sky(10), Verdict.STANDDOWN, resolving(ratings(5, 5, 4)));

        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(region.sampleSufficient()).isFalse();
    }

    @Test
    @DisplayName("one forced rating exempts a thin region: the verdict follows the mean, sufficient stays false")
    void forcedSampleExempts() {
        Map<String, BriefingEvaluationResult> results = ratings(5, 5, 4);
        results.put("Place 0", rated(0, 5, true));

        BriefingRegion region = enriched(sky(10), Verdict.GO, resolving(results));

        assertThat(region.forcedSample()).isTrue();
        assertThat(region.sampleSufficient()).isFalse();
        assertThat(region.verdictEligible()).isTrue();
        assertThat(region.pickEligible()).isFalse();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        // Exempt: no extra LOW floor, so confidence follows the roster rules: HIGH today, one band down for
        // the thin coverage (3 of 10), and not LOW as the unexempted thin sample above is.
        assertThat(region.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("a forced mark on an UNRATED slot does not exempt the region")
    void forcedWithoutRatingDoesNotExempt() {
        Map<String, BriefingEvaluationResult> results = ratings(5, 5, 4);
        results.put("Place 7", new BriefingEvaluationResult("Place 7", null, null, null, null));

        BriefingRegion region = enriched(sky(10), Verdict.GO, resolving(results));

        assertThat(region.forcedSample()).isFalse();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
    }

    @Test
    @DisplayName("batch-triaged places count as examined: 5 rated of 12 fails the gate, plus 2 triaged passes it")
    void triagedNamesMakeASampleSufficient() {
        Map<String, BriefingEvaluationResult> results = ratings(4, 4, 4, 4, 4);

        BriefingRegion without = enriched(sky(12), Verdict.GO, null, DisplayVerdict.UNRATED,
                resolving(results), (regionName, date, targetType) -> Set.of());
        BriefingRegion with = enriched(sky(12), Verdict.GO, null, DisplayVerdict.UNRATED,
                resolving(results), (regionName, date, targetType) -> Set.of("Place 9", "Place 10"));

        assertThat(without.sampleSufficient()).isFalse();
        assertThat(without.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(with.sampleSufficient()).isTrue();
        assertThat(with.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
    }

    @Test
    @DisplayName("a triage reason on the resolved evaluation counts as examined as well")
    void resolvedTriageReasonCountsAsExamined() {
        Map<String, BriefingEvaluationResult> results = ratings(4, 4, 4, 4, 4);
        results.put("Place 9", new BriefingEvaluationResult("Place 9", null, null, null, null,
                TriageReason.HIGH_CLOUD, "cloud"));
        results.put("Place 10", new BriefingEvaluationResult("Place 10", null, null, null, null,
                TriageReason.PRECIPITATION, "rain"));

        BriefingRegion region = enriched(sky(12), Verdict.GO, resolving(results));

        assertThat(region.sampleSufficient()).isTrue();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
    }

    @Test
    @DisplayName("a rated wood is covered but never votes: the mean, best and count come from the sky places")
    void ratedWoodDoesNotVote() {
        List<BriefingSlot> slots = new ArrayList<>(sky(6));
        slots.add(slot(6, true));

        BriefingRegion region = enriched(slots, Verdict.GO, resolving(ratings(2, 2, 2, 2, 2, 2, 5)));

        assertThat(region.meanRating()).isEqualTo(2.0);
        assertThat(region.bestRating()).isEqualTo(2);
        // Coverage counts the wood, because it IS evaluated.
        assertThat(region.scoredLocationCount()).isEqualTo(7);
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
    }

    @Test
    @DisplayName("an all-woodland region votes on its woods (the fallback) but its roster of sky places is empty")
    void allWoodlandRegion() {
        List<BriefingSlot> slots = List.of(slot(0, true), slot(1, true), slot(2, true), slot(3, true),
                slot(4, true), slot(5, true));

        BriefingRegion region = enriched(slots, Verdict.GO, resolving(ratings(4, 4, 4, 4, 4, 4)));

        assertThat(region.meanRating()).isEqualTo(4.0);
        assertThat(region.scoredLocationCount()).isEqualTo(6);
        assertThat(region.sampleSufficient()).isTrue();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
    }

    @Test
    @DisplayName("nothing rated: the weather call stands (GO reads UNRATED), no mean, no best, null confidence")
    void nothingRated() {
        BriefingRegion go = enriched(sky(5), Verdict.GO, resolving(Map.of()));
        BriefingRegion marginal = enriched(sky(5), Verdict.MARGINAL, resolving(Map.of()));
        BriefingRegion standdown = enriched(sky(5), Verdict.STANDDOWN, resolving(Map.of()));

        assertThat(go.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(marginal.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(standdown.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(go.meanRating()).isNull();
        assertThat(go.bestRating()).isNull();
        assertThat(go.scoredLocationCount()).isZero();
        assertThat(go.confidence()).isNull();
        assertThat(go.sampleSufficient()).isFalse();
    }

    @Test
    @DisplayName("a wood-only rating: something is scored, none of it votes, so LOW rather than null")
    void onlyAWoodIsRated() {
        List<BriefingSlot> slots = new ArrayList<>(sky(5));
        slots.add(slot(5, true));

        BriefingRegion region = enriched(slots, Verdict.GO, resolving(ratings(0, 0, 0, 0, 0, 4)));

        assertThat(region.scoredLocationCount()).isEqualTo(1);
        assertThat(region.meanRating()).isNull();
        assertThat(region.confidence()).isEqualTo(Confidence.LOW);
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
    }

    @Test
    @DisplayName("an empty region keeps its weather fallback and a null confidence")
    void emptyRegion() {
        BriefingRegion region = enriched(List.of(), Verdict.GO, resolving(Map.of()));

        assertThat(region.slots()).isEmpty();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(region.scoredLocationCount()).isZero();
        assertThat(region.confidence()).isNull();
    }

    @Test
    @DisplayName("the gloss survives when the verdict is unchanged and is dropped when the rating moves it")
    void glossFollowsTheVerdict() {
        Map<String, BriefingEvaluationResult> results = ratings(4, 4, 4, 4, 4, 4);

        BriefingRegion kept = enriched(sky(6), Verdict.GO, "Fiery", DisplayVerdict.WORTH_IT,
                resolving(results), (regionName, date, targetType) -> Set.of());
        BriefingRegion dropped = enriched(sky(6), Verdict.GO, "Fiery", DisplayVerdict.MAYBE,
                resolving(results), (regionName, date, targetType) -> Set.of());

        assertThat(kept.glossHeadline()).isEqualTo("Fiery");
        assertThat(kept.glossDetail()).isEqualTo("detail");
        assertThat(dropped.glossHeadline()).isNull();
        assertThat(dropped.glossDetail()).isNull();
    }

    @Test
    @DisplayName("a far-out day (3 days) is MEDIUM at best and a 5-day one LOW, over the same sound sample")
    void confidenceFollowsTheHorizon() {
        BriefingRegion near = enrichedOn(TODAY.plusDays(3), sky(12), ratings(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4));
        BriefingRegion far = enrichedOn(TODAY.plusDays(5), sky(12), ratings(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4));

        assertThat(near.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(far.confidence()).isEqualTo(Confidence.LOW);
    }

    private BriefingRegion enrichedOn(LocalDate date, List<BriefingSlot> slots,
            Map<String, BriefingEvaluationResult> results) {
        BriefingRegion region = new BriefingRegion("Lakes", Verdict.GO, "summary", List.of(), slots,
                12.0, 11.0, 3.0, 0, null, null, DisplayVerdict.UNRATED, 0);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of());
        List<BriefingDay> out = rollup.enrich(List.of(new BriefingDay(date, List.of(summary))), resolving(results));
        return out.getFirst().eventSummaries().getFirst().regions().getFirst();
    }
}
