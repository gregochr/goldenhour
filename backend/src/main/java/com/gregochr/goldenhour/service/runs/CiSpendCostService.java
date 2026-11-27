package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.config.CiSpendProperties;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import com.gregochr.goldenhour.model.runs.CiSuite;
import com.gregochr.goldenhour.model.runs.PromptTestRunRow;
import com.gregochr.goldenhour.model.runs.RunCostResponse;
import com.gregochr.goldenhour.model.runs.RunCostResponse.AppExact;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostCombined;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostDay;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTesting;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTestingDay;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTestingTotals;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTotals;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTestingType;
import com.gregochr.goldenhour.repository.CiSpendRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the CI prompt-regression spend the poller stored in {@code ci_spend_run} and serves it as the Cost trends
 * {@code testing} block and the Day view's prompt test runs. It is built <em>beside</em> {@link RunsCostService} and
 * {@link RunsDayService}, which it never calls and which never call it: the controller composes the two, so CI spend
 * cannot reach an app figure.
 *
 * <p>Runs are bucketed by the UK civil date of {@code started_at} and bounded to the app response's own days. A run's
 * pounds are its stored micro-dollars times its stored rate. A run whose usage is unknown, or whose models were not in
 * the price table, counts as a run (and in {@code unknownRuns}) but never as spend, and marks the figure incomplete.
 */
@Service
public class CiSpendCostService {

    private static final double PERCENT = 100.0;
    private static final int USD_SCALE = 2;

    private final CiSpendRunRepository runs;
    private final CiSpendProperties properties;

    /**
     * Constructs the service.
     *
     * @param runs       the stored CI runs
     * @param properties the CI spend configuration (the repository the run links must belong to)
     */
    CiSpendCostService(CiSpendRunRepository runs, CiSpendProperties properties) {
        this.runs = runs;
        this.properties = properties;
    }

    /** What one suite spent in the two periods. */
    private static final class Tally {
        private final double[] gbp = new double[2];
        private final int[] runs = new int[2];
        private final int[] unknown = new int[2];
        private boolean complete = true;
    }

    /**
     * The testing block for the app response's own window.
     *
     * @param firstDay the first day of the app response's days
     * @param splitDay the first day of the last period (equal to {@code firstDay} in all time)
     * @param lastDay  the last day of the app response's days
     * @param allTime  whether the whole history was asked for (one period, no prior)
     * @param app      the app response: its totals and days are added to testing's for {@code totals.combined} and
     *                 each day's {@code appPlusTestingGbp}, from its unrounded figures when it carries them, so a
     *                 sum is rounded once
     * @return the block, or null when no CI run started inside the window
     */
    public CostTesting testing(LocalDate firstDay, LocalDate splitDay, LocalDate lastDay, boolean allTime,
            RunCostResponse app) {
        CostTotals appTotals = app.totals();
        AppExact exact = app.appExact();
        Instant from = ForecastHorizon.ukDayStartUtc(firstDay).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(lastDay.plusDays(1)).toInstant(ZoneOffset.UTC);
        List<CiSpendRunEntity> rows = runs
                .findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscSuiteAscRunAttemptAsc(from, to);

        Map<LocalDate, Map<CiSuite, Double>> perDay = new HashMap<>();
        Map<CiSuite, Tally> tallies = new EnumMap<>(CiSuite.class);
        double lastMicro = 0;
        for (CiSpendRunEntity row : rows) {
            Optional<CiSuite> found = CiSuite.fromCode(row.getSuite());
            if (found.isEmpty()) {
                continue;
            }
            CiSuite suite = found.get();
            LocalDate date = ForecastHorizon.civilDate(row.getStartedAt());
            int period = !allTime && date.isBefore(splitDay) ? 0 : 1;
            Tally tally = tallies.computeIfAbsent(suite, s -> new Tally());
            tally.runs[period]++;
            if (priced(row)) {
                double gbp = RunFx.gbp(row.getCostMicroDollars(), row.getExchangeRateGbpPerUsd());
                tally.gbp[period] += gbp;
                perDay.computeIfAbsent(date, d -> new EnumMap<>(CiSuite.class)).merge(suite, gbp, Double::sum);
                if (period == 1) {
                    lastMicro += row.getCostMicroDollars();
                }
            } else {
                tally.unknown[period]++;
                tally.complete = false;
            }
        }
        if (tallies.isEmpty()) {
            return null;
        }

        List<CostTestingType> types = new ArrayList<>();
        double last = 0;
        double prior = 0;
        int lastRuns = 0;
        int priorRuns = 0;
        int unknown = 0;
        for (CiSuite suite : CiSuite.values()) {
            Tally t = tallies.get(suite);
            if (t == null) {
                continue;
            }
            types.add(type(suite, t));
            last += t.gbp[1];
            prior += t.gbp[0];
            lastRuns += t.runs[1];
            priorRuns += t.runs[0];
            unknown += t.unknown[1];
        }
        Map<LocalDate, Double> appByDay = new HashMap<>();
        for (CostDay d : app.days()) {
            appByDay.put(d.date(), exact != null ? exact.dayGbp().getOrDefault(d.date(), 0.0)
                    : d.totalGbp().doubleValue());
        }
        List<CostTestingDay> days = new ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            days.add(day(d, perDay.getOrDefault(d, Map.of()), appByDay.getOrDefault(d, 0.0)));
        }
        BigDecimal lastGbp = RunFx.pence(last);
        BigDecimal priorGbp = RunFx.pence(prior);
        BigDecimal lastUsd = BigDecimal.valueOf(lastMicro / RunFx.MICRO).setScale(USD_SCALE, RoundingMode.HALF_UP);
        double appLast = exact != null ? exact.lastGbp() : appTotals.lastGbp().doubleValue();
        double appPrior = exact != null ? exact.priorGbp() : appTotals.priorGbp().doubleValue();
        double combinedLast = appLast + last;
        double combinedPrior = appPrior + prior;
        BigDecimal combinedUsd = exact != null
                ? BigDecimal.valueOf((exact.lastMicro() + lastMicro) / RunFx.MICRO).setScale(USD_SCALE,
                        RoundingMode.HALF_UP)
                : appTotals.lastUsd().add(lastUsd);
        CostCombined combined = new CostCombined(RunFx.pence(combinedLast), RunFx.pence(combinedPrior),
                RunFx.pence(combinedLast - combinedPrior), combinedUsd);
        CostTestingTotals totals = new CostTestingTotals(lastGbp, priorGbp, RunFx.pence(last - prior),
                CostArithmetic.percent(last, prior), lastRuns, priorRuns, unknown, lastUsd, combined);
        return new CostTesting(types, days, totals);
    }

    /**
     * The CI runs that started inside one UK day, for the Day view.
     *
     * @param dayStart the start of the UK day (UTC), inclusive
     * @param dayEnd   the start of the next UK day (UTC), exclusive
     * @return the rows ordered by start, suite, then attempt; empty when none
     */
    public List<PromptTestRunRow> promptTestRuns(Instant dayStart, Instant dayEnd) {
        String prefix = "https://github.com/" + properties.getRepository() + "/actions/runs/";
        List<PromptTestRunRow> rows = new ArrayList<>();
        for (CiSpendRunEntity row : runs.findWithModelsStartedBetween(dayStart, dayEnd)) {
            Optional<CiSuite> suite = CiSuite.fromCode(row.getSuite());
            if (suite.isEmpty()) {
                continue;
            }
            boolean tokens = !CiSpendRunEntity.STATE_UNKNOWN.equals(row.getUsageState())
                    && !row.getModels().isEmpty();
            boolean known = priced(row);
            // A KNOWN row with no stored rate is treated as unpriced here as on the Cost trends.
            String state = CiSpendRunEntity.STATE_KNOWN.equals(row.getUsageState()) && !known
                    ? CiSpendRunEntity.STATE_UNPRICED : row.getUsageState();
            String url = row.getHtmlUrl() != null && row.getHtmlUrl().startsWith(prefix) ? row.getHtmlUrl() : null;
            Long in = tokens ? sum(row, CiSpendModelUsageEntity::getInputTokens) : null;
            Long cached = tokens ? sum(row, CiSpendModelUsageEntity::getCacheReadTokens) : null;
            Long inTotal = tokens ? in + cached : null;
            Integer cachedPercent = inTotal == null || inTotal == 0 ? null
                    : (int) Math.round((double) cached / inTotal * PERCENT);
            rows.add(new PromptTestRunRow(row.getPrNumber(), row.getPrTitle(), suite.get(), row.getRunAttempt(),
                    row.getConclusion(), url, row.getStartedAt(), row.getCases(), row.getFailed(),
                    in, tokens ? sum(row, CiSpendModelUsageEntity::getOutputTokens) : null, cached,
                    known ? RunFx.pence(RunFx.gbp(row.getCostMicroDollars(), row.getExchangeRateGbpPerUsd())) : null,
                    known ? RunFx.dollars(row.getCostMicroDollars()) : null, state, inTotal, cachedPercent));
        }
        rows.sort(Comparator.comparing(PromptTestRunRow::startedAt)
                .thenComparing(PromptTestRunRow::suite).thenComparingInt(PromptTestRunRow::attempt));
        return rows;
    }

    private static long sum(CiSpendRunEntity row, Function<CiSpendModelUsageEntity, Long> field) {
        return row.getModels().stream().map(field).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    /** True when the run's cost is known and has a rate to turn it into pounds. */
    private static boolean priced(CiSpendRunEntity row) {
        return CiSpendRunEntity.STATE_KNOWN.equals(row.getUsageState()) && row.getCostMicroDollars() != null
                && row.getExchangeRateGbpPerUsd() != null;
    }

    private static CostTestingType type(CiSuite suite, Tally t) {
        // Cost per run is over the priced runs only: an unknown run has no spend to divide.
        Double multiple = CostArithmetic.perRunMultiple(t.gbp[1], t.runs[1] - t.unknown[1], t.gbp[0],
                t.runs[0] - t.unknown[0]);
        Integer perRun = multiple == null ? null : (int) Math.round((multiple - 1) * PERCENT);
        return new CostTestingType(suite, suite.label(), RunFx.pence(t.gbp[1]), RunFx.pence(t.gbp[0]),
                RunFx.pence(t.gbp[1] - t.gbp[0]), CostArithmetic.percent(t.gbp[1], t.gbp[0]), t.runs[1], t.runs[0],
                perRun, t.unknown[1], t.complete);
    }

    private static CostTestingDay day(LocalDate date, Map<CiSuite, Double> spend, double appTotal) {
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (CiSuite suite : CiSuite.values()) {
            byType.put(suite.name(), RunFx.pence(spend.getOrDefault(suite, 0.0)));
        }
        double testing = spend.values().stream().mapToDouble(Double::doubleValue).sum();
        return new CostTestingDay(date, byType, RunFx.pence(testing), RunFx.pence(appTotal + testing));
    }
}
