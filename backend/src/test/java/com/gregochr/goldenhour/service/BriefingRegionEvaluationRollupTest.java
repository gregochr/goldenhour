package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
        BriefingRegion region = new BriefingRegion("Tyne and Wear", Verdict.GO, "Clear skies",
                List.of(), List.of(slot), 12.0, 11.0, 3.0, 0, null, null,
                DisplayVerdict.WORTH_IT, 1);
        BriefingEventSummary summary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        return List.of(new BriefingDay(DATE, List.of(summary)));
    }

    private BriefingSlot enrichedSlot(BriefingSlot slot, RegionScoreResolver resolver) {
        List<BriefingDay> enriched = rollup.enrich(daysWith(slot), resolver);
        return enriched.getFirst().eventSummaries().getFirst().regions().getFirst().slots()
                .getFirst();
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
}
