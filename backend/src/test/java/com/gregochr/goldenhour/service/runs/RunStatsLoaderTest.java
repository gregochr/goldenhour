package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunPhaseRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import com.gregochr.goldenhour.service.runs.RunStats.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RunStatsLoader} over mocked stores: which query each population reads, how a sample's
 * duration and cost are worked out when a column is missing, and the usual cycle-to-ask-ready gap.
 */
@ExtendWith(MockitoExtension.class)
class RunStatsLoaderTest {

    private static final Instant BEFORE = Instant.parse("2031-06-10T12:00:00Z");
    private static final LocalDateTime BEFORE_UTC = LocalDateTime.parse("2031-06-10T12:00:00");
    private static final Instant FAR = Instant.parse("2040-01-01T00:00:00Z");

    @Mock
    private JobRunRepository jobRuns;
    @Mock
    private PipelineRunRepository pipelineRuns;
    @Mock
    private PipelineRunPhaseRepository phases;
    @Mock
    private SkyRatingEvalRunRepository evalRuns;
    @Mock
    private CycleFactsLoader cycleFacts;

    private RunStatsLoader loader() {
        return new RunStatsLoader(jobRuns, pipelineRuns, phases, evalRuns, cycleFacts);
    }

    private static JobRunEntity job(long id, String start, Long durationMs, String end, Long cost) {
        return JobRunEntity.builder().id(id).runType(RunType.BRIEFING).startedAt(LocalDateTime.parse(start))
                .completedAt(end == null ? null : LocalDateTime.parse(end)).durationMs(durationMs)
                .totalCostMicroDollars(cost).build();
    }

    private static PipelineRunEntity cycle(long id, String trigger, String completed, PipelineRunStatus status) {
        PipelineRunEntity c = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse(trigger));
        c.setId(id);
        c.setStatus(status);
        if (completed != null) {
            c.setCompletedAt(Instant.parse(completed));
        }
        return c;
    }

    private static JobRunEntity ask(long id, String start, long cycleId) {
        JobRunEntity run = JobRunEntity.builder().id(id).runType(RunType.ASK_READY)
                .startedAt(LocalDateTime.parse(start)).build();
        run.setPipelineRunId(cycleId);
        return run;
    }

    private static List<Sample> series(RunStats stats, StatKey key) {
        return stats.before(key, FAR);
    }

    @Test
    @DisplayName("a population with no duration (the daily Ask run) reads nothing and has an empty series")
    void noDurationReadsNothing() {
        StatKey ask = StatKey.ofJob(RunType.ASK, null);

        RunStats stats = loader().load(Map.of(ask, 1), BEFORE);

        assertThat(series(stats, ask)).isEmpty();
    }

    @Test
    @DisplayName("a cycle sample is its trigger-to-finish seconds and its members' summed cost; a cycle that never "
            + "finished is left out")
    void cycleSamplesSumCostAndSkipUnfinished() {
        PipelineRunEntity done = cycle(1, "2031-06-09T01:00:00Z", "2031-06-09T01:10:00Z",
                PipelineRunStatus.COMPLETED);
        PipelineRunEntity open = cycle(2, "2031-06-08T01:00:00Z", null, PipelineRunStatus.COMPLETED);
        when(pipelineRuns.findByCycleTypeAndStatusInAndTriggerTimeLessThanOrderByTriggerTimeDescIdDesc(
                eq(CycleType.NIGHTLY), eq(EnumSet.of(PipelineRunStatus.COMPLETED, PipelineRunStatus.DEGRADED)),
                eq(BEFORE), eq(PageRequest.of(0, 32)))).thenReturn(List.of(done, open));
        JobRunEntity a = job(10, "2031-06-09T01:05:00", null, "2031-06-09T01:06:00", 400L);
        JobRunEntity b = job(11, "2031-06-09T01:06:00", null, "2031-06-09T01:07:00", 600L);
        when(cycleFacts.load(List.of(done, open))).thenReturn(new CycleFacts(Map.of(),
                Map.of(1L, List.of(a, b), 2L, List.of(a))));
        StatKey key = StatKey.ofCycle(CycleType.NIGHTLY);

        RunStats stats = loader().load(Map.of(key, 2), BEFORE);

        assertThat(series(stats, key)).containsExactly(new Sample(Instant.parse("2031-06-09T01:00:00Z"), 600, 1000L));
    }

    @Test
    @DisplayName("a cycle with a member whose cost is unknown has an unknown cost, not a partial sum")
    void cycleWithUnknownMemberCost() {
        PipelineRunEntity done = cycle(1, "2031-06-09T01:00:00Z", "2031-06-09T01:10:00Z",
                PipelineRunStatus.DEGRADED);
        when(pipelineRuns.findByCycleTypeAndStatusInAndTriggerTimeLessThanOrderByTriggerTimeDescIdDesc(
                eq(CycleType.NIGHTLY), eq(EnumSet.of(PipelineRunStatus.COMPLETED, PipelineRunStatus.DEGRADED)),
                eq(BEFORE),
                eq(PageRequest.of(0, 30)))).thenReturn(List.of(done));
        JobRunEntity known = job(10, "2031-06-09T01:05:00", null, "2031-06-09T01:06:00", 400L);
        JobRunEntity unknown = job(11, "2031-06-09T01:06:00", null, "2031-06-09T01:07:00", null);
        when(cycleFacts.load(List.of(done))).thenReturn(new CycleFacts(Map.of(),
                Map.of(1L, List.of(known, unknown))));
        StatKey key = StatKey.ofCycle(CycleType.NIGHTLY);

        RunStats stats = loader().load(Map.of(key, 0), BEFORE);

        assertThat(series(stats, key)).containsExactly(new Sample(Instant.parse("2031-06-09T01:00:00Z"), 600, null));
    }

    @Test
    @DisplayName("sky-eval samples use the stored duration, else the start-to-finish gap, else zero")
    void evalSamples() {
        SkyRatingEvalRunEntity stored = SkyRatingEvalRunEntity.builder().startedAt(LocalDateTime.parse(
                "2031-06-01T22:00:00")).completedAt(LocalDateTime.parse("2031-06-01T22:30:00")).durationMs(90_000L)
                .costMicroDollars(5L).build();
        SkyRatingEvalRunEntity derived = SkyRatingEvalRunEntity.builder().startedAt(LocalDateTime.parse(
                "2031-06-02T22:00:00")).completedAt(LocalDateTime.parse("2031-06-02T22:02:00"))
                .costMicroDollars(6L).build();
        SkyRatingEvalRunEntity unfinished = SkyRatingEvalRunEntity.builder().startedAt(LocalDateTime.parse(
                "2031-06-03T22:00:00")).costMicroDollars(7L).build();
        when(evalRuns.findByStatusAndRunTimestampLessThanOrderByRunTimestampDescIdDesc(
                SkyRatingEvalStatus.COMPLETED, BEFORE_UTC, PageRequest.of(0, 30)))
                .thenReturn(List.of(unfinished, derived, stored));

        RunStats stats = loader().load(Map.of(StatKey.SKY_EVAL, 0), BEFORE);

        assertThat(series(stats, StatKey.SKY_EVAL)).containsExactly(
                new Sample(Instant.parse("2031-06-01T22:00:00Z"), 90, 5L),
                new Sample(Instant.parse("2031-06-02T22:00:00Z"), 120, 6L),
                new Sample(Instant.parse("2031-06-03T22:00:00Z"), 0, 7L));
    }

    @Test
    @DisplayName("a briefing run counts when it is linked to a cycle or started inside a cycle's briefing phase; "
            + "an admin build outside any phase does not")
    void briefingSamples() {
        JobRunEntity linked = job(1, "2031-06-09T01:40:00", 60_000L, "2031-06-09T01:41:00", 10L);
        linked.setPipelineRunId(5L);
        JobRunEntity inPhase = job(2, "2031-06-08T01:40:00", 120_000L, "2031-06-08T01:42:00", 20L);
        // No stored duration: worked out from the two timestamps.
        JobRunEntity inPhaseNoDuration = job(3, "2031-06-07T01:40:00", null, "2031-06-07T01:43:00", 30L);
        JobRunEntity adminBuild = job(4, "2031-06-06T14:00:00", 5_000L, "2031-06-06T14:00:05", 40L);
        when(jobRuns.findFinishedScheduled(RunType.BRIEFING, BEFORE_UTC, PageRequest.of(0, 120)))
                .thenReturn(List.of(linked, inPhase, inPhaseNoDuration, adminBuild));
        PipelineRunPhaseEntity phase8 = new PipelineRunPhaseEntity(8L, PipelinePhase.BRIEFING, 4,
                Instant.parse("2031-06-08T01:38:00Z"));
        PipelineRunPhaseEntity phase7 = new PipelineRunPhaseEntity(7L, PipelinePhase.BRIEFING, 4,
                Instant.parse("2031-06-07T01:38:00Z"));
        when(phases.findByPhaseAndStartedAtGreaterThanEqualAndStartedAtLessThan(eq(PipelinePhase.BRIEFING),
                eq(Instant.parse("2031-06-06T14:00:00Z").minusSeconds(7200)),
                eq(Instant.parse("2031-06-08T01:40:01Z")))).thenReturn(List.of(phase8, phase7));
        StatKey key = StatKey.ofJob(RunType.BRIEFING, null);

        RunStats stats = loader().load(Map.of(key, 0), BEFORE);

        assertThat(series(stats, key)).extracting(Sample::durationSeconds).containsExactly(180L, 120L, 60L);
    }

    @Test
    @DisplayName("when every briefing run is cycle-linked no phase window is read")
    void allLinkedBriefingsReadNoPhases() {
        JobRunEntity linked = job(1, "2031-06-09T01:40:00", 60_000L, "2031-06-09T01:41:00", 10L);
        linked.setPipelineRunId(5L);
        when(jobRuns.findFinishedScheduled(RunType.BRIEFING, BEFORE_UTC, PageRequest.of(0, 120)))
                .thenReturn(List.of(linked));
        StatKey key = StatKey.ofJob(RunType.BRIEFING, null);

        RunStats stats = loader().load(Map.of(key, 0), BEFORE);

        assertThat(series(stats, key)).hasSize(1);
        // Never called at all, so no argument matters.
        verify(phases, never()).findByPhaseAndStartedAtGreaterThanEqualAndStartedAtLessThan(any(), any(), any());
    }

    @Test
    @DisplayName("an 'other' population reads the query for its model, or for runs that recorded none")
    void otherPopulations() {
        StatKey withModel = StatKey.ofJob(RunType.WEATHER, EvaluationModel.WILDLIFE);
        StatKey withoutModel = StatKey.ofJob(RunType.TIDE, null);
        StatKey plain = StatKey.ofJob(RunType.ASK_READY, null);
        when(jobRuns.findFinishedScheduledWithModel(RunType.WEATHER, EvaluationModel.WILDLIFE, BEFORE_UTC,
                PageRequest.of(0, 30))).thenReturn(List.of(job(1, "2031-06-09T15:00:00", 30_000L, null, 1L)));
        when(jobRuns.findFinishedScheduledWithoutModel(RunType.TIDE, BEFORE_UTC, PageRequest.of(0, 30)))
                .thenReturn(List.of(job(2, "2031-06-09T04:00:00", 20_000L, null, 2L)));
        when(jobRuns.findFinishedScheduled(RunType.ASK_READY, BEFORE_UTC, PageRequest.of(0, 30)))
                .thenReturn(List.of(job(3, "2031-06-09T02:00:00", 10_000L, null, 3L)));
        Map<StatKey, Integer> wanted = new LinkedHashMap<>();
        wanted.put(withModel, 0);
        wanted.put(withoutModel, 0);
        wanted.put(plain, 0);

        RunStats stats = loader().load(wanted, BEFORE);

        assertThat(series(stats, withModel)).extracting(Sample::durationSeconds).containsExactly(30L);
        assertThat(series(stats, withoutModel)).extracting(Sample::durationSeconds).containsExactly(20L);
        assertThat(series(stats, plain)).extracting(Sample::durationSeconds).containsExactly(10L);
    }

    @Test
    @DisplayName("the ask-ready gap is null when no recent finished cycle exists")
    void gapNullWithoutCycles() {
        PipelineRunEntity running = cycle(1, "2031-06-09T01:00:00Z", null, PipelineRunStatus.RUNNING);
        PipelineRunEntity failed = cycle(2, "2031-06-08T01:00:00Z", "2031-06-08T01:10:00Z", PipelineRunStatus.FAILED);
        PipelineRunEntity completedNoEnd = cycle(4, "2031-06-07T01:00:00Z", null, PipelineRunStatus.COMPLETED);
        PipelineRunEntity future = cycle(3, "2031-06-11T01:00:00Z", "2031-06-11T01:10:00Z",
                PipelineRunStatus.COMPLETED);
        when(pipelineRuns.findTop50ByOrderByTriggerTimeDesc())
                .thenReturn(List.of(future, running, failed, completedNoEnd));

        assertThat(loader().askReadyGapSeconds(BEFORE)).isNull();
        // Never called at all, so no argument matters.
        verify(jobRuns, never()).findByPipelineRunIdIn(any());
    }

    @Test
    @DisplayName("the ask-ready gap is the median seconds from a cycle's end to its linked ask-ready start, "
            + "ignoring cycles with none and never going negative")
    void gapMedian() {
        PipelineRunEntity c1 = cycle(1, "2031-06-09T01:00:00Z", "2031-06-09T01:10:00Z", PipelineRunStatus.COMPLETED);
        PipelineRunEntity c2 = cycle(2, "2031-06-08T01:00:00Z", "2031-06-08T01:10:00Z", PipelineRunStatus.DEGRADED);
        PipelineRunEntity c3 = cycle(3, "2031-06-07T01:00:00Z", "2031-06-07T01:10:00Z", PipelineRunStatus.COMPLETED);
        PipelineRunEntity c4 = cycle(4, "2031-06-06T01:00:00Z", "2031-06-06T01:10:00Z", PipelineRunStatus.COMPLETED);
        when(pipelineRuns.findTop50ByOrderByTriggerTimeDesc()).thenReturn(List.of(c1, c2, c3, c4));
        JobRunEntity ask1 = ask(21, "2031-06-09T01:12:00", 1L);
        JobRunEntity ask2 = ask(22, "2031-06-08T01:14:00", 2L);
        // Started before its cycle finished: the gap is clamped to zero.
        JobRunEntity ask4 = ask(24, "2031-06-06T01:05:00", 4L);
        // Cycle 3 has a briefing run linked to it but no ask-ready run: it contributes no gap.
        JobRunEntity briefing3 = job(23, "2031-06-07T01:11:00", 60_000L, "2031-06-07T01:12:00", 1L);
        briefing3.setPipelineRunId(3L);
        when(jobRuns.findByPipelineRunIdIn(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(ask1, ask2, briefing3, ask4));
        // Returned by both queries: counted once.
        when(jobRuns.findByRunTypeInAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                List.of(RunType.ASK_READY), LocalDateTime.parse("2031-06-06T01:00:00"), BEFORE_UTC))
                .thenReturn(List.of(ask1));

        assertThat(loader().askReadyGapSeconds(BEFORE)).isEqualTo(120L);
    }
}
