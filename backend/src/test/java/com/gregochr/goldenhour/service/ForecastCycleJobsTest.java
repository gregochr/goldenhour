package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the forecast-cycle registry: three cycles for the watchdog, two of them solar-capable, and the
 * evening refresh carrying no solar rule (evening-sunrise-refresh-plan §3.4).
 */
class ForecastCycleJobsTest {

    @Test
    @DisplayName("the watchdog's list is nightly, intraday, evening; the solar-capable list is the first two")
    void listsAreThreeAndTwo() {
        assertThat(ForecastCycleJobs.allCycleJobs()).extracting(ForecastCycleJobs.Job::key)
                .containsExactly(ForecastCycleJobs.NIGHTLY_JOB, ForecastCycleJobs.INTRADAY_JOB,
                        ForecastCycleJobs.EVENING_JOB);
        assertThat(ForecastCycleJobs.allCycleJobs()).extracting(ForecastCycleJobs.Job::cycleType)
                .containsExactly(CycleType.NIGHTLY, CycleType.INTRADAY, CycleType.EVENING);
        assertThat(ForecastCycleJobs.solarCapableJobs()).extracting(ForecastCycleJobs.Job::key)
                .containsExactly(ForecastCycleJobs.NIGHTLY_JOB, ForecastCycleJobs.INTRADAY_JOB);
    }

    @Test
    @DisplayName("the evening has no solar rule, is not allowed to become solar, and has no cycleOf")
    void eveningIsNotSolar() {
        ForecastCycleJobs.Job evening = ForecastCycleJobs.allCycleJobs().get(2);

        assertThat(evening.rule()).isNull();
        assertThat(ForecastCycleJobs.allowed(ForecastCycleJobs.EVENING_JOB)).isFalse();
        assertThat(ForecastCycleJobs.proposedRule(ForecastCycleJobs.EVENING_JOB)).isNull();
        assertThat(ForecastCycleJobs.cycleOf(ForecastCycleJobs.EVENING_JOB)).isNull();
    }

    @Test
    @DisplayName("the two main cycles keep their rules and cycle types")
    void mainCyclesUnchanged() {
        assertThat(ForecastCycleJobs.proposedRule(ForecastCycleJobs.NIGHTLY_JOB))
                .isEqualTo(ForecastCycleJobs.NIGHTLY_RULE);
        assertThat(ForecastCycleJobs.proposedRule(ForecastCycleJobs.INTRADAY_JOB))
                .isEqualTo(ForecastCycleJobs.INTRADAY_RULE);
        assertThat(ForecastCycleJobs.cycleOf(ForecastCycleJobs.NIGHTLY_JOB)).isEqualTo(CycleType.NIGHTLY);
        assertThat(ForecastCycleJobs.cycleOf(ForecastCycleJobs.INTRADAY_JOB)).isEqualTo(CycleType.INTRADAY);
        assertThat(ForecastCycleJobs.allowed(ForecastCycleJobs.NIGHTLY_JOB)).isTrue();
        assertThat(ForecastCycleJobs.allowed(ForecastCycleJobs.INTRADAY_JOB)).isTrue();
    }
}
