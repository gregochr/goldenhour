package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunDayResponse;
import com.gregochr.goldenhour.model.runs.RunDayRow;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SolarOffsetTrigger;
import com.gregochr.goldenhour.service.SolarReferencePoint;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.SolarTriggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Service tests for the Runs Day view over real repositories (H2, no Docker): UK-day bounds across the clocks going
 * back, cycle folding, chained children, unknown costs, statistics, attention counts and what is still to run.
 * "Now" is Monday 2 November 2026, 09:26 UTC (GMT).
 */
@DataJpaTest
@Import({RunsDayService.class, RunsLoader.class, RunStatsLoader.class, ScheduledFiresLister.class,
    RunsBounds.class, CycleFactsLoader.class, RunFxLoader.class, RunsDayServiceTest.Config.class})
class RunsDayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T09:26:00Z");
    /** The end of the UK civil day of {@link #NOW} (GMT in November). */
    private static final Instant DAY_END = Instant.parse("2026-11-03T00:00:00Z");

    /** Supplies the fixed clock and the real solar calculator. */
    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SolarService solarService() {
            return new SolarService();
        }
    }

    @Autowired
    private RunsDayService service;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private DynamicSchedulerService scheduler;

    @MockitoBean
    private SolarTriggerFactory solarTriggers;

    @MockitoBean
    private SolarReferencePoint referencePoint;

    @BeforeEach
    void setUp() {
        when(referencePoint.lat()).thenReturn(54.776);
        when(referencePoint.lon()).thenReturn(-1.575);
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private JobRunEntity job(RunType type, String startUtc, Integer durationSeconds, Long cost, int succeeded,
            int failed) {
        LocalDateTime start = LocalDateTime.parse(startUtc);
        JobRunEntity run = JobRunEntity.builder().runType(type).startedAt(start)
                .completedAt(durationSeconds == null ? null : start.plusSeconds(durationSeconds))
                .durationMs(durationSeconds == null ? null : durationSeconds * 1000L)
                .succeeded(succeeded).failed(failed).locationsProcessed(succeeded + failed)
                .triggeredManually(false).totalCostMicroDollars(cost).exchangeRateGbpPerUsd(0.8).build();
        return em.persist(run);
    }

    private JobRunEntity done(RunType type, String startUtc, int durationSeconds, Long cost) {
        return job(type, startUtc, durationSeconds, cost, 5, 0);
    }

    private JobRunEntity linked(RunType type, String startUtc, int durationSeconds, Long cost, Long cycleId) {
        JobRunEntity run = JobRunEntity.builder().runType(type).startedAt(LocalDateTime.parse(startUtc))
                .completedAt(LocalDateTime.parse(startUtc).plusSeconds(durationSeconds))
                .durationMs(durationSeconds * 1000L).succeeded(5).failed(0).locationsProcessed(5)
                .triggeredManually(false).totalCostMicroDollars(cost).exchangeRateGbpPerUsd(0.8)
                .pipelineRunId(cycleId).build();
        return em.persist(run);
    }

    private PipelineRunEntity cycle(CycleType type, String triggerUtc, Integer durationSeconds,
            PipelineRunStatus status) {
        Instant trigger = Instant.parse(triggerUtc);
        PipelineRunEntity run = new PipelineRunEntity(type, trigger);
        run.setStatus(status);
        if (durationSeconds != null) {
            run.setCompletedAt(trigger.plusSeconds(durationSeconds));
        }
        return em.persist(run);
    }

    private ForecastBatchEntity batch(String id, Long cycleId, Long jobRunId, int requests) {
        ForecastBatchEntity b = new ForecastBatchEntity(id, BatchType.FORECAST, requests,
                NOW.plusSeconds(86_400));
        b.setPipelineRunId(cycleId);
        b.setJobRunId(jobRunId);
        b.setStatus(BatchStatus.COMPLETED);
        return em.persist(b);
    }

    private SchedulerJobConfigEntity config(String key, ScheduleType type, String cron, SolarEvent event,
            Integer offset) {
        SchedulerJobConfigEntity c = SchedulerJobConfigEntity.builder().jobKey(key).displayName(key)
                .scheduleType(type).cronExpression(cron).solarEvent(event).solarOffsetMinutes(offset)
                .timeZone("Europe/London").status(SchedulerJobStatus.ACTIVE).updatedAt(NOW).build();
        return em.persist(c);
    }

    private void commit() {
        em.flush();
        em.clear();
    }

    private static RunDayRow row(RunDayResponse response, String id) {
        return response.runs().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    // ── Cycles and chained children ───────────────────────────────────────────

    @Test
    @DisplayName("a cycle folds in its batch run, and its briefing and ask-ready runs are rows beneath it")
    void cycleFoldsBatchAndLinksChildren() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, "2026-11-01T01:00:00Z", 3000,
                PipelineRunStatus.COMPLETED);
        JobRunEntity batchRun = job(RunType.SCHEDULED_BATCH, "2026-11-01T01:05:00", 2400, 2_000_000L, 5, 0);
        batch("msgbatch_a", nightly.getId(), batchRun.getId(), 216);
        batch("msgbatch_b", nightly.getId(), null, 10);
        JobRunEntity briefing = linked(RunType.BRIEFING, "2026-11-01T01:40:00", 120, 300_000L, nightly.getId());
        JobRunEntity ask = linked(RunType.ASK_READY, "2026-11-01T01:51:00", 300, 100_000L, nightly.getId());
        JobRunEntity tide = done(RunType.TIDE, "2026-11-01T04:00:00", 20, 0L);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(day.runs()).extracting(RunDayRow::id).containsExactly("cycle-" + nightly.getId(),
                "job-" + briefing.getId(), "job-" + ask.getId(), "job-" + tide.getId());
        assertThat(day.runs()).noneMatch(r -> r.id().equals("job-" + batchRun.getId()));
        RunDayRow c = row(day, "cycle-" + nightly.getId());
        assertThat(c.kind()).isEqualTo(RunDeepKind.CYCLE);
        assertThat(c.type()).isEqualTo(RunDeepType.OVERNIGHT);
        assertThat(c.name()).isEqualTo("Overnight cycle");
        assertThat(c.subline()).isEqualTo("2 batches · 226 requests");
        assertThat(c.durationSeconds()).isEqualTo(3000L);
        assertThat(c.costGbp()).isEqualByComparingTo("1.60");
        assertThat(c.costUsd()).isEqualByComparingTo("2.00");
        assertThat(c.costComplete()).isTrue();
        assertThat(c.facts().batches()).isEqualTo(2);
        assertThat(c.facts().requests()).isEqualTo(226);
        assertThat(c.facts().answersReadyAt()).isEqualTo(Instant.parse("2026-11-01T01:56:00Z"));
        RunDayRow b = row(day, "job-" + briefing.getId());
        assertThat(b.parentCycleId()).isEqualTo(nightly.getId());
        assertThat(b.subline()).isEqualTo("Runs at the end of Overnight cycle");
        assertThat(b.type()).isEqualTo(RunDeepType.BRIEFING);
        RunDayRow a = row(day, "job-" + ask.getId());
        assertThat(a.parentCycleId()).isEqualTo(nightly.getId());
        assertThat(a.subline()).isEqualTo("Runs after Overnight cycle");
        assertThat(day.summary().runs()).isEqualTo(4);
        assertThat(day.summary().cycles()).isEqualTo(1);
        assertThat(day.summary().jobs()).isEqualTo(3);
        assertThat(day.summary().slowestCycle().name()).isEqualTo("Overnight cycle");
        assertThat(day.summary().slowestCycle().durationSeconds()).isEqualTo(3000L);
        assertThat(day.summary().slowestCycle().medianSeconds()).isNull();
    }

    @Test
    @DisplayName("before V214 the briefing is matched on the phase window and the ask-ready run on its start time")
    void childrenFallBackForRowsWithoutTheColumn() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, "2026-11-01T01:00:00Z", 3000,
                PipelineRunStatus.COMPLETED);
        em.persist(new com.gregochr.goldenhour.entity.PipelineRunPhaseEntity(nightly.getId(),
                com.gregochr.goldenhour.entity.PipelinePhase.BRIEFING, 4, Instant.parse("2026-11-01T01:38:00Z")));
        JobRunEntity briefing = done(RunType.BRIEFING, "2026-11-01T01:40:00", 120, null);
        JobRunEntity ask = done(RunType.ASK_READY, "2026-11-01T01:51:30", 60, null);
        JobRunEntity lateAsk = done(RunType.ASK_READY, "2026-11-01T09:00:00", 60, null);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(row(day, "job-" + briefing.getId()).parentCycleId()).isEqualTo(nightly.getId());
        assertThat(row(day, "job-" + ask.getId()).parentCycleId()).isEqualTo(nightly.getId());
        assertThat(row(day, "job-" + lateAsk.getId()).parentCycleId()).isNull();
        assertThat(row(day, "cycle-" + nightly.getId()).facts().answersReadyAt())
                .isEqualTo(Instant.parse("2026-11-01T01:52:30Z"));
    }

    @Test
    @DisplayName("a batch run whose link was never written is folded into its cycle by the notes it carries")
    void notesFallbackFolds() {
        PipelineRunEntity nightly = cycle(CycleType.NIGHTLY, "2026-11-01T01:00:00Z", 3000,
                PipelineRunStatus.COMPLETED);
        JobRunEntity unlinked = job(RunType.SCHEDULED_BATCH, "2026-11-01T01:05:00", 2400, 1_000_000L, 5, 0);
        unlinked.setNotes("Anthropic batch: msgbatch_notes");
        batch("msgbatch_notes", nightly.getId(), null, 100);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(day.runs()).hasSize(1);
        assertThat(day.runs().get(0).costGbp()).isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("a briefing run that belongs to a cycle of the day before is still shown, under that cycle")
    void childOfYesterdaysCycle() {
        PipelineRunEntity evening = cycle(CycleType.EVENING, "2026-11-01T23:30:00Z", 3000,
                PipelineRunStatus.COMPLETED);
        JobRunEntity briefing = linked(RunType.BRIEFING, "2026-11-02T00:10:00", 120, 0L, evening.getId());
        commit();

        RunDayResponse day = service.day(d("2026-11-02"));

        assertThat(day.runs()).hasSize(1);
        assertThat(row(day, "job-" + briefing.getId()).subline())
                .isEqualTo("Runs at the end of Evening sunrise refresh");
    }

    // ── Days and boundaries ───────────────────────────────────────────────────

    @Test
    @DisplayName("25 October 2026 is a 25-hour day; 23:30 UTC on the 24th is 00:30 BST on the 25th")
    void dstDayBounds() {
        JobRunEntity beforeMidnight = done(RunType.TIDE, "2026-10-24T22:30:00", 10, 0L);
        JobRunEntity afterMidnight = done(RunType.TIDE, "2026-10-24T23:30:00", 10, 0L);
        JobRunEntity lateOnTheDay = done(RunType.TIDE, "2026-10-25T23:30:00", 10, 0L);
        JobRunEntity nextDay = done(RunType.TIDE, "2026-10-26T00:10:00", 10, 0L);
        commit();

        RunDayResponse day = service.day(d("2026-10-25"));

        assertThat(day.dayStartUtc()).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"));
        assertThat(day.dayEndUtc()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        assertThat(day.runs()).extracting(RunDayRow::id).containsExactly("job-" + afterMidnight.getId(),
                "job-" + lateOnTheDay.getId());
        assertThat(service.day(d("2026-10-24")).runs()).extracting(RunDayRow::id)
                .containsExactly("job-" + beforeMidnight.getId());
        assertThat(service.day(d("2026-10-26")).runs()).extracting(RunDayRow::id)
                .containsExactly("job-" + nextDay.getId());
        assertThat(day.isToday()).isFalse();
        assertThat(day.now()).isNull();
    }

    @Test
    @DisplayName("a day outside the retained range, in the future, is refused; no date means today")
    void dateValidation() {
        done(RunType.TIDE, "2026-10-10T04:00:00", 20, 0L);
        commit();

        assertThat(service.day(null).date()).isEqualTo(d("2026-11-02"));
        assertThat(service.day(null).isToday()).isTrue();
        assertThat(service.day(null).earliestDay()).isEqualTo(d("2026-10-10"));
        assertThatThrownBy(() -> service.day(d("2026-11-03"))).isInstanceOf(RunsRequestException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.day(d("2026-10-09"))).isInstanceOf(RunsRequestException.class)
                .hasMessageContaining("earliest");
        assertThat(service.day(d("2026-10-10")).runs()).hasSize(1);
        assertThat(service.day(d("2026-10-12")).runs()).isEmpty();
    }

    @Test
    @DisplayName("the earliest day is capped at 400 days back")
    void earliestDayCapped() {
        done(RunType.TIDE, "2024-01-10T04:00:00", 20, 0L);
        commit();

        assertThat(service.day(null).earliestDay()).isEqualTo(d("2026-11-02").minusDays(400));
    }

    @Test
    @DisplayName("sunrise and sunset are served in UK time with their instants")
    void sunTimes() {
        done(RunType.TIDE, "2026-10-30T04:00:00", 20, 0L);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(day.sunrise()).matches("0[67]:\\d\\d");
        assertThat(day.sunset()).matches("16:\\d\\d");
        assertThat(day.sunriseAt()).isBefore(day.sunsetAt());
    }

    // ── Cost, status and statistics ───────────────────────────────────────────

    @Test
    @DisplayName("an unknown cost is null and incomplete, never zero, and it marks the day")
    void unknownCostIsNotZero() {
        JobRunEntity open = job(RunType.TIDE, "2026-11-01T04:00:00", null, null, 0, 0);
        JobRunEntity known = done(RunType.WEATHER, "2026-11-01T05:00:00", 20, 1_000_000L);
        PipelineRunEntity running = cycle(CycleType.INTRADAY, "2026-11-01T13:00:00Z", null,
                PipelineRunStatus.RUNNING);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        RunDayRow o = row(day, "job-" + open.getId());
        assertThat(o.costMicroDollars()).isNull();
        assertThat(o.costGbp()).isNull();
        assertThat(o.costUsd()).isNull();
        assertThat(o.costComplete()).isFalse();
        assertThat(o.status()).isEqualTo(RunDeepStatus.FAILED);
        assertThat(o.note()).isEqualTo("Did not finish");
        assertThat(row(day, "job-" + known.getId()).costGbp()).isEqualByComparingTo("0.80");
        RunDayRow r = row(day, "cycle-" + running.getId());
        assertThat(r.status()).isEqualTo(RunDeepStatus.RUNNING);
        assertThat(r.costComplete()).isFalse();
        assertThat(day.summary().spendComplete()).isFalse();
        assertThat(day.summary().spendGbp()).isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("Wildlife comfort and Astro conditions are told apart by model; the daily Ask run has no duration")
    void namesAndAskDuration() {
        JobRunEntity wildlife = done(RunType.WEATHER, "2026-11-01T05:00:00", 20, 0L);
        wildlife.setEvaluationModel(EvaluationModel.WILDLIFE);
        JobRunEntity astro = done(RunType.WEATHER, "2026-11-01T06:00:00", 20, 0L);
        JobRunEntity ask = done(RunType.ASK, "2026-11-01T07:00:00", 0, 0L);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(row(day, "job-" + wildlife.getId()).name()).isEqualTo("Wildlife comfort");
        assertThat(row(day, "job-" + astro.getId()).name()).isEqualTo("Astro conditions");
        assertThat(row(day, "job-" + ask.getId()).durationSeconds()).isNull();
        assertThat(row(day, "job-" + ask.getId()).type()).isEqualTo(RunDeepType.OTHER);
    }

    @Test
    @DisplayName("slow is above the p90 of the previous 30 runs, and the speed rank is the share beaten")
    void slowAndFasterThan() {
        for (int i = 0; i < 12; i++) {
            done(RunType.TIDE, "2026-10-" + (10 + i) + "T04:00:00", 100 + 10 * i, 0L);
        }
        JobRunEntity slow = done(RunType.TIDE, "2026-11-01T04:00:00", 5000, 0L);
        JobRunEntity quick = done(RunType.TIDE, "2026-11-01T05:00:00", 50, 0L);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        RunDayRow s = row(day, "job-" + slow.getId());
        assertThat(s.slow()).isTrue();
        assertThat(s.facts().fasterThanPercent()).isZero();
        RunDayRow q = row(day, "job-" + quick.getId());
        assertThat(q.slow()).isFalse();
        // The slow run is itself in the window of the quick one: 13 earlier runs, 1 took longer than 50s... all 13.
        assertThat(q.facts().fasterThanPercent()).isEqualTo(100);
        assertThat(day.runs().get(0).facts().startedAt()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
    }

    @Test
    @DisplayName("a run is judged against its own kind: wildlife and astro runs do not share a population")
    void populationsAreSeparate() {
        for (int i = 0; i < 12; i++) {
            JobRunEntity wildlife = done(RunType.WEATHER, "2026-10-" + (10 + i) + "T04:00:00", 10, 0L);
            wildlife.setEvaluationModel(EvaluationModel.WILDLIFE);
        }
        JobRunEntity astro = done(RunType.WEATHER, "2026-11-01T04:00:00", 5000, 0L);
        commit();

        RunDayRow row = row(service.day(d("2026-11-01")), "job-" + astro.getId());

        assertThat(row.slow()).isFalse();
        assertThat(row.facts().fasterThanPercent()).isNull();
    }

    @Test
    @DisplayName("a manual run is shown but is not part of the population others are compared with")
    void manualRunsAreNotBaseline() {
        for (int i = 0; i < 12; i++) {
            JobRunEntity manual = done(RunType.TIDE, "2026-10-" + (10 + i) + "T04:00:00", 10, 0L);
            manual.setTriggeredManually(true);
        }
        JobRunEntity scheduled = done(RunType.TIDE, "2026-11-01T04:00:00", 5000, 0L);
        commit();

        RunDayRow row = row(service.day(d("2026-11-01")), "job-" + scheduled.getId());

        assertThat(row.slow()).isFalse();
    }

    @Test
    @DisplayName("attention counts and filter chips, with a degraded cycle served as partial")
    void attentionAndFilters() {
        JobRunEntity partial = job(RunType.TIDE, "2026-11-01T04:00:00", 20, 0L, 8, 2);
        JobRunEntity failed = job(RunType.TIDE, "2026-11-01T05:00:00", 20, 0L, 0, 5);
        PipelineRunEntity degraded = cycle(CycleType.INTRADAY, "2026-11-01T13:00:00Z", 3000,
                PipelineRunStatus.DEGRADED);
        degraded.setFailureReason("2 locations failed");
        JobRunEntity ask = done(RunType.ASK_READY, "2026-11-01T14:00:00", 60, 0L);
        SkyRatingEvalRunEntity eval = em.persist(SkyRatingEvalRunEntity.builder()
                .runTimestamp(LocalDateTime.parse("2026-11-01T08:00:00"))
                .startedAt(LocalDateTime.parse("2026-11-01T08:00:00"))
                .completedAt(LocalDateTime.parse("2026-11-01T08:10:00")).durationMs(600_000L)
                .model(EvaluationModel.HAIKU).runsPerFixture(3).costMicroDollars(5_000_000L)
                .triggerSource(SkyRatingEvalTrigger.SCHEDULED).status(SkyRatingEvalStatus.COMPLETED).build());
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(row(day, "job-" + partial.getId()).status()).isEqualTo(RunDeepStatus.PARTIAL);
        assertThat(row(day, "job-" + partial.getId()).note()).isEqualTo("2 requests errored");
        assertThat(row(day, "job-" + partial.getId()).noteTone()).isEqualTo(NoteTone.WARN);
        assertThat(row(day, "job-" + failed.getId()).status()).isEqualTo(RunDeepStatus.FAILED);
        RunDayRow d = row(day, "cycle-" + degraded.getId());
        assertThat(d.status()).isEqualTo(RunDeepStatus.PARTIAL);
        assertThat(d.note()).isEqualTo("2 locations failed");
        assertThat(row(day, "job-" + ask.getId()).type()).isEqualTo(RunDeepType.ASK_READY);
        RunDayRow e = row(day, "eval-" + eval.getId());
        assertThat(e.kind()).isEqualTo(RunDeepKind.SKY_EVAL);
        assertThat(e.type()).isEqualTo(RunDeepType.SKY_EVAL);
        assertThat(e.subline()).isEqualTo("Scheduled · HAIKU");
        assertThat(e.costUsd()).isEqualByComparingTo("5.00");
        assertThat(e.costGbp()).isEqualByComparingTo("4.00");
        assertThat(e.durationSeconds()).isEqualTo(600L);
        assertThat(day.summary().needAttention()).isEqualTo(3);
        assertThat(day.summary().partial()).isEqualTo(2);
        assertThat(day.summary().failed()).isEqualTo(1);
        assertThat(day.filterCounts()).containsEntry("ALL", 5).containsEntry("CYCLES", 1)
                .containsEntry("ASK_READY", 1).containsEntry("BRIEFING", 0).containsEntry("SKY_EVAL", 1)
                .containsEntry("OTHER", 2).containsEntry("ATTENTION", 3);
    }

    @Test
    @DisplayName("a running sky eval has an unknown cost, one past 26 hours did not finish, a failed one says why")
    void skyEvalStates() {
        SkyRatingEvalRunEntity running = em.persist(evalRun("2026-11-02T08:00:00", SkyRatingEvalStatus.RUNNING, null));
        SkyRatingEvalRunEntity stale = em.persist(evalRun("2026-10-31T05:00:00", SkyRatingEvalStatus.RUNNING, null));
        SkyRatingEvalRunEntity failed = em.persist(evalRun("2026-11-02T01:00:00", SkyRatingEvalStatus.FAILED,
                "Anthropic said   no\nto this request"));
        commit();

        RunDayResponse today = service.day(d("2026-11-02"));
        RunDayRow r = row(today, "eval-" + running.getId());
        assertThat(r.status()).isEqualTo(RunDeepStatus.RUNNING);
        assertThat(r.costMicroDollars()).isNull();
        assertThat(r.costComplete()).isFalse();
        RunDayRow f = row(today, "eval-" + failed.getId());
        assertThat(f.status()).isEqualTo(RunDeepStatus.FAILED);
        assertThat(f.note()).isEqualTo("Anthropic said no to this request");
        RunDayRow s = row(service.day(d("2026-10-31")), "eval-" + stale.getId());
        assertThat(s.status()).isEqualTo(RunDeepStatus.FAILED);
        assertThat(s.note()).isEqualTo("Did not finish");
    }

    private static SkyRatingEvalRunEntity evalRun(String startUtc, SkyRatingEvalStatus status, String error) {
        return SkyRatingEvalRunEntity.builder().runTimestamp(LocalDateTime.parse(startUtc))
                .startedAt(LocalDateTime.parse(startUtc)).model(EvaluationModel.SONNET).runsPerFixture(1)
                .triggerSource(SkyRatingEvalTrigger.MANUAL).status(status).errorMessage(error).build();
    }

    @Test
    @DisplayName("a past day's spend is compared with the average of the 30 days before it")
    void pastDaySpendAgainstAverage() {
        // Ten days of history at £0.80 a day, then a £1.60 day.
        for (int i = 10; i < 20; i++) {
            done(RunType.TIDE, "2026-10-" + i + "T04:00:00", 20, 1_000_000L);
        }
        done(RunType.TIDE, "2026-10-20T04:00:00", 20, 2_000_000L);
        commit();

        RunDayResponse day = service.day(d("2026-10-20"));

        assertThat(day.summary().spendGbp()).isEqualByComparingTo("1.60");
        assertThat(day.summary().avg30Gbp()).isEqualByComparingTo("0.80");
        assertThat(day.summary().vsAvgPercent()).isEqualTo(100);
        assertThat(service.day(d("2026-10-10")).summary().avg30Gbp()).isNull();
        assertThat(service.day(d("2026-10-10")).summary().vsAvgPercent()).isNull();
    }

    // ── Today: still to run ───────────────────────────────────────────────────

    @Test
    @DisplayName("today lists the fires still to come with trigger sublines, and a cycle's estimated children")
    void todayStillToRun() {
        // History: twelve afternoon cycles of 40 minutes, each with a 10-minute briefing and a 5-minute ask-ready
        // run a minute after the cycle ended.
        for (int i = 0; i < 12; i++) {
            String day = "2026-10-" + (10 + i);
            PipelineRunEntity c = cycle(CycleType.INTRADAY, day + "T12:00:00Z", 2400, PipelineRunStatus.COMPLETED);
            linked(RunType.BRIEFING, day + "T12:30:00", 600, 500_000L, c.getId());
            linked(RunType.ASK_READY, day + "T12:41:00", 300, 0L, c.getId());
        }
        for (int i = 0; i < 3; i++) {
            done(RunType.TIDE, "2026-10-" + (10 + i) + "T04:00:00", 30, 1_000_000L);
        }
        done(RunType.TIDE, "2026-11-02T06:00:00", 30, 1_000_000L);
        config(ForecastCycleJobs.INTRADAY_JOB, ScheduleType.SOLAR_OFFSET, "0 0 12 * * *", SolarEvent.SUNSET, 250);
        config("tide_refresh", ScheduleType.CRON, "0 30 15 * * *", null, null);
        config("aurora_polling", ScheduleType.FIXED_DELAY, null, null, null);
        commit();
        Instant fire = Instant.parse("2026-11-02T12:39:00Z");
        Instant tideFire = Instant.parse("2026-11-02T15:30:00Z");
        when(scheduler.firesBetween(any(SchedulerJobConfigEntity.class), eq(NOW), eq(DAY_END))).thenAnswer(inv -> {
            SchedulerJobConfigEntity c = inv.getArgument(0);
            return switch (c.getJobKey()) {
                case ForecastCycleJobs.INTRADAY_JOB -> List.of(fire);
                case "tide_refresh" -> List.of(tideFire);
                default -> List.<Instant>of();
            };
        });
        when(solarTriggers.create(eq(ForecastCycleJobs.INTRADAY_JOB), eq(SolarEvent.SUNSET), eq(250),
                eq("0 0 12 * * *")))
                .thenReturn(new SolarOffsetTrigger(ForecastCycleJobs.INTRADAY_JOB, SolarEvent.SUNSET, 250, null,
                        (event, date) -> date.atTime(16, 49)));

        RunDayResponse day = service.day(null);

        assertThat(day.isToday()).isTrue();
        assertThat(day.now()).isEqualTo(NOW);
        List<RunDayRow> scheduled = day.runs().stream().filter(r -> r.status() == RunDeepStatus.SCHEDULED).toList();
        assertThat(scheduled).extracting(RunDayRow::name).containsExactly("Afternoon refresh", "Daily briefing",
                "Ask ready answers", "Tide data");
        RunDayRow cycle = scheduled.get(0);
        assertThat(cycle.id()).startsWith("sched-" + ForecastCycleJobs.INTRADAY_JOB + "-");
        assertThat(cycle.kind()).isEqualTo(RunDeepKind.SCHEDULED);
        assertThat(cycle.subline()).isEqualTo("Sun-relative · 4h 10m before sunset (16:49)");
        assertThat(cycle.startedAt()).isEqualTo(fire);
        assertThat(cycle.estimated()).isFalse();
        assertThat(cycle.durationSeconds()).isEqualTo(2400L);
        assertThat(cycle.facts().expectedStart()).isEqualTo(fire);
        assertThat(cycle.facts().expectedFinish()).isEqualTo(fire.plusSeconds(2400));
        RunDayRow briefing = scheduled.get(1);
        assertThat(briefing.estimated()).isTrue();
        assertThat(briefing.startedAt()).isEqualTo(fire.plusSeconds(2400 - 600));
        assertThat(briefing.subline()).isEqualTo("Runs at the end of Afternoon refresh");
        assertThat(briefing.durationSeconds()).isEqualTo(600L);
        assertThat(briefing.costGbp()).isEqualByComparingTo("0.40");
        RunDayRow ask = scheduled.get(2);
        assertThat(ask.estimated()).isTrue();
        assertThat(ask.startedAt()).isEqualTo(fire.plusSeconds(2400 + 60));
        assertThat(ask.subline()).isEqualTo("Runs after Afternoon refresh");
        RunDayRow tideRow = scheduled.get(3);
        assertThat(tideRow.subline()).isEqualTo("Fixed time · 15:30");
        assertThat(tideRow.estimated()).isFalse();
        assertThat(tideRow.durationSeconds()).isEqualTo(30L);
        assertThat(day.runs()).noneMatch(r -> r.id().contains("aurora_polling"));

        assertThat(day.summary().finished()).isEqualTo(1);
        assertThat(day.summary().stillToRun()).isEqualTo(4);
        assertThat(day.summary().spendGbp()).isEqualByComparingTo("0.80");
        // £0.80 so far, + the briefing's £0.40 and the tide run's £0.80 median.
        assertThat(day.summary().projectedGbp()).isEqualByComparingTo("2.00");
        assertThat(day.summary().nextUp().name()).isEqualTo("Afternoon refresh");
        assertThat(day.summary().nextUp().at()).isEqualTo(fire);
        assertThat(day.summary().nextUp().estimated()).isFalse();
        assertThat(day.filterCounts()).containsEntry("CYCLES", 1).containsEntry("BRIEFING", 1)
                .containsEntry("ASK_READY", 1).containsEntry("OTHER", 2).containsEntry("ALL", 5);
    }

    @Test
    @DisplayName("a fixed-time cycle says so, and without history a cycle has no estimate and chains no children")
    void todayWithoutHistory() {
        config(ForecastCycleJobs.EVENING_JOB, ScheduleType.CRON, "0 0 21 * * *", null, null);
        config(ForecastCycleJobs.NIGHTLY_JOB, ScheduleType.CRON, "0 0 1 * * *", null, null);
        commit();
        Instant evening = Instant.parse("2026-11-02T21:00:00Z");
        when(scheduler.firesBetween(any(SchedulerJobConfigEntity.class), eq(NOW), eq(DAY_END))).thenAnswer(inv -> {
            SchedulerJobConfigEntity c = inv.getArgument(0);
            return ForecastCycleJobs.EVENING_JOB.equals(c.getJobKey()) ? List.of(evening) : List.<Instant>of();
        });

        RunDayResponse day = service.day(null);

        assertThat(day.runs()).hasSize(1);
        RunDayRow row = day.runs().get(0);
        assertThat(row.subline()).isEqualTo("Fixed time · 21:00");
        assertThat(row.durationSeconds()).isNull();
        assertThat(row.costMicroDollars()).isNull();
        assertThat(row.facts().expectedFinish()).isNull();
        assertThat(day.summary().nextUp().name()).isEqualTo("Evening sunrise refresh");
        assertThat(day.summary().finished()).isZero();
        assertThat(day.summary().spendGbp()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a paused scheduler job and the aurora batch's unestimated fire are handled")
    void pausedAndUnestimated() {
        SchedulerJobConfigEntity paused = config("tide_refresh", ScheduleType.CRON, "0 30 15 * * *", null, null);
        paused.setStatus(SchedulerJobStatus.PAUSED);
        config("aurora_batch_evaluation", ScheduleType.CRON, "0 0 20 * * *", null, null);
        commit();
        when(scheduler.firesBetween(argThat(c -> c != null && "aurora_batch_evaluation".equals(c.getJobKey())), eq(NOW),
                eq(DAY_END))).thenReturn(List.of(Instant.parse("2026-11-02T20:00:00Z")));

        RunDayResponse day = service.day(null);

        assertThat(day.runs()).extracting(RunDayRow::name).containsExactly("Aurora batch");
        assertThat(day.runs().get(0).type()).isEqualTo(RunDeepType.OTHER);
        assertThat(day.runs().get(0).durationSeconds()).isNull();
    }

    @Test
    @DisplayName("the astro job chains an ask-ready run after it; the sky eval is its own type")
    void astroChainsAsk() {
        for (int i = 0; i < 4; i++) {
            String day = "2026-10-" + (10 + i);
            done(RunType.WEATHER, day + "T15:30:00", 90, 0L);
            done(RunType.ASK_READY, day + "T15:32:00", 200, 0L);
        }
        config("astro_conditions_refresh", ScheduleType.CRON, "0 30 15 * * *", null, null);
        config("sky_rating_eval", ScheduleType.CRON, "0 0 22 * * *", null, null);
        commit();
        Instant astro = Instant.parse("2026-11-02T15:30:00Z");
        when(scheduler.firesBetween(any(SchedulerJobConfigEntity.class), eq(NOW), eq(DAY_END))).thenAnswer(inv -> {
            SchedulerJobConfigEntity c = inv.getArgument(0);
            return "astro_conditions_refresh".equals(c.getJobKey()) ? List.of(astro)
                    : List.of(Instant.parse("2026-11-02T22:00:00Z"));
        });

        RunDayResponse day = service.day(null);

        assertThat(day.runs()).extracting(RunDayRow::name).containsExactly("Astro conditions", "Ask ready answers",
                "Sky eval");
        RunDayRow ask = day.runs().get(1);
        assertThat(ask.subline()).isEqualTo("After Astro conditions");
        assertThat(ask.startedAt()).isEqualTo(astro.plusSeconds(90));
        assertThat(ask.estimated()).isTrue();
        assertThat(day.runs().get(2).type()).isEqualTo(RunDeepType.SKY_EVAL);
    }

    @Test
    @DisplayName("an ask-ready run that follows astro conditions says so; a manual run says who started it")
    void askAfterAstroSubline() {
        done(RunType.WEATHER, "2026-11-01T15:30:00", 90, 0L);
        JobRunEntity ask = done(RunType.ASK_READY, "2026-11-01T15:32:00", 60, 0L);
        JobRunEntity manual = done(RunType.TIDE, "2026-11-01T16:00:00", 60, 0L);
        manual.setTriggeredManually(true);
        commit();

        RunDayResponse day = service.day(d("2026-11-01"));

        assertThat(row(day, "job-" + ask.getId()).subline()).isEqualTo("After Astro conditions");
        assertThat(row(day, "job-" + manual.getId()).subline()).isEqualTo("Started by an admin");
    }

    @Test
    @DisplayName("scheduled rows exist only on today: a past day lists none and calls no scheduler")
    void pastDayHasNoScheduledRows() {
        done(RunType.TIDE, "2026-10-30T04:00:00", 20, 0L);
        config("tide_refresh", ScheduleType.CRON, "0 30 15 * * *", null, null);
        commit();

        RunDayResponse day = service.day(d("2026-10-30"));

        assertThat(day.runs()).noneMatch(r -> r.status() == RunDeepStatus.SCHEDULED);
        assertThat(day.summary().stillToRun()).isNull();
        assertThat(day.summary().runs()).isEqualTo(1);
        Map<String, Integer> counts = day.filterCounts();
        assertThat(counts.get("ALL")).isEqualTo(1);
    }
}
