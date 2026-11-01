package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ApiCallCostRow;
import com.gregochr.goldenhour.model.RunCostProfile;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.util.Rewind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RunCostProfileService}: which logged rows belong to which Run Config tab, how the
 * per-call and per-day figures are derived, and how estimates are priced.
 */
@ExtendWith(MockitoExtension.class)
class RunCostProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 10, 2, 23, 0);
    private static final long MICRO_PER_USD = 1_000_000L;

    @Mock
    private ApiCallLogRepository apiCallLogRepository;
    @Mock
    private JobRunRepository jobRunRepository;

    private CostCalculator costCalculator;
    private CostProperties costProperties;
    private MutableClock clock;
    private RunCostProfileService service;

    @BeforeEach
    void setUp() {
        costProperties = new CostProperties();
        costCalculator = new CostCalculator(costProperties);
        clock = new MutableClock(NOW);
        service = new RunCostProfileService(apiCallLogRepository, jobRunRepository, costCalculator,
                costProperties, clock);
    }

    @AfterEach
    void clearRewind() {
        Rewind.clear();
    }

    /** A clock whose instant a test can move. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static ApiCallCostRow bestBet(long jobRunId, String url, boolean ok, long cost) {
        return new ApiCallCostRow(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), url, null, false, ok,
                EvaluationModel.SONNET, null, 9_500L, 726L, 0L, 0L, cost, jobRunId, null);
    }

    private static ApiCallCostRow batch(String customId, LocalDateTime jobStarted, LocalDate target,
            EvaluationModel model, Long pipelineRunId, boolean ok, long cost) {
        return new ApiCallCostRow(RunType.SCHEDULED_BATCH, jobStarted, null, customId, true, ok, model, target,
                1_000L, 200L, 5_000L, 0L, cost, 500L + (pipelineRunId == null ? 0 : pipelineRunId), pipelineRunId);
    }

    private void rows(ApiCallCostRow... rows) {
        when(apiCallLogRepository.findCostRowsSince(WINDOW_START)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("the window is the 30 UK days ending today, from the injected clock")
    void windowStartsThirtyUkDaysBack() {
        rows();

        service.costProfiles();

        verify(apiCallLogRepository).findCostRowsSince(WINDOW_START);
    }

    @Test
    @DisplayName("best-bet figures count only briefing-best-bet rows, not the gloss rows in the same run")
    void bestBetExcludesGloss() {
        rows(bestBet(1, "briefing-best-bet", true, 39_000),
                bestBet(1, "briefing-gloss", true, 1_500),
                bestBet(2, "briefing-best-bet", true, 41_000));

        RunCostProfile p = service.costProfiles().get("BRIEFING_BEST_BET");

        assertThat(p.callsPerRun()).isEqualTo(1.0);
        assertThat(p.runsPerDay()).isCloseTo(2 / 30.0, within(1e-9));
        assertThat(p.measured()).containsOnlyKeys("SONNET");
        assertThat(p.measured().get("SONNET").calls()).isEqualTo(2);
        assertThat(p.measured().get("SONNET").usdPerCall()).isCloseTo(0.040, within(1e-9));
    }

    @Test
    @DisplayName("failed calls count in per-day spend but not in the per-call average")
    void failedCallsCountOnlyInPerDay() {
        rows(bestBet(1, "briefing-best-bet", true, 40_000),
                bestBet(2, "briefing-best-bet", false, 20_000));

        RunCostProfile.ModelCost m = service.costProfiles().get("BRIEFING_BEST_BET").measured().get("SONNET");

        assertThat(m.calls()).isEqualTo(1);
        assertThat(m.usdPerCall()).isCloseTo(0.040, within(1e-9));
        assertThat(m.usdPerDay()).isCloseTo(0.060 / 30, within(1e-9));
    }

    @Test
    @DisplayName("aurora sums synchronous AURORA_EVALUATION rows and au- batch rows")
    void auroraSumsSyncAndBatch() {
        ApiCallCostRow sync = new ApiCallCostRow(RunType.AURORA_EVALUATION, LocalDateTime.of(2026, 10, 30, 21, 0),
                null, null, false, true, EvaluationModel.SONNET, null, 12_000L, 27_000L, 0L, 0L, 440_000L, 7L, null);
        ApiCallCostRow viaBatch = new ApiCallCostRow(RunType.SCHEDULED_BATCH,
                LocalDateTime.of(2026, 10, 31, 21, 0), null, "au-2026-10-31", true, true,
                EvaluationModel.SONNET, null, 12_000L, 27_000L, 0L, 0L, 220_000L, 8L, 99L);

        rows(sync, viaBatch);

        RunCostProfile p = service.costProfiles().get("AURORA_EVALUATION");

        assertThat(p.measured().get("SONNET").calls()).isEqualTo(2);
        assertThat(p.runsPerDay()).isCloseTo(2 / 30.0, within(1e-9));
    }

    @Test
    @DisplayName("a batch request is NEAR at T+1 and FAR at T+2, bucketed on the UK civil date of the submission")
    void nearFarBoundaryOnUkDate() {
        // 23:30 UTC on 30 Oct is 23:30 GMT? BST ended 25 Oct 2026, so use a summer date instead:
        // the job started 23:30 UTC on 14 Jul = 00:30 BST on 15 Jul, so target 16 Jul is T+1, 17 Jul T+2.
        LocalDateTime started = LocalDateTime.of(2026, 7, 14, 23, 30);
        clock.instant = Instant.parse("2026-07-20T12:00:00Z");
        when(apiCallLogRepository.findCostRowsSince(LocalDateTime.of(2026, 6, 20, 23, 0)))
                .thenReturn(List.of(
                        batch("fc-1-2026-07-16-SUNSET", started, LocalDate.of(2026, 7, 16),
                                EvaluationModel.HAIKU, 10L, true, 2_000),
                        batch("fc-1-2026-07-17-SUNSET", started, LocalDate.of(2026, 7, 17),
                                EvaluationModel.SONNET, 10L, true, 4_000)));

        Map<String, RunCostProfile> profiles = service.costProfiles();

        assertThat(profiles.get("BATCH_NEAR_TERM").measured()).containsOnlyKeys("HAIKU");
        assertThat(profiles.get("BATCH_FAR_TERM").measured()).containsOnlyKeys("SONNET");
    }

    @Test
    @DisplayName("force-submitted batches (no pipeline cycle) are excluded from both batch tabs")
    void forceSubmitExcluded() {
        LocalDateTime started = LocalDateTime.of(2026, 10, 30, 2, 0);
        rows(batch("fc-1-2026-11-02-SUNRISE", started, LocalDate.of(2026, 11, 2), EvaluationModel.HAIKU, null,
                true, 2_000));

        Map<String, RunCostProfile> profiles = service.costProfiles();

        assertThat(profiles.get("BATCH_NEAR_TERM").runsPerDay()).isZero();
        assertThat(profiles.get("BATCH_FAR_TERM").runsPerDay()).isZero();
    }

    @Test
    @DisplayName("batch runs are pipeline cycles: a retry job run inside one cycle does not add a run")
    void retryJobRunsCountInsideOneCycle() {
        LocalDateTime started = LocalDateTime.of(2026, 10, 30, 2, 0);
        LocalDate target = LocalDate.of(2026, 10, 30);
        ApiCallCostRow first = batch("fc-1-2026-10-30-SUNRISE", started, target, EvaluationModel.HAIKU, 10L,
                true, 2_000);
        // same pipeline cycle, but a different (retry) job run
        ApiCallCostRow retry = new ApiCallCostRow(RunType.SCHEDULED_BATCH, started.plusHours(1), null,
                "fc-2-2026-10-30-SUNRISE", true, true, EvaluationModel.HAIKU, target, 1_000L, 200L, 5_000L, 0L,
                2_000L, 777L, 10L);
        rows(first, retry);

        RunCostProfile p = service.costProfiles().get("BATCH_NEAR_TERM");

        assertThat(p.runsPerDay()).isCloseTo(1 / 30.0, within(1e-9));
        assertThat(p.callsPerRun()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a batch row with no target date cannot be bucketed and is left out")
    void nullTargetDateSkipped() {
        rows(batch("fc-1-x", LocalDateTime.of(2026, 10, 30, 2, 0), null, EvaluationModel.HAIKU, 10L, false, 0));

        assertThat(service.costProfiles().get("BATCH_NEAR_TERM").measured()).isEmpty();
        assertThat(service.costProfiles().get("BATCH_FAR_TERM").measured()).isEmpty();
    }

    @Test
    @DisplayName("bb- and wd- batch rows are bucketed like fc- rows; other prefixes are ignored")
    void prefixesFilteredOnCustomId() {
        LocalDateTime started = LocalDateTime.of(2026, 10, 30, 2, 0);
        LocalDate target = LocalDate.of(2026, 10, 30);
        rows(batch("bb-1-2026-10-30-SUNRISE", started, target, EvaluationModel.HAIKU, 10L, true, 1_000),
                batch("wd-1-2026-10-30-SUNRISE", started, target, EvaluationModel.HAIKU, 10L, true, 1_000),
                batch("xx-1-2026-10-30-SUNRISE", started, target, EvaluationModel.HAIKU, 10L, true, 1_000));

        assertThat(service.costProfiles().get("BATCH_NEAR_TERM").measured().get("HAIKU").calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unrelated synchronous row belongs to no tab")
    void unrelatedSyncRowIgnored() {
        rows(new ApiCallCostRow(RunType.ASK, LocalDateTime.of(2026, 10, 30, 2, 0), null, null, false, true,
                EvaluationModel.HAIKU, LocalDate.of(2026, 10, 30), 1L, 1L, 0L, 0L, 5L, 3L, null));

        service.costProfiles().values().forEach(p -> assertThat(p.measured()).isEmpty());
    }

    @Test
    @DisplayName("models that did not run get an estimate priced through CostCalculator on the average mix")
    void estimatesPriceTheAverageTokenMix() {
        rows(bestBet(1, "briefing-best-bet", true, 39_000));

        RunCostProfile p = service.costProfiles().get("BRIEFING_BEST_BET");

        assertThat(p.estimates()).doesNotContainKey("SONNET").containsKeys("HAIKU", "OPUS", "SONNET_55");
        long haiku = costCalculator.calculateCostMicroDollars(EvaluationModel.HAIKU,
                new TokenUsage(9_500, 726, 0, 0), false);
        assertThat(p.estimates().get("HAIKU").usdPerCall()).isCloseTo(haiku / (double) MICRO_PER_USD,
                within(1e-12));
        assertThat(p.estimates().get("HAIKU").usdPerDay())
                .isCloseTo(haiku / (double) MICRO_PER_USD * p.runsPerDay() * p.callsPerRun(), within(1e-12));
        assertThat(p.estimates().get("HAIKU").calls()).isZero();
    }

    @Test
    @DisplayName("on a batch tab an estimate for a model lands within 5% of what that model measures on the "
            + "same token mix, because cache writes are priced at the 1-hour rate")
    void batchEstimateMatchesMeasuredWithinFivePercent() {
        TokenUsage mix = new TokenUsage(1_200, 300, 6_000, 0, 6_000);
        // Measured on HAIKU, estimated for SONNET_55 ...
        RunCostProfile ranOnHaiku = batchProfile(EvaluationModel.HAIKU, mix);
        // ... and measured on SONNET_55 itself, from the same tokens.
        RunCostProfile ranOnSonnet = batchProfile(EvaluationModel.SONNET_55, mix);

        double estimated = ranOnHaiku.estimates().get("SONNET_55").usdPerCall();
        double measured = ranOnSonnet.measured().get("SONNET_55").usdPerCall();

        assertThat(measured).isPositive();
        assertThat(estimated).isCloseTo(measured, within(measured * 0.05));
    }

    private RunCostProfile batchProfile(EvaluationModel model, TokenUsage mix) {
        long cost = costCalculator.calculateCostMicroDollars(model, mix, true);
        LocalDateTime started = LocalDateTime.of(2026, 10, 30, 2, 0);
        List<ApiCallCostRow> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            list.add(new ApiCallCostRow(RunType.SCHEDULED_BATCH, started, null, "fc-" + i + "-x", true, true, model,
                    LocalDate.of(2026, 10, 30), mix.inputTokens(), mix.outputTokens(),
                    mix.cacheCreationInputTokens(), mix.cacheReadInputTokens(), cost, 500L, 10L));
        }
        ApiCallLogRepository repo = org.mockito.Mockito.mock(ApiCallLogRepository.class);
        when(repo.findCostRowsSince(WINDOW_START)).thenReturn(list);
        return new RunCostProfileService(repo, jobRunRepository, costCalculator, costProperties,
                new MutableClock(NOW)).costProfiles().get("BATCH_NEAR_TERM");
    }

    @Test
    @DisplayName("an empty window yields zero runs and no figures on every tab")
    void emptyWindow() {
        rows();

        Map<String, RunCostProfile> profiles = service.costProfiles();

        assertThat(profiles).containsKeys("BRIEFING_BEST_BET", "AURORA_EVALUATION", "BATCH_NEAR_TERM",
                "BATCH_FAR_TERM");
        profiles.values().forEach(p -> {
            assertThat(p.runsPerDay()).isZero();
            assertThat(p.measured()).isEmpty();
            assertThat(p.estimates()).isEmpty();
            assertThat(p.windowDays()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("a tab whose only rows failed has no figures")
    void onlyFailedRowsGiveNoFigures() {
        rows(bestBet(1, "briefing-best-bet", false, 0));

        RunCostProfile p = service.costProfiles().get("BRIEFING_BEST_BET");

        assertThat(p.runsPerDay()).isZero();
        assertThat(p.measured()).isEmpty();
    }

    @Test
    @DisplayName("gbpPerUsd is the newest recorded rate, and absent when no run recorded one")
    void gbpRate() {
        rows();
        when(jobRunRepository.findLatestExchangeRates(any(Pageable.class))).thenReturn(List.of(0.75));
        assertThat(service.gbpPerUsd()).contains(0.75);

        clock.advance(Duration.ofHours(1));
        when(jobRunRepository.findLatestExchangeRates(any(Pageable.class))).thenReturn(List.of());
        assertThat(service.gbpPerUsd()).isEmpty();
    }

    @Test
    @DisplayName("a null exchange rate row is skipped")
    void nullRateSkipped() {
        rows();
        when(jobRunRepository.findLatestExchangeRates(any(Pageable.class)))
                .thenReturn(java.util.Collections.singletonList(null));

        assertThat(service.gbpPerUsd()).isEmpty();
    }

    @Test
    @DisplayName("a failing log query yields empty profiles rather than an exception")
    void queryFailureGivesEmptyMap() {
        when(apiCallLogRepository.findCostRowsSince(any())).thenThrow(new IllegalStateException("db down"));
        when(jobRunRepository.findLatestExchangeRates(any(Pageable.class))).thenReturn(List.of(0.8));

        assertThat(service.costProfiles()).isEmpty();
        assertThat(service.gbpPerUsd()).contains(0.8);
    }

    @Test
    @DisplayName("a failing rate query yields an absent rate rather than an exception")
    void rateFailureGivesEmptyRate() {
        rows();
        when(jobRunRepository.findLatestExchangeRates(any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.gbpPerUsd()).isEmpty();
        assertThat(service.costProfiles()).isNotEmpty();
    }

    @Test
    @DisplayName("the result is reused within MEMO_TTL and recomputed after it")
    void memoExpiry() {
        rows();

        service.costProfiles();
        clock.advance(RunCostProfileService.MEMO_TTL.minusSeconds(1));
        service.costProfiles();
        verify(apiCallLogRepository, times(1)).findCostRowsSince(WINDOW_START);

        clock.advance(Duration.ofSeconds(2));
        service.costProfiles();
        verify(apiCallLogRepository, times(2)).findCostRowsSince(WINDOW_START);
    }

    @Test
    @DisplayName("under an admin rewind the memo is neither read nor written")
    void memoBypassedUnderRewind() {
        rows();
        service.costProfiles();

        Rewind.set(Instant.parse("2026-10-31T12:00:00Z"));
        service.costProfiles();
        service.costProfiles();
        Rewind.clear();

        // one real build, plus two uncached builds under the rewind
        verify(apiCallLogRepository, times(3)).findCostRowsSince(any());
        // and the real memo is still intact for the next real reader
        service.costProfiles();
        verify(apiCallLogRepository, times(3)).findCostRowsSince(any());
    }

    @Test
    @DisplayName("a rewind does not write into the memo for real readers")
    void rewindDoesNotPoisonMemo() {
        rows();
        Rewind.set(Instant.parse("2026-10-31T12:00:00Z"));
        service.costProfiles();
        Rewind.clear();

        service.costProfiles();

        verify(apiCallLogRepository, times(2)).findCostRowsSince(any());
    }

    @Test
    @DisplayName("token rates come from CostProperties for every selectable model")
    void tokenRatesFromConfiguration() {
        Map<String, RunCostProfile.TokenRates> rates = service.tokenRates();

        assertThat(rates.keySet()).containsExactlyElementsOf(
                EvaluationModel.selectable().stream().map(Enum::name).toList());
        assertThat(rates.get("SONNET_55").inputUsdPerMtok()).isEqualTo(costProperties.getSonnet55InputUsdPerMtok());
        assertThat(rates.get("SONNET_55").outputUsdPerMtok())
                .isEqualTo(costProperties.getSonnet55OutputUsdPerMtok());
        assertThat(rates.get("HAIKU").inputUsdPerMtok()).isEqualTo(costProperties.getHaikuInputUsdPerMtok());
        assertThat(rates.get("HAIKU_55").outputUsdPerMtok()).isEqualTo(costProperties.getHaiku55OutputUsdPerMtok());
        assertThat(rates.get("OPUS").inputUsdPerMtok()).isEqualTo(costProperties.getOpusInputUsdPerMtok());
        assertThat(rates.get("OPUS").outputUsdPerMtok()).isEqualTo(costProperties.getOpusOutputUsdPerMtok());
    }
}
