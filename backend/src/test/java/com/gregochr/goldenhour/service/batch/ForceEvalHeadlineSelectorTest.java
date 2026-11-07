package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForceEvalHeadlineSelector}: the horizon window, the merit ordering
 * (GO count, tide alignment, nearest day) and the cap.
 */
class ForceEvalHeadlineSelectorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 12, 21, 0);

    private static BriefingSlot slot(String name, Verdict verdict, BriefingSlot.TideInfo tide) {
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE, 0, 0);
        return new BriefingSlot(1L, name, EVENT_TIME, verdict, weather, tide, List.of(),
                null, null, null, null, null, null, null, false, null, null);
    }

    private static BriefingSlot go(String name) {
        return slot(name, Verdict.GO, BriefingSlot.TideInfo.NONE);
    }

    private static BriefingSlot goTide(String name, Boolean aligned) {
        return slot(name, Verdict.GO, new BriefingSlot.TideInfo("HIGH", aligned, null,
                new BigDecimal("5.0"), false, false, null, null, null, null, null, null, null, null,
                null, null, null, null, null));
    }

    private static BriefingRegion region(BriefingSlot... slots) {
        return new BriefingRegion("R", Verdict.GO, "Summary", List.of(), List.of(slots),
                null, null, null, null, null, null);
    }

    private static BriefingDay day(int daysAhead, TargetType type, BriefingRegion... regions) {
        return new BriefingDay(TODAY.plusDays(daysAhead), List.of(
                new BriefingEventSummary(type, List.of(regions), List.of())));
    }

    private static DailyBriefingResponse briefing(BriefingDay... days) {
        return new DailyBriefingResponse(null, null, List.of(days), null, null, null,
                false, null, List.of(), List.of());
    }

    private static String key(String name, int daysAhead, TargetType type) {
        return ForceEvalHeadlineSelector.forceEvalKey(name, TODAY.plusDays(daysAhead), type);
    }

    @Test
    @DisplayName("forceEvalKey joins location, ISO date and target type name with pipes")
    void forceEvalKeyFormat() {
        assertThat(ForceEvalHeadlineSelector.forceEvalKey(
                "Bamburgh", LocalDate.of(2026, 6, 12), TargetType.SUNRISE))
                .isEqualTo("Bamburgh|2026-06-12|SUNRISE");
    }

    @Test
    @DisplayName("a zero cap selects nothing")
    void zeroCap_empty() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(0, CLOCK);
        assertThat(sel.selectForceEvalKeys(briefing(day(2, TargetType.SUNSET, region(go("A"))))))
                .isEmpty();
    }

    @Test
    @DisplayName("a negative cap selects nothing")
    void negativeCap_empty() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(-1, CLOCK);
        assertThat(sel.selectForceEvalKeys(briefing(day(2, TargetType.SUNSET, region(go("A"))))))
                .isEmpty();
    }

    @Test
    @DisplayName("only days T+2 and T+3 are in the window; T+1 and T+4 are excluded")
    void windowBoundaries() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(
                day(1, TargetType.SUNSET, region(go("T1"))),
                day(2, TargetType.SUNSET, region(go("T2"))),
                day(3, TargetType.SUNSET, region(go("T3"))),
                day(4, TargetType.SUNSET, region(go("T4"))));

        Set<String> keys = sel.selectForceEvalKeys(b);

        assertThat(keys).containsExactly(
                key("T2", 2, TargetType.SUNSET), key("T3", 3, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a region with no GO slots contributes nothing; non-GO slots are not selected")
    void nonGoSlotsIgnored() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(day(2, TargetType.SUNSET,
                region(slot("Marginal", Verdict.MARGINAL, BriefingSlot.TideInfo.NONE)),
                region(go("Winner"), slot("Standdown", Verdict.STANDDOWN,
                        BriefingSlot.TideInfo.NONE))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(key("Winner", 2, TargetType.SUNSET));
    }

    @Test
    @DisplayName("the cell with more GO locations is selected first")
    void goCountDescending() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(
                day(2, TargetType.SUNSET, region(go("Solo"))),
                day(3, TargetType.SUNRISE, region(go("PairA"), go("PairB"))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(
                key("PairA", 3, TargetType.SUNRISE),
                key("PairB", 3, TargetType.SUNRISE),
                key("Solo", 2, TargetType.SUNSET));
    }

    @Test
    @DisplayName("on equal GO counts the tide-aligned cell wins; null or false alignment counts as none")
    void tideAlignedTiebreak() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(
                day(2, TargetType.SUNRISE, region(goTide("NullAligned", null))),
                day(2, TargetType.SUNSET, region(goTide("Aligned", true))),
                day(3, TargetType.SUNSET, region(goTide("NotAligned", false))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(
                key("Aligned", 2, TargetType.SUNSET),
                key("NullAligned", 2, TargetType.SUNRISE),
                key("NotAligned", 3, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a GO slot with no tide info is counted with zero tide alignment")
    void nullTide_countsAsNotAligned() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(
                day(2, TargetType.SUNRISE, region(slot("NoTide", Verdict.GO, null))),
                day(2, TargetType.SUNSET, region(goTide("Aligned", true))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(
                key("Aligned", 2, TargetType.SUNSET), key("NoTide", 2, TargetType.SUNRISE));
    }

    @Test
    @DisplayName("on equal GO count and tide, the nearer day wins even when listed later")
    void nearerDayTiebreak() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(10, CLOCK);
        DailyBriefingResponse b = briefing(
                day(3, TargetType.SUNSET, region(go("Far"))),
                day(2, TargetType.SUNSET, region(go("Near"))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(
                key("Near", 2, TargetType.SUNSET), key("Far", 3, TargetType.SUNSET));
    }

    @Test
    @DisplayName("the cap truncates inside a cell, keeping the first GO locations in order")
    void capTruncatesWithinCell() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(2, CLOCK);
        DailyBriefingResponse b = briefing(day(2, TargetType.SUNSET,
                region(go("A"), go("B"), go("C"))));

        assertThat(sel.selectForceEvalKeys(b)).containsExactly(
                key("A", 2, TargetType.SUNSET), key("B", 2, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a cap exactly equal to the number of candidates returns them all")
    void capEqualToCandidateCount() {
        ForceEvalHeadlineSelector sel = new ForceEvalHeadlineSelector(2, CLOCK);
        DailyBriefingResponse b = briefing(day(2, TargetType.SUNSET, region(go("A"), go("B"))));

        assertThat(sel.selectForceEvalKeys(b)).hasSize(2);
    }
}
