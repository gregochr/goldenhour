package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.CheckDefaults;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Compared;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Comparison;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ComparisonRow;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Direction;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Fixture;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Lane;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.MissSide;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelFixture;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelSession;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelStats;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelStatus;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Moved;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Run;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.RunFixture;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Schedule;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Session;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.SessionStatus;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Verdict;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.VerdictKind;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.VerdictLine;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Version;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFirstSeen;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFixtureAggregate;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assembles {@code GET /api/admin/sky-rating-eval/overview}: everything the redesigned Sky Eval page renders,
 * derived on the server so the client only formats.
 *
 * <p><b>Bounded reads.</b> A request makes a constant number of queries however many runs exist: the newest
 * {@value #RUN_WINDOW} runs, one aggregate over their result rows grouped by run and fixture, one first-seen
 * query, the weekly job row and the lane models. Nothing loops a query per run.
 *
 * <p><b>Definitions.</b> A version is a non-blank {@code git_commit_hash}; a null or blank hash is an unknown
 * version and takes no part in any comparison. The pass rate is in-band over <em>attempts</em> (a failed call is
 * a non-pass); only the mean, minimum and maximum rating skip failed calls. "Weekly" is the
 * {@code SCHEDULED} trigger. Every pooled figure (last four weekly, last two runs, a version's runs) sums
 * attempts rather than averaging rates, and sums only fixtures still in the registry, so a retired fixture
 * never moves a figure. Before and after are compared over the fixtures both sides scored, so a quick check
 * of a few fixtures is set against the same fixtures before it. An "earlier" version is one committed earlier.
 * Costs are summed in micro-dollars per run and converted once at the exchange rate of the run's start date.
 */
@Service
public class SkyRatingEvalOverviewService {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalOverviewService.class);

    /** How many of the newest runs the overview is computed over. */
    static final int RUN_WINDOW = 500;

    /** Minutes within which a run joins the session of the previous run in its chain. */
    static final long SESSION_GAP_MINUTES = 40;

    /** A model is fresh when its newest completed run finished this many days ago or less. */
    static final long FRESH_DAYS = 21;

    /** Points a model must fall by on HEAD to count as having dropped. */
    static final double DROP_POINTS = -3.0;

    /** Points an individual fixture's in-band share must move by to be listed. */
    static final double MOVED_POINTS = 20.0;

    /** Fewest attempts a fixture needs on each side before its movement is trusted. */
    static final long MOVED_MIN_ATTEMPTS = 3;

    /** Completed scheduled runs pooled for the "last 4 weekly" figures. */
    static final int WEEKLY_RUNS = 4;

    /** Completed runs a model's per-fixture "last 4" misses are taken over. */
    static final int FIXTURE_MISS_RUNS = 4;

    /** Completed runs per model whose per-fixture figures are served (the Fixtures tab grid width). */
    static final int GRID_RUNS = 13;

    /** Runs on the earlier version a model's "before" pools. */
    static final int BASELINE_RUNS = 2;

    /** Versions the "did a change make ratings worse" panel lists. */
    static final int COMPARISON_VERSIONS = 5;

    /** Default repeats offered by the Run a check panel. */
    static final int DEFAULT_CHECK_RUNS = 3;

    /** Completed manual runs the per-rating cost and time estimate is taken over. */
    static final int ESTIMATE_RUNS = 4;

    /** Length of the abbreviated sha. */
    static final int SHORT_SHA_LENGTH = 7;

    private static final double PERCENT = 100.0;
    private static final double MICRO = 1_000_000.0;
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final Pattern WEEKLY_CRON = Pattern.compile(
            "\\d+\\s+\\d+\\s+\\d+\\s+[*?]\\s+\\*\\s+([A-Za-z]{3}|[0-7])");
    private static final String PAUSED_LABEL = "weekly run paused";
    private static final String NO_SCHEDULE_LABEL = "no weekly schedule";

    private final SkyRatingEvalRunRepository runRepository;
    private final SkyRatingEvalResultRepository resultRepository;
    private final SkyRatingEvalService evalService;
    private final GitInfoService gitInfoService;
    private final ModelSelectionService modelSelectionService;
    private final ExchangeRateService exchangeRateService;
    private final SchedulerJobConfigRepository schedulerJobConfigRepository;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param runRepository                run reads
     * @param resultRepository             result aggregates
     * @param evalService                  the weekly model list
     * @param gitInfoService               the running build
     * @param modelSelectionService        the models the production lanes run on
     * @param exchangeRateService          GBP per USD by date
     * @param schedulerJobConfigRepository the weekly job's cron and status
     * @param clock                        "now" for freshness and the schedule label
     */
    public SkyRatingEvalOverviewService(SkyRatingEvalRunRepository runRepository,
            SkyRatingEvalResultRepository resultRepository, SkyRatingEvalService evalService,
            GitInfoService gitInfoService, ModelSelectionService modelSelectionService,
            ExchangeRateService exchangeRateService, SchedulerJobConfigRepository schedulerJobConfigRepository,
            Clock clock) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.evalService = evalService;
        this.gitInfoService = gitInfoService;
        this.modelSelectionService = modelSelectionService;
        this.exchangeRateService = exchangeRateService;
        this.schedulerJobConfigRepository = schedulerJobConfigRepository;
        this.clock = clock;
    }

    /**
     * Builds the whole page payload.
     *
     * @return the overview
     */
    public SkyEvalOverview overview() {
        Instant now = clock.instant();
        Ctx ctx = load(now);

        Map<RunType, EvaluationModel> laneModels = new LinkedHashMap<>();
        laneModels.put(RunType.BATCH_NEAR_TERM, modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM));
        laneModels.put(RunType.BATCH_FAR_TERM, modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM));

        EnumSet<EvaluationModel> modelSet = EnumSet.noneOf(EvaluationModel.class);
        ctx.runs.forEach(r -> modelSet.add(r.getModel()));
        modelSet.addAll(laneModels.values());
        List<EvaluationModel> modelOrder = List.copyOf(modelSet);

        Map<EvaluationModel, ModelStats> stats = new LinkedHashMap<>();
        for (EvaluationModel model : modelOrder) {
            stats.put(model, modelStats(ctx, model, lanesOf(model, laneModels), now));
        }
        List<ModelStats> sortedStats = stats.values().stream()
                .sorted(Comparator.comparing(ModelStats::fresh).reversed()
                        .thenComparing(ModelStats::last4WeeklyPct,
                                Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                        .thenComparing(m -> m.model().ordinal()))
                .toList();

        List<Session> sessions = sessions(ctx);
        return new SkyEvalOverview(
                ctx.head,
                schedule(now),
                lanes(laneModels, stats),
                fixtures(ctx),
                versionsServed(ctx),
                sessions,
                runFixtures(ctx, modelOrder),
                sortedStats,
                modelFixtures(ctx, modelOrder),
                verdict(ctx, laneModels, stats),
                comparisons(ctx, laneModels, modelOrder),
                checkDefaults(ctx, laneModels, sessions));
    }

    // ---- loading -----------------------------------------------------------------------------------------

    private Ctx load(Instant now) {
        List<SkyRatingEvalRunEntity> runs = runRepository.findTop500ByOrderByRunTimestampDesc();
        Set<String> current = SkyRatingEvalFixtures.ALL.stream().map(SkyRatingEvalFixture::name)
                .collect(Collectors.toSet());
        Map<Long, Map<String, SkyRatingEvalFixtureAggregate>> aggregates = new HashMap<>();
        if (!runs.isEmpty()) {
            List<Long> ids = runs.stream().map(SkyRatingEvalRunEntity::getId).toList();
            for (SkyRatingEvalFixtureAggregate agg : resultRepository.aggregateByRunAndFixture(ids)) {
                if (current.contains(agg.fixtureName())) {
                    aggregates.computeIfAbsent(agg.runId(), k -> new HashMap<>()).put(agg.fixtureName(), agg);
                }
            }
        }
        Map<String, LocalDateTime> firstSeen = resultRepository.firstSeenByFixture().stream()
                .collect(Collectors.toMap(SkyRatingEvalFirstSeen::fixtureName,
                        SkyRatingEvalFirstSeen::firstRunAt, (a, b) -> a));

        String headSha = blankToNull(gitInfoService.getCommitHash());
        Instant headDate = toInstant(gitInfoService.getCommitDate());
        Version head = headSha == null ? new Version(null, null, null, null)
                : new Version(headSha, shortSha(headSha), blankToNull(gitInfoService.getCommitMessage()), headDate);

        Ctx ctx = new Ctx(runs, aggregates, firstSeen, head);
        ctx.versions.putAll(versionInfo(runs, head, now));
        return ctx;
    }

    private static Map<String, VersionInfo> versionInfo(List<SkyRatingEvalRunEntity> runs, Version head,
            Instant now) {
        Map<String, VersionInfo> out = new HashMap<>();
        // oldest first so a later (newer) run's message and date win
        List<SkyRatingEvalRunEntity> oldestFirst = runs.stream()
                .sorted(Comparator.comparing(SkyRatingEvalRunEntity::getStartedAt)).toList();
        for (SkyRatingEvalRunEntity run : oldestFirst) {
            String sha = versionOf(run);
            if (sha == null) {
                continue;
            }
            VersionInfo existing = out.get(sha);
            Instant firstRun = toInstant(run.getRunTimestamp());
            Instant committed = run.getGitCommitDate() != null ? toInstant(run.getGitCommitDate())
                    : existing != null ? existing.committedAt : firstRun;
            String message = blankToNull(run.getGitCommitMessage());
            if (message == null && existing != null) {
                message = existing.message;
            }
            out.put(sha, new VersionInfo(sha, message, committed));
        }
        if (head.sha() != null) {
            VersionInfo seen = out.get(head.sha());
            Instant committed = head.committedAt() != null ? head.committedAt()
                    : seen != null ? seen.committedAt : now;
            String message = head.message() != null ? head.message() : seen == null ? null : seen.message;
            out.put(head.sha(), new VersionInfo(head.sha(), message, committed));
        }
        return out;
    }

    // ---- model rows --------------------------------------------------------------------------------------

    private ModelStats modelStats(Ctx ctx, EvaluationModel model, List<RunType> lanes, Instant now) {
        List<SkyRatingEvalRunEntity> completed = completedNewestFirst(ctx, model);
        List<SkyRatingEvalRunEntity> weekly = completed.stream()
                .filter(r -> r.getTriggerSource() == SkyRatingEvalTrigger.SCHEDULED).limit(WEEKLY_RUNS).toList();
        Map<String, Tally> weeklyPool = pool(ctx, weekly);
        Tally weeklyTotal = total(weeklyPool);

        Pair pair = ctx.head.sha() == null ? Pair.EMPTY : pair(ctx, completed, ctx.head.sha());
        ModelStatus status;
        if (!pair.hasAfter()) {
            status = ModelStatus.UNCHECKED;
        } else if (!pair.hasBefore()) {
            status = ModelStatus.NO_BASELINE;
        } else {
            status = pair.deltaPts() <= DROP_POINTS ? ModelStatus.DROPPED : ModelStatus.HELD;
        }

        Instant lastTested = completed.isEmpty() ? null : finishedAt(completed.getFirst());
        boolean fresh = lastTested != null && !lastTested.isBefore(now.minus(Duration.ofDays(FRESH_DAYS)));
        return new ModelStats(model, modelLabel(model), lanes, weeklyTotal.pct(), weeklyTotal.below,
                weeklyTotal.above, weeklyTotal.n, pair.afterPct(), pair.afterRuns(), pair.beforePct(),
                pair.deltaPts(), status, meanCost(ctx, weekly), costPerRating(ctx, completed),
                secondsPerRating(completed), lastTested, fresh);
    }

    private Double meanCost(Ctx ctx, List<SkyRatingEvalRunEntity> runs) {
        if (runs.isEmpty()) {
            return null;
        }
        return runs.stream().mapToDouble(r -> costGbp(ctx, r)).average().orElse(0.0);
    }

    private static List<SkyRatingEvalRunEntity> estimateRuns(List<SkyRatingEvalRunEntity> completed) {
        return completed.stream()
                .filter(r -> r.getTriggerSource() == SkyRatingEvalTrigger.MANUAL && r.getBatchId() == null
                        && r.getTotalRuns() > 0 && r.getDurationMs() != null)
                .limit(ESTIMATE_RUNS).toList();
    }

    private Double costPerRating(Ctx ctx, List<SkyRatingEvalRunEntity> completed) {
        List<SkyRatingEvalRunEntity> runs = estimateRuns(completed);
        long ratings = runs.stream().mapToLong(SkyRatingEvalRunEntity::getTotalRuns).sum();
        if (ratings == 0) {
            return null;
        }
        return runs.stream().mapToDouble(r -> costGbp(ctx, r)).sum() / ratings;
    }

    private static Double secondsPerRating(List<SkyRatingEvalRunEntity> completed) {
        List<SkyRatingEvalRunEntity> runs = estimateRuns(completed);
        long ratings = runs.stream().mapToLong(SkyRatingEvalRunEntity::getTotalRuns).sum();
        if (ratings == 0) {
            return null;
        }
        return runs.stream().mapToLong(SkyRatingEvalRunEntity::getDurationMs).sum() / MILLIS_PER_SECOND / ratings;
    }

    /**
     * A model's before and after on one version. After is every completed run of the model on the version; before
     * is its last {@value #BASELINE_RUNS} completed runs on the most recent earlier version it ran on. Both are
     * pooled over the fixtures both sides scored; with no before, after is pooled over all its fixtures.
     */
    private Pair pair(Ctx ctx, List<SkyRatingEvalRunEntity> completedNewestFirst, String sha) {
        List<SkyRatingEvalRunEntity> after = completedNewestFirst.stream()
                .filter(r -> sha.equals(versionOf(r))).toList();
        if (after.isEmpty()) {
            return Pair.EMPTY;
        }
        Map<String, Tally> afterPool = pool(ctx, after);
        Instant shaTime = ctx.versions.get(sha).committedAt;
        Optional<String> earlier = completedNewestFirst.stream().map(SkyRatingEvalOverviewService::versionOf)
                .filter(v -> v != null && !v.equals(sha) && ctx.versions.get(v).committedAt.isBefore(shaTime))
                .max(Comparator.comparing(v -> ctx.versions.get(v).committedAt));
        if (earlier.isEmpty()) {
            return new Pair(true, false, total(afterPool).pct(), null, null, after.size(), List.of());
        }
        List<SkyRatingEvalRunEntity> before = completedNewestFirst.stream()
                .filter(r -> earlier.get().equals(versionOf(r))).limit(BASELINE_RUNS).toList();
        Map<String, Tally> beforePool = pool(ctx, before);
        Set<String> common = new LinkedHashSet<>(afterPool.keySet());
        common.retainAll(beforePool.keySet());
        if (common.isEmpty()) {
            return new Pair(true, false, total(afterPool).pct(), null, null, after.size(), List.of());
        }
        Double afterPct = total(afterPool, common).pct();
        Double beforePct = total(beforePool, common).pct();
        return new Pair(true, true, afterPct, beforePct, afterPct - beforePct, after.size(),
                moved(common, beforePool, afterPool));
    }

    private static List<Moved> moved(Set<String> common, Map<String, Tally> before, Map<String, Tally> after) {
        List<Moved> moved = new ArrayList<>();
        for (String fixture : common) {
            Tally b = before.get(fixture);
            Tally a = after.get(fixture);
            if (b.n < MOVED_MIN_ATTEMPTS || a.n < MOVED_MIN_ATTEMPTS) {
                continue;
            }
            double beforePct = b.pct();
            double afterPct = a.pct();
            if (Math.abs(afterPct - beforePct) < MOVED_POINTS) {
                continue;
            }
            MissSide side = a.below > a.above ? MissSide.BELOW : a.above > a.below ? MissSide.ABOVE : null;
            moved.add(new Moved(fixture, beforePct, afterPct,
                    afterPct > beforePct ? Direction.BETTER : Direction.WORSE, side));
        }
        moved.sort(Comparator.comparingDouble((Moved m) -> Math.abs(m.afterPct() - m.beforePct())).reversed()
                .thenComparing(Moved::fixture));
        return moved;
    }

    // ---- lanes, verdict, comparisons ---------------------------------------------------------------------

    private List<Lane> lanes(Map<RunType, EvaluationModel> laneModels, Map<EvaluationModel, ModelStats> stats) {
        List<Lane> lanes = new ArrayList<>();
        for (Map.Entry<RunType, EvaluationModel> entry : laneModels.entrySet()) {
            boolean near = entry.getKey() == RunType.BATCH_NEAR_TERM;
            ModelStats own = stats.get(entry.getValue());
            List<Compared> compared = new ArrayList<>();
            stats.values().stream()
                    .filter(s -> s.model() != entry.getValue() && s.fresh() && s.last4WeeklyPct() != null)
                    .sorted(Comparator.comparing(ModelStats::last4WeeklyPct).reversed())
                    .forEach(s -> compared.add(new Compared(s.model(), s.lanes(), s.last4WeeklyPct(),
                            own.last4WeeklyPct() == null ? null : s.last4WeeklyPct() - own.last4WeeklyPct(),
                            costMultiple(s.costPerRunGbp(), own.costPerRunGbp()),
                            s.status() == ModelStatus.DROPPED ? s.onHeadPct() : null)));
            lanes.add(new Lane(entry.getKey(),
                    near ? "T and T+1 forecasts" : "T+2 and T+3 look-ahead",
                    near ? "Must be as accurate as possible"
                            : "Cheaper, needs to be accurate enough to plan with",
                    entry.getValue(), compared));
        }
        return lanes;
    }

    private static Double costMultiple(Double other, Double own) {
        return other == null || own == null || own <= 0.0 ? null : other / own;
    }

    private Verdict verdict(Ctx ctx, Map<RunType, EvaluationModel> laneModels,
            Map<EvaluationModel, ModelStats> stats) {
        List<EvaluationModel> production = laneModels.values().stream().distinct().toList();
        List<VerdictLine> lines = production.stream()
                .map(m -> new VerdictLine(m, modelLabel(m), stats.get(m).previousVersionPct(),
                        stats.get(m).onHeadPct()))
                .toList();
        boolean droppedNonProd = stats.values().stream()
                .anyMatch(s -> !production.contains(s.model()) && s.status() == ModelStatus.DROPPED);
        String sha = ctx.head.shortSha();
        if (ctx.head.sha() == null) {
            return new Verdict(VerdictKind.NO_HEAD, "This build has no version recorded.", null, lines, false);
        }
        for (EvaluationModel m : production) {
            if (stats.get(m).status() == ModelStatus.DROPPED) {
                return new Verdict(VerdictKind.DROPPED, sha + " made " + modelLabel(m) + " worse.", m, lines,
                        droppedNonProd);
            }
        }
        for (EvaluationModel m : production) {
            if (stats.get(m).status() == ModelStatus.UNCHECKED) {
                return new Verdict(VerdictKind.UNCHECKED,
                        sha + " hasn't been checked on " + modelLabel(m) + " yet.", m, lines, droppedNonProd);
            }
        }
        if (production.stream().noneMatch(m -> stats.get(m).status() == ModelStatus.HELD)) {
            return new Verdict(VerdictKind.NO_BASELINE, "Nothing to compare " + sha + " with yet.", null, lines,
                    droppedNonProd);
        }
        String headline = production.size() > 1 ? "Both production models held up on today's change."
                : "Production held up on today's change.";
        return new Verdict(VerdictKind.HELD, headline, null, lines, droppedNonProd);
    }

    private List<Comparison> comparisons(Ctx ctx, Map<RunType, EvaluationModel> laneModels,
            List<EvaluationModel> modelOrder) {
        Set<String> withRuns = ctx.runs.stream().filter(r -> r.getStatus() == SkyRatingEvalStatus.COMPLETED)
                .map(SkyRatingEvalOverviewService::versionOf).filter(v -> v != null)
                .collect(Collectors.toSet());
        List<String> shas = withRuns.stream()
                .filter(v -> withRuns.stream().anyMatch(o -> ctx.versions.get(o).committedAt
                        .isBefore(ctx.versions.get(v).committedAt)))
                .sorted(Comparator.comparing((String v) -> ctx.versions.get(v).committedAt).reversed())
                .limit(COMPARISON_VERSIONS).toList();
        List<EvaluationModel> ordered = new ArrayList<>(laneModels.values().stream().distinct().toList());
        modelOrder.stream().filter(m -> !ordered.contains(m)).forEach(ordered::add);

        List<Comparison> out = new ArrayList<>();
        for (String sha : shas) {
            List<ComparisonRow> rows = new ArrayList<>();
            for (EvaluationModel model : ordered) {
                Pair p = pair(ctx, completedNewestFirst(ctx, model), sha);
                rows.add(new ComparisonRow(model, lanesOf(model, laneModels), p.beforePct(), p.afterPct(),
                        p.deltaPts(), !p.hasAfter(), p.moved()));
            }
            out.add(new Comparison(versionServed(ctx, sha), rows));
        }
        return out;
    }

    // ---- sessions ----------------------------------------------------------------------------------------

    private List<Session> sessions(Ctx ctx) {
        List<SkyRatingEvalRunEntity> oldestFirst = ctx.runs.stream()
                .sorted(Comparator.comparing(SkyRatingEvalRunEntity::getStartedAt)
                        .thenComparing(SkyRatingEvalRunEntity::getId)).toList();
        Map<String, List<SkyRatingEvalRunEntity>> groups = new LinkedHashMap<>();
        Map<String, SkyRatingEvalRunEntity> lastInChain = new HashMap<>();
        Map<String, String> openGroup = new HashMap<>();
        for (SkyRatingEvalRunEntity run : oldestFirst) {
            String groupKey;
            if (run.getBatchId() != null) {
                groupKey = "batch:" + run.getBatchId();
            } else {
                String chain = run.getTriggerSource() + "|" + versionOf(run);
                SkyRatingEvalRunEntity previous = lastInChain.get(chain);
                boolean joins = previous != null && !run.getStartedAt()
                        .isAfter(previous.getStartedAt().plusMinutes(SESSION_GAP_MINUTES));
                groupKey = joins ? openGroup.get(chain) : "chain:" + run.getId();
                openGroup.put(chain, groupKey);
                lastInChain.put(chain, run);
            }
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(run);
        }
        return groups.values().stream().map(g -> session(ctx, g))
                .sorted(Comparator.comparing(Session::startedAt).reversed().thenComparing(
                        Comparator.comparingLong(Session::id).reversed()))
                .toList();
    }

    private Session session(Ctx ctx, List<SkyRatingEvalRunEntity> runs) {
        SkyRatingEvalRunEntity first = runs.getFirst();
        List<Run> views = runs.stream().map(r -> runView(ctx, r)).toList();
        SessionStatus status;
        if (runs.stream().anyMatch(r -> r.getStatus() == SkyRatingEvalStatus.RUNNING)) {
            status = SessionStatus.RUNNING;
        } else if (runs.stream().allMatch(r -> r.getStatus() == SkyRatingEvalStatus.FAILED)) {
            status = SessionStatus.FAILED;
        } else {
            status = SessionStatus.DONE;
        }
        Map<EvaluationModel, List<SkyRatingEvalRunEntity>> byModel = runs.stream()
                .collect(Collectors.groupingBy(SkyRatingEvalRunEntity::getModel, LinkedHashMap::new,
                        Collectors.toList()));
        List<ModelSession> models = byModel.entrySet().stream()
                .map(e -> new ModelSession(e.getKey(), e.getValue().size(),
                        total(pool(ctx, e.getValue())).pct()))
                .toList();
        return new Session(first.getId(), first.getTriggerSource(), versionOf(first),
                toInstant(first.getStartedAt()), status, views.stream().mapToDouble(Run::costGbp).sum(), views,
                models);
    }

    private Run runView(Ctx ctx, SkyRatingEvalRunEntity run) {
        Tally t = total(pool(ctx, List.of(run)));
        Double progress = null;
        long expected = (long) run.getRunsPerFixture() * run.getFixtureCount();
        if (run.getStatus() == SkyRatingEvalStatus.RUNNING && run.getBatchId() == null && expected > 0) {
            progress = Math.min(1.0, (double) t.n / expected);
        }
        return new Run(run.getId(), run.getModel(), toInstant(run.getStartedAt()), run.getFixtureCount(),
                run.getRunsPerFixture(), t.n, t.inBand, t.below, t.above, t.pct(), costGbp(ctx, run),
                run.getDurationMs(), run.getStatus(), run.getErrorMessage(), progress);
    }

    private CheckDefaults checkDefaults(Ctx ctx, Map<RunType, EvaluationModel> laneModels,
            List<Session> sessions) {
        List<String> missed = List.of();
        Optional<Session> latest = sessions.stream().filter(s -> s.status() == SessionStatus.DONE).findFirst();
        if (latest.isPresent()) {
            Set<String> names = new LinkedHashSet<>();
            for (Run run : latest.get().runs()) {
                Map<String, SkyRatingEvalFixtureAggregate> byFixture = ctx.aggregates.get(run.id());
                if (byFixture != null) {
                    byFixture.values().stream().filter(a -> a.below() + a.above() > 0)
                            .forEach(a -> names.add(a.fixtureName()));
                }
            }
            missed = SkyRatingEvalFixtures.ALL.stream().map(SkyRatingEvalFixture::name)
                    .filter(names::contains).toList();
        }
        return new CheckDefaults(laneModels.values().stream().distinct().toList(), DEFAULT_CHECK_RUNS, missed);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    private List<Fixture> fixtures(Ctx ctx) {
        return SkyRatingEvalFixtures.ALL.stream()
                .map(f -> new Fixture(f.name(), f.band().min(), f.band().max(),
                        toInstant(ctx.firstSeen.get(f.name()))))
                .toList();
    }

    private List<RunFixture> runFixtures(Ctx ctx, List<EvaluationModel> models) {
        Set<SkyRatingEvalRunEntity> chosen = new LinkedHashSet<>();
        for (EvaluationModel model : models) {
            chosen.addAll(completedNewestFirst(ctx, model).stream().limit(GRID_RUNS).toList());
        }
        List<RunFixture> out = new ArrayList<>();
        chosen.stream().sorted(Comparator.comparing(SkyRatingEvalRunEntity::getStartedAt)
                .thenComparing(SkyRatingEvalRunEntity::getId)).forEach(run -> {
                    Map<String, SkyRatingEvalFixtureAggregate> byFixture = ctx.aggregates.get(run.getId());
                    if (byFixture == null) {
                        return;
                    }
                    for (SkyRatingEvalFixture f : SkyRatingEvalFixtures.ALL) {
                        SkyRatingEvalFixtureAggregate a = byFixture.get(f.name());
                        if (a != null) {
                            Double mean = a.ratedCount() == 0 || a.ratingSum() == null ? null
                                    : (double) a.ratingSum() / a.ratedCount();
                            out.add(new RunFixture(run.getId(), f.name(), a.attempts(), a.inBand(), a.below(),
                                    a.above(), mean, a.minRating(), a.maxRating()));
                        }
                    }
                });
        return out;
    }

    private List<ModelFixture> modelFixtures(Ctx ctx, List<EvaluationModel> models) {
        List<ModelFixture> out = new ArrayList<>();
        for (EvaluationModel model : models) {
            Map<String, Tally> pool = pool(ctx,
                    completedNewestFirst(ctx, model).stream().limit(FIXTURE_MISS_RUNS).toList());
            for (SkyRatingEvalFixture f : SkyRatingEvalFixtures.ALL) {
                Tally t = pool.get(f.name());
                if (t != null && t.n > 0) {
                    out.add(new ModelFixture(model, f.name(), t.below + t.above, t.pct()));
                }
            }
        }
        return out;
    }

    // ---- versions ----------------------------------------------------------------------------------------

    private List<Version> versionsServed(Ctx ctx) {
        return ctx.versions.keySet().stream()
                .sorted(Comparator.comparing((String v) -> ctx.versions.get(v).committedAt).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .map(v -> versionServed(ctx, v)).toList();
    }

    private Version versionServed(Ctx ctx, String sha) {
        VersionInfo info = ctx.versions.get(sha);
        Instant committed = sha.equals(ctx.head.sha()) ? ctx.head.committedAt() : info.committedAt;
        return new Version(sha, shortSha(sha), info.message, committed == null ? info.committedAt : committed);
    }

    // ---- schedule ----------------------------------------------------------------------------------------

    private Schedule schedule(Instant now) {
        Optional<SchedulerJobConfigEntity> job = schedulerJobConfigRepository
                .findByJobKey(SkyRatingEvalService.JOB_KEY);
        List<EvaluationModel> models = evalService.scheduledModels();
        if (job.isEmpty()) {
            return new Schedule(NO_SCHEDULE_LABEL, null, models);
        }
        SchedulerJobConfigEntity config = job.get();
        SchedulerJobStatus status = config.getStatus();
        String label = status == SchedulerJobStatus.ACTIVE ? weeklyLabel(config, now) : PAUSED_LABEL;
        return new Schedule(label, status, models);
    }

    /** {@code weekly, Mon 04:00} in UK time for a single weekly cron; the raw cron for anything else. */
    private String weeklyLabel(SchedulerJobConfigEntity config, Instant now) {
        String cron = config.getCronExpression();
        if (cron == null || !WEEKLY_CRON.matcher(cron.trim()).matches()) {
            return cron == null ? NO_SCHEDULE_LABEL : cron;
        }
        try {
            ZoneId zone = DynamicSchedulerService.cronZone(config);
            ZonedDateTime next = CronExpression.parse(cron.trim()).next(ZonedDateTime.ofInstant(now, zone));
            if (next == null) {
                return cron;
            }
            ZonedDateTime uk = next.withZoneSameInstant(UK);
            return String.format(Locale.UK, "weekly, %s %02d:%02d",
                    uk.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.UK), uk.getHour(), uk.getMinute());
        } catch (IllegalArgumentException e) {
            LOG.warn("Sky-rating eval cron '{}' could not be rendered: {}", cron, e.getMessage());
            return cron;
        }
    }

    // ---- shared helpers ----------------------------------------------------------------------------------

    private static List<RunType> lanesOf(EvaluationModel model, Map<RunType, EvaluationModel> laneModels) {
        return laneModels.entrySet().stream().filter(e -> e.getValue() == model).map(Map.Entry::getKey).toList();
    }

    private static List<SkyRatingEvalRunEntity> completedNewestFirst(Ctx ctx, EvaluationModel model) {
        return ctx.runs.stream()
                .filter(r -> r.getModel() == model && r.getStatus() == SkyRatingEvalStatus.COMPLETED)
                .sorted(Comparator.comparing(SkyRatingEvalRunEntity::getStartedAt).reversed()
                        .thenComparing(Comparator.comparing(SkyRatingEvalRunEntity::getId).reversed()))
                .toList();
    }

    private static Instant finishedAt(SkyRatingEvalRunEntity run) {
        return toInstant(run.getCompletedAt() != null ? run.getCompletedAt() : run.getStartedAt());
    }

    private double costGbp(Ctx ctx, SkyRatingEvalRunEntity run) {
        LocalDate date = run.getStartedAt().toLocalDate();
        double rate = ctx.rates.computeIfAbsent(date, exchangeRateService::getRate);
        return run.getCostMicroDollars() / MICRO * rate;
    }

    /** Sums the runs' per-fixture tallies (current fixtures only). */
    private static Map<String, Tally> pool(Ctx ctx, List<SkyRatingEvalRunEntity> runs) {
        Map<String, Tally> out = new LinkedHashMap<>();
        for (SkyRatingEvalRunEntity run : runs) {
            Map<String, SkyRatingEvalFixtureAggregate> byFixture = ctx.aggregates.get(run.getId());
            if (byFixture == null) {
                continue;
            }
            byFixture.forEach((fixture, a) -> out.merge(fixture,
                    new Tally(a.attempts(), a.inBand(), a.below(), a.above()), Tally::plus));
        }
        out.values().removeIf(t -> t.n == 0);
        return out;
    }

    private static Tally total(Map<String, Tally> pool) {
        return pool.values().stream().reduce(Tally.ZERO, Tally::plus);
    }

    private static Tally total(Map<String, Tally> pool, Set<String> only) {
        return pool.entrySet().stream().filter(e -> only.contains(e.getKey())).map(Map.Entry::getValue)
                .reduce(Tally.ZERO, Tally::plus);
    }

    private static String versionOf(SkyRatingEvalRunEntity run) {
        return blankToNull(run.getGitCommitHash());
    }

    private static String shortSha(String sha) {
        return sha.length() > SHORT_SHA_LENGTH ? sha.substring(0, SHORT_SHA_LENGTH) : sha;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static String modelLabel(EvaluationModel model) {
        return switch (model) {
            case HAIKU -> "Haiku 4.5";
            case HAIKU_55 -> "Haiku 5.5";
            case SONNET -> "Sonnet 4.6";
            case SONNET_ET -> "Sonnet 4.6 (ET)";
            case SONNET_55 -> "Sonnet 5.5";
            case OPUS -> "Opus";
            case OPUS_ET -> "Opus (ET)";
            case WILDLIFE -> "Wildlife";
        };
    }

    /** Attempts and outcomes for some set of results. */
    private record Tally(long n, long inBand, long below, long above) {
        static final Tally ZERO = new Tally(0, 0, 0, 0);

        Tally plus(Tally o) {
            return new Tally(n + o.n, inBand + o.inBand, below + o.below, above + o.above);
        }

        /** In band over attempts as a percentage, or null with no attempts. */
        Double pct() {
            return n == 0 ? null : inBand * PERCENT / n;
        }
    }

    /** Before and after for one model on one version. */
    private record Pair(boolean hasAfter, boolean hasBefore, Double afterPct, Double beforePct, Double deltaPts,
            int afterRuns, List<Moved> moved) {
        static final Pair EMPTY = new Pair(false, false, null, null, null, 0, List.of());
    }

    /** A version's date and message, as best the runs and the build know them. */
    private record VersionInfo(String sha, String message, Instant committedAt) {
    }

    /** Everything one request reads, so each query runs once. */
    private static final class Ctx {
        final List<SkyRatingEvalRunEntity> runs;
        final Map<Long, Map<String, SkyRatingEvalFixtureAggregate>> aggregates;
        final Map<String, LocalDateTime> firstSeen;
        final Version head;
        final Map<String, VersionInfo> versions = new HashMap<>();
        final Map<LocalDate, Double> rates = new HashMap<>();

        Ctx(List<SkyRatingEvalRunEntity> runs, Map<Long, Map<String, SkyRatingEvalFixtureAggregate>> aggregates,
                Map<String, LocalDateTime> firstSeen, Version head) {
            this.runs = runs;
            this.aggregates = aggregates;
            this.firstSeen = firstSeen;
            this.head = head;
        }
    }
}
