package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a cycle is linked to the briefing and ask-ready runs it started: by the V214 column, and, for older rows, by the
 * phase window and the two-minute start rule.
 */
class CycleChildLinkerTest {

    private static final Instant TRIGGER = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-11-02T01:50:00Z");

    private static PipelineRunEntity cycle(long id, Instant completed) {
        PipelineRunEntity c = new PipelineRunEntity(CycleType.NIGHTLY, TRIGGER);
        c.setId(id);
        c.setCompletedAt(completed);
        return c;
    }

    private static JobRunEntity job(long id, RunType type, String startedAtUtc, Long pipelineRunId, boolean manual) {
        return JobRunEntity.builder().id(id).runType(type).triggeredManually(manual).pipelineRunId(pipelineRunId)
                .startedAt(LocalDateTime.parse(startedAtUtc)).build();
    }

    private static PipelineRunPhaseEntity briefingPhase(long cycleId, Instant start, Instant end) {
        PipelineRunPhaseEntity p = new PipelineRunPhaseEntity(cycleId, PipelinePhase.BRIEFING, 4, start);
        p.setCompletedAt(end);
        return p;
    }

    @Test
    @DisplayName("a run carrying the cycle id is its child, and the latest briefing wins after a resume")
    void linkedByColumn() {
        PipelineRunEntity c = cycle(7, ENDED);
        JobRunEntity firstBriefing = job(1, RunType.BRIEFING, "2026-11-02T01:30:00", 7L, false);
        JobRunEntity laterBriefing = job(2, RunType.BRIEFING, "2026-11-02T01:45:00", 7L, false);
        JobRunEntity ask = job(3, RunType.ASK_READY, "2026-11-02T01:51:00", 7L, false);
        JobRunEntity otherCycle = job(4, RunType.BRIEFING, "2026-11-02T01:46:00", 8L, false);

        Map<Long, CycleChildLinker.Children> out = CycleChildLinker.link(List.of(c),
                List.of(firstBriefing, laterBriefing, ask, otherCycle), List.of());

        assertThat(out.get(7L).briefing()).isSameAs(laterBriefing);
        assertThat(out.get(7L).askReady()).isSameAs(ask);
    }

    @Test
    @DisplayName("before V214 a briefing is the one that starts inside the BRIEFING phase window")
    void briefingByPhaseWindow() {
        PipelineRunEntity c = cycle(7, ENDED);
        JobRunEntity inside = job(1, RunType.BRIEFING, "2026-11-02T01:32:00", null, false);
        JobRunEntity before = job(2, RunType.BRIEFING, "2026-11-02T01:10:00", null, false);
        JobRunEntity after = job(3, RunType.BRIEFING, "2026-11-02T01:49:00", null, false);
        PipelineRunPhaseEntity phase = briefingPhase(7, Instant.parse("2026-11-02T01:30:00Z"),
                Instant.parse("2026-11-02T01:40:00Z"));

        Map<Long, CycleChildLinker.Children> out = CycleChildLinker.link(List.of(c), List.of(inside, before, after),
                List.of(phase));

        assertThat(out.get(7L).briefing()).isSameAs(inside);
    }

    @Test
    @DisplayName("an open BRIEFING phase runs to the cycle's end")
    void openPhaseRunsToCycleEnd() {
        PipelineRunEntity c = cycle(7, ENDED);
        JobRunEntity late = job(1, RunType.BRIEFING, "2026-11-02T01:45:00", null, false);
        PipelineRunPhaseEntity open = briefingPhase(7, Instant.parse("2026-11-02T01:30:00Z"), null);

        assertThat(CycleChildLinker.link(List.of(c), List.of(late), List.of(open)).get(7L).briefing())
                .isSameAs(late);
        assertThat(CycleChildLinker.insidePhase(Instant.parse("2026-11-02T03:00:00Z"), open, null)).isTrue();
        assertThat(CycleChildLinker.insidePhase(Instant.parse("2026-11-02T01:00:00Z"), open, null)).isFalse();
    }

    @Test
    @DisplayName("before V214 an ask-ready run is a non-manual one starting within two minutes after the cycle ends")
    void askByStartWindow() {
        PipelineRunEntity c = cycle(7, ENDED);
        JobRunEntity soon = job(1, RunType.ASK_READY, "2026-11-02T01:51:30", null, false);
        JobRunEntity tooLate = job(2, RunType.ASK_READY, "2026-11-02T01:53:00", null, false);
        JobRunEntity manual = job(3, RunType.ASK_READY, "2026-11-02T01:50:30", null, true);
        JobRunEntity tooEarly = job(4, RunType.ASK_READY, "2026-11-02T01:49:59", null, false);

        Map<Long, CycleChildLinker.Children> out = CycleChildLinker.link(List.of(c),
                List.of(tooLate, manual, tooEarly, soon), List.of());

        assertThat(out.get(7L).askReady()).isSameAs(soon);
        assertThat(out.get(7L).briefing()).isNull();
    }

    @Test
    @DisplayName("no match leaves the cycle without children, and a running cycle has no ask-ready fallback")
    void noMatch() {
        PipelineRunEntity running = cycle(7, null);
        JobRunEntity ask = job(1, RunType.ASK_READY, "2026-11-02T01:51:30", null, false);
        JobRunEntity tide = job(2, RunType.TIDE, "2026-11-02T01:51:30", 7L, false);

        assertThat(CycleChildLinker.link(List.of(running), List.of(ask, tide), List.of())).isEmpty();
        assertThat(CycleChildLinker.link(List.of(), List.of(ask), List.of())).isEmpty();
        assertThat(ask.getStartedAt().toInstant(ZoneOffset.UTC)).isAfter(ENDED);
    }
}
