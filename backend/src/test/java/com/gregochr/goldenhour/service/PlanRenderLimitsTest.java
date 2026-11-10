package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rendered-horizon rule: whole days, today to T+3, with {@link PlanHorizon} the single owner of
 * the day count.
 */
class PlanRenderLimitsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Test
    @DisplayName("today through T+3 are inside the horizon, inclusive at both ends")
    void withinHorizon_inclusiveBothEnds() {
        assertThat(PlanRenderLimits.withinHorizon(TODAY, TODAY)).isTrue();
        assertThat(PlanRenderLimits.withinHorizon(TODAY.plusDays(1), TODAY)).isTrue();
        assertThat(PlanRenderLimits.withinHorizon(TODAY.plusDays(3), TODAY)).isTrue();
    }

    @Test
    @DisplayName("T+4 is outside the horizon")
    void withinHorizon_t4IsOutside() {
        assertThat(PlanRenderLimits.withinHorizon(TODAY.plusDays(4), TODAY)).isFalse();
    }

    @Test
    @DisplayName("yesterday is outside the horizon: an aged briefing's first day is never rendered")
    void withinHorizon_yesterdayIsOutside() {
        assertThat(PlanRenderLimits.withinHorizon(TODAY.minusDays(1), TODAY)).isFalse();
    }

    @Test
    @DisplayName("the horizon's last date is PlanHorizon's, so there is one owner of the day count")
    void withinHorizon_lastDateIsPlanHorizons() {
        LocalDate last = PlanHorizon.lastPlanDate(TODAY);

        assertThat(PlanRenderLimits.withinHorizon(last, TODAY)).isTrue();
        assertThat(PlanRenderLimits.withinHorizon(last.plusDays(1), TODAY)).isFalse();
    }

    @Test
    @DisplayName("the event ceiling is two events on each Plan day: eight")
    void maxVisibleEvents_isTwoPerPlanDay() {
        assertThat(PlanRenderLimits.MAX_VISIBLE_EVENTS).isEqualTo(PlanHorizon.PLAN_OWNED_DAYS * 2);
        assertThat(PlanRenderLimits.MAX_VISIBLE_EVENTS).isEqualTo(8);
    }
}
