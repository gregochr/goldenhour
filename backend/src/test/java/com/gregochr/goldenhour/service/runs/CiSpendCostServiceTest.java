package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.config.CiSpendProperties;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import com.gregochr.goldenhour.model.runs.CiSuite;
import com.gregochr.goldenhour.model.runs.PromptTestRunRow;
import com.gregochr.goldenhour.model.runs.RunCostResponse;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostDay;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTesting;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTestingType;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTotals;
import com.gregochr.goldenhour.repository.CiSpendRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CI spend read side: windows, UK-day bucketing, the two periods, dense days, the unknown and
 * unpriced states, and the Day view rows with the link guard. "Today" is 2 November 2026; with a 7-day period the
 * prior period is 19 to 25 October and the last period 26 October to 1 November.
 */
class CiSpendCostServiceTest {

    private static final LocalDate FIRST = LocalDate.parse("2026-10-19");
    private static final LocalDate SPLIT = LocalDate.parse("2026-10-26");
    private static final LocalDate LAST = LocalDate.parse("2026-11-01");
    private static final String URL = "https://github.com/gregochr/goldenhour/actions/runs/9/attempts/1";

    private static final Instant DAY_START = ForecastHorizon.ukDayStartUtc(LocalDate.parse("2026-11-01"))
            .toInstant(ZoneOffset.UTC);
    private static final Instant DAY_END = ForecastHorizon.ukDayStartUtc(LocalDate.parse("2026-11-02"))
            .toInstant(ZoneOffset.UTC);
    private static final CostTotals APP_TOTALS = new CostTotals(new BigDecimal("10.00"), new BigDecimal("8.00"),
            new BigDecimal("2.00"), 25, new BigDecimal("12.66"), new BigDecimal("0.7900"), new BigDecimal("0.71"),
            new BigDecimal("0.57"), true);
    private static final List<CostDay> APP_DAYS = daysOfApp();

    private CiSpendRunRepository repo;
    private CiSpendCostService service;
    private final List<CiSpendRunEntity> stored = new ArrayList<>();

    private static List<CostDay> daysOfApp() {
        List<CostDay> days = new ArrayList<>();
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) {
            days.add(new CostDay(d, java.util.Map.of(), new BigDecimal("1.50"), true));
        }
        return days;
    }

    private static RunCostResponse appResponse(RunCostResponse.AppExact exact) {
        return new RunCostResponse(7, FIRST, LAST, false, APP_DAYS, List.of(), APP_TOTALS, null, null, exact);
    }

    private CostTesting testing() {
        return service.testing(FIRST, SPLIT, LAST, false, appResponse(null));
    }

    @BeforeEach
    void setUp() {
        repo = mock(CiSpendRunRepository.class);
        service = new CiSpendCostService(repo, new CiSpendProperties());
        Instant from = ForecastHorizon.ukDayStartUtc(FIRST).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(LAST.plusDays(1)).toInstant(ZoneOffset.UTC);
        when(repo.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscSuiteAscRunAttemptAsc(from, to))
                .thenAnswer(i -> stored);
        when(repo.findWithModelsStartedBetween(DAY_START, DAY_END)).thenAnswer(i -> stored);
    }

    private CiSpendRunEntity run(String suite, String startedAt, String state, Long micro, Double rate) {
        CiSpendRunEntity r = new CiSpendRunEntity();
        r.setGithubRunId(9L);
        r.setRunAttempt(1);
        r.setSuite(suite);
        r.setStartedAt(Instant.parse(startedAt));
        r.setUsageState(state);
        r.setCostMicroDollars(micro);
        r.setExchangeRateGbpPerUsd(rate);
        r.setHtmlUrl(URL);
        stored.add(r);
        return r;
    }

    private CiSpendRunEntity known(String suite, String startedAt, long micro) {
        return run(suite, startedAt, CiSpendRunEntity.STATE_KNOWN, micro, 0.5);
    }

    @Test
    @DisplayName("no CI row in the window means no testing block")
    void nothingMeansNull() {
        assertThat(testing()).isNull();
    }

    @Test
    @DisplayName("a row of an unknown suite code is ignored, so a block of only those is null")
    void unknownSuiteIgnored() {
        known("other", "2026-10-30T10:00:00Z", 1_000_000);

        assertThat(testing()).isNull();
    }

    @Test
    @DisplayName("spend is micro-dollars times the stored rate, split into the two periods per suite, sky then ask")
    void spendPerSuiteAndPeriod() {
        known("ask", "2026-10-30T10:00:00Z", 2_000_000);
        known("sky", "2026-10-27T10:00:00Z", 4_000_000);
        known("sky", "2026-10-20T10:00:00Z", 1_000_000);

        CostTesting testing = testing();

        assertThat(testing.types()).extracting(CostTestingType::suite).containsExactly(CiSuite.SKY, CiSuite.ASK);
        CostTestingType sky = testing.types().get(0);
        assertThat(sky.label()).isEqualTo("CI tests · sky");
        assertThat(sky.lastGbp()).isEqualByComparingTo("2.00");
        assertThat(sky.priorGbp()).isEqualByComparingTo("0.50");
        assertThat(sky.deltaGbp()).isEqualByComparingTo("1.50");
        assertThat(sky.deltaPercent()).isEqualTo(300);
        assertThat(sky.lastRuns()).isEqualTo(1);
        assertThat(sky.priorRuns()).isEqualTo(1);
        assertThat(sky.perRunChangePercent()).isEqualTo(300);
        assertThat(sky.costComplete()).isTrue();
        assertThat(testing.totals().lastGbp()).isEqualByComparingTo("3.00");
        assertThat(testing.totals().priorGbp()).isEqualByComparingTo("0.50");
        assertThat(testing.totals().runs()).isEqualTo(2);
        assertThat(testing.totals().priorRuns()).isEqualTo(1);
        assertThat(testing.totals().lastUsd()).isEqualByComparingTo("6.00");
        assertThat(testing.totals().unknownRuns()).isZero();
    }

    @Test
    @DisplayName("a smoke run is a third type after sky and ask, served with its own label and day series")
    void smokeIsAThirdType() {
        known("smoke", "2026-10-27T10:00:00Z", 1_000_000);
        known("sky", "2026-10-27T10:00:00Z", 2_000_000);

        CostTesting testing = testing();

        assertThat(testing.types()).extracting(CostTestingType::suite).containsExactly(CiSuite.SKY, CiSuite.SMOKE);
        assertThat(testing.types().get(1).label()).isEqualTo("CI tests · smoke");
        assertThat(testing.types().get(1).lastGbp()).isEqualByComparingTo("0.50");
        assertThat(testing.days().get(8).byType().get("SMOKE")).isEqualByComparingTo("0.50");
        assertThat(testing.days().get(8).byType().get("ASK")).isEqualByComparingTo("0.00");
        assertThat(testing.totals().lastGbp()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("promptTestRuns serves the event: a scheduled smoke carries schedule, never a PR")
    void dayRowsCarryTheEvent() {
        CiSpendRunEntity r = known("smoke", "2026-11-01T06:05:00Z", 1_000_000);
        r.setEvent("schedule");

        List<PromptTestRunRow> rows = service.promptTestRuns(DAY_START, DAY_END);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).suite()).isEqualTo(CiSuite.SMOKE);
        assertThat(rows.get(0).event()).isEqualTo("schedule");
        assertThat(rows.get(0).prNumber()).isNull();
    }

    @Test
    @DisplayName("a suite that never ran in the window has no type row")
    void onlySuitesThatRan() {
        known("ask", "2026-10-30T10:00:00Z", 2_000_000);

        CostTesting testing = testing();

        assertThat(testing.types()).extracting(CostTestingType::suite).containsExactly(CiSuite.ASK);
        assertThat(testing.types().get(0).perRunChangePercent()).isNull();
        assertThat(testing.types().get(0).deltaPercent()).isNull();
    }

    @Test
    @DisplayName("days are dense: one per app day in the same order, with both suites on every day")
    void denseDays() {
        known("sky", "2026-10-27T10:00:00Z", 4_000_000);

        CostTesting testing = testing();

        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) {
            expected.add(d);
        }
        assertThat(testing.days()).extracting(d -> d.date()).containsExactlyElementsOf(expected);
        assertThat(testing.days()).allSatisfy(d -> assertThat(d.byType()).containsKeys("SKY", "ASK", "SMOKE"));
        assertThat(testing.days().get(8).date()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(testing.days().get(8).byType().get("SKY")).isEqualByComparingTo("2.00");
        assertThat(testing.days().get(8).totalGbp()).isEqualByComparingTo("2.00");
        assertThat(testing.days().get(0).totalGbp()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a run is bucketed by the UK civil date of its start: 23:30 UTC in BST is the next day")
    void ukCivilDateBucketing() {
        known("sky", "2026-10-24T23:30:00Z", 2_000_000);

        CostTesting testing = testing();

        assertThat(testing.days().stream().filter(d -> d.date().equals(LocalDate.parse("2026-10-25"))).findFirst()
                .orElseThrow().totalGbp()).isEqualByComparingTo("1.00");
        assertThat(testing.types().get(0).priorRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("an UNKNOWN or UNPRICED run counts as a run but never as spend, and marks the figure incomplete")
    void unknownAndUnpriced() {
        known("sky", "2026-10-27T10:00:00Z", 2_000_000);
        run("sky", "2026-10-28T10:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);
        run("sky", "2026-10-29T10:00:00Z", CiSpendRunEntity.STATE_UNPRICED, null, null);
        run("sky", "2026-10-21T10:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);

        CostTesting testing = testing();

        CostTestingType sky = testing.types().get(0);
        assertThat(sky.lastRuns()).isEqualTo(3);
        assertThat(sky.priorRuns()).isEqualTo(1);
        assertThat(sky.unknownRuns()).isEqualTo(2);
        assertThat(sky.lastGbp()).isEqualByComparingTo("1.00");
        assertThat(sky.priorGbp()).isEqualByComparingTo("0.00");
        assertThat(sky.costComplete()).isFalse();
        assertThat(testing.totals().unknownRuns()).isEqualTo(2);
        assertThat(testing.totals().runs()).isEqualTo(3);
    }

    @Test
    @DisplayName("a KNOWN run with no stored rate is treated as unpriced, never as 0")
    void knownWithoutRate() {
        run("ask", "2026-10-27T10:00:00Z", CiSpendRunEntity.STATE_KNOWN, 2_000_000L, null);

        CostTesting testing = testing();

        assertThat(testing.types().get(0).unknownRuns()).isEqualTo(1);
        assertThat(testing.types().get(0).costComplete()).isFalse();
        assertThat(testing.totals().lastGbp()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("all time has one period: everything is last, nothing is prior")
    void allTime() {
        known("sky", "2026-10-20T10:00:00Z", 2_000_000);
        known("sky", "2026-10-30T10:00:00Z", 2_000_000);

        CostTesting testing = service.testing(FIRST, FIRST, LAST, true, appResponse(null));

        assertThat(testing.totals().lastGbp()).isEqualByComparingTo("2.00");
        assertThat(testing.totals().priorGbp()).isEqualByComparingTo("0.00");
        assertThat(testing.totals().priorRuns()).isZero();
        assertThat(testing.totals().deltaPercent()).isNull();
    }

    @Test
    @DisplayName("promptTestRuns sums tokens over models, prices known runs and gates the link")
    void dayRows() {
        CiSpendRunEntity r = known("sky", "2026-11-01T10:00:00Z", 2_000_000);
        r.setPrNumber(1912);
        r.setPrTitle("fix: valley mist wording");
        r.setConclusion("failure");
        r.setCases(42);
        r.setFailed(2);
        r.addModel(model(100, 10, 50));
        r.addModel(model(200, 20, 25));

        List<PromptTestRunRow> rows = service.promptTestRuns(DAY_START, DAY_END);

        assertThat(rows).hasSize(1);
        PromptTestRunRow row = rows.get(0);
        assertThat(row.prNumber()).isEqualTo(1912);
        assertThat(row.suite()).isEqualTo(CiSuite.SKY);
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.input()).isEqualTo(300L);
        assertThat(row.output()).isEqualTo(30L);
        assertThat(row.cacheRead()).isEqualTo(75L);
        assertThat(row.costGbp()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(row.costUsd()).isEqualByComparingTo("2.00");
        assertThat(row.htmlUrl()).isEqualTo(URL);
        assertThat(row.usageState()).isEqualTo("KNOWN");
        assertThat(row.inputTotal()).isEqualTo(375L);
        assertThat(row.cachedPercent()).isEqualTo(20);
    }

    @Test
    @DisplayName("promptTestRuns: an unknown run has no tokens or cost; a foreign link is not served; order is stable")
    void dayRowsUnknownAndLinkGuard() {
        CiSpendRunEntity later = run("sky", "2026-11-01T12:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);
        later.setHtmlUrl("https://evil.example/gregochr/goldenhour/actions/runs/1");
        CiSpendRunEntity ask = known("ask", "2026-11-01T10:00:00Z", 1_000_000);
        ask.setRunAttempt(2);
        CiSpendRunEntity sky = known("sky", "2026-11-01T10:00:00Z", 1_000_000);
        sky.setHtmlUrl(null);
        CiSpendRunEntity bad = run("nope", "2026-11-01T09:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);
        bad.setHtmlUrl(URL);
        CiSpendRunEntity unpriced = run("ask", "2026-11-01T13:00:00Z", CiSpendRunEntity.STATE_UNPRICED, null, null);
        unpriced.addModel(model(5, 6, 7));

        List<PromptTestRunRow> rows = service.promptTestRuns(DAY_START, DAY_END);

        assertThat(rows).extracting(PromptTestRunRow::suite, PromptTestRunRow::attempt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CiSuite.SKY, 1),
                        org.assertj.core.groups.Tuple.tuple(CiSuite.ASK, 2),
                        org.assertj.core.groups.Tuple.tuple(CiSuite.SKY, 1),
                        org.assertj.core.groups.Tuple.tuple(CiSuite.ASK, 1));
        PromptTestRunRow unknown = rows.get(2);
        assertThat(unknown.htmlUrl()).isNull();
        assertThat(unknown.input()).isNull();
        assertThat(unknown.costGbp()).isNull();
        assertThat(unknown.costUsd()).isNull();
        assertThat(rows.get(0).htmlUrl()).isNull();
        PromptTestRunRow priceless = rows.get(3);
        assertThat(priceless.input()).isEqualTo(5L);
        assertThat(priceless.costGbp()).isNull();
        assertThat(priceless.usageState()).isEqualTo("UNPRICED");
    }

    private static CiSpendModelUsageEntity model(long in, long out, long cacheRead) {
        CiSpendModelUsageEntity m = new CiSpendModelUsageEntity();
        m.setInputTokens(in);
        m.setOutputTokens(out);
        m.setCacheReadTokens(cacheRead);
        return m;
    }

    @Test
    @DisplayName("cost per run is over the priced runs: one unknown last-period run with unchanged per-run cost is 0%")
    void perRunChangeIgnoresUnknownRuns() {
        known("sky", "2026-10-20T10:00:00Z", 2_000_000);
        known("sky", "2026-10-27T10:00:00Z", 2_000_000);
        run("sky", "2026-10-28T10:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);

        CostTestingType sky = testing().types().get(0);

        assertThat(sky.perRunChangePercent()).isZero();
        assertThat(sky.lastRuns()).isEqualTo(2);
    }

    @Test
    @DisplayName("cost per run has no change when a period has no priced run")
    void perRunChangeNullWithoutPricedRuns() {
        known("sky", "2026-10-20T10:00:00Z", 2_000_000);
        run("sky", "2026-10-28T10:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);

        assertThat(testing().types().get(0).perRunChangePercent()).isNull();
    }

    @Test
    @DisplayName("combined totals and each day's appPlusTestingGbp are the app's served figures plus testing's")
    void combinedFigures() {
        known("sky", "2026-10-27T10:00:00Z", 4_000_000);
        known("ask", "2026-10-20T10:00:00Z", 2_000_000);

        CostTesting testing = testing();

        assertThat(testing.totals().combined().lastGbp()).isEqualByComparingTo("12.00");
        assertThat(testing.totals().combined().priorGbp()).isEqualByComparingTo("9.00");
        assertThat(testing.totals().combined().deltaGbp()).isEqualByComparingTo("3.00");
        assertThat(testing.totals().combined().lastUsd()).isEqualByComparingTo("16.66");
        assertThat(testing.days().get(8).appPlusTestingGbp()).isEqualByComparingTo("3.50");
        assertThat(testing.days().get(1).appPlusTestingGbp()).isEqualByComparingTo("2.50");
        assertThat(testing.days().get(0).appPlusTestingGbp()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("combined figures are rounded once from unrounded values: 1.005 + 1.005 is 2.01, not 1.01 + 1.01")
    void combinedRoundsOnce() {
        // 2_010_000 micro-dollars at 0.5 GBP per USD is 1.005 pounds, which rounds up to 1.01 on its own.
        known("sky", "2026-10-27T10:00:00Z", 2_010_000);
        RunCostResponse.AppExact exact = new RunCostResponse.AppExact(1.005, 0.0, 2_010_000L,
                java.util.Map.of(LocalDate.parse("2026-10-27"), 1.005));
        RunCostResponse app = new RunCostResponse(7, FIRST, LAST, false, APP_DAYS, List.of(),
                new CostTotals(new BigDecimal("1.01"), BigDecimal.ZERO, new BigDecimal("1.01"), null,
                        new BigDecimal("2.01"), new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ZERO, true),
                null, null, exact);

        CostTesting testing = service.testing(FIRST, SPLIT, LAST, false, app);

        assertThat(testing.totals().deltaGbp()).isEqualByComparingTo("1.01");
        assertThat(testing.totals().combined().lastGbp()).isEqualByComparingTo("2.01");
        assertThat(testing.totals().combined().deltaGbp()).isEqualByComparingTo("2.01");
        assertThat(testing.totals().combined().lastUsd()).isEqualByComparingTo("4.02");
        assertThat(testing.days().get(8).appPlusTestingGbp()).isEqualByComparingTo("2.01");
    }

    @Test
    @DisplayName("a KNOWN Day row with no stored rate reports UNPRICED and no cost")
    void knownWithoutRateIsUnpricedOnTheDay() {
        run("sky", "2026-11-01T10:00:00Z", CiSpendRunEntity.STATE_KNOWN, 1_000_000L, null);

        PromptTestRunRow row = service.promptTestRuns(DAY_START, DAY_END).get(0);

        assertThat(row.usageState()).isEqualTo("UNPRICED");
        assertThat(row.costGbp()).isNull();
        assertThat(row.costUsd()).isNull();
    }

    @Test
    @DisplayName("a row with no tokens has no input total or cached percent; zero input is no percent")
    void cachedPercentEdges() {
        CiSpendRunEntity unknown = run("sky", "2026-11-01T10:00:00Z", CiSpendRunEntity.STATE_UNKNOWN, null, null);
        unknown.setHtmlUrl(null);
        CiSpendRunEntity zero = run("ask", "2026-11-01T11:00:00Z", CiSpendRunEntity.STATE_UNPRICED, null, null);
        zero.addModel(model(0, 0, 0));

        List<PromptTestRunRow> rows = service.promptTestRuns(DAY_START, DAY_END);

        assertThat(rows.get(0).inputTotal()).isNull();
        assertThat(rows.get(0).cachedPercent()).isNull();
        assertThat(rows.get(1).inputTotal()).isZero();
        assertThat(rows.get(1).cachedPercent()).isNull();
    }

    @Test
    @DisplayName("the repository is queried with the app days' own UTC bounds")
    void windowIsTheAppDays() {
        testing();

        org.mockito.Mockito.verify(repo)
                .findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscSuiteAscRunAttemptAsc(
                        eq(Instant.parse("2026-10-18T23:00:00Z")), eq(Instant.parse("2026-11-02T00:00:00Z")));
    }
}
