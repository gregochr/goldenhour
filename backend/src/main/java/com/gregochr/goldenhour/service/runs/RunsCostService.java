package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.RunCostResponse;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostDay;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostTotals;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostType;
import com.gregochr.goldenhour.model.runs.RunCostResponse.CostVerdict;
import com.gregochr.goldenhour.model.runs.RunCostResponse.Direction;
import com.gregochr.goldenhour.model.runs.RunCostResponse.LocationsPair;
import com.gregochr.goldenhour.model.runs.RunCostResponse.SkyEvalShare;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Runs "Cost trends" view: the spend of the last N full UK days against the N before them, by day and by
 * run type, and the verdict as structured fields.
 *
 * <p>Spend comes only from the stored run costs (a cycle's member job runs, every other job run, and the sky eval's
 * own cost), each priced with its own recorded rate, read through the shared {@link RunsLoader}: never from the API
 * call log, and never from the network. Today is left out because it is incomplete. A cost that is not known yet is
 * not counted as zero: it marks the day, type and total {@code costComplete: false}.
 */
@Service
public class RunsCostService {

    /** The period lengths that can be asked for, in days. */
    static final List<Integer> RANGES = List.of(7, 14, 30, 60, 90);

    /** The request value that asks for every day of the Runs history. */
    static final String ALL_TIME = "all";

    /** The period length used when none is asked for. */
    static final int DEFAULT_RANGE = 30;

    /** A total change of at least this many percent (up or down) is not "flat". */
    static final int VERDICT_PERCENT = 10;

    /** A run count that moved by less than this many percent is "as often as before". */
    static final int RUNS_PERCENT = 10;

    /** The sky eval's share of spend above which its sentence is served. */
    static final int SKY_EVAL_SHARE_PERCENT = 4;

    private static final double PERCENT = 100.0;
    private static final int FX_SCALE = 4;
    private static final int MULTIPLE_SCALE = 1;

    private final RunsLoader loader;
    private final RunsBounds bounds;

    /**
     * Constructs the service.
     *
     * @param loader loads the rows of both periods in one pass
     * @param bounds the UK civil today
     */
    RunsCostService(RunsLoader loader, RunsBounds bounds) {
        this.loader = loader;
        this.bounds = bounds;
    }

    /** What one type spent in the two periods. */
    private static final class Tally {
        private final double[] gbp = new double[2];
        private final long[] micro = new long[2];
        private final int[] runs = new int[2];
        private final long[] locations = new long[2];
        private final int[] locationRuns = new int[2];
        private boolean complete = true;
    }

    /**
     * Serves the cost trends.
     *
     * @param requested the period length in days (7, 14, 30, 60 or 90), {@code all} (case-insensitive) for the whole
     *                  Runs history, or null for {@value #DEFAULT_RANGE}
     * @return the days, types, totals and verdict
     * @throws RunsRequestException if the value is none of those
     */
    public RunCostResponse cost(String requested) {
        boolean allTime = requested != null && ALL_TIME.equalsIgnoreCase(requested.trim());
        int n = allTime ? 0 : parseDays(requested);
        LocalDate today = bounds.today();
        LocalDate lastDay = today.minusDays(1);
        LocalDate firstDay;
        LocalDate splitDay;
        if (allTime) {
            LocalDate earliest = bounds.earliestDay();
            firstDay = earliest.isAfter(lastDay) ? lastDay : earliest;
            splitDay = firstDay;
            n = (int) ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        } else {
            firstDay = today.minusDays(2L * n);
            splitDay = today.minusDays(n);
        }
        Instant from = ForecastHorizon.ukDayStartUtc(firstDay).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(today).toInstant(ZoneOffset.UTC);
        RunsLoader.LoadedRuns loaded = loader.load(from, to);

        Map<LocalDate, Map<RunDeepType, Double>> perDay = new HashMap<>();
        Map<LocalDate, Boolean> dayComplete = new HashMap<>();
        Map<RunDeepType, Tally> tallies = new EnumMap<>(RunDeepType.class);
        for (DeepRun run : loaded.rows()) {
            LocalDate date = ForecastHorizon.civilDate(run.startedAt());
            Map<RunDeepType, Double> types = perDay.computeIfAbsent(date, d -> new EnumMap<>(RunDeepType.class));
            types.merge(run.type(), run.costGbp(), Double::sum);
            if (!run.costComplete()) {
                dayComplete.put(date, false);
            }
            Tally tally = tallies.computeIfAbsent(run.type(), t -> new Tally());
            int period = date.isBefore(splitDay) ? 0 : 1;
            tally.gbp[period] += run.costGbp();
            tally.runs[period]++;
            if (run.costMicro() != null) {
                tally.micro[period] += run.costMicro();
            }
            if (run.type() == RunDeepType.ASK_READY && run.locations() != null) {
                tally.locations[period] += run.locations();
                tally.locationRuns[period]++;
            }
            if (!run.costComplete()) {
                tally.complete = false;
            }
        }

        List<CostDay> days = new ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            days.add(day(d, perDay.getOrDefault(d, Map.of()), dayComplete.getOrDefault(d, true)));
        }
        List<CostType> types = tallies.entrySet().stream().map(e -> costType(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(CostType::deltaGbp).reversed()).toList();
        double last = tallies.values().stream().mapToDouble(t -> t.gbp[1]).sum();
        double prior = tallies.values().stream().mapToDouble(t -> t.gbp[0]).sum();
        long lastMicro = tallies.values().stream().mapToLong(t -> t.micro[1]).sum();
        boolean allComplete = tallies.values().stream().allMatch(t -> t.complete);
        CostTotals totals = new CostTotals(RunFx.pence(last), RunFx.pence(prior), RunFx.pence(last - prior),
                percent(last, prior), RunFx.dollars(lastMicro),
                BigDecimal.valueOf(loaded.fx().rateOn(lastDay)).setScale(FX_SCALE, RoundingMode.HALF_UP),
                RunFx.pence(last / n), RunFx.pence(prior / n), allComplete);
        return new RunCostResponse(n, firstDay, lastDay, allTime, days, types, totals, verdict(tallies, last, prior));
    }

    private static int parseDays(String requested) {
        if (requested == null) {
            return DEFAULT_RANGE;
        }
        try {
            int n = Integer.parseInt(requested.trim());
            if (RANGES.contains(n)) {
                return n;
            }
        } catch (NumberFormatException e) {
            // falls through to the same 400 as an unlisted number
        }
        throw new RunsRequestException("days must be one of " + RANGES + " or " + ALL_TIME);
    }

    private static CostDay day(LocalDate date, Map<RunDeepType, Double> spend, boolean complete) {
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (RunDeepType type : RunDeepType.values()) {
            byType.put(type.name(), RunFx.pence(spend.getOrDefault(type, 0.0)));
        }
        return new CostDay(date, byType, RunFx.pence(spend.values().stream().mapToDouble(Double::doubleValue).sum()),
                complete);
    }

    private static CostType costType(RunDeepType type, Tally t) {
        boolean ask = type == RunDeepType.ASK_READY;
        Integer perRun = perRunChange(t);
        return new CostType(type, type.label(), RunFx.pence(t.gbp[1]), RunFx.pence(t.gbp[0]),
                RunFx.pence(t.gbp[1] - t.gbp[0]), percent(t.gbp[1], t.gbp[0]), t.runs[1], t.runs[0],
                percent(t.runs[1], t.runs[0]), perRun, ask ? average(t, 1) : null, ask ? average(t, 0) : null,
                t.complete);
    }

    private static Integer average(Tally t, int period) {
        return t.locationRuns[period] == 0 ? null
                : (int) Math.round((double) t.locations[period] / t.locationRuns[period]);
    }

    private static Double perRunMultiple(Tally t) {
        if (t.runs[0] == 0 || t.runs[1] == 0 || t.gbp[0] <= 0) {
            return null;
        }
        return (t.gbp[1] / t.runs[1]) / (t.gbp[0] / t.runs[0]);
    }

    private static Integer perRunChange(Tally t) {
        Double multiple = perRunMultiple(t);
        return multiple == null ? null : (int) Math.round((multiple - 1) * PERCENT);
    }

    /**
     * The whole-percent change of a figure on a base.
     *
     * @param now  the figure
     * @param base the base
     * @return the signed change, or null when the base is zero or negative
     */
    static Integer percent(double now, double base) {
        return base <= 0 ? null : (int) Math.round((now - base) / base * PERCENT);
    }

    private static CostVerdict verdict(Map<RunDeepType, Tally> tallies, double last, double prior) {
        Integer pct = percent(last, prior);
        Direction direction = pct == null || Math.abs(pct) < VERDICT_PERCENT ? Direction.FLAT
                : pct > 0 ? Direction.UP : Direction.DOWN;
        RunDeepType top = tallies.entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue().gbp[1] - e.getValue().gbp[0])))
                .map(Map.Entry::getKey).orElse(null);
        Tally topTally = top == null ? null : tallies.get(top);
        boolean asOften = topTally != null && (topTally.runs[0] == 0 && topTally.runs[1] == 0
                || percent(topTally.runs[1], topTally.runs[0]) != null
                && Math.abs(percent(topTally.runs[1], topTally.runs[0])) < RUNS_PERCENT);
        Double multiple = topTally == null ? null : perRunMultiple(topTally);
        LocationsPair pair = null;
        if (top == RunDeepType.ASK_READY && average(topTally, 1) != null && average(topTally, 0) != null) {
            pair = new LocationsPair(average(topTally, 1), average(topTally, 0));
        }
        return new CostVerdict(direction, pct, top, top == null ? null : top.label(),
                topTally == null ? null : RunFx.pence(topTally.gbp[1] - topTally.gbp[0]),
                RunFx.pence(last - prior), asOften,
                multiple == null ? null
                        : BigDecimal.valueOf(multiple).setScale(MULTIPLE_SCALE, RoundingMode.HALF_UP),
                pair, skyEval(tallies.get(RunDeepType.SKY_EVAL), last));
    }

    private static SkyEvalShare skyEval(Tally sky, double last) {
        if (sky == null || last <= 0) {
            return null;
        }
        int share = (int) Math.round(sky.gbp[1] / last * PERCENT);
        if (sky.gbp[1] / last * PERCENT <= SKY_EVAL_SHARE_PERCENT) {
            return null;
        }
        return new SkyEvalShare(share, RunFx.pence(sky.gbp[1]), percent(sky.gbp[1], sky.gbp[0]));
    }
}
