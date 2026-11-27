package com.gregochr.goldenhour.service.ci;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.ArtifactDownloadException;
import com.gregochr.goldenhour.client.GitHubActionsClient;
import com.gregochr.goldenhour.client.GitHubArtifactDownloader;
import com.gregochr.goldenhour.config.CiSpendProperties;
import com.gregochr.goldenhour.entity.CiSpendAttemptEntity;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.CiSpendAttemptRepository;
import com.gregochr.goldenhour.repository.CiSpendRunRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ExchangeRateService;
import com.gregochr.goldenhour.service.SchedulerFireOutcome;
import com.gregochr.goldenhour.util.SecretRedactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls GitHub Actions for the token-count artifacts the paid CI prompt-regression suites upload, prices them with
 * {@link CostCalculator} and stores them in {@code ci_spend_run} (docs/engineering/ci-spend-plan.md, C2).
 *
 * <p>Runs on the {@code ci_spend_poll} scheduler row and self-gates on {@code photocast.ci-spend.enabled} plus a
 * token. One poll: reprices {@code UNPRICED} rows whose models now resolve; lists the workflow's runs from the
 * look-back window (no status filter); then, for each attempt {@code 1..run_attempt} with no
 * {@code ci_spend_attempt} row, reads the attempt and its jobs, decides which suites actually ran in <em>that</em>
 * attempt, and for each fetches, validates, prices and stores its artifact in its own {@code REQUIRES_NEW}
 * transaction. An attempt is settled (never read again) once every suite that ran holds a {@code KNOWN} or
 * {@code UNPRICED} row. A {@code UNKNOWN} row (the suite ran but no artifact exists) leaves the attempt unsettled,
 * so a later artifact can replace it; the look-back window and the call budget bound that retrying.
 *
 * <p>A suite ran in attempt {@code n} only when its job (exact name) has {@code run_attempt == n} (a job carried
 * over by "Re-run failed jobs" does not count), is not {@code skipped}, and its paid Maven step has a non-null
 * {@code started_at}. A job cancelled while queued or before Maven spent nothing and gets no row.
 *
 * <p>The artifact is attacker-influenced, so everything identifying a run (event, PR number and title, SHA, URL,
 * times, conclusion) comes from the GitHub API, never the file. The poller writes no {@code job_run} and no
 * {@code api_call_log}. The token is never logged; exception text passes through {@link SecretRedactor}, and the
 * blob URL (a signature) is never logged, only its host.
 */
@Component
public class CiSpendPollService {

    private static final Logger LOG = LoggerFactory.getLogger(CiSpendPollService.class);

    /** The scheduler row this job runs on. */
    public static final String JOB_KEY = "ci_spend_poll";

    /** The sky suite's key. */
    public static final String SUITE_SKY = "sky";

    /** The ask suite's key. */
    public static final String SUITE_ASK = "ask";

    /** Exact job name of the sky suite (em dash, U+2014). */
    static final String SKY_JOB_NAME = "Prompt regression — sky";

    /** Exact job name of the ask suite (em dash, U+2014). */
    static final String ASK_JOB_NAME = "Prompt regression — ask";

    /** Exact name of the sky suite's paid Maven step. */
    static final String SKY_STEP_NAME = "Sky rating and token-count tests";

    /** Exact name of the ask suite's paid Maven step. */
    static final String ASK_STEP_NAME = "Ask regression tests";

    /** The most bytes an artifact zip may be, checked against {@code size_in_bytes} and the stream. */
    static final int MAX_ARTIFACT_BYTES = 1024 * 1024;

    /** GitHub's page size for the runs and jobs listings. */
    static final int PAGE_SIZE = 100;

    /** The most pages of runs one poll reads. */
    static final int MAX_RUN_PAGES = 10;

    /** The longest stored pull request title. */
    static final int MAX_TITLE_LENGTH = 300;

    private static final String EVENT_PULL_REQUEST = "pull_request";
    private static final String STATUS_COMPLETED = "completed";
    private static final String CONCLUSION_SKIPPED = "skipped";
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNPROCESSABLE = 422;
    private static final int MAX_LOGGED_MESSAGE = 200;

    /** The suites in stack order, each with the exact names that identify its job and paid step. */
    private static final Map<String, SuiteSpec> SUITES = Map.of(
            SUITE_SKY, new SuiteSpec(SKY_JOB_NAME, SKY_STEP_NAME),
            SUITE_ASK, new SuiteSpec(ASK_JOB_NAME, ASK_STEP_NAME));

    private static final List<String> SUITE_ORDER = List.of(SUITE_SKY, SUITE_ASK);

    private final GitHubActionsClient client;
    private final GitHubArtifactDownloader downloader;
    private final CiUsageArtifactReader reader;
    private final CiSpendProperties properties;
    private final CiSpendRunRepository runRepository;
    private final CiSpendAttemptRepository attemptRepository;
    private final CostCalculator costCalculator;
    private final ExchangeRateService exchangeRateService;
    private final DynamicSchedulerService scheduler;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /** Guards against overlap: the schedule, Run Now and a reschedule can all call {@link #poll()}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates the service.
     *
     * @param client              the GitHub Actions client
     * @param downloader          fetches the signed blob URL without credentials
     * @param reader              validates a downloaded artifact
     * @param properties          CI-spend configuration
     * @param runRepository       the {@code ci_spend_run} store
     * @param attemptRepository   the {@code ci_spend_attempt} store
     * @param costCalculator      the one price table
     * @param exchangeRateService the GBP-per-USD rates
     * @param scheduler           the dynamic scheduler the job registers with
     * @param mapper              a Jackson mapper for the GitHub JSON
     * @param clock               the real clock (the poller is never rewound)
     * @param transactionManager  the transaction manager, for one transaction per stored suite
     */
    public CiSpendPollService(GitHubActionsClient client, GitHubArtifactDownloader downloader,
            CiUsageArtifactReader reader, CiSpendProperties properties, CiSpendRunRepository runRepository,
            CiSpendAttemptRepository attemptRepository, CostCalculator costCalculator,
            ExchangeRateService exchangeRateService, DynamicSchedulerService scheduler, ObjectMapper mapper,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.client = client;
        this.downloader = downloader;
        this.reader = reader;
        this.properties = properties;
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.costCalculator = costCalculator;
        this.exchangeRateService = exchangeRateService;
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Registers the job with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        scheduler.registerJobTarget(JOB_KEY, this::poll);
    }

    /**
     * The scheduler's target: one poll, unless the feature is off or a poll is already running.
     */
    public void poll() {
        if (!properties.isActive()) {
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED,
                    "CI spend polling is switched off or has no GitHub token");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            LOG.warn("CI spend poll skipped: a poll is already running");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Already running, skipped");
            return;
        }
        try {
            runPoll();
        } finally {
            running.set(false);
        }
    }

    private void runPoll() {
        Poll poll = new Poll(properties.getMaxCallsPerPoll());
        try {
            repriceUnpriced(poll);
            for (JsonNode run : listRuns(poll)) {
                processRun(run, poll);
            }
            finish(poll, false);
        } catch (BudgetExhausted e) {
            finish(poll, true);
        } catch (RestClientException | PollAbort e) {
            String why = describe(e);
            LOG.warn("CI spend poll ended early: {}", why);
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, summary(poll, "ended early: " + why));
        }
    }

    private void finish(Poll poll, boolean budgetHit) {
        String note = budgetHit ? "stopped at the call budget, the next poll resumes" : null;
        String summary = summary(poll, note);
        LOG.info("CI spend poll: {}", summary);
        SchedulerFireOutcome.report(poll.invalid > 0 ? SchedulerFireStatus.PARTIAL : SchedulerFireStatus.SUCCEEDED,
                summary);
    }

    private static String summary(Poll poll, String note) {
        String text = poll.stored + " stored, " + poll.upgraded + " upgraded, " + poll.repriced + " repriced, "
                + poll.invalid + " rejected, " + poll.calls + " calls" + (note == null ? "" : ", " + note);
        return text.length() > MAX_LOGGED_MESSAGE ? text.substring(0, MAX_LOGGED_MESSAGE) : text;
    }

    // ---- listing ----------------------------------------------------------------------------------------------

    private List<JsonNode> listRuns(Poll poll) {
        LocalDate since = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(properties.getLookbackDays());
        String created = ">=" + since;
        List<JsonNode> runs = new ArrayList<>();
        for (int page = 1; page <= MAX_RUN_PAGES; page++) {
            poll.spend();
            JsonNode root = parse(client.listWorkflowRuns(properties.owner(), properties.repoName(),
                    properties.getWorkflowFile(), created, PAGE_SIZE, page));
            JsonNode items = root.path("workflow_runs");
            items.forEach(runs::add);
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        return runs;
    }

    private void processRun(JsonNode run, Poll poll) {
        long runId = run.path("id").asLong();
        int latestAttempt = Math.max(1, run.path("run_attempt").asInt(1));
        for (int attempt = 1; attempt <= latestAttempt; attempt++) {
            if (attemptRepository.existsById(new CiSpendAttemptEntity.Key(runId, attempt))) {
                continue;
            }
            processAttempt(runId, attempt, poll);
        }
    }

    private void processAttempt(long runId, int attempt, Poll poll) {
        poll.spend();
        JsonNode attemptJson = parse(client.getRunAttempt(properties.owner(), properties.repoName(), runId, attempt));
        if (!STATUS_COMPLETED.equals(attemptJson.path("status").asText())) {
            return;
        }
        poll.spend();
        JsonNode jobs = parse(client.listAttemptJobs(properties.owner(), properties.repoName(), runId, attempt,
                PAGE_SIZE)).path("jobs");
        Map<String, RanJob> ran = new LinkedHashMap<>();
        for (String suite : SUITE_ORDER) {
            RanJob job = ranJob(jobs, SUITES.get(suite), attempt);
            if (job != null) {
                ran.put(suite, job);
            }
        }
        if (ran.isEmpty()) {
            settle(runId, attempt, "");
            return;
        }
        IdentityMemo identity = new IdentityMemo(attemptJson, runId, attempt, poll);
        boolean allSettled = true;
        for (Map.Entry<String, RanJob> e : ran.entrySet()) {
            Outcome outcome = handleSuite(runId, attempt, e.getKey(), e.getValue(), identity, poll);
            allSettled &= outcome == Outcome.SETTLED;
        }
        if (allSettled) {
            settle(runId, attempt, String.join(",", ran.keySet()));
        }
    }

    /** The suite's job when it ran in this attempt, with its start time, or null. */
    private static RanJob ranJob(JsonNode jobs, SuiteSpec spec, int attempt) {
        for (JsonNode job : jobs) {
            if (!spec.jobName().equals(job.path("name").asText())
                    || job.path("run_attempt").asInt(-1) != attempt
                    || CONCLUSION_SKIPPED.equals(job.path("conclusion").asText())) {
                continue;
            }
            for (JsonNode step : job.path("steps")) {
                Instant stepStart = instant(step, "started_at");
                if (spec.stepName().equals(step.path("name").asText()) && stepStart != null) {
                    Instant jobStart = instant(job, "started_at");
                    return new RanJob(job, jobStart != null ? jobStart : stepStart);
                }
            }
        }
        return null;
    }

    // ---- one suite --------------------------------------------------------------------------------------------

    private Outcome handleSuite(long runId, int attempt, String suite, RanJob job, IdentityMemo identity,
            Poll poll) {
        Optional<CiSpendRunEntity> existing = runRepository.findByGithubRunIdAndRunAttemptAndSuite(runId, attempt,
                suite);
        if (existing.isPresent() && !CiSpendRunEntity.STATE_UNKNOWN.equals(existing.get().getUsageState())) {
            return Outcome.SETTLED;
        }
        JsonNode artifact = findArtifact(runId, attempt, suite, poll);
        if (artifact == null) {
            if (existing.isEmpty()) {
                store(runId, attempt, suite, job, identity.get(), null, poll);
            }
            return Outcome.UNKNOWN;
        }
        if (artifact.path("size_in_bytes").asLong() > MAX_ARTIFACT_BYTES) {
            return reject(runId, attempt, suite, "the artifact is larger than " + MAX_ARTIFACT_BYTES + " bytes", poll);
        }
        CiUsageArtifactReader.Usage usage;
        try {
            usage = fetchUsage(runId, attempt, suite, artifact.path("id").asLong(), poll);
        } catch (InvalidArtifactException | ArtifactDownloadException e) {
            return reject(runId, attempt, suite, e.getMessage(), poll);
        }
        return store(runId, attempt, suite, job, identity.get(), usage, poll);
    }

    private Outcome reject(long runId, int attempt, String suite, String reason, Poll poll) {
        LOG.warn("CI usage artifact rejected: run {} attempt {} suite {}: {}", runId, attempt, suite, reason);
        poll.invalid++;
        return Outcome.INVALID;
    }

    /** The newest non-expired artifact with the exact name, or null when none exists or all have expired. */
    private JsonNode findArtifact(long runId, int attempt, String suite, Poll poll) {
        String name = "prompt-regression-usage-" + suite + "-" + attempt;
        poll.spend();
        JsonNode artifacts = parse(client.listRunArtifacts(properties.owner(), properties.repoName(), runId, name,
                PAGE_SIZE)).path("artifacts");
        JsonNode best = null;
        for (JsonNode a : artifacts) {
            if (name.equals(a.path("name").asText()) && !a.path("expired").asBoolean(false)
                    && (best == null || a.path("id").asLong() > best.path("id").asLong())) {
                best = a;
            }
        }
        return best;
    }

    private CiUsageArtifactReader.Usage fetchUsage(long runId, int attempt, String suite, long artifactId, Poll poll)
            throws InvalidArtifactException, ArtifactDownloadException {
        poll.spend();
        ResponseEntity<Void> redirect = client.getArtifactZip(properties.owner(), properties.repoName(), artifactId);
        String location = redirect.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (!redirect.getStatusCode().is3xxRedirection() || location == null) {
            throw new InvalidArtifactException("GitHub gave no redirect to the artifact");
        }
        poll.spend();
        byte[] zip = downloader.download(location, MAX_ARTIFACT_BYTES);
        return reader.read(zip, suite, runId, attempt);
    }

    // ---- identity ---------------------------------------------------------------------------------------------

    /** What GitHub says identifies the run, built lazily so a suite that needs no write costs no extra call. */
    private final class IdentityMemo {
        private final JsonNode attemptJson;
        private final long runId;
        private final int attempt;
        private final Poll poll;
        private Identity memo;

        IdentityMemo(JsonNode attemptJson, long runId, int attempt, Poll poll) {
            this.attemptJson = attemptJson;
            this.runId = runId;
            this.attempt = attempt;
            this.poll = poll;
        }

        Identity get() {
            if (memo == null) {
                memo = identityOf(attemptJson, runId, attempt, poll);
            }
            return memo;
        }
    }

    private Identity identityOf(JsonNode attemptJson, long runId, int attempt, Poll poll) {
        String event = text(attemptJson, "event");
        String sha = text(attemptJson, "head_sha");
        boolean pullRequest = EVENT_PULL_REQUEST.equals(event);
        Integer prNumber = null;
        JsonNode prs = attemptJson.path("pull_requests");
        if (prs.isArray() && !prs.isEmpty() && prs.get(0).path("number").asInt() > 0) {
            prNumber = prs.get(0).path("number").asInt();
        } else if (pullRequest && sha != null) {
            prNumber = pullNumberForCommit(sha, poll);
        }
        String title = pullRequest ? truncate(text(attemptJson, "display_title"), MAX_TITLE_LENGTH) : null;
        String url = "https://github.com/" + properties.getRepository() + "/actions/runs/" + runId + "/attempts/"
                + attempt;
        return new Identity(event, sha, prNumber, title, url);
    }

    private Integer pullNumberForCommit(String sha, Poll poll) {
        poll.spend();
        try {
            JsonNode pulls = parse(client.listCommitPulls(properties.owner(), properties.repoName(), sha));
            if (pulls.isArray() && !pulls.isEmpty() && pulls.get(0).path("number").asInt() > 0) {
                return pulls.get(0).path("number").asInt();
            }
            return null;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HTTP_NOT_FOUND || e.getStatusCode().value() == HTTP_UNPROCESSABLE) {
                return null;
            }
            throw e;
        }
    }

    // ---- storing ----------------------------------------------------------------------------------------------

    /**
     * Stores one suite in its own {@code REQUIRES_NEW} transaction: insert when absent, replace when the stored row
     * is {@code UNKNOWN}, never rewrite {@code KNOWN} or {@code UNPRICED}. A null {@code usage} stores an
     * {@code UNKNOWN} row. A unique-key violation is caught here, outside the transaction, and read as already stored.
     */
    private Outcome store(long runId, int attempt, String suite, RanJob job, Identity identity,
            CiUsageArtifactReader.Usage usage, Poll poll) {
        try {
            return transactions.execute(status -> write(runId, attempt, suite, job, identity, usage, poll));
        } catch (DataIntegrityViolationException e) {
            LOG.info("CI spend run {} attempt {} suite {} was stored concurrently; treating it as stored", runId,
                    attempt, suite);
            return storedOutcome(runId, attempt, suite);
        }
    }

    private Outcome storedOutcome(long runId, int attempt, String suite) {
        return runRepository.findByGithubRunIdAndRunAttemptAndSuite(runId, attempt, suite)
                .filter(r -> !CiSpendRunEntity.STATE_UNKNOWN.equals(r.getUsageState()))
                .map(r -> Outcome.SETTLED)
                .orElse(Outcome.UNKNOWN);
    }

    private Outcome write(long runId, int attempt, String suite, RanJob ran, Identity identity,
            CiUsageArtifactReader.Usage usage, Poll poll) {
        JsonNode job = ran.job();
        Optional<CiSpendRunEntity> locked = runRepository.lockByKey(runId, attempt, suite);
        if (locked.isPresent() && !CiSpendRunEntity.STATE_UNKNOWN.equals(locked.get().getUsageState())) {
            return Outcome.SETTLED;
        }
        if (locked.isPresent()) {
            poll.upgraded++;
        } else {
            poll.stored++;
        }
        CiSpendRunEntity row = locked.orElseGet(CiSpendRunEntity::new);
        row.setGithubRunId(runId);
        row.setRunAttempt(attempt);
        row.setSuite(suite);
        row.setEvent(identity.event());
        row.setHeadSha(truncate(identity.headSha(), 40));
        row.setPrNumber(identity.prNumber());
        row.setPrTitle(identity.prTitle());
        row.setHtmlUrl(identity.htmlUrl());
        row.setStartedAt(ran.startedAt());
        row.setFinishedAt(instant(job, "completed_at"));
        row.setConclusion(truncate(text(job, "conclusion"), 32));
        row.setFetchedAt(clock.instant());
        if (usage == null) {
            row.setUsageState(CiSpendRunEntity.STATE_UNKNOWN);
            row.setCases(null);
            row.setFailed(null);
            row.setErroredCalls(0);
            row.setCostMicroDollars(null);
            row.setExchangeRateGbpPerUsd(null);
            row.getModels().clear();
            runRepository.save(row);
            return Outcome.UNKNOWN;
        }
        row.setCases(usage.cases());
        row.setFailed(usage.failed());
        applyPricing(row, usage.models().stream().map(CiSpendPollService::tokensOf).toList());
        runRepository.save(row);
        return Outcome.SETTLED;
    }

    private void settle(long runId, int attempt, String suitesRan) {
        try {
            transactions.executeWithoutResult(status -> attemptRepository.save(
                    new CiSpendAttemptEntity(runId, attempt, clock.instant(), suitesRan)));
        } catch (DataIntegrityViolationException e) {
            LOG.info("CI spend attempt {}/{} was settled concurrently", runId, attempt);
        }
    }

    // ---- pricing ----------------------------------------------------------------------------------------------

    /**
     * Prices the row from its model tokens and replaces its model rows: {@code KNOWN} with a cost, the rate snapshot
     * and the exchange rate when every model resolves with {@link EvaluationModel#fromModelId}; otherwise
     * {@code UNPRICED} with the tokens only and a null cost.
     */
    private void applyPricing(CiSpendRunEntity row, List<ModelTokens> tokens) {
        List<Optional<EvaluationModel>> resolved = tokens.stream()
                .map(t -> EvaluationModel.fromModelId(t.model())).toList();
        boolean priceable = resolved.stream().allMatch(Optional::isPresent);
        List<CiSpendModelUsageEntity> rows = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < tokens.size(); i++) {
            ModelTokens t = tokens.get(i);
            CiSpendModelUsageEntity usageRow = usageRow(t);
            if (priceable) {
                EvaluationModel model = resolved.get(i).get();
                CostCalculator.ModelRates rates = costCalculator.ratesFor(model);
                usageRow.setInputUsdPerMtok(rates.inputUsdPerMtok());
                usageRow.setOutputUsdPerMtok(rates.outputUsdPerMtok());
                usageRow.setCacheWriteUsdPerMtok(rates.cacheWriteUsdPerMtok());
                usageRow.setCacheWrite1hUsdPerMtok(rates.cacheWrite1hUsdPerMtok());
                usageRow.setCacheReadUsdPerMtok(rates.cacheReadUsdPerMtok());
                long cost = costCalculator.calculateCostMicroDollars(model, new TokenUsage(t.input(), t.output(),
                        t.cacheWrite(), t.cacheRead(), t.cacheWrite1h()), false);
                usageRow.setCostMicroDollars(cost);
                total += cost;
            }
            rows.add(usageRow);
        }
        row.setErroredCalls(tokens.stream().mapToInt(ModelTokens::erroredCalls).sum());
        row.getModels().clear();
        row.getModels().addAll(rows);
        if (priceable) {
            row.setUsageState(CiSpendRunEntity.STATE_KNOWN);
            row.setCostMicroDollars(total);
            row.setExchangeRateGbpPerUsd(exchangeRateService.getRate(
                    row.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate()));
        } else {
            row.setUsageState(CiSpendRunEntity.STATE_UNPRICED);
            row.setCostMicroDollars(null);
            row.setExchangeRateGbpPerUsd(null);
        }
    }

    private static CiSpendModelUsageEntity usageRow(ModelTokens t) {
        CiSpendModelUsageEntity row = new CiSpendModelUsageEntity();
        row.setModel(t.model());
        row.setCalls(t.calls());
        row.setErroredCalls(t.erroredCalls());
        row.setInputTokens(t.input());
        row.setOutputTokens(t.output());
        row.setCacheReadTokens(t.cacheRead());
        row.setCacheWriteTokens(t.cacheWrite());
        row.setCacheWrite1hTokens(t.cacheWrite1h());
        return row;
    }

    private static ModelTokens tokensOf(CiUsageArtifactReader.ModelUsage m) {
        return new ModelTokens(m.model(), m.calls(), m.erroredCalls(), m.input(), m.output(), m.cacheRead(),
                m.cacheWrite(), m.cacheWrite1h());
    }

    private static ModelTokens tokensOf(CiSpendModelUsageEntity m) {
        return new ModelTokens(m.getModel(), m.getCalls(), m.getErroredCalls(), m.getInputTokens(),
                m.getOutputTokens(), m.getCacheReadTokens(), m.getCacheWriteTokens(), m.getCacheWrite1hTokens());
    }

    /**
     * Step 8: reprices every {@code UNPRICED} row whose models now all resolve, from its stored tokens, with no
     * GitHub call. A row that still has an unknown model is left as it is.
     */
    private void repriceUnpriced(Poll poll) {
        List<Long> ids = runRepository.findByUsageStateOrderByStartedAtAsc(CiSpendRunEntity.STATE_UNPRICED).stream()
                .map(CiSpendRunEntity::getId).toList();
        for (Long id : ids) {
            Boolean repriced = transactions.execute(status -> repriceOne(id));
            if (Boolean.TRUE.equals(repriced)) {
                poll.repriced++;
            }
        }
    }

    private Boolean repriceOne(Long id) {
        Optional<CiSpendRunEntity> found = runRepository.findById(id);
        if (found.isEmpty() || !CiSpendRunEntity.STATE_UNPRICED.equals(found.get().getUsageState())) {
            return false;
        }
        CiSpendRunEntity row = found.get();
        List<ModelTokens> tokens = row.getModels().stream().map(CiSpendPollService::tokensOf).toList();
        if (!tokens.stream().allMatch(t -> EvaluationModel.fromModelId(t.model()).isPresent())) {
            return false;
        }
        applyPricing(row, tokens);
        row.setFetchedAt(clock.instant());
        runRepository.save(row);
        return true;
    }

    // ---- small helpers ----------------------------------------------------------------------------------------

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PollAbort("GitHub returned a body that is not JSON");
        }
    }

    private static Instant instant(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException r) {
            return "HTTP " + r.getStatusCode().value();
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String clean = SecretRedactor.redactKeys(message).replaceAll("[\\r\\n]+", " ");
        return truncate(clean, MAX_LOGGED_MESSAGE);
    }

    // ---- value types ------------------------------------------------------------------------------------------

    private enum Outcome { SETTLED, UNKNOWN, INVALID }

    private record SuiteSpec(String jobName, String stepName) {
    }

    private record RanJob(JsonNode job, Instant startedAt) {
    }

    private record Identity(String event, String headSha, Integer prNumber, String prTitle, String htmlUrl) {
    }

    private record ModelTokens(String model, int calls, int erroredCalls, long input, long output, long cacheRead,
            long cacheWrite, long cacheWrite1h) {
    }

    /** The per-poll call budget and tallies. */
    private static final class Poll {
        private final int maxCalls;
        private int calls;
        private int stored;
        private int upgraded;
        private int repriced;
        private int invalid;

        Poll(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        void spend() {
            if (calls >= maxCalls) {
                throw new BudgetExhausted();
            }
            calls++;
        }
    }

    /** Thrown when the call budget is spent; ends the poll cleanly. */
    private static final class BudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhausted() {
            super("call budget reached", null, false, false);
        }
    }

    /** Thrown when GitHub's answer cannot be used at all; ends the poll with a warning. */
    private static final class PollAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PollAbort(String message) {
            super(message);
        }
    }
}
