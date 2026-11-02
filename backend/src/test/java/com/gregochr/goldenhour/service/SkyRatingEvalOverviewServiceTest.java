package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Comparison;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ComparisonRow;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelStats;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.ModelStatus;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.Session;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.SessionStatus;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview.VerdictKind;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFirstSeen;
import com.gregochr.goldenhour.model.skyeval.SkyRatingEvalFixtureAggregate;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalResultRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkyRatingEvalOverviewService}. The repositories are mocked and answer with exactly the
 * runs and per-fixture aggregates each test builds; the clock is fixed so freshness and the schedule label are
 * deterministic.
 */
@ExtendWith(MockitoExtension.class)
class SkyRatingEvalOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2031-11-02T12:00:00Z");
    private static final String OLD = "0ld5ha0000000000000000000000000000000001";
    private static final String MID = "m1d5ha0000000000000000000000000000000002";
    private static final String HEAD = "a91b0e2c4d5e6f708192a3b4c5d6e7f801234567";
    private static final LocalDateTime OLD_DATE = LocalDateTime.of(2031, 10, 13, 9, 0);
    private static final LocalDateTime MID_DATE = LocalDateTime.of(2031, 10, 20, 9, 0);
    private static final LocalDateTime HEAD_DATE = LocalDateTime.of(2031, 11, 2, 8, 0);
    private static final int FIXTURES = SkyRatingEvalFixtures.ALL.size();
    private static final int REPEATS = 8;
    private static final double RATE = 0.8;

    @Mock
    private SkyRatingEvalRunRepository runRepository;
    @Mock
    private SkyRatingEvalResultRepository resultRepository;
    @Mock
    private SkyRatingEvalService evalService;
    @Mock
    private GitInfoService gitInfoService;
    @Mock
    private ModelSelectionService modelSelectionService;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private SchedulerJobConfigRepository schedulerJobConfigRepository;

    private final List<SkyRatingEvalRunEntity> runs = new ArrayList<>();
    private final List<SkyRatingEvalFixtureAggregate> aggregates = new ArrayList<>();
    private final List<SkyRatingEvalFirstSeen> firstSeen = new ArrayList<>();
    private long nextId = 1;
    private String headSha = HEAD;
    private LocalDateTime headDate = HEAD_DATE;
    private String headMessage = "Prompt: stricter cirrus penalty";
    private EvaluationModel nearModel = EvaluationModel.SONNET_55;
    private EvaluationModel farModel = EvaluationModel.HAIKU;
    private Optional<SchedulerJobConfigEntity> job = Optional.empty();
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    // ---- scenario builders -------------------------------------------------------------------------------

    /** A completed run in which every fixture was attempted {@value #REPEATS} times with {@code inBand} passes. */
    private SkyRatingEvalRunEntity done(EvaluationModel model, SkyRatingEvalTrigger trigger, LocalDateTime start,
            String sha, int inBand) {
        SkyRatingEvalRunEntity run = base(model, trigger, SkyRatingEvalStatus.COMPLETED, start, sha);
        run.setTotalRuns(FIXTURES * REPEATS);
        run.setTotalPasses(FIXTURES * inBand);
        run.setCompletedAt(start.plusMinutes(10));
        run.setDurationMs(120_000L);
        run.setCostMicroDollars(250_000L);
        for (var fixture : SkyRatingEvalFixtures.ALL) {
            aggregates.add(new SkyRatingEvalFixtureAggregate(run.getId(), fixture.name(), (long) REPEATS,
                    (long) inBand, (long) (REPEATS - inBand), 0L, (long) REPEATS, (long) REPEATS * 3, 3, 3));
        }
        return run;
    }

    private SkyRatingEvalRunEntity base(EvaluationModel model, SkyRatingEvalTrigger trigger,
            SkyRatingEvalStatus status, LocalDateTime start, String sha) {
        LocalDateTime commitDate = OLD.equals(sha) ? OLD_DATE : MID.equals(sha) ? MID_DATE
                : HEAD.equals(sha) ? HEAD_DATE : null;
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .id(nextId++).runTimestamp(start).startedAt(start).model(model).runsPerFixture(REPEATS)
                .fixtureCount(FIXTURES).triggerSource(trigger).status(status).gitCommitHash(sha)
                .gitCommitDate(commitDate)
                .gitCommitMessage(sha == null || sha.length() < 4 ? null : "msg " + sha.substring(0, 4))
                .build();
        runs.add(run);
        return run;
    }

    private SkyEvalOverview build() {
        when(runRepository.findTop500ByOrderByRunTimestampDesc()).thenReturn(runs);
        if (!runs.isEmpty()) {
            when(resultRepository.aggregateByRunAndFixture(runs.stream().map(SkyRatingEvalRunEntity::getId).toList()))
                    .thenReturn(aggregates);
            Set<LocalDate> dates = new HashSet<>();
            runs.forEach(r -> dates.add(r.getStartedAt().toLocalDate()));
            dates.forEach(d -> when(exchangeRateService.getRate(d)).thenReturn(RATE));
        }
        when(resultRepository.firstSeenByFixture()).thenReturn(firstSeen);
        when(gitInfoService.getCommitHash()).thenReturn(headSha);
        when(gitInfoService.getCommitDate()).thenReturn(headDate);
        if (headSha != null) {
            when(gitInfoService.getCommitMessage()).thenReturn(headMessage);
        }
        when(modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM)).thenReturn(nearModel);
        when(modelSelectionService.getActiveModel(RunType.BATCH_FAR_TERM)).thenReturn(farModel);
        when(evalService.scheduledModels()).thenReturn(List.of(EvaluationModel.HAIKU, EvaluationModel.SONNET_55));
        when(schedulerJobConfigRepository.findByJobKey("sky_rating_eval")).thenReturn(job);
        return new SkyRatingEvalOverviewService(runRepository, resultRepository, evalService, gitInfoService,
                modelSelectionService, exchangeRateService, schedulerJobConfigRepository, clock).overview();
    }

    private static ModelStats stats(SkyEvalOverview o, EvaluationModel model) {
        return o.models().stream().filter(m -> m.model() == model).findFirst().orElseThrow();
    }

    private static final LocalDateTime SUN = LocalDateTime.of(2031, 10, 25, 3, 0);
    private static final LocalDateTime SUN2 = LocalDateTime.of(2031, 10, 18, 3, 0);

    /** The standard history: Sonnet 5.5 and Haiku 4.5 each ran twice on MID, never on HEAD. */
    private void priorHistory() {
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED, SUN.minusHours(5), MID, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusHours(5), MID, 7);
    }

    // ---- head, fixtures, versions ------------------------------------------------------------------------

    @Test
    @DisplayName("head carries the build's sha, short sha, message and commit instant")
    void headIsTheRunningBuild() {
        SkyEvalOverview o = build();

        assertThat(o.head().sha()).isEqualTo(HEAD);
        assertThat(o.head().shortSha()).isEqualTo("a91b0e2");
        assertThat(o.head().message()).isEqualTo("Prompt: stricter cirrus penalty");
        assertThat(o.head().committedAt()).isEqualTo(Instant.parse("2031-11-02T08:00:00Z"));
        assertThat(o.versions()).singleElement().satisfies(v -> assertThat(v.sha()).isEqualTo(HEAD));
    }

    @Test
    @DisplayName("an unknown build has an all-null head, a NO_HEAD verdict and unchecked lane models")
    void unknownHeadGivesNoHeadVerdict() {
        headSha = null;
        headDate = null;
        priorHistory();

        SkyEvalOverview o = build();

        assertThat(o.head().sha()).isNull();
        assertThat(o.head().shortSha()).isNull();
        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.NO_HEAD);
        assertThat(o.verdict().headline()).isEqualTo("This build has no version recorded.");
        assertThat(stats(o, EvaluationModel.SONNET_55).status()).isEqualTo(ModelStatus.UNCHECKED);
    }

    @Test
    @DisplayName("fixtures list the registry in order with the first run time that scored each")
    void fixturesCarryBandAndFirstSeen() {
        firstSeen.add(new SkyRatingEvalFirstSeen(SkyRatingEvalFixtures.ALL.get(1).name(),
                LocalDateTime.of(2031, 9, 1, 3, 0)));

        SkyEvalOverview o = build();

        assertThat(o.fixtures()).hasSize(FIXTURES);
        assertThat(o.fixtures().get(0).name()).isEqualTo(SkyRatingEvalFixtures.ALL.get(0).name());
        assertThat(o.fixtures().get(0).bandLo()).isEqualTo(SkyRatingEvalFixtures.ALL.get(0).band().min());
        assertThat(o.fixtures().get(0).bandHi()).isEqualTo(SkyRatingEvalFixtures.ALL.get(0).band().max());
        assertThat(o.fixtures().get(0).firstSeenAt()).isNull();
        assertThat(o.fixtures().get(1).firstSeenAt()).isEqualTo(Instant.parse("2031-09-01T03:00:00Z"));
    }

    @Test
    @DisplayName("versions are distinct non-blank hashes plus HEAD, newest first; a blank hash is unknown")
    void versionsAreDistinctNewestFirstAndBlankIsUnknown() {
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2, OLD, 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2.minusDays(7), "", 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2.minusDays(14), null, 7);

        SkyEvalOverview o = build();

        assertThat(o.versions()).extracting(v -> v.sha()).containsExactly(HEAD, MID, OLD);
        assertThat(o.versions().get(1).shortSha()).isEqualTo("m1d5ha0");
        assertThat(o.versions().get(1).message()).isEqualTo("msg m1d5");
        assertThat(o.versions().get(0).message()).isEqualTo("Prompt: stricter cirrus penalty");
    }

    @Test
    @DisplayName("a version with no commit date takes its first run time, and HEAD without a date sorts newest")
    void versionDateFallsBackToFirstRunTime() {
        headDate = null;
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, "n0date0000000", 7);

        SkyEvalOverview o = build();

        assertThat(o.versions()).extracting(v -> v.sha()).containsExactly(HEAD, "n0date0000000");
        assertThat(o.versions().get(1).committedAt()).isEqualTo(Instant.parse("2031-10-25T03:00:00Z"));
        assertThat(o.head().committedAt()).isNull();
    }

    // ---- sessions ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("runs 40 minutes apart share a session; 41 minutes apart do not")
    void sessionGapEdge() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 9, 0);
        SkyRatingEvalRunEntity a = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, t, MID, 7);
        SkyRatingEvalRunEntity b = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(40), MID, 7);
        SkyRatingEvalRunEntity c = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(81), MID, 7);

        SkyEvalOverview o = build();

        assertThat(o.sessions()).hasSize(2);
        assertThat(o.sessions().get(0).id()).isEqualTo(c.getId());
        assertThat(o.sessions().get(0).runs()).extracting(r -> r.id()).containsExactly(c.getId());
        assertThat(o.sessions().get(1).id()).isEqualTo(a.getId());
        assertThat(o.sessions().get(1).runs()).extracting(r -> r.id()).containsExactly(a.getId(), b.getId());
    }

    @Test
    @DisplayName("a session chains from the previous run, not from its first")
    void sessionChainsFromThePreviousRun() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 9, 0);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, t, MID, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(35), MID, 7);
        done(EvaluationModel.SONNET, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(70), MID, 7);

        SkyEvalOverview o = build();

        assertThat(o.sessions()).singleElement().satisfies(s -> assertThat(s.runs()).hasSize(3));
    }

    @Test
    @DisplayName("a different trigger or version starts its own session, and a null version is its own chain")
    void sessionSplitsOnTriggerAndVersion() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 9, 0);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, t, MID, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED, t.plusMinutes(5), MID, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(10), OLD, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(15), "", 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(20), null, 7);

        SkyEvalOverview o = build();

        assertThat(o.sessions()).hasSize(4);
        Session unknown = o.sessions().get(0);
        assertThat(unknown.version()).isNull();
        assertThat(unknown.runs()).hasSize(2);
    }

    @Test
    @DisplayName("runs sharing a batch id form one session however far apart they started")
    void sessionGroupsByBatchId() {
        SkyRatingEvalRunEntity a = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 7);
        SkyRatingEvalRunEntity b = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED, SUN.plusHours(3), MID, 7);
        a.setBatchId("msgbatch_1");
        b.setBatchId("msgbatch_1");

        SkyEvalOverview o = build();

        assertThat(o.sessions()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(a.getId());
            assertThat(s.runs()).hasSize(2);
            assertThat(s.byModel()).extracting(m -> m.model())
                    .containsExactly(EvaluationModel.HAIKU, EvaluationModel.OPUS);
        });
    }

    @Test
    @DisplayName("session status is RUNNING if any run runs, FAILED if all failed, otherwise DONE")
    void sessionStatus() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 9, 0);
        base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalStatus.RUNNING, t, MID);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(1), MID, 7);
        LocalDateTime u = t.minusDays(1);
        base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalStatus.FAILED, u, MID);
        base(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalStatus.FAILED, u.plusMinutes(1), MID);
        LocalDateTime v = t.minusDays(2);
        base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalStatus.FAILED, v, MID);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, v.plusMinutes(1), MID, 7);

        SkyEvalOverview o = build();

        assertThat(o.sessions()).extracting(Session::status)
                .containsExactly(SessionStatus.RUNNING, SessionStatus.FAILED, SessionStatus.DONE);
    }

    @Test
    @DisplayName("a running synchronous run reports progress from persisted attempts; a running batch run does not")
    void progress() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 9, 0);
        SkyRatingEvalRunEntity sync = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.RUNNING, t, MID);
        String fixture = SkyRatingEvalFixtures.ALL.get(0).name();
        aggregates.add(new SkyRatingEvalFixtureAggregate(sync.getId(), fixture, 8L, 8L, 0L, 0L, 8L, 24L, 3, 3));
        aggregates.add(new SkyRatingEvalFixtureAggregate(sync.getId(), SkyRatingEvalFixtures.ALL.get(1).name(),
                8L, 8L, 0L, 0L, 8L, 24L, 3, 3));
        SkyRatingEvalRunEntity batch = base(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.RUNNING, t.minusDays(1), MID);
        batch.setBatchId("msgbatch_9");

        SkyEvalOverview o = build();

        SkyEvalOverview.Run syncView = o.sessions().get(0).runs().get(0);
        assertThat(syncView.progress()).isCloseTo(16.0 / (FIXTURES * REPEATS), within(1e-9));
        assertThat(syncView.ratings()).isEqualTo(16);
        assertThat(syncView.durationMs()).isNull();
        assertThat(o.sessions().get(1).runs().get(0).progress()).isNull();
        assertThat(o.sessions().get(1).runs().get(0).inBandPct()).isNull();
    }

    @Test
    @DisplayName("a run reports attempts, in band, misses and its pass rate as in band over attempts")
    void runCounts() {
        SkyRatingEvalRunEntity run = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SUN, MID, 6);
        run.setErrorMessage("2 of 80 evaluations failed");

        SkyEvalOverview o = build();

        SkyEvalOverview.Run view = o.sessions().get(0).runs().get(0);
        assertThat(view.ratings()).isEqualTo((long) FIXTURES * REPEATS);
        assertThat(view.inBand()).isEqualTo((long) FIXTURES * 6);
        assertThat(view.below()).isEqualTo((long) FIXTURES * 2);
        assertThat(view.above()).isZero();
        assertThat(view.inBandPct()).isCloseTo(75.0, within(1e-9));
        assertThat(view.fixtureCount()).isEqualTo(FIXTURES);
        assertThat(view.runsPerFixture()).isEqualTo(REPEATS);
        assertThat(view.errorMessage()).isEqualTo("2 of 80 evaluations failed");
        assertThat(view.durationMs()).isEqualTo(120_000L);
        assertThat(o.sessions().get(0).byModel()).singleElement().satisfies(m -> {
            assertThat(m.runCount()).isEqualTo(1);
            assertThat(m.inBandPct()).isCloseTo(75.0, within(1e-9));
        });
    }

    // ---- money -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("cost converts per run at its start date's rate; the session sums the pounds")
    void costConvertsPerRunDate() {
        LocalDateTime t = LocalDateTime.of(2031, 10, 28, 23, 50);
        SkyRatingEvalRunEntity a = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, t, MID, 7);
        SkyRatingEvalRunEntity b = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, t.plusMinutes(20), MID, 7);
        a.setCostMicroDollars(1_000_000L);
        b.setCostMicroDollars(2_000_000L);

        SkyEvalOverview o = build();

        Session s = o.sessions().get(0);
        assertThat(s.runs().get(0).costGbp()).isCloseTo(1.0 * RATE, within(1e-9));
        assertThat(s.runs().get(1).costGbp()).isCloseTo(2.0 * RATE, within(1e-9));
        assertThat(s.costGbp()).isCloseTo(3.0 * RATE, within(1e-9));
        verify(exchangeRateService).getRate(LocalDate.of(2031, 10, 28));
        verify(exchangeRateService).getRate(LocalDate.of(2031, 10, 29));
        verifyNoMoreInteractions(exchangeRateService);
    }

    @Test
    @DisplayName("costPerRun averages the last four scheduled runs; per-rating figures come from manual runs")
    void costFigures() {
        for (int i = 0; i < 5; i++) {
            SkyRatingEvalRunEntity r = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                    SUN.minusDays(7L * i), MID, 7);
            r.setCostMicroDollars(i == 4 ? 9_000_000L : 1_000_000L);
        }
        SkyRatingEvalRunEntity manual = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                LocalDateTime.of(2031, 10, 30, 9, 0), MID, 7);
        manual.setTotalRuns(10);
        manual.setCostMicroDollars(500_000L);
        manual.setDurationMs(20_000L);

        SkyEvalOverview o = build();

        ModelStats haiku = stats(o, EvaluationModel.HAIKU);
        assertThat(haiku.costPerRunGbp()).isCloseTo(1.0 * RATE, within(1e-9));
        assertThat(haiku.costPerRatingGbp()).isCloseTo(0.5 * RATE / 10, within(1e-9));
        assertThat(haiku.secondsPerRating()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("a model with no manual run has no per-rating estimate")
    void noManualRunMeansNoEstimate() {
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 7);

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        assertThat(haiku.costPerRatingGbp()).isNull();
        assertThat(haiku.secondsPerRating()).isNull();
    }

    // ---- model rows --------------------------------------------------------------------------------------

    @Test
    @DisplayName("last 4 weekly pools the newest four scheduled completed runs by attempts, ignoring manual runs")
    void lastFourWeeklyPoolsAttempts() {
        int[] inBands = {8, 8, 6, 6, 0};
        for (int i = 0; i < inBands.length; i++) {
            done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusDays(7L * i), MID, inBands[i]);
        }
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SUN.plusDays(1), MID, 0);

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        // newest four scheduled: 8, 8, 6, 6 in band of 8 => 28 of 32 = 87.5%
        assertThat(haiku.last4WeeklyPct()).isCloseTo(87.5, within(1e-9));
        assertThat(haiku.last4Ratings()).isEqualTo(4L * FIXTURES * REPEATS);
        assertThat(haiku.last4Below()).isEqualTo(4L * FIXTURES);
        assertThat(haiku.last4Above()).isZero();
    }

    @Test
    @DisplayName("a pooled rate is a pooled count, not an average of rates")
    void pooledNotMeanOfRates() {
        SkyRatingEvalRunEntity big = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        // a small run: one attempt per fixture, all misses
        SkyRatingEvalRunEntity small = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN.minusDays(7), MID);
        small.setCompletedAt(SUN.minusDays(7).plusMinutes(5));
        for (var f : SkyRatingEvalFixtures.ALL) {
            aggregates.add(new SkyRatingEvalFixtureAggregate(small.getId(), f.name(), 1L, 0L, 0L, 1L, 1L, 5L, 5, 5));
        }
        assertThat(big.getId()).isNotEqualTo(small.getId());

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        // 80 in band of 90 attempts, not the mean of 100% and 0%
        assertThat(haiku.last4WeeklyPct()).isCloseTo(80.0 * 100 / 90, within(1e-9));
    }

    @Test
    @DisplayName("a model never run has null figures, UNCHECKED, and is not fresh")
    void neverRunModel() {
        ModelStats sonnet = stats(build(), EvaluationModel.SONNET_55);

        assertThat(sonnet.last4WeeklyPct()).isNull();
        assertThat(sonnet.onHeadPct()).isNull();
        assertThat(sonnet.lastTestedAt()).isNull();
        assertThat(sonnet.fresh()).isFalse();
        assertThat(sonnet.status()).isEqualTo(ModelStatus.UNCHECKED);
        assertThat(sonnet.lanes()).containsExactly(RunType.BATCH_NEAR_TERM);
        assertThat(sonnet.label()).isEqualTo("Sonnet 5.5");
    }

    @Test
    @DisplayName("HELD when HEAD is within three points of the last two runs on the previous version")
    void statusHeld() {
        priorHistory();
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        ModelStats sonnet = stats(o, EvaluationModel.SONNET_55);
        assertThat(sonnet.status()).isEqualTo(ModelStatus.HELD);
        assertThat(sonnet.onHeadPct()).isCloseTo(100.0, within(1e-9));
        assertThat(sonnet.previousVersionPct()).isCloseTo(100.0, within(1e-9));
        assertThat(sonnet.deltaPts()).isCloseTo(0.0, within(1e-9));
        assertThat(sonnet.onHeadRuns()).isEqualTo(1);
        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.HELD);
        assertThat(o.verdict().headline()).isEqualTo("Both production models held up on today's change.");
    }

    @Test
    @DisplayName("DROPPED at exactly minus three points; HELD just above it")
    void droppedBoundary() {
        for (int i = 0; i < 2; i++) {
            SkyRatingEvalRunEntity prev = base(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED,
                    SkyRatingEvalStatus.COMPLETED, SUN.minusHours(i), MID);
            prev.setCompletedAt(SUN.minusHours(i).plusMinutes(5));
            oneFixture(prev, 100, 0);
        }
        SkyRatingEvalRunEntity head = base(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, HEAD_DATE.plusHours(1), HEAD);
        head.setCompletedAt(HEAD_DATE.plusHours(2));
        oneFixture(head, 97, 3);
        SkyRatingEvalRunEntity haikuPrev = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN, MID);
        haikuPrev.setCompletedAt(SUN.plusMinutes(5));
        oneFixture(haikuPrev, 100, 0);
        SkyRatingEvalRunEntity haikuHead = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, HEAD_DATE.plusHours(1), HEAD);
        haikuHead.setCompletedAt(HEAD_DATE.plusHours(2));
        oneFixture(haikuHead, 98, 2);

        SkyEvalOverview o = build();

        // Sonnet: the last two previous runs pool 200 of 200 (100%); HEAD is 97 of 100 => exactly -3.0
        assertThat(stats(o, EvaluationModel.SONNET_55).deltaPts()).isCloseTo(-3.0, within(1e-9));
        assertThat(stats(o, EvaluationModel.SONNET_55).status()).isEqualTo(ModelStatus.DROPPED);
        // Haiku: 100% -> 98% is -2.0, inside the three-point allowance
        assertThat(stats(o, EvaluationModel.HAIKU).deltaPts()).isCloseTo(-2.0, within(1e-9));
        assertThat(stats(o, EvaluationModel.HAIKU).status()).isEqualTo(ModelStatus.HELD);
    }

    /** Gives the run one fixture's worth of results: {@code inBand} passes and {@code below} misses. */
    private void oneFixture(SkyRatingEvalRunEntity run, int inBand, int below) {
        aggregates.add(new SkyRatingEvalFixtureAggregate(run.getId(), SkyRatingEvalFixtures.ALL.get(0).name(),
                (long) (inBand + below), (long) inBand, (long) below, 0L, (long) (inBand + below), 300L, 3, 3));
    }

    @Test
    @DisplayName("DROPPED: the verdict names the first dropped production model and the body lines carry before/after")
    void verdictDropped() {
        priorHistory();
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 6);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        assertThat(stats(o, EvaluationModel.SONNET_55).status()).isEqualTo(ModelStatus.DROPPED);
        assertThat(stats(o, EvaluationModel.SONNET_55).deltaPts()).isCloseTo(-25.0, within(1e-9));
        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.DROPPED);
        assertThat(o.verdict().headline()).isEqualTo("a91b0e2 made Sonnet 5.5 worse.");
        assertThat(o.verdict().model()).isEqualTo(EvaluationModel.SONNET_55);
        assertThat(o.verdict().droppedNonProduction()).isFalse();
        assertThat(o.verdict().lines()).hasSize(2);
        assertThat(o.verdict().lines().get(0).beforePct()).isCloseTo(100.0, within(1e-9));
        assertThat(o.verdict().lines().get(0).afterPct()).isCloseTo(75.0, within(1e-9));
        assertThat(o.verdict().lines().get(1).label()).isEqualTo("Haiku 4.5");
    }

    @Test
    @DisplayName("UNCHECKED: a production model with no run on HEAD")
    void verdictUnchecked() {
        priorHistory();
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.UNCHECKED);
        assertThat(o.verdict().headline()).isEqualTo("a91b0e2 hasn't been checked on Sonnet 5.5 yet.");
        assertThat(o.verdict().model()).isEqualTo(EvaluationModel.SONNET_55);
    }

    @Test
    @DisplayName("a drop outranks an unchecked model in the verdict")
    void droppedOutranksUnchecked() {
        priorHistory();
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 2);

        SkyEvalOverview o = build();

        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.DROPPED);
        assertThat(o.verdict().headline()).isEqualTo("a91b0e2 made Haiku 4.5 worse.");
    }

    @Test
    @DisplayName("NO_BASELINE: run on HEAD with nothing earlier, so nothing to compare with")
    void verdictNoBaseline() {
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        assertThat(stats(o, EvaluationModel.SONNET_55).status()).isEqualTo(ModelStatus.NO_BASELINE);
        assertThat(stats(o, EvaluationModel.SONNET_55).deltaPts()).isNull();
        assertThat(stats(o, EvaluationModel.SONNET_55).onHeadPct()).isCloseTo(100.0, within(1e-9));
        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.NO_BASELINE);
        assertThat(o.verdict().headline()).isEqualTo("Nothing to compare a91b0e2 with yet.");
    }

    @Test
    @DisplayName("one lane model with a baseline and one without is HELD")
    void verdictHeldWhenOnlyOneLaneHasABaseline() {
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.HELD);
    }

    @Test
    @DisplayName("both lanes on one model: one verdict line, 'Production' wording, the model listed once in defaults")
    void bothLanesOnTheSameModel() {
        farModel = EvaluationModel.SONNET_55;
        priorHistory();
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 8);

        SkyEvalOverview o = build();

        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.HELD);
        assertThat(o.verdict().headline()).isEqualTo("Production held up on today's change.");
        assertThat(o.verdict().lines()).hasSize(1);
        assertThat(stats(o, EvaluationModel.SONNET_55).lanes())
                .containsExactly(RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM);
        assertThat(o.lanes()).extracting(l -> l.model())
                .containsExactly(EvaluationModel.SONNET_55, EvaluationModel.SONNET_55);
        assertThat(o.checkDefaults().models()).containsExactly(EvaluationModel.SONNET_55);
    }

    @Test
    @DisplayName("a drop in a model not used in production sets droppedNonProduction without changing a HELD verdict")
    void droppedNonProduction() {
        priorHistory();
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 4);

        SkyEvalOverview o = build();

        assertThat(o.verdict().kind()).isEqualTo(VerdictKind.HELD);
        assertThat(o.verdict().droppedNonProduction()).isTrue();
        assertThat(stats(o, EvaluationModel.OPUS).lanes()).isEmpty();
    }

    @Test
    @DisplayName("the baseline is the last two runs on the most recent earlier version, not older versions")
    void baselineIsLastTwoRunsOnMostRecentEarlierVersion() {
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2, OLD, 0);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusHours(3), MID, 2);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusHours(2), MID, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusHours(1), MID, 6);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        // last two MID runs: 8 and 6 in band of 8 => 87.5%
        assertThat(haiku.previousVersionPct()).isCloseTo(87.5, within(1e-9));
        assertThat(haiku.onHeadPct()).isCloseTo(87.5, within(1e-9));
        assertThat(haiku.deltaPts()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("a later-committed version is not a baseline for HEAD")
    void laterVersionsAreNotBaselines() {
        headDate = MID_DATE.minusDays(1);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 4);

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        assertThat(haiku.status()).isEqualTo(ModelStatus.NO_BASELINE);
    }

    @Test
    @DisplayName("before and after are compared over the fixtures both sides ran (a subset check)")
    void likeForLikeOverIntersectionOfFixtures() {
        String first = SkyRatingEvalFixtures.ALL.get(0).name();
        // previous: every fixture, fixture 0 perfect and all others at 0%
        SkyRatingEvalRunEntity prev = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN, MID);
        prev.setCompletedAt(SUN.plusMinutes(5));
        for (var f : SkyRatingEvalFixtures.ALL) {
            long inBand = f.name().equals(first) ? 8 : 0;
            aggregates.add(new SkyRatingEvalFixtureAggregate(prev.getId(), f.name(), 8L, inBand, 8 - inBand, 0L,
                    8L, 24L, 3, 3));
        }
        // HEAD: a quick check of fixture 0 only, 6 of 8 in band
        SkyRatingEvalRunEntity head = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, HEAD_DATE.plusHours(1), HEAD);
        head.setCompletedAt(HEAD_DATE.plusHours(2));
        head.setFixtureNames(first);
        aggregates.add(new SkyRatingEvalFixtureAggregate(head.getId(), first, 8L, 6L, 2L, 0L, 8L, 24L, 3, 3));

        ModelStats haiku = stats(build(), EvaluationModel.HAIKU);

        assertThat(haiku.previousVersionPct()).isCloseTo(100.0, within(1e-9));
        assertThat(haiku.onHeadPct()).isCloseTo(75.0, within(1e-9));
        assertThat(haiku.deltaPts()).isCloseTo(-25.0, within(1e-9));
        assertThat(haiku.status()).isEqualTo(ModelStatus.DROPPED);
    }

    @Test
    @DisplayName("a retired fixture (not in the registry) never moves a figure")
    void retiredFixtureIsDropped() {
        SkyRatingEvalRunEntity run = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);
        aggregates.add(new SkyRatingEvalFixtureAggregate(run.getId(), "retired-fixture", 8L, 0L, 8L, 0L, 8L, 8L, 1, 1));

        SkyEvalOverview o = build();

        assertThat(stats(o, EvaluationModel.HAIKU).last4WeeklyPct()).isCloseTo(100.0, within(1e-9));
        assertThat(o.runFixtures()).extracting(rf -> rf.fixture()).doesNotContain("retired-fixture");
    }

    @Test
    @DisplayName("a failed attempt is a non-pass in the pass rate but is left out of mean, min and max")
    void failedAttemptsExcludedFromMeansOnly() {
        SkyRatingEvalRunEntity run = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN, MID);
        run.setCompletedAt(SUN.plusMinutes(5));
        String fixture = SkyRatingEvalFixtures.ALL.get(0).name();
        // 4 attempts: 2 in band (ratings 3, 4), 1 below (rating 1), 1 failed (no rating)
        aggregates.add(new SkyRatingEvalFixtureAggregate(run.getId(), fixture, 4L, 2L, 1L, 0L, 3L, 8L, 1, 4));

        SkyEvalOverview o = build();

        assertThat(stats(o, EvaluationModel.HAIKU).last4WeeklyPct()).isCloseTo(50.0, within(1e-9));
        assertThat(o.runFixtures()).singleElement().satisfies(rf -> {
            assertThat(rf.n()).isEqualTo(4);
            assertThat(rf.inBand()).isEqualTo(2);
            assertThat(rf.below()).isEqualTo(1);
            assertThat(rf.mean()).isCloseTo(8.0 / 3, within(1e-9));
            assertThat(rf.min()).isEqualTo(1);
            assertThat(rf.max()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("a fixture whose every attempt failed has a null mean")
    void allFailedFixtureHasNullMean() {
        SkyRatingEvalRunEntity run = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN, MID);
        run.setCompletedAt(SUN.plusMinutes(5));
        aggregates.add(new SkyRatingEvalFixtureAggregate(run.getId(), SkyRatingEvalFixtures.ALL.get(0).name(),
                2L, null, null, null, null, null, null, null));

        SkyEvalOverview o = build();

        assertThat(o.runFixtures()).singleElement().satisfies(rf -> {
            assertThat(rf.mean()).isNull();
            assertThat(rf.min()).isNull();
            assertThat(rf.inBand()).isZero();
        });
    }

    @Test
    @DisplayName("fresh means finished within 21 days; models sort fresh first, then by accuracy, unknowns last")
    void freshnessAndSorting() {
        // NOW = 2031-11-02T12:00Z. Boundary: completedAt exactly 21 days earlier is still fresh.
        LocalDateTime edge = LocalDateTime.of(2031, 10, 12, 12, 0);
        SkyRatingEvalRunEntity sonnet = done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED,
                edge.minusMinutes(10), MID, 8);
        sonnet.setCompletedAt(edge);
        SkyRatingEvalRunEntity opus = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED,
                edge.minusMinutes(11), MID, 8);
        opus.setCompletedAt(edge.minusSeconds(1));
        done(EvaluationModel.HAIKU_55, SkyRatingEvalTrigger.SCHEDULED, LocalDateTime.of(2031, 10, 30, 3, 0), MID, 6);
        done(EvaluationModel.SONNET, SkyRatingEvalTrigger.SCHEDULED, LocalDateTime.of(2031, 10, 31, 3, 0), MID, 7);

        SkyEvalOverview o = build();

        assertThat(stats(o, EvaluationModel.SONNET_55).fresh()).isTrue();
        assertThat(stats(o, EvaluationModel.OPUS).fresh()).isFalse();
        assertThat(stats(o, EvaluationModel.HAIKU).fresh()).isFalse();
        assertThat(o.models()).extracting(ModelStats::model).containsExactly(
                EvaluationModel.SONNET_55, EvaluationModel.SONNET, EvaluationModel.HAIKU_55,
                EvaluationModel.OPUS, EvaluationModel.HAIKU);
        assertThat(stats(o, EvaluationModel.OPUS).lastTestedAt()).isEqualTo(Instant.parse("2031-10-12T11:59:59Z"));
    }

    // ---- lanes -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("lanes are labelled, in order, and compare other fresh models with the lane's model")
    void lanesCompareFreshModels() {
        priorHistory();
        SkyRatingEvalRunEntity opusPrev = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 6);
        opusPrev.setCostMicroDollars(1_000_000L);
        SkyRatingEvalRunEntity opusHead = done(EvaluationModel.OPUS, SkyRatingEvalTrigger.MANUAL,
                HEAD_DATE.plusHours(1), HEAD, 4);
        opusHead.setCostMicroDollars(1_000_000L);
        // a stale model is not compared
        done(EvaluationModel.SONNET, SkyRatingEvalTrigger.SCHEDULED, LocalDateTime.of(2031, 9, 1, 3, 0), MID, 8);
        for (SkyRatingEvalRunEntity r : runs) {
            if (r.getModel() == EvaluationModel.HAIKU || r.getModel() == EvaluationModel.SONNET_55) {
                r.setCostMicroDollars(500_000L);
            }
        }
        runs.stream().filter(r -> r.getModel() == EvaluationModel.HAIKU)
                .forEach(r -> r.setCostMicroDollars(100_000L));

        SkyEvalOverview o = build();

        assertThat(o.lanes()).extracting(l -> l.runType())
                .containsExactly(RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM);
        SkyEvalOverview.Lane near = o.lanes().get(0);
        assertThat(near.label()).isEqualTo("T and T+1 forecasts");
        assertThat(near.purpose()).isEqualTo("Must be as accurate as possible");
        assertThat(near.model()).isEqualTo(EvaluationModel.SONNET_55);
        assertThat(o.lanes().get(1).label()).isEqualTo("T+2 and T+3 look-ahead");
        assertThat(o.lanes().get(1).purpose())
                .isEqualTo("Cheaper, needs to be accurate enough to plan with");
        assertThat(near.compared()).extracting(c -> c.model())
                .containsExactly(EvaluationModel.HAIKU, EvaluationModel.OPUS);
        SkyEvalOverview.Compared haiku = near.compared().get(0);
        assertThat(haiku.lanes()).containsExactly(RunType.BATCH_FAR_TERM);
        assertThat(haiku.last4WeeklyPct()).isCloseTo(87.5, within(1e-9));
        assertThat(haiku.deltaPts()).isCloseTo(-12.5, within(1e-9));
        assertThat(haiku.costMultiple()).isCloseTo(0.2, within(1e-9));
        assertThat(haiku.fellToOnHeadPct()).isNull();
        SkyEvalOverview.Compared opus = near.compared().get(1);
        assertThat(opus.lanes()).isEmpty();
        assertThat(opus.fellToOnHeadPct()).isCloseTo(50.0, within(1e-9));
        assertThat(opus.costMultiple()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("a lane whose model has no weekly history leaves deltas and multiples null")
    void laneWithoutHistory() {
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 7);

        SkyEvalOverview o = build();

        SkyEvalOverview.Compared haiku = o.lanes().get(0).compared().get(0);
        assertThat(haiku.model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(haiku.deltaPts()).isNull();
        assertThat(haiku.costMultiple()).isNull();
    }

    // ---- comparisons -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a fixture that moves 20 points is listed with its direction and dominant miss side")
    void comparisonListsMovedFixtures() {
        String f0 = SkyRatingEvalFixtures.ALL.get(0).name();
        String f1 = SkyRatingEvalFixtures.ALL.get(1).name();
        String f2 = SkyRatingEvalFixtures.ALL.get(2).name();
        String f3 = SkyRatingEvalFixtures.ALL.get(3).name();
        String f4 = SkyRatingEvalFixtures.ALL.get(4).name();
        SkyRatingEvalRunEntity prev = base(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED,
                SkyRatingEvalStatus.COMPLETED, SUN, MID);
        prev.setCompletedAt(SUN.plusMinutes(5));
        SkyRatingEvalRunEntity head = base(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, HEAD_DATE.plusHours(1), HEAD);
        head.setCompletedAt(HEAD_DATE.plusHours(2));
        // f0: 100% -> 70% rated too low (worse, BELOW); f1: 50% -> 80% (better, no misses side: 2 above)
        // f2: 90% -> 80% (not moved); f3: 100% -> 50% with a tie 2 below / 2 above (side null)
        // f4: 2 attempts each side (below the three-attempt floor)
        pair(prev, head, f0, 10, 10, 10, 7, 3, 0);
        pair(prev, head, f1, 10, 5, 10, 8, 0, 2);
        pair(prev, head, f2, 10, 9, 10, 8, 2, 0);
        pair(prev, head, f3, 4, 4, 4, 2, 2, 2);
        pair(prev, head, f4, 2, 2, 2, 0, 2, 0);

        SkyEvalOverview o = build();

        Comparison c = o.comparisons().get(0);
        assertThat(c.version().sha()).isEqualTo(HEAD);
        ComparisonRow row = c.rows().stream().filter(r -> r.model() == EvaluationModel.SONNET_55).findFirst()
                .orElseThrow();
        assertThat(row.notRun()).isFalse();
        assertThat(row.moved()).extracting(m -> m.fixture()).containsExactly(f3, f0, f1);
        assertThat(row.moved().get(1).direction()).isEqualTo(SkyEvalOverview.Direction.WORSE);
        assertThat(row.moved().get(1).missSide()).isEqualTo(SkyEvalOverview.MissSide.BELOW);
        assertThat(row.moved().get(1).beforePct()).isCloseTo(100.0, within(1e-9));
        assertThat(row.moved().get(1).afterPct()).isCloseTo(70.0, within(1e-9));
        assertThat(row.moved().get(2).direction()).isEqualTo(SkyEvalOverview.Direction.BETTER);
        assertThat(row.moved().get(2).missSide()).isEqualTo(SkyEvalOverview.MissSide.ABOVE);
        assertThat(row.moved().get(0).missSide()).isNull();
    }

    /** Adds one fixture to a before run and an after run: attempts, in band, below and above for the after run. */
    private void pair(SkyRatingEvalRunEntity before, SkyRatingEvalRunEntity after, String fixture,
            long beforeN, long beforeIn, long afterN, long afterIn, long afterBelow, long afterAbove) {
        aggregates.add(new SkyRatingEvalFixtureAggregate(before.getId(), fixture, beforeN, beforeIn,
                beforeN - beforeIn, 0L, beforeN, beforeN * 3, 3, 3));
        aggregates.add(new SkyRatingEvalFixtureAggregate(after.getId(), fixture, afterN, afterIn, afterBelow,
                afterAbove, afterN, afterN * 3, 3, 3));
    }

    @Test
    @DisplayName("comparisons list the last five versions that have an earlier version with runs, newest first")
    void comparisonsLimitToFiveVersions() {
        String[] shas = {"v1sha0000000", "v2sha0000000", "v3sha0000000", "v4sha0000000", "v5sha0000000",
            "v6sha0000000", "v7sha0000000"};
        for (int i = 0; i < shas.length; i++) {
            SkyRatingEvalRunEntity r = done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                    LocalDateTime.of(2031, 10, 1 + i, 3, 0), shas[i], 7);
            r.setGitCommitDate(LocalDateTime.of(2031, 10, 1 + i, 1, 0));
        }

        SkyEvalOverview o = build();

        // the oldest version has nothing earlier, so six qualify; five are served
        assertThat(o.comparisons()).extracting(c -> c.version().sha())
                .containsExactly(shas[6], shas[5], shas[4], shas[3], shas[2]);
    }

    @Test
    @DisplayName("a model with no run on the compared version is notRun; production models come first")
    void comparisonNotRunAndOrder() {
        priorHistory();
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2, OLD, 7);
        done(EvaluationModel.OPUS, SkyRatingEvalTrigger.SCHEDULED, SUN2, OLD, 7);
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, HEAD_DATE.plusHours(1), HEAD, 7);

        SkyEvalOverview o = build();

        Comparison head = o.comparisons().get(0);
        assertThat(head.version().sha()).isEqualTo(HEAD);
        assertThat(head.rows()).extracting(r -> r.model())
                .containsExactly(EvaluationModel.SONNET_55, EvaluationModel.HAIKU, EvaluationModel.OPUS);
        assertThat(head.rows().get(0).notRun()).isTrue();
        assertThat(head.rows().get(0).afterPct()).isNull();
        assertThat(head.rows().get(1).notRun()).isFalse();
        assertThat(head.rows().get(1).beforePct()).isCloseTo(87.5, within(1e-9));
        assertThat(head.rows().get(2).notRun()).isTrue();
    }

    @Test
    @DisplayName("a version only one model ran on has an after figure but no before when nothing precedes it")
    void comparisonWithoutEarlierRunsForTheModel() {
        done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN2, OLD, 7);
        done(EvaluationModel.SONNET_55, SkyRatingEvalTrigger.SCHEDULED, SUN, MID, 8);

        SkyEvalOverview o = build();

        ComparisonRow sonnet = o.comparisons().get(0).rows().stream()
                .filter(r -> r.model() == EvaluationModel.SONNET_55).findFirst().orElseThrow();
        assertThat(o.comparisons().get(0).version().sha()).isEqualTo(MID);
        assertThat(sonnet.afterPct()).isCloseTo(100.0, within(1e-9));
        assertThat(sonnet.beforePct()).isNull();
        assertThat(sonnet.deltaPts()).isNull();
        assertThat(sonnet.moved()).isEmpty();
    }

    // ---- per model and fixture ---------------------------------------------------------------------------

    @Test
    @DisplayName("modelFixtures pools each model's last four completed runs per fixture")
    void modelFixturesLastFour() {
        String f0 = SkyRatingEvalFixtures.ALL.get(0).name();
        for (int i = 0; i < 5; i++) {
            SkyRatingEvalRunEntity r = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                    SkyRatingEvalStatus.COMPLETED, SUN.minusDays(i), MID);
            r.setCompletedAt(SUN.minusDays(i).plusMinutes(5));
            // the oldest run (i = 4) misses everything but must fall outside the window
            long in = i == 4 ? 0 : 6;
            aggregates.add(new SkyRatingEvalFixtureAggregate(r.getId(), f0, 8L, in, 1L, 8 - in - 1, 8L, 24L, 3, 3));
        }

        SkyEvalOverview o = build();

        assertThat(o.modelFixtures()).singleElement().satisfies(mf -> {
            assertThat(mf.model()).isEqualTo(EvaluationModel.HAIKU);
            assertThat(mf.fixture()).isEqualTo(f0);
            assertThat(mf.last4Misses()).isEqualTo(8L);
            assertThat(mf.last4Pct()).isCloseTo(75.0, within(1e-9));
        });
    }

    @Test
    @DisplayName("runFixtures keep each model's newest thirteen completed runs only")
    void runFixturesCapAtThirteenPerModel() {
        for (int i = 0; i < 15; i++) {
            SkyRatingEvalRunEntity r = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED,
                    SkyRatingEvalStatus.COMPLETED, SUN.minusDays(i), MID);
            r.setCompletedAt(SUN.minusDays(i).plusMinutes(5));
            oneFixture(r, 8, 0);
        }
        base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SkyRatingEvalStatus.FAILED, SUN.plusDays(1), MID);

        SkyEvalOverview o = build();

        assertThat(o.runFixtures()).hasSize(13);
    }

    // ---- check defaults ----------------------------------------------------------------------------------

    @Test
    @DisplayName("checkDefaults offer the production models, three repeats and the latest session misses")
    void checkDefaults() {
        String f0 = SkyRatingEvalFixtures.ALL.get(0).name();
        String f3 = SkyRatingEvalFixtures.ALL.get(3).name();
        // an older session missed f0; the latest finished session missed f3 (and a running newer one is ignored)
        SkyRatingEvalRunEntity older = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, LocalDateTime.of(2031, 10, 20, 9, 0), MID);
        aggregates.add(new SkyRatingEvalFixtureAggregate(older.getId(), f0, 8L, 4L, 4L, 0L, 8L, 8L, 1, 1));
        SkyRatingEvalRunEntity latest = base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL,
                SkyRatingEvalStatus.COMPLETED, LocalDateTime.of(2031, 10, 28, 9, 0), MID);
        aggregates.add(new SkyRatingEvalFixtureAggregate(latest.getId(), f3, 8L, 4L, 0L, 4L, 8L, 8L, 1, 1));
        aggregates.add(new SkyRatingEvalFixtureAggregate(latest.getId(), f0, 8L, 8L, 0L, 0L, 8L, 8L, 1, 1));
        base(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalStatus.RUNNING,
                LocalDateTime.of(2031, 11, 1, 9, 0), MID);

        SkyEvalOverview o = build();

        assertThat(o.checkDefaults().models())
                .containsExactly(EvaluationModel.SONNET_55, EvaluationModel.HAIKU);
        assertThat(o.checkDefaults().runsPerFixture()).isEqualTo(3);
        assertThat(o.checkDefaults().missedLastTime()).containsExactly(f3);
    }

    @Test
    @DisplayName("with no finished session nothing was missed last time")
    void checkDefaultsEmptyHistory() {
        SkyEvalOverview o = build();

        assertThat(o.checkDefaults().missedLastTime()).isEmpty();
        assertThat(o.sessions()).isEmpty();
        assertThat(o.runFixtures()).isEmpty();
        assertThat(o.comparisons()).isEmpty();
        verify(runRepository).findTop500ByOrderByRunTimestampDesc();
        verify(resultRepository).firstSeenByFixture();
        verifyNoMoreInteractions(resultRepository);
    }

    @Test
    @DisplayName("the overview reads the result table in exactly two queries however many runs there are")
    void constantNumberOfResultQueries() {
        for (int i = 0; i < 12; i++) {
            done(EvaluationModel.HAIKU, SkyRatingEvalTrigger.SCHEDULED, SUN.minusDays(i), MID, 7);
        }
        List<Long> ids = runs.stream().map(SkyRatingEvalRunEntity::getId).toList();

        build();

        verify(resultRepository).aggregateByRunAndFixture(ids);
        verify(resultRepository).firstSeenByFixture();
        verifyNoMoreInteractions(resultRepository);
        verify(runRepository).findTop500ByOrderByRunTimestampDesc();
        verifyNoMoreInteractions(runRepository);
    }

    // ---- schedule ----------------------------------------------------------------------------------------

    private static SchedulerJobConfigEntity cronJob(String cron, SchedulerJobStatus status, String zone) {
        return SchedulerJobConfigEntity.builder().jobKey("sky_rating_eval").cronExpression(cron).status(status)
                .timeZone(zone).build();
    }

    @Test
    @DisplayName("a weekly cron renders in UK time: 03:00 UTC is 03:00 in November and 04:00 in summer")
    void scheduleLabelWeekly() {
        job = Optional.of(cronJob("0 0 3 * * MON", SchedulerJobStatus.ACTIVE, null));

        SkyEvalOverview winter = build();

        assertThat(winter.schedule().label()).isEqualTo("weekly, Mon 03:00");
        assertThat(winter.schedule().jobStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        assertThat(winter.schedule().models()).containsExactly(EvaluationModel.HAIKU, EvaluationModel.SONNET_55);
    }

    @Test
    @DisplayName("in British summer time the same UTC cron reads an hour later")
    void scheduleLabelSummerTime() {
        clock = Clock.fixed(Instant.parse("2031-07-01T12:00:00Z"), ZoneOffset.UTC);
        job = Optional.of(cronJob("0 0 3 * * MON", SchedulerJobStatus.ACTIVE, null));

        assertThat(build().schedule().label()).isEqualTo("weekly, Mon 04:00");
    }

    @Test
    @DisplayName("the cron is read in the job's own zone before being shown in UK time")
    void scheduleLabelHonoursCronZone() {
        job = Optional.of(cronJob("0 30 9 * * SUN", SchedulerJobStatus.ACTIVE, "America/New_York"));

        // 09:30 New York in November is 14:30 UTC, which is 14:30 in London
        assertThat(build().schedule().label()).isEqualTo("weekly, Sun 14:30");
    }

    @Test
    @DisplayName("a paused or config-disabled job reads 'weekly run paused'")
    void scheduleLabelPaused() {
        job = Optional.of(cronJob("0 0 3 * * MON", SchedulerJobStatus.PAUSED, null));
        assertThat(build().schedule().label()).isEqualTo("weekly run paused");
    }

    @Test
    @DisplayName("a cron that is not a single weekly fire is shown raw")
    void scheduleLabelRawCron() {
        job = Optional.of(cronJob("0 0 3 * * MON-FRI", SchedulerJobStatus.ACTIVE, null));
        assertThat(build().schedule().label()).isEqualTo("0 0 3 * * MON-FRI");
    }

    @Test
    @DisplayName("an unreadable time zone or cron falls back to the raw cron")
    void scheduleLabelBadZone() {
        job = Optional.of(cronJob("0 0 3 * * MON", SchedulerJobStatus.ACTIVE, "Not/AZone"));
        assertThat(build().schedule().label()).isEqualTo("0 0 3 * * MON");
    }

    @Test
    @DisplayName("an active job with no cron, and a missing job row, have their own labels")
    void scheduleLabelNoCronAndNoRow() {
        job = Optional.of(cronJob(null, SchedulerJobStatus.ACTIVE, null));
        assertThat(build().schedule().label()).isEqualTo("no weekly schedule");
    }

    @Test
    @DisplayName("without a job row the schedule has no status")
    void scheduleNoRow() {
        SkyEvalOverview o = build();

        assertThat(o.schedule().label()).isEqualTo("no weekly schedule");
        assertThat(o.schedule().jobStatus()).isNull();
    }
}
