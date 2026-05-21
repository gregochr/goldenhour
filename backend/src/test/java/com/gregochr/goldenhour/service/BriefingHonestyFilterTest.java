package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
        assertThat(rewritten.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(rewritten.verdictLabel()).isEqualTo("Too unsettled to forecast");
        assertThat(rewritten.summary()).isEqualTo(BriefingHonestyFilter.REPLACEMENT_SUMMARY);
        assertThat(rewritten.glossHeadline()).isNull();
        assertThat(rewritten.glossDetail()).isEqualTo(BriefingHonestyFilter.REPLACEMENT_GLOSS_DETAIL);
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
                List.of(), null, null, false, false, 0, null, List.of(), List.of());

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);
        List<BriefingRegion> regions = out.days().get(0).eventSummaries().get(0).regions();

        assertThat(regions.get(0).displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(regions.get(0).verdictLabel()).isEqualTo("Too unsettled to forecast");
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
                List.of(), null, null, false, false, 0, null, List.of(), List.of());

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
                true, true, 7, "Opus", List.of(), List.of("BLUEBELL"));

        DailyBriefingResponse out = BriefingHonestyFilter.apply(response);

        assertThat(out.generatedAt()).isEqualTo(when);
        assertThat(out.headline()).isEqualTo("best day this week");
        assertThat(out.stale()).isTrue();
        assertThat(out.partialFailure()).isTrue();
        assertThat(out.failedLocationCount()).isEqualTo(7);
        assertThat(out.bestBetModel()).isEqualTo("Opus");
        assertThat(out.seasonalFeatures()).containsExactly("BLUEBELL");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    private static BriefingRegion firstRegion(DailyBriefingResponse r) {
        return r.days().get(0).eventSummaries().get(0).regions().get(0);
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
                null, BriefingSlot.TideInfo.NONE, List.of(), null);
    }
}
