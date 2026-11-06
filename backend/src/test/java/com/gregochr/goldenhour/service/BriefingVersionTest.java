package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The briefing version (reach-scoped-card-plan R2 step 5): stable for the same ratings and dispositions, different
 * as soon as any input the reach computation reads changes, and blind to what it does not read.
 */
class BriefingVersionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final BriefingSlot.WeatherConditions WEATHER = new BriefingSlot.WeatherConditions(
            10, BigDecimal.ZERO, 20000, 60, 12.0, 10.0, 1, BigDecimal.ONE, 0, 0);

    private static BriefingSlot slot(Integer rating, boolean forced, boolean triaged) {
        BriefingSlot bare = new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 1, 6, 30), Verdict.GO,
                WEATHER, BriefingSlot.TideInfo.NONE, List.of());
        BriefingSlot rated = rating == null ? bare : bare.withClaudeScores(rating, rating, 70, 60, "why", "Fiery");
        return rated.withReachStamps(forced, triaged);
    }

    private static List<BriefingDay> days(BriefingSlot slot, String gloss, String regionName) {
        BriefingRegion region = new BriefingRegion(regionName, Verdict.GO, "summary", List.of(), List.of(slot),
                12.0, 10.0, 1.0, 1, gloss, null, DisplayVerdict.WORTH_IT, 1);
        return List.of(new BriefingDay(TODAY, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of()))));
    }

    private static String version(BriefingSlot slot) {
        return BriefingVersion.of(days(slot, "Fiery", "Northumberland"), null, TODAY);
    }

    @Test
    @DisplayName("the same ratings give the same short hex version, every time")
    void stable() {
        String first = version(slot(4, false, false));

        assertThat(first).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(version(slot(4, false, false))).isEqualTo(first);
    }

    @Test
    @DisplayName("a rating, a forced mark, a triaged mark or the UK date each change it")
    void everyInputMovesIt() {
        String base = version(slot(4, false, false));

        assertThat(version(slot(3, false, false))).isNotEqualTo(base);
        assertThat(version(slot(null, false, false))).isNotEqualTo(base);
        assertThat(version(slot(4, true, false))).isNotEqualTo(base);
        assertThat(version(slot(4, false, true))).isNotEqualTo(base);
        assertThat(BriefingVersion.of(days(slot(4, false, false), "Fiery", "Northumberland"), null, TODAY.plusDays(1)))
                .isNotEqualTo(base);
        assertThat(BriefingVersion.of(days(slot(4, false, false), "Changed", "Northumberland"), null, TODAY))
                .isNotEqualTo(base);
        assertThat(BriefingVersion.of(days(slot(4, false, false), "Fiery", "Elsewhere"), null, TODAY))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("a rating of nothing is not the string 'null', and a neighbour mark moves it")
    void nullIsNotATextAndTheNeighbourMarkCounts() {
        BriefingSlot marked = slot(4, false, false).withNeighbourOutlier(Boolean.TRUE);

        assertThat(version(marked)).isNotEqualTo(version(slot(4, false, false)));
        assertThat(BriefingVersion.of(days(slot(4, false, false), null, "Northumberland"), null, TODAY))
                .isNotEqualTo(BriefingVersion.of(days(slot(4, false, false), "null", "Northumberland"), null, TODAY));
    }

    @Test
    @DisplayName("prose and weather figures the reach computation never reads do not move it")
    void unreadFieldsDoNotMoveIt() {
        BriefingSlot withProse = slot(4, false, false).withFlags(List.of("Clear"));

        assertThat(version(withProse)).isEqualTo(version(slot(4, false, false)));
    }

    @Test
    @DisplayName("the rendered events are an input: the same ratings under another render horizon differ")
    void renderedEventsMoveIt() {
        List<BriefingDay> days = days(slot(4, false, false), "Fiery", "Northumberland");
        PlanRenderedEvent sunrise = new PlanRenderedEvent(TODAY, TargetType.SUNRISE);
        PlanRenderedEvent sunset = new PlanRenderedEvent(TODAY, TargetType.SUNSET);

        String one = BriefingVersion.of(days, List.of(sunrise), TODAY);

        assertThat(BriefingVersion.of(days, List.of(sunrise), TODAY)).isEqualTo(one);
        assertThat(BriefingVersion.of(days, List.of(sunset), TODAY)).isNotEqualTo(one);
        assertThat(BriefingVersion.of(days, List.of(sunrise, sunset), TODAY)).isNotEqualTo(one);
        assertThat(BriefingVersion.of(days, null, TODAY)).isNotEqualTo(one);
    }

    @Test
    @DisplayName("an algorithm the platform lacks is an IllegalStateException naming it, not a checked exception")
    void unknownAlgorithmFailsLoudly() {
        assertThatThrownBy(() -> BriefingVersion.digest("NOT-A-DIGEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT-A-DIGEST");
        assertThat(BriefingVersion.digest("SHA-256").getAlgorithm()).isEqualTo("SHA-256");
    }

    @Test
    @DisplayName("the assembler stamps it on the response, and every wither carries it through")
    void withBriefingVersionSurvivesTheWithers() {
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "h",
                days(slot(4, false, false), "Fiery", "Northumberland"), List.of(), null, null, false, null,
                List.of(), List.of(), null, null, null, null, null).withBriefingVersion("v-1");

        assertThat(base.briefingVersion()).isEqualTo("v-1");
        assertThat(base.withDays(base.days()).briefingVersion()).isEqualTo("v-1");
        assertThat(base.withBigSeas(List.of()).briefingVersion()).isEqualTo("v-1");
        assertThat(base.withBriefingVersion(null).briefingVersion()).isNull();
    }
}
