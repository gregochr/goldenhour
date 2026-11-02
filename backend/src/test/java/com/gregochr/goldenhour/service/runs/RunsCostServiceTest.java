package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunCostResponse;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostDay;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostType;
import com.gregochr.goldenhour.model.runs.RunCostResponse.Direction;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Runs Cost trends view: the verdict thresholds, the top contributor, the sky eval share
 * sentence, unknown costs, the rate fallback and the DST day. "Today" is Monday 2 November 2026; with a 14-day
 * period the prior period is 5 to 18 October and the last period 19 October to 1 November.
 */
class RunsCostServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-02");
    private static final String PRIOR_DAY = "2026-10-10T04:00:00Z";
    private static final String LAST_DAY = "2026-10-25T04:00:00Z";

    private RunsLoader loader;
    private RunsCostService service;
    private List<DeepRun> rows;
    private RunFx fx = new RunFx(List.of(), null);

    @BeforeEach
    void setUp() {
        loader = mock(RunsLoader.class);
        RunsBounds bounds = mock(RunsBounds.class);
        when(bounds.today()).thenReturn(TODAY);
        service = new RunsCostService(loader, bounds);
        rows = new ArrayList<>();
        stubLoader(14);
    }

    /** Serves the rows for exactly the window a period of n days asks for: 2n UK days up to today's midnight. */
    private void stubLoader(int n) {
        Instant from = ForecastHorizon.ukDayStartUtc(TODAY.minusDays(2L * n)).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(TODAY).toInstant(ZoneOffset.UTC);
        when(loader.load(eq(from), eq(to))).thenAnswer(i -> new RunsLoader.LoadedRuns(rows, fx));
    }

    private DeepRun run(RunDeepType type, String start, Double gbp, Integer locations) {
        Long micro = gbp == null ? null : Math.round(gbp / RunFx.FALLBACK_GBP_PER_USD * RunFx.MICRO);
        return new DeepRun("job-" + rows.size(), type.isCycle() ? RunDeepKind.CYCLE : RunDeepKind.JOB, type, null,
                null, type.label(), null, Instant.parse(start), Instant.parse(start).plusSeconds(60), 60L, micro,
                gbp != null, gbp == null ? 0 : gbp, RunDeepStatus.DONE, null, NoteTone.INFO, null, null, null,
                null, null, null, locations, false);
    }

    private void spend(RunDeepType type, String start, double gbp) {
        rows.add(run(type, start, gbp, null));
    }

    @ParameterizedTest(name = "{0} against {1} is {2}")
    @CsvSource({"110.0,100.0,UP,10", "109.0,100.0,FLAT,9", "91.0,100.0,FLAT,-9", "90.0,100.0,DOWN,-10",
        "100.0,100.0,FLAT,0"})
    @DisplayName("the headline is UP at +10% or more, DOWN at -10% or less, otherwise FLAT")
    void verdictThresholds(double last, double prior, Direction expected, int percent) {
        spend(RunDeepType.OVERNIGHT, PRIOR_DAY, prior);
        spend(RunDeepType.OVERNIGHT, LAST_DAY, last);

        RunCostResponse response = service.cost(14);

        assertThat(response.verdict().direction()).isEqualTo(expected);
        assertThat(response.verdict().percent()).isEqualTo(percent);
        assertThat(response.totals().deltaPercent()).isEqualTo(percent);
    }

    @Test
    @DisplayName("a prior period that spent nothing has no percentage and the headline is FLAT")
    void nothingToCompareWith() {
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 5.0);

        RunCostResponse response = service.cost(14);

        assertThat(response.verdict().direction()).isEqualTo(Direction.FLAT);
        assertThat(response.verdict().percent()).isNull();
        assertThat(response.totals().deltaPercent()).isNull();
    }

    @Test
    @DisplayName("no runs at all serves zeros, no top type and no sentences")
    void allZeros() {
        stubLoader(30);
        RunCostResponse response = service.cost(null);

        assertThat(response.rangeDays()).isEqualTo(30);
        assertThat(response.days()).hasSize(60);
        assertThat(response.types()).isEmpty();
        assertThat(response.totals().lastGbp()).isEqualByComparingTo("0");
        assertThat(response.totals().costComplete()).isTrue();
        assertThat(response.verdict().topType()).isNull();
        assertThat(response.verdict().skyEval()).isNull();
    }

    @Test
    @DisplayName("the top contributor is the biggest change in pounds, split into run count and cost per run")
    void topContributorAndRunsSplit() {
        for (int i = 0; i < 10; i++) {
            rows.add(run(RunDeepType.ASK_READY, PRIOR_DAY, 1.0, 18));
            rows.add(run(RunDeepType.ASK_READY, LAST_DAY, 1.3, 24));
        }
        spend(RunDeepType.OVERNIGHT, PRIOR_DAY, 20.0);
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 21.0);

        RunCostResponse response = service.cost(14);

        assertThat(response.verdict().topType()).isEqualTo(RunDeepType.ASK_READY);
        assertThat(response.verdict().topLabel()).isEqualTo("Ask ready answers");
        assertThat(response.verdict().topDeltaGbp()).isEqualByComparingTo("3.00");
        assertThat(response.verdict().totalDeltaGbp()).isEqualByComparingTo("4.00");
        assertThat(response.verdict().runsAsOften()).isTrue();
        assertThat(response.verdict().perRunMultiple()).isEqualByComparingTo("1.3");
        assertThat(response.verdict().askLocations().last()).isEqualTo(24);
        assertThat(response.verdict().askLocations().prior()).isEqualTo(18);
        assertThat(response.types()).extracting(CostType::type)
                .containsExactly(RunDeepType.ASK_READY, RunDeepType.OVERNIGHT);
        CostType ask = response.types().get(0);
        assertThat(ask.runsChangePercent()).isZero();
        assertThat(ask.perRunChangePercent()).isEqualTo(30);
        assertThat(ask.avgLocationsLast()).isEqualTo(24);
        assertThat(ask.avgLocationsPrior()).isEqualTo(18);
        assertThat(response.types().get(1).avgLocationsLast()).isNull();
    }

    @Test
    @DisplayName("a top type that ran twice as often is not 'as often as before' and carries no locations pair")
    void runCountChanged() {
        spend(RunDeepType.BRIEFING, PRIOR_DAY, 1.0);
        spend(RunDeepType.BRIEFING, PRIOR_DAY, 1.0);
        for (int i = 0; i < 4; i++) {
            spend(RunDeepType.BRIEFING, LAST_DAY, 1.0);
        }

        RunCostResponse response = service.cost(14);

        assertThat(response.verdict().topType()).isEqualTo(RunDeepType.BRIEFING);
        assertThat(response.verdict().runsAsOften()).isFalse();
        assertThat(response.verdict().perRunMultiple()).isEqualByComparingTo("1.0");
        assertThat(response.verdict().askLocations()).isNull();
    }

    @Test
    @DisplayName("the sky eval share is served above 4% of spend and withheld at or below it")
    void skyEvalShareGate() {
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 95.0);
        spend(RunDeepType.SKY_EVAL, LAST_DAY, 5.0);
        spend(RunDeepType.SKY_EVAL, PRIOR_DAY, 4.0);

        RunCostResponse above = service.cost(14);

        assertThat(above.verdict().skyEval()).isNotNull();
        assertThat(above.verdict().skyEval().sharePercent()).isEqualTo(5);
        assertThat(above.verdict().skyEval().gbp()).isEqualByComparingTo("5.00");
        assertThat(above.verdict().skyEval().changePercent()).isEqualTo(25);

        rows.clear();
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 96.0);
        spend(RunDeepType.SKY_EVAL, LAST_DAY, 4.0);

        assertThat(service.cost(14).verdict().skyEval()).isNull();
    }

    @Test
    @DisplayName("a run whose cost is not known marks its day and the totals incomplete, and adds nothing")
    void unknownCostIsNotZero() {
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 2.0);
        rows.add(run(RunDeepType.EVENING, "2026-10-26T01:00:00Z", null, null));

        RunCostResponse response = service.cost(14);

        CostDay open = day(response, "2026-10-26");
        assertThat(open.costComplete()).isFalse();
        assertThat(open.totalGbp()).isEqualByComparingTo("0");
        assertThat(day(response, "2026-10-25").costComplete()).isTrue();
        assertThat(response.totals().costComplete()).isFalse();
        assertThat(response.types()).filteredOn(t -> t.type() == RunDeepType.EVENING)
                .allMatch(t -> !t.costComplete());
        assertThat(response.types()).filteredOn(t -> t.type() == RunDeepType.OVERNIGHT)
                .allMatch(CostType::costComplete);
    }

    @Test
    @DisplayName("with no stored rate and no network the dollar total uses the 0.79 constant")
    void fxFallbackWithoutNetwork() {
        fx = new RunFx(List.of(), null);
        spend(RunDeepType.OVERNIGHT, LAST_DAY, 7.9);

        RunCostResponse response = service.cost(14);

        assertThat(response.totals().fxRate()).isEqualByComparingTo("0.7900");
        assertThat(response.totals().lastUsd()).isEqualByComparingTo("10.00");
        assertThat(response.totals().lastGbp()).isEqualByComparingTo("7.90");
        assertThat(response.totals().perDayLast()).isEqualByComparingTo("0.56");
    }

    @Test
    @DisplayName("a run on the eve of the clocks going back lands on its UK day: 25 October is 25 hours long")
    void dstDay() {
        spend(RunDeepType.OVERNIGHT, "2026-10-24T22:30:00Z", 1.0);
        spend(RunDeepType.OVERNIGHT, "2026-10-24T23:30:00Z", 2.0);
        spend(RunDeepType.OVERNIGHT, "2026-10-25T23:30:00Z", 4.0);
        spend(RunDeepType.OVERNIGHT, "2026-10-26T00:10:00Z", 8.0);

        RunCostResponse response = service.cost(14);

        assertThat(day(response, "2026-10-24").totalGbp()).isEqualByComparingTo("1.00");
        assertThat(day(response, "2026-10-25").totalGbp()).isEqualByComparingTo("6.00");
        assertThat(day(response, "2026-10-26").totalGbp()).isEqualByComparingTo("8.00");
        assertThat(day(response, "2026-10-25").byType().get("OVERNIGHT")).isEqualByComparingTo("6.00");
        assertThat(day(response, "2026-10-25").byType().get("ASK_READY")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the period covers the 2N full days before today, oldest first, and today is left out")
    void daysCoverTwoPeriods() {
        RunCostResponse response = service.cost(14);

        assertThat(response.days()).hasSize(28);
        assertThat(response.days().get(0).date()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(response.days().get(27).date()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(response.firstDay()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(response.lastDay()).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    @DisplayName("only 14, 30 or 60 days can be asked for")
    void invalidRange() {
        assertThatThrownBy(() -> service.cost(7)).isInstanceOf(RunsRequestException.class);
        assertThatThrownBy(() -> service.cost(0)).isInstanceOf(RunsRequestException.class);
        stubLoader(60);
        assertThat(service.cost(60).days()).hasSize(120);
    }

    private static CostDay day(RunCostResponse response, String date) {
        return response.days().stream().filter(d -> d.date().equals(LocalDate.parse(date))).findFirst().orElseThrow();
    }
}
