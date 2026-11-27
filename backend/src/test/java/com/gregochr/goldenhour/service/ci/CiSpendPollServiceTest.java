package com.gregochr.goldenhour.service.ci;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.ArtifactDownloadException;
import com.gregochr.goldenhour.client.GitHubActionsClient;
import com.gregochr.goldenhour.client.GitHubArtifactDownloader;
import com.gregochr.goldenhour.config.CiSpendProperties;
import com.gregochr.goldenhour.config.CostProperties;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.SONNET_55_ID;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.artifactDoc;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.artifactsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.attemptJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.attemptJsonUpdatedAt;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.jobJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.jobsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.manyRunsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.modelJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.noArtifactsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.noRunsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.runsJson;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.twoEntryZip;
import static com.gregochr.goldenhour.service.ci.CiSpendFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests {@link CiSpendPollService} with a mocked GitHub client, downloader and repositories (every stub argument
 * pinned), a real artifact reader, a real {@link CostCalculator}, and a captured log appender.
 */
@ExtendWith(MockitoExtension.class)
class CiSpendPollServiceTest {

    private static final String OWNER = "gregochr";
    private static final String REPO = "goldenhour";
    private static final String WORKFLOW = "prompt-regression.yml";
    private static final String CREATED = ">=2025-02-28";
    private static final long RUN = 5551L;
    private static final long ARTIFACT = 777L;
    private static final String LOCATION = "https://blob.test/a?sig=SECRETSIG";
    private static final String TOKEN = "github_pat_TOKENVALUE1234567890";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final LocalDate JOB_DAY = LocalDate.of(2025, 3, 14);
    private static final String STEP_START = "2025-03-14T10:01:00Z";
    private static final int MAX = CiSpendPollService.MAX_ARTIFACT_BYTES;
    private static final String SONNET_MODEL = modelJson(SONNET_55_ID, 42, 1, 612_000, 38_000, 434_000, 21_000,
            9_000);
    private static final String PR_ARRAY = "[{\"number\":1912}]";

    @Mock
    private GitHubActionsClient client;
    @Mock
    private GitHubArtifactDownloader downloader;
    @Mock
    private CiSpendRunRepository runRepository;
    @Mock
    private CiSpendAttemptRepository attemptRepository;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private DynamicSchedulerService scheduler;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final CostCalculator costCalculator = new CostCalculator(new CostProperties());
    private final CiSpendProperties properties = new CiSpendProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2025-03-14T12:00:00Z"), ZoneOffset.UTC);
    private CiSpendPollService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setGithubToken(TOKEN);
        service = newService();
        logger = (Logger) LoggerFactory.getLogger(CiSpendPollService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    private CiSpendPollService newService() {
        return new CiSpendPollService(client, downloader, new CiUsageArtifactReader(), properties, runRepository,
                attemptRepository, costCalculator, exchangeRateService, scheduler, new ObjectMapper(), clock,
                transactionManager);
    }

    private SchedulerFireOutcome.Report poll() {
        return SchedulerFireOutcome.capture(service::poll);
    }

    private List<String> logMessages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---- stubbing helpers ------------------------------------------------------------------------------------

    private void stubList(long runId, int latestAttempt) {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1))
                .thenReturn(runsJson(runId, latestAttempt));
    }

    private void stubAttempt(long runId, int attempt, String json) {
        when(client.getRunAttempt(OWNER, REPO, runId, attempt)).thenReturn(json);
    }

    private void stubJobs(long runId, int attempt, String... jobs) {
        when(client.listAttemptJobs(OWNER, REPO, runId, attempt, 100)).thenReturn(jobsJson(jobs));
    }

    private void stubArtifacts(long runId, int attempt, String suite, String json) {
        when(client.listRunArtifacts(OWNER, REPO, runId, "prompt-regression-usage-" + suite + "-" + attempt, 100))
                .thenReturn(json);
    }

    private void stubZipRedirect(long artifactId, String location) {
        when(client.getArtifactZip(OWNER, REPO, artifactId))
                .thenReturn(ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build());
    }

    private void stubDownload(String location, byte[] bytes) throws ArtifactDownloadException {
        when(downloader.download(location, MAX)).thenReturn(bytes);
    }

    private static String pullRequestAttempt() {
        return attemptJson("completed", "pull_request", SHA, "feat: a title", PR_ARRAY);
    }

    private static String skyJob(int runAttempt) {
        return jobJson(CiSpendPollService.SKY_JOB_NAME, runAttempt, "success", CiSpendPollService.SKY_STEP_NAME,
                STEP_START);
    }

    private static String askJob(int runAttempt) {
        return jobJson(CiSpendPollService.ASK_JOB_NAME, runAttempt, "failure", CiSpendPollService.ASK_STEP_NAME,
                STEP_START);
    }

    private static byte[] skyZip(long runId, int attempt, String models) {
        return zip(CiUsageArtifactReader.entryName("sky"), artifactDoc("sky", runId, attempt, models));
    }

    /** Stubs a completed PR attempt on which only the sky suite ran, and returns nothing: the zip is the caller's. */
    private void stubSkyOnly(long runId, int attempt, byte[] zip) throws ArtifactDownloadException {
        stubSkyOnly(runId, attempt, zip, pullRequestAttempt());
    }

    private void stubSkyOnly(long runId, int attempt, byte[] zip, String attemptJson)
            throws ArtifactDownloadException {
        stubAttempt(runId, attempt, attemptJson);
        stubJobs(runId, attempt, skyJob(attempt),
                jobJson(CiSpendPollService.ASK_JOB_NAME, attempt, "skipped", CiSpendPollService.ASK_STEP_NAME,
                        null));
        stubArtifacts(runId, attempt, "sky", artifactsJson("prompt-regression-usage-sky-" + attempt, ARTIFACT, false,
                2_000));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, zip);
    }

    private CiSpendRunEntity savedRun() {
        ArgumentCaptor<CiSpendRunEntity> captor = ArgumentCaptor.forClass(CiSpendRunEntity.class);
        verify(runRepository).save(captor.capture());
        return captor.getValue();
    }

    private CiSpendAttemptEntity savedAttempt() {
        ArgumentCaptor<CiSpendAttemptEntity> captor = ArgumentCaptor.forClass(CiSpendAttemptEntity.class);
        verify(attemptRepository).save(captor.capture());
        return captor.getValue();
    }

    private static CiSpendRunEntity existingRow(Long id, String state, String modelId) {
        CiSpendRunEntity row = new CiSpendRunEntity();
        row.setId(id);
        row.setGithubRunId(RUN);
        row.setRunAttempt(1);
        row.setSuite("sky");
        row.setUsageState(state);
        row.setStartedAt(Instant.parse("2025-03-07T10:00:00Z"));
        row.setHtmlUrl("https://github.com/gregochr/goldenhour/actions/runs/" + RUN + "/attempts/1");
        row.setFetchedAt(Instant.parse("2025-03-07T11:00:00Z"));
        if (modelId != null) {
            CiSpendModelUsageEntity m = new CiSpendModelUsageEntity();
            m.setModel(modelId);
            m.setCalls(10);
            m.setErroredCalls(2);
            m.setInputTokens(100_000L);
            m.setOutputTokens(5_000L);
            m.setCacheReadTokens(50_000L);
            m.setCacheWriteTokens(8_000L);
            m.setCacheWrite1hTokens(3_000L);
            row.addModel(m);
        }
        return row;
    }

    private static long expectedCost(EvaluationModel model, CostCalculator calc, long in, long out, long read,
            long write, long write1h) {
        CostCalculator.ModelRates r = calc.ratesFor(model);
        return Math.round(in * r.inputUsdPerMtok()) + Math.round(out * r.outputUsdPerMtok())
                + Math.round(read * r.cacheReadUsdPerMtok())
                + Math.round((write - write1h) * r.cacheWriteUsdPerMtok())
                + Math.round(write1h * r.cacheWrite1hUsdPerMtok());
    }

    // ---- registration and gating -----------------------------------------------------------------------------

    @Test
    @DisplayName("registerJob registers the poll under the ci_spend_poll key")
    void registersTheJob() {
        service.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(eq("ci_spend_poll"), target.capture());
        assertThat(target.getValue()).isNotNull();
    }

    @Test
    @DisplayName("an inactive configuration skips the poll and calls nothing")
    void inactiveSkips() {
        properties.setGithubToken("");

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        verifyNoMoreInteractions(client, downloader, runRepository, attemptRepository);
    }

    // ---- the happy path --------------------------------------------------------------------------------------

    @Test
    @DisplayName("first poll: reads the attempt and jobs, stores a priced KNOWN row from the API's identity, settles")
    void firstPollInsertsAKnownRow() throws Exception {
        stubList(RUN, 1);
        // GitHub reports an empty pull_requests for an open same-repository PR, so the commits fallback is the
        // normal way a PR number is found.
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, SONNET_MODEL),
                attemptJson("completed", "pull_request", SHA, "feat: a title", "[]"));
        when(client.listCommitPulls(OWNER, REPO, SHA)).thenReturn("[{\"number\":1912}]");
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        SchedulerFireOutcome.Report report = poll();

        CiSpendRunEntity row = savedRun();
        long expected = expectedCost(EvaluationModel.SONNET_55, costCalculator, 612_000, 38_000, 434_000, 21_000,
                9_000);
        assertThat(row.getUsageState()).isEqualTo("KNOWN");
        assertThat(row.getCostMicroDollars()).isEqualTo(expected).isPositive();
        assertThat(row.getCostMicroDollars()).isEqualTo(costCalculator.calculateCostMicroDollars(
                EvaluationModel.SONNET_55, new TokenUsage(612_000, 38_000, 21_000, 434_000, 9_000), false));
        assertThat(row.getExchangeRateGbpPerUsd()).isEqualTo(0.8);
        assertThat(row.getGithubRunId()).isEqualTo(RUN);
        assertThat(row.getRunAttempt()).isEqualTo(1);
        assertThat(row.getSuite()).isEqualTo("sky");
        assertThat(row.getEvent()).isEqualTo("pull_request");
        assertThat(row.getHeadSha()).isEqualTo(SHA);
        assertThat(row.getPrNumber()).isEqualTo(1912);
        assertThat(row.getPrTitle()).isEqualTo("feat: a title");
        assertThat(row.getHtmlUrl()).isEqualTo("https://github.com/gregochr/goldenhour/actions/runs/5551/attempts/1");
        assertThat(row.getStartedAt()).isEqualTo(Instant.parse("2025-03-14T10:00:00Z"));
        assertThat(row.getFinishedAt()).isEqualTo(Instant.parse("2025-03-14T10:05:00Z"));
        assertThat(row.getConclusion()).isEqualTo("success");
        assertThat(row.getCases()).isEqualTo(42);
        assertThat(row.getFailed()).isEqualTo(3);
        assertThat(row.getErroredCalls()).isEqualTo(1);
        assertThat(row.getFetchedAt()).isEqualTo(Instant.parse("2025-03-14T12:00:00Z"));
        assertThat(row.getModels()).hasSize(1);
        CiSpendModelUsageEntity m = row.getModels().get(0);
        CostCalculator.ModelRates rates = costCalculator.ratesFor(EvaluationModel.SONNET_55);
        assertThat(m.getModel()).isEqualTo(SONNET_55_ID);
        assertThat(m.getInputTokens()).isEqualTo(612_000L);
        assertThat(m.getOutputTokens()).isEqualTo(38_000L);
        assertThat(m.getCacheReadTokens()).isEqualTo(434_000L);
        assertThat(m.getCacheWriteTokens()).isEqualTo(21_000L);
        assertThat(m.getCacheWrite1hTokens()).isEqualTo(9_000L);
        assertThat(m.getInputUsdPerMtok()).isEqualTo(rates.inputUsdPerMtok());
        assertThat(m.getOutputUsdPerMtok()).isEqualTo(rates.outputUsdPerMtok());
        assertThat(m.getCacheWriteUsdPerMtok()).isEqualTo(rates.cacheWriteUsdPerMtok());
        assertThat(m.getCacheWrite1hUsdPerMtok()).isEqualTo(rates.cacheWrite1hUsdPerMtok());
        assertThat(m.getCacheReadUsdPerMtok()).isEqualTo(rates.cacheReadUsdPerMtok());
        assertThat(m.getCostMicroDollars()).isEqualTo(expected);
        CiSpendAttemptEntity attempt = savedAttempt();
        assertThat(attempt.getGithubRunId()).isEqualTo(RUN);
        assertThat(attempt.getRunAttempt()).isEqualTo(1);
        assertThat(attempt.getSuitesRan()).isEqualTo("sky");
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("1 stored");
        assertThat(String.join("\n", logMessages())).doesNotContain("SECRETSIG").doesNotContain("TOKENVALUE");
    }

    @Test
    @DisplayName("both suites in one attempt: two rows in sky-then-ask order, one settle, one identity lookup")
    void bothSuitesRan() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA, "feat: both", "[]"));
        stubJobs(RUN, 1, askJob(1), skyJob(1));
        when(client.listCommitPulls(OWNER, REPO, SHA)).thenReturn("[{\"number\":77}]");
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", 11L, false, 100));
        stubArtifacts(RUN, 1, "ask", artifactsJson("prompt-regression-usage-ask-1", 12L, false, 100));
        stubZipRedirect(11L, "https://blob.test/sky?sig=A");
        stubZipRedirect(12L, "https://blob.test/ask?sig=B");
        stubDownload("https://blob.test/sky?sig=A", skyZip(RUN, 1, SONNET_MODEL));
        stubDownload("https://blob.test/ask?sig=B", zip(CiUsageArtifactReader.entryName("ask"),
                artifactDoc("ask", RUN, 1, modelJson("claude-haiku-4-5-20251001", 5, 0, 1000, 200, 0, 0, 0))));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.75);

        poll();

        ArgumentCaptor<CiSpendRunEntity> rows = ArgumentCaptor.forClass(CiSpendRunEntity.class);
        verify(runRepository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(CiSpendRunEntity::getSuite).containsExactly("sky", "ask");
        assertThat(rows.getAllValues()).extracting(CiSpendRunEntity::getPrNumber).containsExactly(77, 77);
        assertThat(rows.getAllValues().get(1).getConclusion()).isEqualTo("failure");
        verify(client, times(1)).listCommitPulls(OWNER, REPO, SHA);
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky,ask");
    }

    // ---- idempotence and attempts ----------------------------------------------------------------------------

    @Test
    @DisplayName("a settled attempt costs no calls beyond the run listing")
    void settledAttemptsCostNoCalls() {
        stubList(RUN, 1);
        when(attemptRepository.existsById(new CiSpendAttemptEntity.Key(RUN, 1))).thenReturn(true);

        SchedulerFireOutcome.Report report = poll();

        verify(client).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1);
        verifyNoMoreInteractions(client, downloader);
        verify(runRepository, never()).save(argThat(r -> true));
        verify(attemptRepository, never()).save(argThat(a -> true));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("1 calls");
    }

    @Test
    @DisplayName("attempt 2 adds its own row while the settled attempt 1 is skipped")
    void attemptTwoAddsARow() throws Exception {
        stubList(RUN, 2);
        when(attemptRepository.existsById(new CiSpendAttemptEntity.Key(RUN, 1))).thenReturn(true);
        stubSkyOnly(RUN, 2, skyZip(RUN, 2, SONNET_MODEL));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        verify(client, never()).getRunAttempt(OWNER, REPO, RUN, 1);
        CiSpendRunEntity row = savedRun();
        assertThat(row.getRunAttempt()).isEqualTo(2);
        assertThat(row.getHtmlUrl()).endsWith("/actions/runs/5551/attempts/2");
        assertThat(savedAttempt().getRunAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a job carried over from attempt 1 (run_attempt 1) adds nothing in attempt 2 and settles empty")
    void carriedOverJobAddsNothing() {
        stubList(RUN, 2);
        when(attemptRepository.existsById(new CiSpendAttemptEntity.Key(RUN, 1))).thenReturn(true);
        stubAttempt(RUN, 2, pullRequestAttempt());
        stubJobs(RUN, 2, skyJob(1), askJob(1));

        poll();

        verify(client, never()).listRunArtifacts(OWNER, REPO, RUN, "prompt-regression-usage-sky-2", 100);
        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(savedAttempt().getSuitesRan()).isEmpty();
    }

    @Test
    @DisplayName("skipped jobs, jobs cancelled before Maven, other job names and a missing step add nothing")
    void notRanJobsAddNothing() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1,
                jobJson(CiSpendPollService.SKY_JOB_NAME, 1, "skipped", CiSpendPollService.SKY_STEP_NAME,
                        STEP_START),
                jobJson(CiSpendPollService.ASK_JOB_NAME, 1, "cancelled", CiSpendPollService.ASK_STEP_NAME, null),
                jobJson("Prompt regression - sky", 1, "success", CiSpendPollService.SKY_STEP_NAME, STEP_START),
                jobJson(CiSpendPollService.SKY_JOB_NAME, 1, "success", "Some other step", STEP_START));

        poll();

        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(savedAttempt().getSuitesRan()).isEmpty();
    }

    @Test
    @DisplayName("an in-progress attempt is left alone: no jobs read, nothing settled")
    void inProgressAttemptIsLeft() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("in_progress", "pull_request", SHA, "t", PR_ARRAY));

        poll();

        verify(client, never()).listAttemptJobs(OWNER, REPO, RUN, 1, 100);
        verify(attemptRepository, never()).save(argThat(a -> true));
        verify(runRepository, never()).save(argThat(r -> true));
    }

    @Test
    @DisplayName("a KNOWN row already stored is never refetched, and its attempt then settles")
    void knownRowIsNeverRewritten() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        when(runRepository.findByGithubRunIdAndRunAttemptAndSuite(RUN, 1, "sky"))
                .thenReturn(Optional.of(existingRow(3L, "KNOWN", SONNET_55_ID)));

        poll();

        verify(client, never()).listRunArtifacts(OWNER, REPO, RUN, "prompt-regression-usage-sky-1", 100);
        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
    }

    // ---- unknown and upgrade ---------------------------------------------------------------------------------

    @Test
    @DisplayName("no artifact, or only an expired one, stores an UNKNOWN row and leaves the attempt unsettled")
    void missingAndExpiredArtifactsAreUnknown() {
        stubList(RUN, 2);
        when(attemptRepository.existsById(new CiSpendAttemptEntity.Key(RUN, 1))).thenReturn(false);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1), askJob(1));
        stubArtifacts(RUN, 1, "sky", noArtifactsJson());
        stubArtifacts(RUN, 1, "ask", artifactsJson("prompt-regression-usage-ask-1", 5L, true, 100));
        stubAttempt(RUN, 2, attemptJson("completed", "workflow_dispatch", SHA, "Manual", "[]"));
        stubJobs(RUN, 2);

        poll();

        ArgumentCaptor<CiSpendRunEntity> rows = ArgumentCaptor.forClass(CiSpendRunEntity.class);
        verify(runRepository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(CiSpendRunEntity::getUsageState).containsOnly("UNKNOWN");
        assertThat(rows.getAllValues()).extracting(CiSpendRunEntity::getCostMicroDollars).containsOnlyNulls();
        assertThat(rows.getAllValues()).extracting(CiSpendRunEntity::getCases).containsOnlyNulls();
        assertThat(rows.getAllValues().get(0).getModels()).isEmpty();
        verify(attemptRepository, times(1)).save(argThat(a -> a.getRunAttempt() == 2));
        verify(attemptRepository, never()).save(argThat(a -> a.getRunAttempt() == 1));
    }

    @Test
    @DisplayName("an UNKNOWN row whose artifact is still absent is not rewritten")
    void unknownStaysUnknown() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        when(runRepository.findByGithubRunIdAndRunAttemptAndSuite(RUN, 1, "sky"))
                .thenReturn(Optional.of(existingRow(3L, "UNKNOWN", null)));
        stubArtifacts(RUN, 1, "sky", noArtifactsJson());

        poll();

        verify(runRepository, never()).save(argThat(r -> true));
        verify(attemptRepository, never()).save(argThat(a -> true));
    }

    @Test
    @DisplayName("within the one-hour grace an UNKNOWN row is upgraded when a valid artifact turns up, then it settles")
    void unknownIsUpgraded() throws Exception {
        CiSpendRunEntity unknown = existingRow(3L, "UNKNOWN", null);
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        when(runRepository.findByGithubRunIdAndRunAttemptAndSuite(RUN, 1, "sky"))
                .thenReturn(Optional.of(unknown));
        when(runRepository.lockByKey(RUN, 1, "sky")).thenReturn(Optional.of(unknown));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, SONNET_MODEL));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        SchedulerFireOutcome.Report report = poll();

        assertThat(savedRun()).isSameAs(unknown);
        assertThat(unknown.getUsageState()).isEqualTo("KNOWN");
        assertThat(unknown.getCostMicroDollars()).isPositive();
        assertThat(unknown.getModels()).hasSize(1);
        assertThat(report.summary()).contains("1 upgraded").contains("0 stored");
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
    }

    @Test
    @DisplayName("a row that turns out KNOWN under the lock is left as it is")
    void rowKnownUnderTheLockIsNotRewritten() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, SONNET_MODEL));
        when(runRepository.lockByKey(RUN, 1, "sky")).thenReturn(Optional.of(existingRow(3L, "KNOWN", null)));

        poll();

        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
    }

    // ---- validation ------------------------------------------------------------------------------------------

    static Stream<Arguments> invalidArtifacts() {
        String sky = CiUsageArtifactReader.entryName("sky");
        String secret = "TOP-SECRET-FILE-CONTENT";
        return Stream.of(
                Arguments.of("bad schema", zip(sky, artifactDoc("sky", RUN, 1, "").replace("\"schema\":1",
                        "\"schema\":9"))),
                Arguments.of("mismatched runId", zip(sky, artifactDoc("sky", 999L, 1, ""))),
                Arguments.of("mismatched attempt", zip(sky, artifactDoc("sky", RUN, 2, ""))),
                Arguments.of("wrong suite", zip(sky, artifactDoc("ask", RUN, 1, ""))),
                Arguments.of("two entries", twoEntryZip(sky, "extra.json")),
                Arguments.of("wrong entry name", zip("other.json", artifactDoc("sky", RUN, 1, ""))),
                Arguments.of("too much uncompressed", zip(sky, artifactDoc("sky", RUN, 1, "") + " ".repeat(70_000))),
                Arguments.of("negative count", zip(sky, artifactDoc("sky", RUN, 1,
                        modelJson("m", 1, 0, -1, 0, 0, 0, 0)))),
                Arguments.of("huge count", zip(sky, artifactDoc("sky", RUN, 1,
                        modelJson("m", 1, 0, 999_999_999_999L, 0, 0, 0, 0)))),
                Arguments.of("huge calls", zip(sky, artifactDoc("sky", RUN, 1,
                        modelJson("m", 6000, 0, 1, 0, 0, 0, 0)))),
                Arguments.of("not json", zip(sky, secret)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArtifacts")
    @DisplayName("each limit is rejected and logged without content: nothing stored, attempt left unsettled")
    void invalidArtifactsAreRejected(String label, byte[] zip) throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, zip);

        SchedulerFireOutcome.Report report = poll();

        verify(runRepository, never()).save(argThat(r -> true));
        verify(attemptRepository, never()).save(argThat(a -> true));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(report.summary()).contains("1 rejected");
        assertThat(logMessages()).anyMatch(m -> m.contains("rejected") && m.contains("5551") && m.contains("sky"));
        assertThat(String.join("\n", logMessages())).doesNotContain("TOP-SECRET").doesNotContain("SECRETSIG");
    }

    @Test
    @DisplayName("an oversized size_in_bytes is rejected before anything is downloaded")
    void oversizedArtifactIsNotDownloaded() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, MAX + 1L));

        poll();

        verify(client, never()).getArtifactZip(OWNER, REPO, ARTIFACT);
        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(logMessages()).anyMatch(m -> m.contains("rejected") && m.contains("larger than"));
    }

    @Test
    @DisplayName("an oversized stream (the download refuses it whatever the header said) is rejected")
    void oversizedStreamIsRejected() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        when(downloader.download(LOCATION, MAX))
                .thenThrow(new ArtifactDownloadException("download exceeds 1048576 bytes"));

        poll();

        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(logMessages()).anyMatch(m -> m.contains("rejected") && m.contains("download exceeds"));
    }

    @Test
    @DisplayName("a zip call that does not redirect is rejected")
    void noRedirectIsRejected() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        when(client.getArtifactZip(OWNER, REPO, ARTIFACT)).thenReturn(ResponseEntity.ok().build());

        poll();

        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(logMessages()).anyMatch(m -> m.contains("rejected") && m.contains("no redirect"));
    }

    @Test
    @DisplayName("the newest non-expired artifact with the exact name is the one fetched")
    void picksTheNewestUnexpiredArtifact() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        String name = "prompt-regression-usage-sky-1";
        stubArtifacts(RUN, 1, "sky", "{\"artifacts\":["
                + "{\"id\":900,\"name\":\"" + name + "\",\"expired\":true,\"size_in_bytes\":1},"
                + "{\"id\":10,\"name\":\"" + name + "\",\"expired\":false,\"size_in_bytes\":1},"
                + "{\"id\":20,\"name\":\"" + name + "\",\"expired\":false,\"size_in_bytes\":1},"
                + "{\"id\":999,\"name\":\"other\",\"expired\":false,\"size_in_bytes\":1}]}");
        stubZipRedirect(20L, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, SONNET_MODEL));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        assertThat(savedRun().getUsageState()).isEqualTo("KNOWN");
        verify(client, never()).getArtifactZip(OWNER, REPO, 900L);
    }

    // ---- pricing ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an unknown model stores the tokens UNPRICED with no cost and no exchange rate lookup")
    void unknownModelIsUnpriced() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, modelJson(SONNET_55_ID, 1, 0, 10, 10, 10, 10, 0) + ","
                + modelJson("claude-future-9", 3, 1, 1000, 500, 250, 125, 25)));

        poll();

        CiSpendRunEntity row = savedRun();
        assertThat(row.getUsageState()).isEqualTo("UNPRICED");
        assertThat(row.getCostMicroDollars()).isNull();
        assertThat(row.getExchangeRateGbpPerUsd()).isNull();
        assertThat(row.getErroredCalls()).isEqualTo(1);
        assertThat(row.getModels()).extracting(CiSpendModelUsageEntity::getModel)
                .containsExactly(SONNET_55_ID, "claude-future-9");
        assertThat(row.getModels()).extracting(CiSpendModelUsageEntity::getCostMicroDollars).containsOnlyNulls();
        assertThat(row.getModels()).extracting(CiSpendModelUsageEntity::getInputUsdPerMtok).containsOnlyNulls();
        assertThat(row.getModels().get(1).getInputTokens()).isEqualTo(1000L);
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
        verify(exchangeRateService, never()).getRate(JOB_DAY);
    }

    @Test
    @DisplayName("an artifact with no models is KNOWN at zero cost")
    void emptyModelsIsKnownZero() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        CiSpendRunEntity row = savedRun();
        assertThat(row.getUsageState()).isEqualTo("KNOWN");
        assertThat(row.getCostMicroDollars()).isZero();
        assertThat(row.getModels()).isEmpty();
    }

    @Test
    @DisplayName("repricing: an UNPRICED row whose models now resolve becomes KNOWN from its stored tokens")
    void unpricedRowIsRepriced() {
        CiSpendRunEntity row = existingRow(9L, "UNPRICED", SONNET_55_ID);
        when(runRepository.findByUsageStateOrderByStartedAtAsc("UNPRICED")).thenReturn(List.of(row));
        when(runRepository.findById(9L)).thenReturn(Optional.of(row));
        when(exchangeRateService.getRate(LocalDate.of(2025, 3, 7))).thenReturn(0.79);
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenReturn(noRunsJson());

        SchedulerFireOutcome.Report report = poll();

        assertThat(savedRun()).isSameAs(row);
        assertThat(row.getUsageState()).isEqualTo("KNOWN");
        assertThat(row.getCostMicroDollars()).isEqualTo(expectedCost(EvaluationModel.SONNET_55, costCalculator,
                100_000, 5_000, 50_000, 8_000, 3_000));
        assertThat(row.getExchangeRateGbpPerUsd()).isEqualTo(0.79);
        assertThat(row.getErroredCalls()).isEqualTo(2);
        assertThat(row.getModels()).hasSize(1);
        assertThat(row.getModels().get(0).getInputUsdPerMtok())
                .isEqualTo(costCalculator.ratesFor(EvaluationModel.SONNET_55).inputUsdPerMtok());
        assertThat(row.getFetchedAt()).isEqualTo(Instant.parse("2025-03-14T12:00:00Z"));
        assertThat(report.summary()).contains("1 repriced");
    }

    @Test
    @DisplayName("repricing leaves a still-unknown model, a vanished row and a row no longer UNPRICED")
    void repricingSkipsWhatItCannotPrice() {
        CiSpendRunEntity stillUnknown = existingRow(1L, "UNPRICED", "claude-future-9");
        CiSpendRunEntity gone = existingRow(2L, "UNPRICED", SONNET_55_ID);
        CiSpendRunEntity raced = existingRow(3L, "KNOWN", SONNET_55_ID);
        when(runRepository.findByUsageStateOrderByStartedAtAsc("UNPRICED"))
                .thenReturn(List.of(stillUnknown, gone, raced));
        when(runRepository.findById(1L)).thenReturn(Optional.of(stillUnknown));
        when(runRepository.findById(2L)).thenReturn(Optional.empty());
        when(runRepository.findById(3L)).thenReturn(Optional.of(raced));
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenReturn(noRunsJson());

        SchedulerFireOutcome.Report report = poll();

        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(stillUnknown.getUsageState()).isEqualTo("UNPRICED");
        assertThat(report.summary()).contains("0 repriced");
    }

    // ---- identity --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a manual run stores no title and no PR, and never asks for the commit's pulls")
    void manualRunHasNoTitleOrPr() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "workflow_dispatch", SHA, "Some manual title", "[]"));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        CiSpendRunEntity row = savedRun();
        assertThat(row.getEvent()).isEqualTo("workflow_dispatch");
        assertThat(row.getPrTitle()).isNull();
        assertThat(row.getPrNumber()).isNull();
        verify(client, never()).listCommitPulls(OWNER, REPO, SHA);
    }

    @Test
    @DisplayName("an empty pull_requests list falls back to the commit's pulls for a pull_request event")
    void emptyPullRequestsFallsBackToCommits() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA, "feat: merged", "[]"));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(client.listCommitPulls(OWNER, REPO, SHA)).thenReturn("[{\"number\":77},{\"number\":78}]");
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        assertThat(savedRun().getPrNumber()).isEqualTo(77);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fallbackAnswersWithNoPull")
    @DisplayName("a commit-pulls fallback that finds nothing (404, 422, empty, not an array) leaves the PR null")
    void fallbackWithoutAPull(String label, Object answer) throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA, "feat: merged", "[]"));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        if (answer instanceof RuntimeException e) {
            when(client.listCommitPulls(OWNER, REPO, SHA)).thenThrow(e);
        } else {
            when(client.listCommitPulls(OWNER, REPO, SHA)).thenReturn((String) answer);
        }
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        assertThat(savedRun().getPrNumber()).isNull();
        assertThat(savedRun().getPrTitle()).isEqualTo("feat: merged");
    }

    static Stream<Arguments> fallbackAnswersWithNoPull() {
        return Stream.of(
                Arguments.of("404", HttpClientErrorException.create(HttpStatusCode.valueOf(404), "x",
                        HttpHeaders.EMPTY, new byte[0], null)),
                Arguments.of("422", HttpClientErrorException.create(HttpStatusCode.valueOf(422), "x",
                        HttpHeaders.EMPTY, new byte[0], null)),
                Arguments.of("empty array", "[]"),
                Arguments.of("an object", "{\"message\":\"x\"}"),
                Arguments.of("a pull without a number", "[{\"title\":\"x\"}]"));
    }

    @Test
    @DisplayName("a server error on the fallback ends the poll like any other GitHub failure")
    void fallbackServerErrorEndsThePoll() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA, "t", "[]"));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", noArtifactsJson());
        when(client.listCommitPulls(OWNER, REPO, SHA)).thenThrow(HttpServerErrorException.create(
                HttpStatusCode.valueOf(502), "x", HttpHeaders.EMPTY, new byte[0], null));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).contains("HTTP 502");
        verify(runRepository, never()).save(argThat(r -> true));
    }

    @Test
    @DisplayName("the PR title is truncated to 300 characters and the head SHA to the column's 40")
    void titleAndShaAreTruncated() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA + "ff", "x".repeat(400), PR_ARRAY));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        CiSpendRunEntity row = savedRun();
        assertThat(row.getPrTitle()).hasSize(300);
        assertThat(row.getHeadSha()).isEqualTo(SHA);
    }

    @Test
    @DisplayName("a title cut at 300 characters never splits a surrogate pair")
    void titleTruncationKeepsSurrogatePairsWhole() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJson("completed", "pull_request", SHA,
                "x".repeat(299) + "\uD83D\uDE00tail", PR_ARRAY));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        assertThat(savedRun().getPrTitle()).isEqualTo("x".repeat(299));
    }

    @Test
    @DisplayName("the exchange rate is looked up before the write transaction opens, never inside it")
    void exchangeRateIsLookedUpBeforeTheTransaction() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, SONNET_MODEL));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        InOrder order = inOrder(exchangeRateService, transactionManager);
        order.verify(exchangeRateService).getRate(JOB_DAY);
        order.verify(transactionManager).getTransaction(argThat(d -> true));
    }

    @Test
    @DisplayName("an aged attempt with no artifact settles after one poll, then costs no calls")
    void agedArtifactlessAttemptSettlesAndThenCostsNothing() {
        stubList(RUN, 1);
        when(attemptRepository.existsById(new CiSpendAttemptEntity.Key(RUN, 1))).thenReturn(false, true);
        stubAttempt(RUN, 1, attemptJsonUpdatedAt("2025-03-14T10:30:00Z", PR_ARRAY));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", noArtifactsJson());

        poll();

        assertThat(savedRun().getUsageState()).isEqualTo("UNKNOWN");
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
        SchedulerFireOutcome.Report second = poll();

        verify(client, times(1)).getRunAttempt(OWNER, REPO, RUN, 1);
        verify(client, times(1)).listAttemptJobs(OWNER, REPO, RUN, 1, 100);
        verify(client, times(1)).listRunArtifacts(OWNER, REPO, RUN, "prompt-regression-usage-sky-1", 100);
        assertThat(second.summary()).contains("1 calls");
    }

    @Test
    @DisplayName("an attempt updated exactly an hour ago is still inside the grace; a missing time counts as old")
    void graceBoundaryAndMissingTime() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, attemptJsonUpdatedAt("2025-03-14T11:00:00Z", PR_ARRAY));
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", noArtifactsJson());

        poll();

        verify(attemptRepository, never()).save(argThat(a -> true));
        service = newService();
        stubAttempt(RUN, 1, attemptJsonUpdatedAt("2025-03-14T11:00:00Z", PR_ARRAY)
                .replace(",\"updated_at\":\"2025-03-14T11:00:00Z\"", ""));
        poll();
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
    }

    @Test
    @DisplayName("an invalid artifact is retried, and the attempt settles on the third rejected poll, logged once")
    void thirdRejectSettlesTheAttempt() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, zip("other.json", "{}"));

        poll();
        poll();
        verify(attemptRepository, never()).save(argThat(a -> true));
        poll();

        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
        verify(runRepository, never()).save(argThat(r -> true));
        assertThat(logMessages()).filteredOn(m -> m.contains("settling without it")).hasSize(1);
        assertThat(logMessages()).filteredOn(m -> m.contains("rejected:")).hasSize(3);
    }

    @Test
    @DisplayName("any runtime failure ends the poll with a redacted summary, even one that is not a REST error")
    void anyRuntimeFailureIsRedacted() {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1))
                .thenThrow(new IllegalStateException("boom Bearer " + TOKEN + " sig=1"));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary() + String.join("", logMessages())).doesNotContain("TOKENVALUE");
    }

    @Test
    @DisplayName("a job with no start time falls back to its paid step's start")
    void jobStartFallsBackToTheStep() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1).replace("\"started_at\":\"2025-03-14T10:00:00Z\",", ""));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);

        poll();

        assertThat(savedRun().getStartedAt()).isEqualTo(Instant.parse(STEP_START));
    }

    @Test
    @DisplayName("a job that started at 23:30 UTC in summer (00:30 in London) is priced at the UTC day's rate")
    void exchangeRateUsesTheUtcDate() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1).replace("2025-03-14T10:00:00Z", "2025-07-01T23:30:00Z"));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(LocalDate.of(2025, 7, 1))).thenReturn(0.77);

        poll();

        assertThat(savedRun().getExchangeRateGbpPerUsd()).isEqualTo(0.77);
    }

    // ---- budget, overlap, errors -----------------------------------------------------------------------------

    @Test
    @DisplayName("the poll stops cleanly at the call budget and says the next poll resumes")
    void budgetStopsThePoll() {
        properties.setMaxCallsPerPoll(2);
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());

        SchedulerFireOutcome.Report report = poll();

        verify(client, never()).listAttemptJobs(OWNER, REPO, RUN, 1, 100);
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("2 calls").contains("call budget");
    }

    @Test
    @DisplayName("a second concurrent fire returns at once while one is running")
    void overlapGuard() {
        SchedulerFireOutcome.Report[] inner = new SchedulerFireOutcome.Report[1];
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenAnswer(invocation -> {
            inner[0] = SchedulerFireOutcome.capture(service::poll);
            return noRunsJson();
        });

        SchedulerFireOutcome.Report outer = poll();

        assertThat(inner[0].status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        assertThat(inner[0].summary()).contains("Already running");
        assertThat(outer.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        verify(client, times(1)).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1);
        // And the guard is released afterwards: a later poll runs.
        assertThat(poll().status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        verify(client, times(2)).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1);
    }

    @Test
    @DisplayName("a 403 mid-poll ends the poll with one warning; rows already committed stay")
    void forbiddenEndsThePoll() throws Exception {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenReturn(
                "{\"workflow_runs\":[{\"id\":1,\"run_attempt\":1},{\"id\":2,\"run_attempt\":1}]}");
        stubAttempt(1L, 1, pullRequestAttempt());
        stubJobs(1L, 1, skyJob(1));
        stubArtifacts(1L, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        stubDownload(LOCATION, skyZip(1L, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);
        when(client.getRunAttempt(OWNER, REPO, 2L, 1)).thenThrow(HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        SchedulerFireOutcome.Report report = poll();

        assertThat(savedRun().getGithubRunId()).isEqualTo(1L);
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).contains("HTTP 403");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    }

    @Test
    @DisplayName("a transport failure ends the poll, and neither the token nor a signed URL reaches any log or summary")
    void secretsNeverReachTheLogs() {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenThrow(new ResourceAccessException(
                "I/O error on GET request for \"https://blob.test/a?sv=1&sig=SIGNATURE\": Bearer " + TOKEN
                        + " rejected\r\nforged line"));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        String everything = String.join("\n", logMessages()) + "\n" + report.summary();
        assertThat(everything).doesNotContain("SIGNATURE").doesNotContain("TOKENVALUE").doesNotContain("\r")
                .contains("sig=***");
        logs.list.forEach(e -> assertThat(e.toString()).doesNotContain("TOKENVALUE"));
    }

    @Test
    @DisplayName("an exception with no message is described by its type")
    void messagelessExceptionIsDescribed() {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1))
                .thenThrow(new ResourceAccessException(null));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).contains("ended early");
    }

    @Test
    @DisplayName("a blob host failure ends the poll like any other transport failure")
    void blobFailureEndsThePoll() throws Exception {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1, skyJob(1));
        stubArtifacts(RUN, 1, "sky", artifactsJson("prompt-regression-usage-sky-1", ARTIFACT, false, 100));
        stubZipRedirect(ARTIFACT, LOCATION);
        when(downloader.download(LOCATION, MAX)).thenThrow(HttpServerErrorException.create(
                HttpStatusCode.valueOf(500), "x", HttpHeaders.EMPTY, new byte[0], null));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).contains("HTTP 500");
    }

    @Test
    @DisplayName("a body that is not JSON (or no body at all) ends the poll")
    void unreadableBodyEndsThePoll() {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1))
                .thenReturn("<html>nope</html>", (String) null);

        assertThat(poll().summary()).contains("not JSON");
        assertThat(poll().summary()).contains("not JSON");
    }

    @Test
    @DisplayName("a full page of runs asks for the next page, and an empty page ends the listing")
    void paginatesRuns() {
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 1)).thenReturn(manyRunsJson(1, 100));
        when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 2)).thenReturn(noRunsJson());
        for (int i = 1; i <= 100; i++) {
            when(attemptRepository.existsById(new CiSpendAttemptEntity.Key((long) i, 1))).thenReturn(true);
        }

        SchedulerFireOutcome.Report report = poll();

        verify(client).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 2);
        verify(client, never()).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, 3);
        assertThat(report.summary()).contains("2 calls");
    }

    @Test
    @DisplayName("the listing stops at the page cap")
    void pageCap() {
        for (int page = 1; page <= CiSpendPollService.MAX_RUN_PAGES; page++) {
            when(client.listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100, page))
                    .thenReturn(manyRunsJson(page * 1000, 100));
        }
        for (int page = 1; page <= CiSpendPollService.MAX_RUN_PAGES; page++) {
            for (int i = 0; i < 100; i++) {
                when(attemptRepository.existsById(new CiSpendAttemptEntity.Key((long) (page * 1000 + i), 1)))
                        .thenReturn(true);
            }
        }
        properties.setMaxCallsPerPoll(1000);

        poll();

        verify(client, never()).listWorkflowRuns(OWNER, REPO, WORKFLOW, CREATED, 100,
                CiSpendPollService.MAX_RUN_PAGES + 1);
    }

    @Test
    @DisplayName("a unique-key race on insert is read as already stored: no failure, and the attempt settles")
    void duplicateKeyRaceIsTreatedAsStored() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);
        when(runRepository.findByGithubRunIdAndRunAttemptAndSuite(RUN, 1, "sky"))
                .thenReturn(Optional.empty(), Optional.of(existingRow(4L, "KNOWN", null)));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(runRepository)
                .save(argThat(r -> "sky".equals(r.getSuite())));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("0 stored");
        assertThat(savedAttempt().getSuitesRan()).isEqualTo("sky");
        assertThat(logMessages()).anyMatch(m -> m.contains("stored concurrently"));
    }

    @Test
    @DisplayName("a duplicate-key race against an UNKNOWN winner leaves the attempt unsettled")
    void duplicateKeyRaceAgainstUnknown() throws Exception {
        stubList(RUN, 1);
        stubSkyOnly(RUN, 1, skyZip(RUN, 1, ""));
        when(exchangeRateService.getRate(JOB_DAY)).thenReturn(0.8);
        when(runRepository.findByGithubRunIdAndRunAttemptAndSuite(RUN, 1, "sky"))
                .thenReturn(Optional.empty(), Optional.of(existingRow(4L, "UNKNOWN", null)));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(runRepository)
                .save(argThat(r -> "sky".equals(r.getSuite())));

        poll();

        verify(attemptRepository, never()).save(argThat(a -> true));
    }

    @Test
    @DisplayName("a duplicate-key race on the settle marker is tolerated")
    void duplicateKeyOnSettleIsTolerated() {
        stubList(RUN, 1);
        stubAttempt(RUN, 1, pullRequestAttempt());
        stubJobs(RUN, 1);
        doThrow(new DataIntegrityViolationException("duplicate key")).when(attemptRepository)
                .save(argThat(a -> a.getGithubRunId() == RUN));

        SchedulerFireOutcome.Report report = poll();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(logMessages()).anyMatch(m -> m.contains("settled concurrently"));
    }
}
