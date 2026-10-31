package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the Gate 2 honesty filter — the transform that
 * suppresses positive verdicts on regions with zero Claude-scored locations.
 *
 * <p>The transform is structurally identical to the input (same days/events
 * arrangement) and rewrites only the four user-facing presentation fields on
 * regions whose {@code scoredLocationCount == 0}.
 */
class BriefingHonestyFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 23);

    @Test
    @DisplayName("Zero-coverage region: all four display fields are rewritten")
    void zeroCoverageRegion_isRewritten() {
        BriefingRegion zeroCov = regionWith(
                "The Lake District", Verdict.GO, "Clear at 16 of 49 locations",
                DisplayVerdict.WORTH_IT, 0, threeSampleSlots());
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, zeroCov);

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);

        BriefingRegion rewritten = firstRegion(out);
        assertThat(rewritten.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(rewritten.verdictLabel()).isEqualTo("Not scored");
        assertThat(rewritten.summary()).isEqualTo(BriefingHonestyFilter.UNKNOWN_SUMMARY);
        assertThat(rewritten.glossHeadline()).isNull();
        assertThat(rewritten.glossDetail()).isEqualTo(BriefingHonestyFilter.UNKNOWN_GLOSS_DETAIL);
        assertThat(rewritten.slots()).isEmpty();
    }

    @Test
    @DisplayName("Zero-coverage region: triage verdict and weather fields are preserved")
    void zeroCoverageRegion_preservesTriageAndWeather() {
        BriefingRegion zeroCov = regionWith(
                "The Lake District", Verdict.GO, "Clear at 16 of 49 locations",
                DisplayVerdict.WORTH_IT, 0, threeSampleSlots());
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, zeroCov);

        BriefingRegion rewritten = firstRegion(BriefingHonestyFilter.apply(response));

        // Triage verdict survives so downstream consumers reading the API
        // response (e.g. anyone counting GO/MARGINAL slots) still see the
        // factual triage outcome.
        assertThat(rewritten.verdict()).isEqualTo(Verdict.GO);
        // Weather snapshot is factual and stays untouched.
        assertThat(rewritten.regionTemperatureCelsius()).isEqualTo(14.0);
        assertThat(rewritten.regionWindSpeedMs()).isEqualTo(4.5);
        assertThat(rewritten.regionWeatherCode()).isEqualTo(3);
        // Tide highlights stay too (factual, not part of the misleading state).
        assertThat(rewritten.tideHighlights()).containsExactly("Spring tide at 2 coastal spots");
    }

    @Test
    @DisplayName("Zero-coverage region: the event keeps its own time after its slots are emptied")
    void zeroCoverageRegion_preservesTheEventTime() {
        // The regression this filter caused elsewhere. Emptying the slot list is correct — no
        // per-location claim may survive — but the slots were also the only carrier of WHEN the
        // event happens. A blanked day therefore served with no time anywhere in it, and the
        // client, which treats a timeless event as one still to come, kept a long-elapsed sunrise
        // in its fixed-size list of upcoming events and lost the far end of the window.
        //
        // A solar event time is almanac data: it is true whether or not anything was evaluated,
        // so nothing about the honesty argument requires withholding it.
        LocalDateTime eventTime = LocalDateTime.of(2026, 8, 5, 5, 20);
        BriefingRegion zeroCov = regionWith(
                "The Lake District", Verdict.GO, "Clear at 16 of 49 locations",
                DisplayVerdict.WORTH_IT, 0, threeSampleSlots());
        DailyBriefingResponse response = new DailyBriefingResponse(
                LocalDateTime.now(), "headline",
                List.of(new BriefingDay(TODAY, List.of(new BriefingEventSummary(
                        TargetType.SUNRISE, List.of(zeroCov), List.of(), eventTime, null)))),
                List.of(), null, null, false, null, List.of(), List.of());

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);

        BriefingEventSummary rewritten = out.days().get(0).eventSummaries().get(0);
        assertThat(rewritten.regions().get(0).slots()).isEmpty();
        assertThat(rewritten.solarEventTime()).isEqualTo(eventTime);
    }

    @Test
    @DisplayName("Covered region: response is returned unchanged (same instance)")
    void coveredRegion_isUntouched() {
        BriefingRegion covered = regionWith(
                "Tyne and Wear", Verdict.GO, "Clear at 5 of 5 locations",
                DisplayVerdict.WORTH_IT, 5, threeSampleSlots());
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, covered);

        BriefingRegion rewritten = firstRegion(BriefingHonestyFilter.apply(response));

        // Same record instance (or at least field-equal) — no rewrite path taken.
        assertThat(rewritten.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(rewritten.verdictLabel()).isNull();
        assertThat(rewritten.summary()).isEqualTo("Clear at 5 of 5 locations");
        assertThat(rewritten.slots()).hasSize(3);
    }

    @Test
    @DisplayName("scoredLocationCount = 1 (the boundary) does NOT trigger override")
    void boundary_oneScoredLocation_isNotOverridden() {
        // Kills the mutant where the threshold becomes >= 1 → 0.
        BriefingRegion oneScored = regionWith(
                "North East", Verdict.GO, "Clear at 1 of 5 locations",
                DisplayVerdict.WORTH_IT, 1, threeSampleSlots());
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, oneScored);

        BriefingRegion rewritten = firstRegion(BriefingHonestyFilter.apply(response));

        assertThat(rewritten.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(rewritten.summary()).isEqualTo("Clear at 1 of 5 locations");
        assertThat(rewritten.slots()).hasSize(3);
        assertThat(rewritten.verdictLabel()).isNull();
    }

    @Test
    @DisplayName("Mixed regions: zero-coverage one is rewritten, covered one is not")
    void mixedRegions_overrideOnlyFiresOnZeroCoverage() {
        BriefingRegion zero = regionWith(
                "The Lake District", Verdict.GO, "Clear at 0 of 49 locations",
                DisplayVerdict.WORTH_IT, 0, threeSampleSlots());
        BriefingRegion covered = regionWith(
                "Tyne and Wear", Verdict.GO, "Clear at 5 of 5 locations",
                DisplayVerdict.WORTH_IT, 5, threeSampleSlots());
        DailyBriefingResponse response = new DailyBriefingResponse(
                LocalDateTime.now(), "headline",
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(TargetType.SUNSET,
                                List.of(zero, covered), List.of())))),
                List.of(), null, null, false, null, List.of(), List.of());

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);
        List<BriefingRegion> regions = out.days().get(0).eventSummaries().get(0).regions();

        assertThat(regions.get(0).displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(regions.get(0).verdictLabel()).isEqualTo("Not scored");
        assertThat(regions.get(0).slots()).isEmpty();
        assertThat(regions.get(1).displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(regions.get(1).verdictLabel()).isNull();
        assertThat(regions.get(1).slots()).hasSize(3);
    }

    @Test
    @DisplayName("Null response returns null (no NPE)")
    void nullInput_returnsNull() {
        assertThat(BriefingHonestyFilter.apply(null)).isNull();
    }

    // ── Canopy slots are not missing coverage ──────────────────────────────────
    // This filter is failure-defence: its premise is "we expected Claude coverage and did not get
    // it". A canopy slot is deliberately excluded from the sky batch, so it never expected any.
    // Counting it would make a wood-bearing region read as zero-coverage and receive the failure
    // message — a lie twice over, since nothing failed and a per-location forecast DOES exist
    // (the deterministic woodland verdict) — and would erase those verdicts daily.

    private static BriefingSlot canopySlot(String name, Verdict verdict) {
        return BriefingSlot.canopySlot(
                name, LocalDateTime.of(TODAY, java.time.LocalTime.of(7, 30)),
                verdict, null, List.of("Mist", "Soft even light"));
    }

    @Test
    @DisplayName("An all-canopy region is passed through, not blanked as a failure")
    void allCanopyRegion_isNotBlanked() {
        BriefingRegion woods = new BriefingRegion(
                "Durham", Verdict.GO, "Clear at 2 of 2 locations", List.of(),
                List.of(canopySlot("Houghall Woods", Verdict.GO),
                        canopySlot("Plessey Woods", Verdict.GO)),
                11.0, 10.0, 2.0, 3, null, null, DisplayVerdict.WORTH_IT, 0);
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNRISE, woods);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(response, 0.5));

        assertThat(out.slots()).as("woodland verdicts survive").hasSize(2);
        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(out.summary()).isNotEqualTo(BriefingHonestyFilter.REPLACEMENT_SUMMARY);
        assertThat(out.verdictLabel()).isNotEqualTo(BriefingHonestyFilter.VERDICT_LABEL);
    }

    @Test
    @DisplayName("A genuine batch failure is still blanked — the defence is not disabled")
    void skyRegionWithZeroCoverage_isStillBlanked() {
        BriefingRegion failed = new BriefingRegion(
                "Northumberland", Verdict.GO, "Clear at 4 of 4 locations", List.of(), goSlots(4),
                14.0, 13.0, 4.5, 3, "gloss", "detail", DisplayVerdict.WORTH_IT, 0);
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, failed);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(response, 0.5));

        assertThat(out.summary()).isEqualTo(BriefingHonestyFilter.UNKNOWN_SUMMARY);
        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
    }

    @Test
    @DisplayName("The canopy flag survives withClaudeScores")
    void canopyFlagSurvivesScoring() {
        BriefingSlot scored = canopySlot("Houghall Woods", Verdict.GO)
                .withClaudeScores(4, 70, 65, "summary");
        assertThat(scored.canopy())
                .as("a copy that drops the flag restores the ambiguity it exists to remove")
                .isTrue();
    }

    // ── Low but non-zero coverage: the region is served as it is ────────────────

    @Test
    @DisplayName("Low coverage (3 of 54): nothing suppressed")
    void lowCoverageRegion_isNotBlanked() {
        // 3 scored of a 54-location roster = ~6% coverage, below 0.5.
        BriefingRegion lowCov = new BriefingRegion(
                "The Yorkshire Dales", Verdict.GO, "Clear at 54 of 54 locations",
                List.of("Spring tide at 2 coastal spots"), goSlots(54),
                14.0, 13.0, 4.5, 3,
                "Clear sky for the win", "The evaluated spots look promising.",
                DisplayVerdict.WORTH_IT, 3);
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, lowCov);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(response, 0.5));

        // Lighter than the zero tier: real data is preserved, nothing blanked.
        assertThat(out.slots()).hasSize(54);
        assertThat(out.summary()).isEqualTo("Clear at 54 of 54 locations");
        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(out.glossDetail()).isEqualTo("The evaluated spots look promising.");
        assertThat(out.verdictLabel()).isNull();
        assertThat(out.scoredLocationCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Well-covered region (40 of 54): unchanged")
    void wellCoveredRegion_isUnchanged() {
        BriefingRegion wellCov = new BriefingRegion(
                "The Yorkshire Dales", Verdict.GO, "Clear at 54 of 54 locations",
                List.of(), goSlots(54), 14.0, 13.0, 4.5, 3,
                "Clear sky for the win", "Region-wide clean colour ramp.",
                DisplayVerdict.WORTH_IT, 40);
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, wellCov);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(response, 0.5));

        assertThat(out.summary()).isEqualTo("Clear at 54 of 54 locations");
        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(out.slots()).hasSize(54);
    }

    @Test
    @DisplayName("Zero coverage still gets the full rewrite with a non-zero ratio passed")
    void zeroCoverage_stillFullRewrite_withRatioEnabled() {
        BriefingRegion zeroCov = new BriefingRegion(
                "Region", Verdict.GO, "Clear at 54 of 54 locations",
                List.of(), goSlots(54), 14.0, 13.0, 4.5, 3,
                "headline", "detail", DisplayVerdict.WORTH_IT, 0);
        DailyBriefingResponse response = wrapAsResponse(TargetType.SUNSET, zeroCov);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(response, 0.5));

        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(out.verdictLabel()).isEqualTo("Not scored");
        assertThat(out.summary()).isEqualTo(BriefingHonestyFilter.UNKNOWN_SUMMARY);
        assertThat(out.slots()).isEmpty();
    }

    @Test
    @DisplayName("Hierarchy structure (days/events) is preserved across the transform")
    void hierarchy_isStructurallyPreserved() {
        BriefingRegion r = regionWith("North East", Verdict.GO, "summary",
                DisplayVerdict.WORTH_IT, 3, threeSampleSlots());
        DailyBriefingResponse response = new DailyBriefingResponse(
                LocalDateTime.now(), "headline",
                List.of(
                        new BriefingDay(TODAY, List.of(
                                new BriefingEventSummary(TargetType.SUNRISE, List.of(r), List.of()),
                                new BriefingEventSummary(TargetType.SUNSET, List.of(r), List.of())
                        )),
                        new BriefingDay(TODAY.plusDays(1), List.of(
                                new BriefingEventSummary(TargetType.SUNSET, List.of(r), List.of())
                        ))
                ),
                List.of(), null, null, false, null, List.of(), List.of());

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);

        assertThat(out.days()).hasSize(2);
        assertThat(out.days().get(0).eventSummaries()).hasSize(2);
        assertThat(out.days().get(0).eventSummaries().get(0).targetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(out.days().get(0).eventSummaries().get(1).targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(out.days().get(1).eventSummaries()).hasSize(1);
    }

    @Test
    @DisplayName("Top-level response fields (generatedAt, headline, aurora, hot topics) survive")
    void topLevelFields_arePreserved() {
        LocalDateTime when = LocalDateTime.of(2026, 5, 23, 6, 0);
        DailyBriefingResponse response = new DailyBriefingResponse(
                when, "best day this week", List.of(), List.of(), null, null,
                true, "Opus", List.of(), List.of("BLUEBELL"));

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);

        assertThat(out.generatedAt()).isEqualTo(when);
        assertThat(out.headline()).isEqualTo("best day this week");
        assertThat(out.stale()).isTrue();
        assertThat(out.bestBetModel()).isEqualTo("Opus");
        assertThat(out.seasonalFeatures()).containsExactly("BLUEBELL");
    }

    // ── Best Bets naming a blanked region ─────────────────────────────────────

    @Test
    @DisplayName("A Best Bet recommending a blanked region is withdrawn")
    void bestBetOnBlankedRegionWithdrawn() {
        // Picks are chosen at briefing BUILD time and frozen; coverage is re-derived at SERVE
        // time. When it drops to zero the rewrite says "No per-location forecast" — and the pick
        // would still be crowning that exact region and event on the same screen.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBets()).isEmpty();
        assertThat(firstRegion(out).summary())
                .isEqualTo(BriefingHonestyFilter.UNKNOWN_SUMMARY);
    }

    @Test
    @DisplayName("A Best Bet on a well-covered region is left alone")
    void bestBetOnCoveredRegionKept() {
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET,
                        regionWith("Northumberland", Verdict.GO, "Clear at 3 of 3",
                                DisplayVerdict.WORTH_IT, 3, threeSampleSlots()),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBets()).hasSize(1);
        assertThat(out.bestBets().get(0).region()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("Losing rank 1 withdraws rank 2 as well — it is defined relative to rank 1")
    void losingRankOneWithdrawsRankTwo() {
        // Rank 2 carries relationship/differsBy describing how it differs from rank 1, and prose
        // written as the runner-up to it. The banner also reads picks[0] as the top pick, so a
        // surviving rank 2 renders as a lone "② ALSO GOOD" compared against itself.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"),
                                bet(2, "The Lake District", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBets()).isEmpty();
        // An empty list has four producers and the client explains three of them as "the week was
        // flat". Only the flag distinguishes this one, where a standout existed and was pulled.
        assertThat(out.bestBetsWithdrawn()).isTrue();
    }

    @Test
    @DisplayName("Losing only rank 2 keeps rank 1 — nothing above it is left dangling")
    void losingRankTwoKeepsRankOne() {
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "The Lake District", "2026-05-23_sunset"),
                                bet(2, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBets()).hasSize(1);
        assertThat(out.bestBets().get(0).rank()).isEqualTo(1);
        assertThat(out.bestBets().get(0).region()).isEqualTo("The Lake District");
        // The surviving list is now indistinguishable from "the advisor only ever had one pick",
        // and the banner explains that case in prose — "nothing else scored well enough to be
        // worth a second trip. That's the forecast, not a missing recommendation" — which is
        // false here. The flag is what lets the client tell the two apart.
        assertThat(out.bestBetsWithdrawn()).isTrue();
    }

    @Test
    @DisplayName("Withdrawing nothing leaves the flag null — the ordinary case stays quiet")
    void noWithdrawalLeavesFlagNull() {
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET,
                        regionWith("Northumberland", Verdict.GO, "Clear at 3 of 3",
                                DisplayVerdict.WORTH_IT, 3, threeSampleSlots()),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBetsWithdrawn()).isNull();
    }

    @Test
    @DisplayName("The flag survives withDays — the projector rebuilds the response after this")
    void flagSurvivesWithDays() {
        // PlanWindowProjector is the OUTERMOST serve step and rebuilds via withDays. Dropping the
        // flag there would silently restore the false note this whole mechanism suppresses.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "The Lake District", "2026-05-23_sunset"),
                                bet(2, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.withDays(out.days()).bestBetsWithdrawn()).isTrue();
    }

    @Test
    @DisplayName("The same region at a DIFFERENT event is not withdrawn")
    void sameRegionDifferentEventKept() {
        // The key is region AND event. Blanking Northumberland's sunset says nothing about its
        // sunrise, which is a separate cell with its own coverage.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunrise"))),
                0.5);

        assertThat(out.bestBets()).hasSize(1);
    }

    @Test
    @DisplayName("Stay-home and aurora picks are never withdrawn")
    void stayHomeAndAuroraPicksUntouched() {
        // A stay-home pick names no region or event at all; an aurora pick's event is not a solar
        // slot. Neither can be describing the region this filter blanked.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, null, null),
                                bet(2, "Northumberland", "aurora_tonight"))),
                0.5);

        assertThat(out.bestBets()).hasSize(2);
    }

    @Test
    @DisplayName("A thinly covered (non-zero) region does not withdraw its Best Bet")
    void thinlyCoveredRegionKeepsItsBet() {
        // Only the zero-coverage rewrite contradicts a pick. A thinly covered region keeps its
        // real slots, scores and gloss — it is scoped, not unevaluable.
        DailyBriefingResponse out = BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET,
                        regionWith("Northumberland", Verdict.GO, "Clear at 1 of 10",
                                DisplayVerdict.WORTH_IT, 1, goSlots(10)),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"))),
                0.5);

        assertThat(out.bestBets()).hasSize(1);
    }

    @Test
    @DisplayName("Withdrawal does not touch bestBetStatus — the advisor did not fail")
    void withdrawalLeavesStatusAlone() {
        // Flipping it to FAILED would libel an advisor that succeeded, and would invite
        // applyBestBetFallback to replace these picks with a stale set for no reason. (Since the
        // reorder that set is now filtered too, so it would no longer smuggle in a phantom — but
        // the claim about the advisor would still be false, which is reason enough.)
        DailyBriefingResponse in = responseWith(TargetType.SUNSET,
                zeroCoverageRegion("Northumberland"),
                List.of(bet(1, "Northumberland", "2026-05-23_sunset")));

        DailyBriefingResponse out = BriefingHonestyFilter.apply(in, 0.5);

        assertThat(out.bestBets()).isEmpty();
        assertThat(out.bestBetStatus()).isEqualTo(in.bestBetStatus());
    }

    // ── Wording by coverage reason ────────────────────────────────────────────

    private static final Clock T_PLUS_0 = Clock.fixed(
            Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC);
    private static final Clock T_PLUS_4 = Clock.fixed(
            Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);

    private static Map<String, EvaluationViewService.LatestDisposition> latestForAll(
            DispositionCategory category, boolean cycleEnded) {
        Map<String, EvaluationViewService.LatestDisposition> latest = new HashMap<>();
        for (BriefingSlot s : threeSampleSlots()) {
            latest.put(EvaluationViewService.stabilitySkipKey(
                            s.locationName(), TODAY, TargetType.SUNSET),
                    new EvaluationViewService.LatestDisposition(category, cycleEnded));
        }
        return latest;
    }

    private static DailyBriefingResponse blanked(DispositionCategory category, boolean cycleEnded,
            Clock clock) {
        return BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"),
                        List.of(bet(1, "Northumberland", "2026-05-23_sunset"))),
                0.5, latestForAll(category, cycleEnded), clock);
    }

    private static void assertStandDownWording(BriefingRegion r) {
        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(r.verdictLabel()).isEqualTo("Too unsettled to forecast");
        assertThat(r.summary()).isEqualTo(BriefingHonestyFilter.REPLACEMENT_SUMMARY);
        assertThat(r.glossDetail()).isEqualTo(BriefingHonestyFilter.REPLACEMENT_GLOSS_DETAIL);
    }

    private static void assertNotScoredWording(BriefingRegion r) {
        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(r.verdictLabel()).isEqualTo("Not scored");
        assertThat(r.summary()).isEqualTo("No per-location forecast — the record does not say why");
        assertThat(r.glossDetail()).startsWith("No per-location forecast was produced for this region")
                .contains("Nothing here is a judgement about the weather.");
    }

    @Test
    @DisplayName("A travel day reads AWAITING 'Not forecast', never 'too unsettled'")
    void travelDay_isAwayWording() {
        BriefingRegion r = firstRegion(blanked(DispositionCategory.SKIPPED_TRAVEL_DAY, false,
                T_PLUS_0));

        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(r.verdictLabel()).isEqualTo("Not forecast");
        assertThat(r.summary()).isEqualTo("Marked as a travel day — no forecast was made");
        assertThat(r.glossDetail()).isEqualTo("This day is marked as a travel day, so no "
                + "per-location forecast was made for it.");
    }

    @Test
    @DisplayName("T+4 stability-skipped reads AWAITING 'Not scored at this range'")
    void tPlusFourStabilitySkip_isNotAttemptedWording() {
        BriefingRegion r = firstRegion(blanked(DispositionCategory.SKIPPED_STABILITY, false,
                T_PLUS_4));

        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(r.verdictLabel()).isEqualTo("Not scored at this range");
        assertThat(r.summary())
                .isEqualTo("Not scored this far ahead — forecasts firm up nearer the day");
        assertThat(r.glossDetail()).startsWith("Forecasts this far ahead are only made when");
    }

    @Test
    @DisplayName("A region triage stood down keeps STAND_DOWN and 'Too unsettled to forecast'")
    void triageStandDown_keepsStandDownWording() {
        assertStandDownWording(firstRegion(blanked(DispositionCategory.SKIPPED_TRIAGED, false,
                T_PLUS_0)));
    }

    /**
     * The three sample slots' latest dispositions, all SKIPPED_TRIAGED, the first
     * {@code lowVisibilityCount} of them for the visibility rule (valley-mist M1b).
     */
    private static Map<String, EvaluationViewService.LatestDisposition> triagedWithVisibility(
            int lowVisibilityCount) {
        Map<String, EvaluationViewService.LatestDisposition> latest = new HashMap<>();
        List<BriefingSlot> slots = threeSampleSlots();
        for (int i = 0; i < slots.size(); i++) {
            latest.put(EvaluationViewService.stabilitySkipKey(
                            slots.get(i).locationName(), TODAY, TargetType.SUNSET),
                    new EvaluationViewService.LatestDisposition(
                            DispositionCategory.SKIPPED_TRIAGED, false, i < lowVisibilityCount));
        }
        return latest;
    }

    private static BriefingRegion blankedWithVisibility(int lowVisibilityCount) {
        return firstRegion(BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"), List.of()),
                0.5, triagedWithVisibility(lowVisibilityCount), T_PLUS_0));
    }

    @Test
    @DisplayName("valley-mist M1b: a stand-down whose triage was mostly visibility reads 'Fog forecast', "
            + "still STAND_DOWN and still a STOOD_DOWN coverage reason")
    void visibilityMajority_readsFogForecast() {
        BriefingRegion r = blankedWithVisibility(3);

        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(r.verdictLabel()).isEqualTo("Fog forecast");
        assertThat(r.summary()).isEqualTo(BriefingHonestyFilter.FOG_SUMMARY);
        assertThat(r.glossDetail()).isEqualTo(BriefingHonestyFilter.FOG_GLOSS_DETAIL);
        assertThat(r.coverageReason()).isEqualTo(com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN);
        // Two of three is still a strict majority.
        assertThat(blankedWithVisibility(2).verdictLabel()).isEqualTo("Fog forecast");
    }

    @Test
    @DisplayName("valley-mist M1b: a visibility minority keeps 'Too unsettled to forecast'")
    void visibilityMinority_keepsTooUnsettled() {
        assertStandDownWording(blankedWithVisibility(1));
        assertStandDownWording(blankedWithVisibility(0));
    }

    @Test
    @DisplayName("valley-mist M1b: an exact tie keeps 'Too unsettled to forecast' — a specific cause "
            + "needs a majority")
    void visibilityTie_keepsTooUnsettled() {
        // Four scoreable slots, two stood down for visibility and two for anything else.
        List<BriefingSlot> four = List.of(
                slot("Buttermere", Verdict.GO), slot("Wast Water", Verdict.GO),
                slot("Coniston Water", Verdict.GO), slot("Ullswater", Verdict.GO));
        Map<String, EvaluationViewService.LatestDisposition> latest = new HashMap<>();
        for (int i = 0; i < four.size(); i++) {
            latest.put(EvaluationViewService.stabilitySkipKey(
                            four.get(i).locationName(), TODAY, TargetType.SUNSET),
                    new EvaluationViewService.LatestDisposition(
                            DispositionCategory.SKIPPED_TRIAGED, false, i < 2));
        }
        BriefingRegion region = regionWith("Northumberland", Verdict.GO, "Clear at 0 of 4",
                DisplayVerdict.WORTH_IT, 0, four);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, region, List.of()), 0.5, latest, T_PLUS_0));

        assertStandDownWording(out);
    }

    @Test
    @DisplayName("valley-mist M1b: only TRIAGE stand-downs make up the majority's denominator — two "
            + "visibility stand-downs among three triaged and two stability skips is still fog")
    void fogMajority_ignoresNonTriagedSlots() {
        List<BriefingSlot> five = List.of(
                slot("A", Verdict.GO), slot("B", Verdict.GO), slot("C", Verdict.GO),
                slot("D", Verdict.GO), slot("E", Verdict.GO));
        Map<String, EvaluationViewService.LatestDisposition> latest = new HashMap<>();
        for (int i = 0; i < five.size(); i++) {
            boolean triaged = i < 3;
            latest.put(EvaluationViewService.stabilitySkipKey(
                            five.get(i).locationName(), TODAY, TargetType.SUNSET),
                    new EvaluationViewService.LatestDisposition(
                            triaged ? DispositionCategory.SKIPPED_TRIAGED
                                    : DispositionCategory.SKIPPED_STABILITY,
                            false, i < 2));
        }
        BriefingRegion region = regionWith("Northumberland", Verdict.GO, "Clear at 0 of 5",
                DisplayVerdict.WORTH_IT, 0, five);

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, region, List.of()), 0.5, latest, T_PLUS_0));

        // Triage 3 beats stability 2, so the fold is STOOD_DOWN; 2 of 3 triaged are visibility.
        assertThat(out.verdictLabel()).isEqualTo("Fog forecast");
    }

    @Test
    @DisplayName("valley-mist M1b: a region that is not a triage stand-down never reads 'Fog forecast', "
            + "whatever the lowVisibility flag says (it only ever rides a SKIPPED_TRIAGED row)")
    void fogWording_onlyForAStandDown() {
        Map<String, EvaluationViewService.LatestDisposition> latest = new HashMap<>();
        for (BriefingSlot s : threeSampleSlots()) {
            latest.put(EvaluationViewService.stabilitySkipKey(
                            s.locationName(), TODAY, TargetType.SUNSET),
                    new EvaluationViewService.LatestDisposition(
                            DispositionCategory.SKIPPED_TRAVEL_DAY, false, true));
        }

        BriefingRegion r = firstRegion(BriefingHonestyFilter.apply(
                responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"), List.of()),
                0.5, latest, T_PLUS_0));

        assertThat(r.verdictLabel()).isEqualTo("Not forecast");
    }

    @Test
    @DisplayName("A submission that failed reads AWAITING 'Forecast unavailable'")
    void failedSubmission_isFailedWording() {
        BriefingRegion r = firstRegion(blanked(DispositionCategory.SUBMISSION_FAILED, false,
                T_PLUS_0));

        assertThat(r.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(r.verdictLabel()).isEqualTo("Forecast unavailable");
        assertThat(r.summary())
                .isEqualTo("No per-location forecast — the forecast run didn't complete");
        assertThat(r.glossDetail()).startsWith("The forecast run for this window didn't complete");
    }

    @Test
    @DisplayName("EVALUATED in an ended cycle is FAILED; in a cycle still going the record cannot say, "
            + "so it reads 'Not scored'")
    void evaluatedReadsByWhetherTheCycleEnded() {
        assertThat(firstRegion(blanked(DispositionCategory.EVALUATED, true, T_PLUS_0))
                .verdictLabel()).isEqualTo("Forecast unavailable");
        assertNotScoredWording(firstRegion(blanked(DispositionCategory.EVALUATED, false,
                T_PLUS_0)));
    }

    @Test
    @DisplayName("A region whose record is incomplete never reads STAND_DOWN: no lookup, an empty "
            + "lookup or an unsettled record read AWAITING 'Not scored'")
    void aRegionWhoseRecordIsIncompleteNeverReadsStandDown() {
        DailyBriefingResponse in = responseWith(TargetType.SUNSET,
                zeroCoverageRegion("Northumberland"), List.of());

        assertNotScoredWording(firstRegion(BriefingHonestyFilter.apply(in, 0.5)));
        assertNotScoredWording(firstRegion(
                BriefingHonestyFilter.apply(in, 0.5, Map.of(), T_PLUS_0)));
        assertNotScoredWording(firstRegion(blanked(
                DispositionCategory.SKIPPED_NO_REFRESH_NEEDED, false, T_PLUS_0)));
    }

    @Test
    @DisplayName("Whatever the reason the slots are emptied and a Best Bet on the region is "
            + "withdrawn")
    void everyReasonStaysBlanked() {
        List<DailyBriefingResponse> outs = List.of(
                blanked(DispositionCategory.SKIPPED_TRAVEL_DAY, false, T_PLUS_0),
                blanked(DispositionCategory.SKIPPED_STABILITY, false, T_PLUS_4),
                blanked(DispositionCategory.SKIPPED_TRIAGED, false, T_PLUS_0),
                blanked(DispositionCategory.SUBMISSION_FAILED, false, T_PLUS_0),
                blanked(DispositionCategory.EVALUATED, false, T_PLUS_0));

        for (DailyBriefingResponse out : outs) {
            assertThat(firstRegion(out).slots()).isEmpty();
            assertThat(out.bestBets()).isEmpty();
        }
    }

    @Test
    @DisplayName("A blanked region never reads UNRATED, whatever the reason — an UNRATED input is "
            + "re-worded AWAITING or STAND_DOWN with its reason wording (unrated-verdict-plan.md U1)")
    void aBlankedRegionNeverReadsUnrated() {
        // The shape a zero-coverage region now arrives in: weather GO, nothing rated -> UNRATED.
        BriefingRegion unratedIn = regionWith("Northumberland", Verdict.GO, "Clear at 0 of 3",
                DisplayVerdict.UNRATED, 0, threeSampleSlots());
        DailyBriefingResponse in = responseWith(TargetType.SUNSET, unratedIn, List.of());

        Map<DispositionCategory, Clock> awaiting = new java.util.LinkedHashMap<>();
        awaiting.put(DispositionCategory.SKIPPED_TRAVEL_DAY, T_PLUS_0);
        awaiting.put(DispositionCategory.SUBMISSION_FAILED, T_PLUS_0);
        awaiting.put(DispositionCategory.SKIPPED_STABILITY, T_PLUS_4);
        for (Map.Entry<DispositionCategory, Clock> scenario : awaiting.entrySet()) {
            BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(
                    in, 0.5, latestForAll(scenario.getKey(), false), scenario.getValue()));

            assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        }
        assertThat(firstRegion(blankedFrom(in, DispositionCategory.SKIPPED_TRIAGED))
                .displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        // No lookup at all: the record cannot say, so AWAITING, still not UNRATED.
        assertThat(firstRegion(BriefingHonestyFilter.apply(in, 0.5)).displayVerdict())
                .isEqualTo(DisplayVerdict.AWAITING);
    }

    private static DailyBriefingResponse blankedFrom(DailyBriefingResponse in,
            DispositionCategory category) {
        return BriefingHonestyFilter.apply(in, 0.5, latestForAll(category, false), T_PLUS_0);
    }

    @Test
    @DisplayName("The filter's reason rides the blanked region (the Ask window spread tells a real stand-down "
            + "from the UNKNOWN the filter words AWAITING); a region it did not blank carries none")
    void blankedRegionCarriesItsCoverageReason() {
        assertThat(firstRegion(blanked(DispositionCategory.SKIPPED_TRIAGED, false, T_PLUS_0)).coverageReason())
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN);
        assertThat(firstRegion(blanked(DispositionCategory.SKIPPED_TRAVEL_DAY, false, T_PLUS_0)).coverageReason())
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.AWAY);
        assertThat(firstRegion(blanked(DispositionCategory.SKIPPED_STABILITY, false, T_PLUS_4)).coverageReason())
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.NOT_ATTEMPTED);
        assertThat(firstRegion(blanked(DispositionCategory.SUBMISSION_FAILED, false, T_PLUS_0)).coverageReason())
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.FAILED);
        DailyBriefingResponse in = responseWith(TargetType.SUNSET, zeroCoverageRegion("Northumberland"), List.of());
        assertThat(firstRegion(BriefingHonestyFilter.apply(in, 0.5)).coverageReason())
                .as("no lookup is UNKNOWN, worded AWAITING and never mistaken for a stand-down")
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.UNKNOWN);
        assertThat(firstRegion(BriefingHonestyFilter.apply(in, 0.5, Map.of(), T_PLUS_0)).coverageReason())
                .isEqualTo(com.gregochr.goldenhour.model.CoverageReason.UNKNOWN);
    }

    @Test
    @DisplayName("A region the filter did not blank (scored, or only thinly covered) carries no reason")
    void aRegionNotBlankedCarriesNoCoverageReason() {
        DailyBriefingResponse scored = responseWith(TargetType.SUNSET,
                regionWith("Northumberland", Verdict.GO, "Clear at 10 of 10", DisplayVerdict.WORTH_IT, 10,
                        goSlots(10)), List.of());
        DailyBriefingResponse light = responseWith(TargetType.SUNSET,
                regionWith("Northumberland", Verdict.GO, "Clear at 1 of 10", DisplayVerdict.WORTH_IT, 1,
                        goSlots(10)), List.of());

        assertThat(firstRegion(BriefingHonestyFilter.apply(scored, 0.5)).coverageReason()).isNull();
        BriefingRegion thin = firstRegion(BriefingHonestyFilter.apply(light, 0.5));
        assertThat(thin.coverageReason()).isNull();
    }

    @Test
    @DisplayName("fullRewrite carries exactly the fields it always did and nulls the rest, for "
            + "every reason")
    void fullRewriteCarriesAndNullsTheSameFields() {
        BriefingRegion rich = zeroCoverageRegion("Northumberland")
                .withConfidence(Confidence.HIGH).withMeanRating(4.0).withBestRating(5)
                .withMeanRatingDelta(0.5).withSampleSufficient(true).withForcedSample(true);
        DailyBriefingResponse in = responseWith(TargetType.SUNSET, rich, List.of());

        Map<DispositionCategory, Clock> scenarios = new java.util.LinkedHashMap<>();
        scenarios.put(DispositionCategory.SKIPPED_TRAVEL_DAY, T_PLUS_0);
        scenarios.put(DispositionCategory.SKIPPED_TRIAGED, T_PLUS_0);
        scenarios.put(DispositionCategory.SUBMISSION_FAILED, T_PLUS_0);
        scenarios.put(DispositionCategory.SKIPPED_STABILITY, T_PLUS_4);
        for (Map.Entry<DispositionCategory, Clock> scenario : scenarios.entrySet()) {
            BriefingRegion r = firstRegion(BriefingHonestyFilter.apply(
                    in, 0.5, latestForAll(scenario.getKey(), false), scenario.getValue()));

            assertThat(r.regionName()).isEqualTo("Northumberland");
            assertThat(r.verdict()).isEqualTo(Verdict.GO);
            assertThat(r.tideHighlights()).containsExactly("Spring tide at 2 coastal spots");
            assertThat(r.regionTemperatureCelsius()).isEqualTo(14.0);
            assertThat(r.regionApparentTemperatureCelsius()).isEqualTo(13.0);
            assertThat(r.regionWindSpeedMs()).isEqualTo(4.5);
            assertThat(r.regionWeatherCode()).isEqualTo(3);
            assertThat(r.glossHeadline()).isNull();
            assertThat(r.scoredLocationCount()).isZero();
            assertThat(r.confidence()).isNull();
            assertThat(r.meanRating()).isNull();
            assertThat(r.bestRating()).isNull();
            assertThat(r.meanRatingDelta()).isNull();
            assertThat(r.sampleSufficient()).isNull();
            assertThat(r.forcedSample()).isNull();
            assertThat(r.verdictEligible()).isFalse();
        }
    }

    @Test
    @DisplayName("The fold sees scoreable slots only: a canopy slot with no record cannot turn a "
            + "stability-skipped sky region UNKNOWN")
    void foldIgnoresCanopySlots() {
        List<BriefingSlot> mixed = new java.util.ArrayList<>(threeSampleSlots());
        mixed.add(canopySlot("Delamere Wood", Verdict.GO));
        BriefingRegion region = regionWith("Northumberland", Verdict.GO, "Clear at 0 of 4",
                DisplayVerdict.WORTH_IT, 0, mixed);
        DailyBriefingResponse in = responseWith(TargetType.SUNSET, region, List.of());

        BriefingRegion out = firstRegion(BriefingHonestyFilter.apply(in, 0.5,
                latestForAll(DispositionCategory.SKIPPED_STABILITY, false), T_PLUS_0));

        assertThat(out.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(out.verdictLabel()).isEqualTo("Not scored at this range");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A region with real slots but nothing scored — what the zero-coverage rewrite fires on. */
    private static BriefingRegion zeroCoverageRegion(String name) {
        return regionWith(name, Verdict.GO, "Clear at 0 of 3",
                DisplayVerdict.WORTH_IT, 0, threeSampleSlots());
    }

    private static BestBet bet(int rank, String region, String event) {
        return new BestBet(rank, "Headline", "Detail", event, region,
                Confidence.HIGH, 45, "Today", "sunset", "21:07");
    }

    private static DailyBriefingResponse responseWith(TargetType target, BriefingRegion region,
            List<BestBet> bets) {
        return new DailyBriefingResponse(
                LocalDateTime.now(), "headline",
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(target, List.of(region), List.of())))),
                bets, null, null, false, null, List.of(), List.of());
    }

    private static BriefingRegion regionWith(String name, Verdict verdict, String summary,
            DisplayVerdict dv, int scoredLocationCount, List<BriefingSlot> slots) {
        return new BriefingRegion(
                name, verdict, summary,
                List.of("Spring tide at 2 coastal spots"),
                slots,
                14.0, 13.0, 4.5, 3,
                "Clear sky for the win", "The whole valley is set up for a clean colour ramp.",
                dv, scoredLocationCount);
    }

    private static DailyBriefingResponse wrapAsResponse(TargetType target, BriefingRegion region) {
        return new DailyBriefingResponse(
                LocalDateTime.now(), "headline",
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(target, List.of(region), List.of())))),
                List.of(), null, null, false, null, List.of(), List.of());
    }

    private static BriefingRegion firstRegion(DailyBriefingResponse r) {
        return r.days().get(0).eventSummaries().get(0).regions().get(0);
    }

    private static List<BriefingSlot> goSlots(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> slot("Loc" + i, Verdict.GO))
                .toList();
    }

    private static List<BriefingSlot> threeSampleSlots() {
        return List.of(
                slot("Buttermere", Verdict.GO),
                slot("Wast Water", Verdict.GO),
                slot("Coniston Water", Verdict.GO));
    }

    private static BriefingSlot slot(String name, Verdict verdict) {
        return new BriefingSlot(
                name,
                LocalDateTime.of(TODAY, java.time.LocalTime.of(18, 0)),
                verdict,
                null, BriefingSlot.TideInfo.NONE, List.of());
    }
}
