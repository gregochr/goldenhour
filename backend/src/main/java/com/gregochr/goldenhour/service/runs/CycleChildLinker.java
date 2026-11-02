package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.RunType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Links a cycle to the two job runs it chains: the BRIEFING run of its BRIEFING phase and the ASK_READY run that
 * follows it.
 *
 * <p>A run written since V214 carries its cycle's id, and that is the link. A run written before has none, so:
 * a BRIEFING run with no cycle id that starts inside the cycle's BRIEFING phase window belongs to that cycle, and a
 * non-manual ASK_READY run with no cycle id that starts within {@value #ASK_START_WINDOW_MINUTES} minutes after the
 * cycle's {@code completed_at} belongs to it. No match leaves the child unlinked, and the cycle has no "answers
 * ready" time. A cycle resumed at BRIEFING can have more than one BRIEFING run; the latest is the cycle's.
 */
final class CycleChildLinker {

    /** How soon after a cycle ends its ask-ready run must start to be taken as the cycle's, before V214 links. */
    static final int ASK_START_WINDOW_MINUTES = 2;

    private static final Duration ASK_START_WINDOW = Duration.ofMinutes(ASK_START_WINDOW_MINUTES);

    private CycleChildLinker() {
    }

    /**
     * A cycle's chained runs.
     *
     * @param briefing the cycle's BRIEFING run, or null
     * @param askReady the cycle's ASK_READY run, or null
     */
    record Children(JobRunEntity briefing, JobRunEntity askReady) {
    }

    /**
     * Links each cycle to its children.
     *
     * @param cycles          the cycles to link
     * @param candidates      job runs that might be their children (BRIEFING and ASK_READY runs)
     * @param briefingPhases  the BRIEFING phase rows of the cycles
     * @return the children by cycle id; a cycle with no child has no entry
     */
    static Map<Long, Children> link(Collection<PipelineRunEntity> cycles, Collection<JobRunEntity> candidates,
            Collection<PipelineRunPhaseEntity> briefingPhases) {
        Map<Long, Children> out = new HashMap<>();
        for (PipelineRunEntity cycle : cycles) {
            JobRunEntity briefing = briefing(cycle, candidates, briefingPhases);
            JobRunEntity ask = askReady(cycle, candidates);
            if (briefing != null || ask != null) {
                out.put(cycle.getId(), new Children(briefing, ask));
            }
        }
        return out;
    }

    private static JobRunEntity briefing(PipelineRunEntity cycle, Collection<JobRunEntity> candidates,
            Collection<PipelineRunPhaseEntity> phases) {
        Optional<JobRunEntity> linked = candidates.stream()
                .filter(r -> r.getRunType() == RunType.BRIEFING && cycle.getId().equals(r.getPipelineRunId()))
                .max(Comparator.comparing(JobRunEntity::getStartedAt));
        if (linked.isPresent()) {
            return linked.get();
        }
        List<PipelineRunPhaseEntity> mine = phases.stream()
                .filter(p -> p.getPhase() == PipelinePhase.BRIEFING && cycle.getId().equals(p.getPipelineRunId()))
                .toList();
        return candidates.stream()
                .filter(r -> r.getRunType() == RunType.BRIEFING && r.getPipelineRunId() == null)
                .filter(r -> mine.stream().anyMatch(p -> insidePhase(r, p, cycle)))
                .max(Comparator.comparing(JobRunEntity::getStartedAt))
                .orElse(null);
    }

    private static boolean insidePhase(JobRunEntity run, PipelineRunPhaseEntity phase, PipelineRunEntity cycle) {
        return insidePhase(run.getStartedAt().toInstant(ZoneOffset.UTC), phase, cycle.getCompletedAt());
    }

    /**
     * Whether a start falls inside a phase's window.
     *
     * @param start       the start to test
     * @param phase       the phase
     * @param fallbackEnd the end to use when the phase never recorded one, or null for open-ended
     * @return true when the start is not before the phase began and not after it ended
     */
    static boolean insidePhase(Instant start, PipelineRunPhaseEntity phase, Instant fallbackEnd) {
        if (start.isBefore(phase.getStartedAt())) {
            return false;
        }
        Instant end = phase.getCompletedAt() != null ? phase.getCompletedAt() : fallbackEnd;
        return end == null || !start.isAfter(end);
    }

    private static JobRunEntity askReady(PipelineRunEntity cycle, Collection<JobRunEntity> candidates) {
        Optional<JobRunEntity> linked = candidates.stream()
                .filter(r -> r.getRunType() == RunType.ASK_READY && cycle.getId().equals(r.getPipelineRunId()))
                .max(Comparator.comparing(JobRunEntity::getStartedAt));
        if (linked.isPresent()) {
            return linked.get();
        }
        Instant ended = cycle.getCompletedAt();
        if (ended == null) {
            return null;
        }
        Instant latest = ended.plus(ASK_START_WINDOW);
        return candidates.stream()
                .filter(r -> r.getRunType() == RunType.ASK_READY && r.getPipelineRunId() == null
                        && !Boolean.TRUE.equals(r.getTriggeredManually()))
                .filter(r -> {
                    Instant start = r.getStartedAt().toInstant(ZoneOffset.UTC);
                    return !start.isBefore(ended) && !start.isAfter(latest);
                })
                .min(Comparator.comparing(JobRunEntity::getStartedAt))
                .orElse(null);
    }
}
