package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads {@link CycleFacts} for a set of cycles with a fixed handful of set queries, however many cycles there are.
 */
@Component
class CycleFactsLoader {

    private final ForecastBatchRepository batches;
    private final JobRunRepository jobRuns;

    /**
     * Constructs the loader.
     *
     * @param batches Anthropic batch store
     * @param jobRuns job run store
     */
    CycleFactsLoader(ForecastBatchRepository batches, JobRunRepository jobRuns) {
        this.batches = batches;
        this.jobRuns = jobRuns;
    }

    /**
     * Loads the batches and member job runs of the cycles.
     *
     * @param cycles the cycles
     * @return their facts; empty maps when there are no cycles
     */
    CycleFacts load(Collection<PipelineRunEntity> cycles) {
        if (cycles.isEmpty()) {
            return new CycleFacts(Map.of(), Map.of());
        }
        List<Long> cycleIds = cycles.stream().map(PipelineRunEntity::getId).toList();
        Map<Long, List<ForecastBatchEntity>> byCycle = batches.findByPipelineRunIdIn(cycleIds).stream()
                .collect(Collectors.groupingBy(ForecastBatchEntity::getPipelineRunId));

        // A batch whose job-run link was never written is matched on the batch id its run's notes name.
        List<String> unlinkedNotes = byCycle.values().stream().flatMap(List::stream)
                .filter(b -> b.getJobRunId() == null)
                .map(b -> TopLevelResolver.BATCH_NOTE_PREFIX + b.getAnthropicBatchId())
                .toList();
        Map<String, JobRunEntity> byNotes = new HashMap<>();
        if (!unlinkedNotes.isEmpty()) {
            for (JobRunEntity run : jobRuns.findByRunTypeAndNotesIn(RunType.SCHEDULED_BATCH, unlinkedNotes)) {
                TopLevelResolver.batchNamedInNotes(run).ifPresent(id -> byNotes.put(id, run));
            }
        }

        Set<Long> runIds = new LinkedHashSet<>();
        byCycle.values().forEach(list -> list.forEach(b -> {
            if (b.getJobRunId() != null) {
                runIds.add(b.getJobRunId());
            }
        }));
        cycles.stream().map(PipelineRunEntity::getDispositionJobRunId).filter(java.util.Objects::nonNull)
                .forEach(runIds::add);
        Map<Long, JobRunEntity> loaded = runIds.isEmpty() ? Map.of()
                : jobRuns.findAllById(runIds).stream()
                        .collect(Collectors.toMap(JobRunEntity::getId, Function.identity()));

        Map<Long, List<JobRunEntity>> members = new LinkedHashMap<>();
        for (PipelineRunEntity cycle : cycles) {
            Set<JobRunEntity> mine = new LinkedHashSet<>();
            for (ForecastBatchEntity b : byCycle.getOrDefault(cycle.getId(), List.of())) {
                JobRunEntity run = b.getJobRunId() != null ? loaded.get(b.getJobRunId())
                        : byNotes.get(b.getAnthropicBatchId());
                if (run != null) {
                    mine.add(run);
                }
            }
            if (cycle.getDispositionJobRunId() != null && loaded.containsKey(cycle.getDispositionJobRunId())) {
                mine.add(loaded.get(cycle.getDispositionJobRunId()));
            }
            members.put(cycle.getId(), List.copyOf(mine));
        }
        return new CycleFacts(byCycle, members);
    }
}
