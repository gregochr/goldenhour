package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.RunCalendarResponse;
import com.gregochr.goldenhour.model.runs.RunCalendarResponse.CalendarDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service tests for the Runs calendar month over real repositories (H2, no Docker). "Now" is Monday 2 November 2026.
 */
@DataJpaTest
@Import({RunsCalendarService.class, RunsLoader.class, RunsBounds.class, CycleFactsLoader.class,
    RunFxLoader.class, RunsCalendarServiceTest.Config.class})
class RunsCalendarServiceTest {

    /** Supplies the fixed clock. */
    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T09:26:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private RunsCalendarService service;

    @Autowired
    private TestEntityManager em;

    private JobRunEntity job(String startUtc, Long cost, int succeeded, int failed) {
        LocalDateTime start = LocalDateTime.parse(startUtc);
        return em.persist(JobRunEntity.builder().runType(RunType.TIDE).startedAt(start)
                .completedAt(start.plusSeconds(20)).durationMs(20_000L).succeeded(succeeded).failed(failed)
                .locationsProcessed(succeeded + failed).triggeredManually(false).totalCostMicroDollars(cost)
                .exchangeRateGbpPerUsd(0.8).build());
    }

    private static CalendarDay day(RunCalendarResponse response, String date) {
        return response.days().stream().filter(d -> d.date().equals(LocalDate.parse(date))).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("each UK day carries its runs, spend and attention count; the busiest spend sets the scale")
    void daysCarryRunsSpendAndAttention() {
        job("2026-10-27T04:00:00", 1_000_000L, 5, 0);
        job("2026-10-27T05:00:00", 1_000_000L, 0, 5);
        job("2026-10-28T04:00:00", 5_000_000L, 8, 2);
        PipelineRunEntity cycle = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse("2026-10-29T01:00:00Z"));
        cycle.setStatus(PipelineRunStatus.COMPLETED);
        cycle.setCompletedAt(Instant.parse("2026-10-29T01:50:00Z"));
        em.persist(cycle);
        em.flush();
        em.clear();

        RunCalendarResponse october = service.month(YearMonth.of(2026, 10));

        assertThat(october.month()).isEqualTo("2026-10");
        assertThat(october.earliestDay()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(october.days()).extracting(CalendarDay::date).first().isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(october.days()).hasSize(5);
        CalendarDay d27 = day(october, "2026-10-27");
        assertThat(d27.runs()).isEqualTo(2);
        assertThat(d27.spendGbp()).isEqualByComparingTo("1.60");
        assertThat(d27.attention()).isEqualTo(1);
        assertThat(d27.spendComplete()).isTrue();
        CalendarDay d28 = day(october, "2026-10-28");
        assertThat(d28.spendGbp()).isEqualByComparingTo("4.00");
        assertThat(d28.attention()).isEqualTo(1);
        assertThat(day(october, "2026-10-29").runs()).isEqualTo(1);
        assertThat(day(october, "2026-10-30").runs()).isZero();
        assertThat(october.maxSpendGbp()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("a run at 23:30 UTC on 24 October (00:30 BST on the 25th) counts on the 25th")
    void dstBoundary() {
        job("2026-10-24T22:30:00", 0L, 5, 0);
        job("2026-10-24T23:30:00", 0L, 5, 0);
        job("2026-10-25T23:30:00", 0L, 5, 0);
        job("2026-10-26T00:10:00", 0L, 5, 0);
        em.flush();
        em.clear();

        RunCalendarResponse october = service.month(YearMonth.of(2026, 10));

        assertThat(day(october, "2026-10-24").runs()).isEqualTo(1);
        assertThat(day(october, "2026-10-25").runs()).isEqualTo(2);
        assertThat(day(october, "2026-10-26").runs()).isEqualTo(1);
    }

    @Test
    @DisplayName("a day with a cost still accruing is marked incomplete; the current month stops at today")
    void incompleteCostAndCurrentMonth() {
        LocalDateTime start = LocalDateTime.parse("2026-11-01T04:00:00");
        em.persist(JobRunEntity.builder().runType(RunType.TIDE).startedAt(start).succeeded(0).failed(0)
                .locationsProcessed(0).triggeredManually(false).build());
        em.flush();
        em.clear();

        RunCalendarResponse november = service.month(YearMonth.of(2026, 11));

        assertThat(november.days()).extracting(CalendarDay::date).containsExactly(LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-02"));
        assertThat(day(november, "2026-11-01").spendComplete()).isFalse();
        assertThat(day(november, "2026-11-01").runs()).isEqualTo(1);
        assertThat(day(november, "2026-11-02").spendComplete()).isTrue();
    }

    @Test
    @DisplayName("a month after this one, or one that ended before the first run, is refused")
    void monthValidation() {
        job("2026-10-10T04:00:00", 0L, 5, 0);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.month(YearMonth.of(2026, 12))).isInstanceOf(RunsRequestException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.month(YearMonth.of(2026, 9))).isInstanceOf(RunsRequestException.class)
                .hasMessageContaining("earliest");
        assertThat(service.month(YearMonth.of(2026, 10)).days().get(0).date()).isEqualTo(LocalDate.parse("2026-10-10"));
    }

    @Test
    @DisplayName("with no runs at all the calendar still serves today with zero spend")
    void emptyHistory() {
        RunCalendarResponse november = service.month(YearMonth.of(2026, 11));

        assertThat(november.days()).hasSize(1);
        assertThat(november.maxSpendGbp()).isEqualByComparingTo("0");
        assertThat(november.earliestDay()).isEqualTo(LocalDate.parse("2026-11-02"));
    }
}
