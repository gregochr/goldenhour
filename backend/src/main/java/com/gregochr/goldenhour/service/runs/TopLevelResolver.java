package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one rule for which job runs are <em>top level</em> (a row of their own) and which belong to a cycle.
 *
 * <p>Shared by the Runs list ({@link AdminRunsService}) and the Day and Calendar views, so a run is folded
 * into its cycle by the same three set queries everywhere. A job run belongs to a cycle when it is the job run of
 * any batch a cycle submitted, any cycle's disposition anchor, or (closing the gap where the batch-to-run link was
 * never written) a batch run whose {@code notes} name a cycle's Anthropic batch. An anchor whose disposition link
 * rolled back stays top level; that is accepted.
 */
class TopLevelResolver {

    /** Prefix of the {@code notes} a batch's job run is created with. */
    static final String BATCH_NOTE_PREFIX = "Anthropic batch: ";

    private final ForecastBatchRepository batches;
    private final PipelineRunRepository pipelineRuns;

    /**
     * Constructs the resolver.
     *
     * @param batches      Anthropic batch store
     * @param pipelineRuns pipeline run store
     */
    TopLevelResolver(ForecastBatchRepository batches, PipelineRunRepository pipelineRuns) {
        this.batches = batches;
        this.pipelineRuns = pipelineRuns;
    }

    /** A top-level job run with the type of its Anthropic batch, when it has one. */
    record TopLevelRun(JobRunEntity run, BatchType batchType) {
    }

    /**
     * The top-level job runs, plus the runs the notes fallback hid, keyed by the Anthropic batch id their
     * notes name (so a cycle can still count their cost).
     */
    record TopLevel(List<TopLevelRun> runs, Map<String, JobRunEntity> hiddenByNotes) {
    }

    /**
     * The Anthropic batch id a batch run's {@code notes} name, when it has the standard note.
     *
     * @param run the job run
     * @return the batch id, or empty
     */
    static Optional<String> batchNamedInNotes(JobRunEntity run) {
        if (run.getRunType() != RunType.SCHEDULED_BATCH || run.getNotes() == null
                || !run.getNotes().startsWith(BATCH_NOTE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(run.getNotes().substring(BATCH_NOTE_PREFIX.length()));
    }

    /**
     * Drops every job run that belongs to a cycle, and attaches the batch type of those that remain.
     *
     * @param candidates the job runs to consider
     * @return the top-level runs and the runs hidden by the notes fallback
     */
    TopLevel resolve(Collection<JobRunEntity> candidates) {
        if (candidates.isEmpty()) {
            return new TopLevel(List.of(), Map.of());
        }
        long minId = candidates.stream().mapToLong(JobRunEntity::getId).min().orElse(0L);
        Set<Long> hidden = new HashSet<>(batches.findCycleBatchJobRunIdsFrom(minId));
        hidden.addAll(pipelineRuns.findDispositionJobRunIdsFrom(minId));

        // The notes fallback: a batch run whose link to its batch row was never written is matched on the
        // Anthropic batch id its notes name. A cycle's batch hides the run; any batch gives it its type.
        Map<String, List<JobRunEntity>> byBatchNote = new LinkedHashMap<>();
        for (JobRunEntity run : candidates) {
            if (!hidden.contains(run.getId())) {
                batchNamedInNotes(run).ifPresent(
                        id -> byBatchNote.computeIfAbsent(id, k -> new ArrayList<>()).add(run));
            }
        }
        Map<String, ForecastBatchEntity> namedBatches = new LinkedHashMap<>();
        if (!byBatchNote.isEmpty()) {
            for (ForecastBatchEntity batch : batches.findByAnthropicBatchIdIn(byBatchNote.keySet())) {
                namedBatches.put(batch.getAnthropicBatchId(), batch);
            }
        }
        Map<String, JobRunEntity> hiddenByNotes = new LinkedHashMap<>();
        byBatchNote.forEach((batchId, runs) -> {
            ForecastBatchEntity named = namedBatches.get(batchId);
            if (named != null && named.getPipelineRunId() != null) {
                runs.forEach(r -> {
                    hidden.add(r.getId());
                    hiddenByNotes.put(batchId, r);
                });
            }
        });

        List<JobRunEntity> remaining = candidates.stream().filter(r -> !hidden.contains(r.getId())).toList();
        Set<Long> batchRunIds = remaining.stream()
                .filter(r -> r.getRunType() == RunType.SCHEDULED_BATCH)
                .map(JobRunEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, BatchType> batchTypes = new LinkedHashMap<>();
        if (!batchRunIds.isEmpty()) {
            for (ForecastBatchEntity batch : batches.findByJobRunIdIn(batchRunIds)) {
                batchTypes.put(batch.getJobRunId(), batch.getBatchType());
            }
        }
        List<TopLevelRun> runs = remaining.stream().map(r -> new TopLevelRun(r, batchTypes.getOrDefault(r.getId(),
                batchNamedInNotes(r).map(namedBatches::get).map(ForecastBatchEntity::getBatchType).orElse(null))))
                .toList();
        return new TopLevel(runs, hiddenByNotes);
    }
}
