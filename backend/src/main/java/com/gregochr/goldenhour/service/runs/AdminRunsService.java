package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.JobRunDto;
import com.gregochr.goldenhour.model.runs.CycleCost;
import com.gregochr.goldenhour.model.runs.RunBatchInfo;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunJobDetail;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.model.runs.RunListResponse;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.model.runs.RunListSummary;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.BatchSummaryDeriver;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the merged Runs list for the admin Operations tab: pipeline runs (cycles) and top-level job
 * runs in one newest-first list, each with a served group, name, status and note, plus range-scoped
 * aggregates. The client never merges, groups or judges a status.
 *
 * <p>A job run is <em>top level</em> unless it belongs to a cycle. Three set queries decide that, over
 * all history rather than only the cycles in range: the job run of any batch a cycle submitted, any
 * cycle's disposition anchor, and (closing the gap where the batch-to-run link was never written) a
 * batch run whose {@code notes} name a cycle's Anthropic batch. An anchor whose disposition link rolled
 * back stays top level; that is accepted.
 *
 * <p>Volume is tens of runs a day, so the merge is in memory over at most {@value #SCAN_CAP} job runs.
 */
@Service
public class AdminRunsService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminRunsService.class);

    /** The most job runs scanned for one request; more sets {@code truncated}. */
    static final int SCAN_CAP = 5_000;

    /** The page size when the caller gives none. */
    public static final int DEFAULT_SIZE = 20;

    /** The largest page size honoured. */
    public static final int MAX_SIZE = 100;

    /** Prefix of the {@code notes} a batch's job run is created with. */
    static final String BATCH_NOTE_PREFIX = "Anthropic batch: ";

    /** Key in {@code kindCounts} for the count across every group. */
    static final String ALL_KEY = RunGroup.ALL;

    private final JobRunRepository jobRuns;
    private final PipelineRunRepository pipelineRuns;
    private final ForecastBatchRepository batches;
    private final BatchSummaryDeriver batchSummaryDeriver;
    private final DynamicSchedulerService scheduler;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param jobRuns             job run store
     * @param pipelineRuns        pipeline run store
     * @param batches             Anthropic batch store
     * @param batchSummaryDeriver derives a batch run's scope line
     * @param scheduler           source of the nightly cycle's next fire
     * @param clock               the application clock
     */
    public AdminRunsService(JobRunRepository jobRuns, PipelineRunRepository pipelineRuns,
            ForecastBatchRepository batches, BatchSummaryDeriver batchSummaryDeriver,
            DynamicSchedulerService scheduler, Clock clock) {
        this.jobRuns = jobRuns;
        this.pipelineRuns = pipelineRuns;
        this.batches = batches;
        this.batchSummaryDeriver = batchSummaryDeriver;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** A top-level job run with the type of its Anthropic batch, when it has one. */
    private record TopLevelRun(JobRunEntity run, BatchType batchType) {
    }

    /**
     * The top-level job runs, plus the runs the notes fallback hid, keyed by the Anthropic batch id their
     * notes name (so a cycle can still count their cost).
     */
    private record TopLevel(List<TopLevelRun> runs, Map<String, JobRunEntity> hiddenByNotes) {
    }

    /**
     * Serves one page of the merged list and its aggregates.
     *
     * @param range     the UK-day-aligned window
     * @param kind      the group filter, or null for all
     * @param attention true to keep only DEGRADED, PARTIAL and FAILED rows
     * @param before    the cursor of the last row already shown, or null for the first page
     * @param size      the page size, already within 1 to {@value #MAX_SIZE}
     * @return the page and aggregates
     */
    public RunListResponse list(RunRange range, RunGroup kind, boolean attention, RunCursor before, int size) {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime fromUtc = ForecastHorizon.ukDayStartUtc(
                ForecastHorizon.today(clock).minusDays(range.daysBack()));
        Instant fromInstant = fromUtc.toInstant(ZoneOffset.UTC);

        List<JobRunEntity> scanned = jobRuns.findByStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
                fromUtc, PageRequest.of(0, SCAN_CAP + 1));
        boolean truncated = scanned.size() > SCAN_CAP;
        List<JobRunEntity> inRange = truncated ? scanned.subList(0, SCAN_CAP) : scanned;
        Set<Long> inRangeIds = inRange.stream().map(JobRunEntity::getId).collect(Collectors.toSet());

        Map<Long, JobRunEntity> candidates = new LinkedHashMap<>();
        inRange.forEach(r -> candidates.put(r.getId(), r));
        jobRuns.findByCompletedAtIsNullAndStartedAtGreaterThanEqual(
                nowUtc.minus(RunClassifier.longestStaleCeiling()))
                .forEach(r -> candidates.putIfAbsent(r.getId(), r));
        TopLevel topLevel = topLevel(candidates.values());

        List<PipelineRunEntity> cyclesInRange =
                pipelineRuns.findByTriggerTimeGreaterThanEqualOrderByTriggerTimeDescIdDesc(fromInstant);
        List<PipelineRunEntity> runningCycles =
                pipelineRuns.findByStatusOrderByTriggerTimeDesc(PipelineRunStatus.RUNNING);

        Map<Long, PipelineRunEntity> cycleSet = new LinkedHashMap<>();
        cyclesInRange.forEach(c -> cycleSet.put(c.getId(), c));
        runningCycles.forEach(c -> cycleSet.putIfAbsent(c.getId(), c));
        CycleFacts cycleFacts = loadCycleFacts(cycleSet.values(), topLevel.hiddenByNotes());

        List<RunListItem> allItems = new ArrayList<>();
        for (PipelineRunEntity cycle : cyclesInRange) {
            allItems.add(cycleItem(cycle, cycleFacts));
        }
        long runningJobs = 0;
        for (TopLevelRun top : topLevel.runs()) {
            RunListItem item = jobItem(top.run(), top.batchType(), nowUtc);
            if (item.status() == RunListStatus.RUNNING) {
                runningJobs++;
            }
            if (inRangeIds.contains(top.run().getId())) {
                allItems.add(item);
            }
        }

        List<RunListItem> kindItems = allItems.stream().filter(i -> kind == null || i.group() == kind).toList();
        List<RunListItem> filtered = kindItems.stream()
                .filter(i -> !attention || i.status().needsAttention())
                .sorted(RunCursor.ORDER)
                .toList();

        List<RunListItem> afterCursor = before == null ? filtered
                : filtered.stream().filter(before::isFollowedBy).toList();
        boolean hasMore = afterCursor.size() > size;
        List<RunListItem> page = hasMore ? afterCursor.subList(0, size) : afterCursor;
        String nextCursor = hasMore ? RunCursor.of(page.get(page.size() - 1)).encode() : null;

        return new RunListResponse(
                withBatchSummaries(page, candidates),
                nextCursor,
                filtered.size(),
                summarise(kindItems, cyclesInRange),
                kindCounts(allItems),
                (int) allItems.stream().filter(i -> i.status().needsAttention()).count(),
                (int) (runningCycles.size() + runningJobs),
                runningCycles.isEmpty() ? null : runningCycles.get(0).getId(),
                nextOvernightAt(),
                truncated);
    }

    /**
     * Serves one job run in full, whether or not it appears at the top level of the list.
     *
     * @param id the job run id
     * @return the run, or empty when none exists
     */
    public Optional<RunJobDetail> jobDetail(long id) {
        return jobRuns.findById(id).map(run -> {
            Optional<ForecastBatchEntity> batch = batches.findByJobRunId(id)
                    .or(() -> batchNamedInNotes(run).flatMap(batches::findByAnthropicBatchId));
            BatchType batchType = batch.map(ForecastBatchEntity::getBatchType).orElse(null);
            batchSummaryDeriver.derive(run).ifPresent(run::setBatchSummary);
            RunClassifier.Outcome outcome = RunClassifier.classifyJob(run, nowUtc());
            return new RunJobDetail(
                    JobRunDto.from(run),
                    outcome.status(),
                    RunClassifier.groupOf(run.getRunType(), batchType),
                    RunClassifier.jobName(run.getRunType(), batchType, run.getActiveStrategies()),
                    outcome.note(),
                    outcome.tone(),
                    Boolean.TRUE.equals(run.getTriggeredManually()),
                    batch.map(RunBatchInfo::from).orElse(null));
        });
    }

    /** The Anthropic batch id a batch run's {@code notes} name, when it has the standard note. */
    private static Optional<String> batchNamedInNotes(JobRunEntity run) {
        if (run.getRunType() != RunType.SCHEDULED_BATCH || run.getNotes() == null
                || !run.getNotes().startsWith(BATCH_NOTE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(run.getNotes().substring(BATCH_NOTE_PREFIX.length()));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ── Top level ─────────────────────────────────────────────────────────────

    /**
     * Drops every job run that belongs to a cycle, and attaches the batch type of those that remain.
     */
    private TopLevel topLevel(Collection<JobRunEntity> candidates) {
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

    // ── Cycles ────────────────────────────────────────────────────────────────

    /** The batches and the cost-bearing job runs of a set of cycles. */
    private record CycleFacts(Map<Long, List<ForecastBatchEntity>> batchesByCycle,
            Map<Long, JobRunEntity> costRuns, Map<String, JobRunEntity> hiddenByNotes) {
    }

    private CycleFacts loadCycleFacts(Collection<PipelineRunEntity> cycles, Map<String, JobRunEntity> byNotes) {
        if (cycles.isEmpty()) {
            return new CycleFacts(Map.of(), Map.of(), byNotes);
        }
        List<Long> cycleIds = cycles.stream().map(PipelineRunEntity::getId).toList();
        Map<Long, List<ForecastBatchEntity>> byCycle = batches.findByPipelineRunIdIn(cycleIds).stream()
                .collect(Collectors.groupingBy(ForecastBatchEntity::getPipelineRunId));
        Set<Long> costRunIds = new HashSet<>();
        byCycle.values().forEach(list -> list.stream().map(ForecastBatchEntity::getJobRunId)
                .filter(Objects::nonNull).forEach(costRunIds::add));
        cycles.stream().map(PipelineRunEntity::getDispositionJobRunId)
                .filter(Objects::nonNull).forEach(costRunIds::add);
        Map<Long, JobRunEntity> costRuns = costRunIds.isEmpty() ? Map.of()
                : jobRuns.findAllById(costRunIds).stream()
                        .collect(Collectors.toMap(JobRunEntity::getId, Function.identity()));
        return new CycleFacts(byCycle, costRuns, byNotes);
    }

    /**
     * Maps pipeline runs to list items by the rules the Runs list uses, with set queries for their batches and
     * cost runs. The Scheduler's recent runs reuse it so a cycle reads the same there as in Runs.
     *
     * @param cycles the pipeline runs, in the order to serve them
     * @return one item per cycle, in the same order
     */
    public List<RunListItem> cycleItems(Collection<PipelineRunEntity> cycles) {
        if (cycles.isEmpty()) {
            return List.of();
        }
        CycleFacts loaded = loadCycleFacts(cycles, Map.of());
        List<String> unlinkedNotes = loaded.batchesByCycle().values().stream()
                .flatMap(List::stream)
                .filter(b -> b.getJobRunId() == null)
                .map(b -> BATCH_NOTE_PREFIX + b.getAnthropicBatchId())
                .toList();
        Map<String, JobRunEntity> byNotes = new LinkedHashMap<>();
        if (!unlinkedNotes.isEmpty()) {
            for (JobRunEntity run : jobRuns.findByRunTypeAndNotesIn(RunType.SCHEDULED_BATCH, unlinkedNotes)) {
                batchNamedInNotes(run).ifPresent(id -> byNotes.put(id, run));
            }
        }
        CycleFacts facts = new CycleFacts(loaded.batchesByCycle(), loaded.costRuns(), byNotes);
        return cycles.stream().map(c -> cycleItem(c, facts)).toList();
    }

    /**
     * Maps job runs of one batch type (or none) to list items by the rules the Runs list uses. The
     * Scheduler's recent runs reuse it so a job run reads the same there as in Runs. The batch scope
     * ({@code batchSummary}) is not derived.
     *
     * @param runs      the job runs, in the order to serve them
     * @param batchType the type of their Anthropic batches, or null when they have none
     * @return one item per run, in the same order
     */
    public List<RunListItem> jobItems(Collection<JobRunEntity> runs, BatchType batchType) {
        LocalDateTime now = nowUtc();
        return runs.stream().map(r -> jobItem(r, batchType, now)).toList();
    }

    private RunListItem cycleItem(PipelineRunEntity cycle, CycleFacts facts) {
        RunClassifier.Outcome outcome = RunClassifier.classifyCycle(cycle);
        List<ForecastBatchEntity> cycleBatches = facts.batchesByCycle().getOrDefault(cycle.getId(), List.of());
        int done = (int) cycleBatches.stream()
                .filter(b -> ForecastBatchEntity.BatchStatus.TERMINAL.contains(b.getStatus())).count();

        CycleCost cycleCost = cycleCost(cycle, cycleBatches, facts.costRuns(), facts.hiddenByNotes());

        Instant ended = cycle.getCompletedAt();
        Long duration = ended == null ? null : Duration.between(cycle.getTriggerTime(), ended).toSeconds();
        return new RunListItem(RunKind.CYCLE, cycle.getId(), RunGroup.CYCLES,
                RunClassifier.cycleName(cycle.getCycleType()), cycle.getTriggerTime(), ended, duration,
                outcome.status(), outcome.note(), outcome.tone(), cycleCost.costMicroDollars(), cycleCost.costGbpRate(),
                done, cycleBatches.size(), null, null, null,
                cycle.getCycleType(), cycleBatches.size(), null, null, null);
    }

    /**
     * The cost of one cycle for its detail view, by the same rule the list uses ({@link #cycleCost(PipelineRunEntity,
     * List, Map, Map)}): the distinct job runs behind its batches plus its disposition anchor, and a batch whose
     * job-run link was never written is matched on the Anthropic batch id its run's notes name.
     *
     * @param cycle        the pipeline run
     * @param cycleBatches the forecast batches tagged with the cycle
     * @return the batch cost and the latest rate; a cost of zero when nothing carries one
     */
    public CycleCost cycleCost(PipelineRunEntity cycle, List<ForecastBatchEntity> cycleBatches) {
        Set<Long> costRunIds = new HashSet<>();
        cycleBatches.stream().map(ForecastBatchEntity::getJobRunId).filter(Objects::nonNull).forEach(costRunIds::add);
        if (cycle.getDispositionJobRunId() != null) {
            costRunIds.add(cycle.getDispositionJobRunId());
        }
        Map<Long, JobRunEntity> costRuns = costRunIds.isEmpty() ? Map.of()
                : jobRuns.findAllById(costRunIds).stream()
                        .collect(Collectors.toMap(JobRunEntity::getId, Function.identity()));
        Map<String, JobRunEntity> byNotes = new LinkedHashMap<>();
        List<String> unlinkedNotes = cycleBatches.stream().filter(b -> b.getJobRunId() == null)
                .map(b -> BATCH_NOTE_PREFIX + b.getAnthropicBatchId()).toList();
        if (!unlinkedNotes.isEmpty()) {
            for (JobRunEntity run : jobRuns.findByRunTypeAndNotesIn(RunType.SCHEDULED_BATCH, unlinkedNotes)) {
                batchNamedInNotes(run).ifPresent(id -> byNotes.put(id, run));
            }
        }
        return cycleCost(cycle, cycleBatches, costRuns, byNotes);
    }

    /**
     * The one cost rule for a cycle, shared by the list and the detail: distinct job run ids (the disposition anchor
     * is the first batch's run, so it must not count twice), summed; the rate is the latest run's.
     */
    private static CycleCost cycleCost(PipelineRunEntity cycle, List<ForecastBatchEntity> cycleBatches,
            Map<Long, JobRunEntity> loadedRuns, Map<String, JobRunEntity> hiddenByNotes) {
        Set<Long> runIds = new HashSet<>();
        Map<Long, JobRunEntity> costRunsById = new LinkedHashMap<>(loadedRuns);
        for (ForecastBatchEntity b : cycleBatches) {
            JobRunEntity linked = b.getJobRunId() != null ? null : hiddenByNotes.get(b.getAnthropicBatchId());
            if (linked != null) {
                // The batch row has no job run link; the run is matched on its notes instead.
                runIds.add(linked.getId());
                costRunsById.put(linked.getId(), linked);
            } else if (b.getJobRunId() != null) {
                runIds.add(b.getJobRunId());
            }
        }
        if (cycle.getDispositionJobRunId() != null) {
            runIds.add(cycle.getDispositionJobRunId());
        }
        List<JobRunEntity> costRuns = runIds.stream().map(costRunsById::get)
                .filter(Objects::nonNull).toList();
        long cost = costRuns.stream().map(JobRunEntity::getTotalCostMicroDollars)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        Double rate = costRuns.stream()
                .filter(r -> r.getExchangeRateGbpPerUsd() != null)
                .max(Comparator.comparing(JobRunEntity::getStartedAt))
                .map(JobRunEntity::getExchangeRateGbpPerUsd).orElse(null);
        return new CycleCost(cost, rate);
    }

    // ── Job runs ──────────────────────────────────────────────────────────────

    private RunListItem jobItem(JobRunEntity run, BatchType batchType, LocalDateTime nowUtc) {
        RunClassifier.Outcome outcome = RunClassifier.classifyJob(run, nowUtc);
        Instant started = run.getStartedAt().toInstant(ZoneOffset.UTC);
        Instant ended = run.getCompletedAt() == null ? null : run.getCompletedAt().toInstant(ZoneOffset.UTC);
        Long duration = null;
        if (ended != null && !RunClassifier.hasNoDuration(run.getRunType())) {
            duration = run.getDurationMs() != null ? Duration.ofMillis(run.getDurationMs()).toSeconds()
                    : Duration.between(started, ended).toSeconds();
        }
        long cost = run.getTotalCostMicroDollars() == null ? 0L : run.getTotalCostMicroDollars();
        return new RunListItem(RunKind.JOB, run.getId(),
                RunClassifier.groupOf(run.getRunType(), batchType),
                RunClassifier.jobName(run.getRunType(), batchType, run.getActiveStrategies()),
                started, ended, duration, outcome.status(), outcome.note(), outcome.tone(),
                cost, run.getExchangeRateGbpPerUsd(), null, null,
                run.getSucceeded(), run.getFailed(), run.getLocationsProcessed(),
                null, null, run.getRunType(), Boolean.TRUE.equals(run.getTriggeredManually()), null);
    }

    private List<RunListItem> withBatchSummaries(List<RunListItem> page, Map<Long, JobRunEntity> runs) {
        return page.stream().map(item -> {
            if (item.kind() != RunKind.JOB || item.runType() != RunType.SCHEDULED_BATCH) {
                return item;
            }
            return batchSummaryDeriver.derive(runs.get(item.id())).map(item::withBatchSummary).orElse(item);
        }).toList();
    }

    // ── Aggregates ────────────────────────────────────────────────────────────

    private RunListSummary summarise(List<RunListItem> kindItems, List<PipelineRunEntity> cyclesInRange) {
        Map<RunListStatus, Long> byStatus = kindItems.stream()
                .collect(Collectors.groupingBy(RunListItem::status, Collectors.counting()));
        int degraded = byStatus.getOrDefault(RunListStatus.DEGRADED, 0L).intValue();
        int partial = byStatus.getOrDefault(RunListStatus.PARTIAL, 0L).intValue();
        int failed = byStatus.getOrDefault(RunListStatus.FAILED, 0L).intValue();
        int cycles = (int) kindItems.stream().filter(i -> i.kind() == RunKind.CYCLE).count();
        long spend = kindItems.stream().mapToLong(i -> i.costMicroDollars() == null ? 0L : i.costMicroDollars())
                .sum();
        Double rate = kindItems.stream()
                .filter(i -> i.costGbpRate() != null)
                .max(Comparator.comparing(RunListItem::startedAt))
                .map(RunListItem::costGbpRate).orElse(null);

        List<Long> overnight = cyclesInRange.stream()
                .filter(c -> c.getCycleType() == CycleType.NIGHTLY && c.getCompletedAt() != null)
                .filter(c -> c.getStatus() == PipelineRunStatus.COMPLETED
                        || c.getStatus() == PipelineRunStatus.DEGRADED)
                .map(c -> Duration.between(c.getTriggerTime(), c.getCompletedAt()).toSeconds())
                .sorted()
                .toList();
        return new RunListSummary(kindItems.size(), cycles, kindItems.size() - cycles,
                degraded + partial + failed, degraded, partial, failed, spend, rate,
                median(overnight), overnight.size());
    }

    /** The median of an ascending list: the middle value, or the mean of the two middle values. */
    static Long median(List<Long> ascending) {
        if (ascending.isEmpty()) {
            return null;
        }
        int middle = ascending.size() / 2;
        return ascending.size() % 2 == 1 ? ascending.get(middle)
                : (ascending.get(middle - 1) + ascending.get(middle)) / 2;
    }

    private static Map<String, Integer> kindCounts(List<RunListItem> allItems) {
        Map<RunGroup, Integer> byGroup = new EnumMap<>(RunGroup.class);
        for (RunGroup group : RunGroup.values()) {
            byGroup.put(group, 0);
        }
        allItems.forEach(i -> byGroup.merge(i.group(), 1, Integer::sum));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(ALL_KEY, allItems.size());
        byGroup.forEach((group, count) -> counts.put(group.name(), count));
        return counts;
    }

    /**
     * The next fire of the nightly cycle job, from the scheduler's own arithmetic. Null when the job is
     * paused, disabled or unknown.
     */
    private Instant nextOvernightAt() {
        try {
            return scheduler.getAll().stream()
                    .filter(c -> ForecastCycleJobs.NIGHTLY_JOB.equals(c.getJobKey()))
                    .findFirst()
                    .map(scheduler::calculateNextFireTime)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("Could not read the next overnight fire time: {}", e.getMessage());
            return null;
        }
    }
}
