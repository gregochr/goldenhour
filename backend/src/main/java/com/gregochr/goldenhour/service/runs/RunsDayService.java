package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.RunDayResponse;
import com.gregochr.goldenhour.model.runs.RunDayRow;
import com.gregochr.goldenhour.model.runs.RunDaySummary;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.model.runs.RunFacts;
import com.gregochr.goldenhour.service.SolarReferencePoint;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the Runs "Day" view: one UK civil day's rows in chronological order with their medians, "slow" flags and
 * statuses, what is still to run when the day is today, the summary strip and the filter counts.
 *
 * <p>Every number is worked out here and served; the client lays out and draws. "Today" and "now" come from the
 * injected clock, never the Rewind clock. A UK day runs from one UK midnight to the next, so it is 23, 24 or 25 hours
 * of UTC; the response carries both bounds.
 */
@Service
public class RunsDayService {

    /** Days of history the spend average is taken over. */
    static final int AVERAGE_DAYS = 30;

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);
    private static final double PERCENT = 100.0;

    private final RunsLoader loader;
    private final RunStatsLoader statsLoader;
    private final ScheduledFiresLister fires;
    private final RunsBounds bounds;
    private final SolarService solar;
    private final SolarReferencePoint referencePoint;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param loader         loads the day's rows
     * @param statsLoader    loads the samples behind medians and p90s
     * @param fires          lists what the scheduler will still run today
     * @param bounds         the days that can be asked about
     * @param solar          the sunrise and sunset calculator
     * @param referencePoint the point sunrise and sunset are computed at
     * @param clock          the application clock
     */
    RunsDayService(RunsLoader loader, RunStatsLoader statsLoader, ScheduledFiresLister fires, RunsBounds bounds,
            SolarService solar, SolarReferencePoint referencePoint, Clock clock) {
        this.loader = loader;
        this.statsLoader = statsLoader;
        this.fires = fires;
        this.bounds = bounds;
        this.solar = solar;
        this.referencePoint = referencePoint;
        this.clock = clock;
    }

    /**
     * Serves one UK day.
     *
     * @param requested the day, or null for today
     * @return the day's rows, summary and filter counts
     * @throws RunsRequestException if the day is in the future or before the earliest day
     */
    public RunDayResponse day(LocalDate requested) {
        LocalDate today = bounds.today();
        LocalDate earliest = bounds.earliestDay();
        LocalDate date = requested == null ? today : requested;
        bounds.requireSelectable(date, earliest);
        boolean isToday = date.equals(today);

        Instant dayStart = ForecastHorizon.ukDayStartUtc(date).toInstant(ZoneOffset.UTC);
        Instant dayEnd = ForecastHorizon.ukDayStartUtc(date.plusDays(1)).toInstant(ZoneOffset.UTC);
        Instant now = clock.instant();

        RunsLoader.LoadedRuns loaded = loader.load(dayStart, dayEnd);
        List<ScheduledFiresLister.Fire> pending = isToday ? fires.fires(now, dayEnd) : List.of();

        Map<StatKey, Integer> wanted = new LinkedHashMap<>();
        loaded.rows().stream().filter(DeepRun::finished).forEach(r -> wanted.merge(r.key(), 1, Integer::sum));
        ScheduledFiresLister.keysNeeded(pending).forEach(k -> wanted.putIfAbsent(k, 0));
        RunStats stats = statsLoader.load(wanted, dayEnd);

        boolean chains = pending.stream().anyMatch(f -> f.role() != ScheduledFiresLister.Role.PLAIN);
        Long gap = chains ? statsLoader.askReadyGapSeconds(now) : null;
        List<ScheduledFiresLister.Upcoming> upcoming = fires.rows(pending, stats, gap, now, dayEnd);

        double rate = loaded.fx().rateOn(date);
        List<RunDayRow> rows = new ArrayList<>();
        loaded.rows().forEach(r -> rows.add(finishedRow(r, stats)));
        upcoming.forEach(u -> rows.add(scheduledRow(u, rate)));
        rows.sort(Comparator.comparing(RunDayRow::startedAt).thenComparing(RunDayRow::id));

        RunDaySummary summary = isToday
                ? todaySummary(loaded.rows(), upcoming, date, earliest, rate)
                : pastSummary(loaded.rows(), date, earliest, stats);
        Instant sunrise = solar.sunriseUtc(referencePoint.lat(), referencePoint.lon(), date)
                .toInstant(ZoneOffset.UTC);
        Instant sunset = solar.sunsetUtc(referencePoint.lat(), referencePoint.lon(), date).toInstant(ZoneOffset.UTC);
        return new RunDayResponse(date, isToday, earliest, HHMM.format(sunrise.atZone(UK)),
                HHMM.format(sunset.atZone(UK)), sunrise, sunset, dayStart, dayEnd, isToday ? now : null, rows,
                summary, filterCounts(rows));
    }

    // ── Rows ──────────────────────────────────────────────────────────────────

    private static RunDayRow finishedRow(DeepRun r, RunStats stats) {
        boolean done = r.finished();
        boolean slow = done && stats.slow(r.key(), r.startedAt(), r.durationSeconds());
        Integer faster = done ? stats.fasterThanPercent(r.key(), r.startedAt(), r.durationSeconds()) : null;
        RunFacts facts = new RunFacts(r.startedAt(), r.endedAt(), faster, r.batches(), r.requests(),
                r.answersReadyAt(), null, null);
        return new RunDayRow(r.id(), r.kind(), r.type(), r.type().label(), r.name(), r.subline(), r.startedAt(),
                r.endedAt(), false, r.durationSeconds(), r.costMicro(), r.costComplete(),
                r.costMicro() == null ? null : RunFx.pence(r.costGbp()),
                r.costMicro() == null ? null : RunFx.dollars(r.costMicro()), r.status(), r.note(), r.tone(), slow,
                r.parentCycleId(), r.cycleId(), r.jobRunId(), facts);
    }

    private static RunDayRow scheduledRow(ScheduledFiresLister.Upcoming u, double rate) {
        RunFacts facts = new RunFacts(null, null, null, null, null, null, u.start(), u.finish());
        return new RunDayRow(u.id(), RunDeepKind.SCHEDULED, u.type(), u.type().label(), u.name(), u.subline(),
                u.start(), null, u.estimated(), u.durationSeconds(), u.costMicro(), u.costMicro() != null,
                u.costMicro() == null ? null : RunFx.pence(RunFx.gbp(u.costMicro(), rate)),
                u.costMicro() == null ? null : RunFx.dollars(u.costMicro()), RunDeepStatus.SCHEDULED, null, null,
                false, null, null, null, facts);
    }

    private static Map<String, Integer> filterCounts(List<RunDayRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ALL", rows.size());
        counts.put("CYCLES", (int) rows.stream().filter(r -> r.type().isCycle()).count());
        counts.put("ASK_READY", countType(rows, RunDeepType.ASK_READY));
        counts.put("BRIEFING", countType(rows, RunDeepType.BRIEFING));
        counts.put("SKY_EVAL", countType(rows, RunDeepType.SKY_EVAL));
        counts.put("OTHER", countType(rows, RunDeepType.OTHER));
        counts.put("ATTENTION", (int) rows.stream().filter(r -> r.status().needsAttention()).count());
        return counts;
    }

    private static int countType(List<RunDayRow> rows, RunDeepType type) {
        return (int) rows.stream().filter(r -> r.type() == type).count();
    }

    // ── Summaries ─────────────────────────────────────────────────────────────

    private RunDaySummary todaySummary(List<DeepRun> runs,
            List<ScheduledFiresLister.Upcoming> upcoming, LocalDate date, LocalDate earliest, double rate) {
        int finished = (int) runs.stream().filter(DeepRun::finished).count();
        int running = (int) runs.stream().filter(r -> r.status() == RunDeepStatus.RUNNING).count();
        List<DeepRun> attention = runs.stream().filter(r -> r.status().needsAttention()).toList();
        double spend = runs.stream().mapToDouble(DeepRun::costGbp).sum();
        double estimates = upcoming.stream().filter(u -> u.costMicro() != null)
                .mapToDouble(u -> RunFx.gbp(u.costMicro(), rate)).sum();
        RunDaySummary.NextUp next = upcoming.stream().findFirst()
                .map(u -> new RunDaySummary.NextUp(u.name(), u.start(), u.estimated())).orElse(null);
        return new RunDaySummary(finished, running, upcoming.size(), null, null, null, attention.size(),
                (int) attention.stream().filter(r -> r.status() == RunDeepStatus.PARTIAL).count(),
                (int) attention.stream().filter(r -> r.status() == RunDeepStatus.FAILED).count(),
                attention.stream().map(DeepRun::name).toList(), RunFx.pence(spend),
                runs.stream().allMatch(DeepRun::costComplete), RunFx.pence(spend + estimates),
                averageSpend(date, earliest), null, next, null);
    }

    private RunDaySummary pastSummary(List<DeepRun> runs, LocalDate date, LocalDate earliest, RunStats stats) {
        int cycles = (int) runs.stream().filter(r -> r.kind() == RunDeepKind.CYCLE).count();
        List<DeepRun> attention = runs.stream().filter(r -> r.status().needsAttention()).toList();
        double spend = runs.stream().mapToDouble(DeepRun::costGbp).sum();
        BigDecimal average = averageSpend(date, earliest);
        Integer vsAvg = average == null || average.signum() == 0 ? null
                : (int) Math.round((spend - average.doubleValue()) / average.doubleValue() * PERCENT);
        RunDaySummary.SlowestCycle slowest = runs.stream()
                .filter(r -> r.kind() == RunDeepKind.CYCLE && r.durationSeconds() != null)
                .max(Comparator.comparingLong(DeepRun::durationSeconds))
                .map(r -> new RunDaySummary.SlowestCycle(r.name(), r.durationSeconds(),
                        stats.window(r.key(), r.startedAt()).medianSeconds()))
                .orElse(null);
        return new RunDaySummary(null, null, null, runs.size(), cycles, runs.size() - cycles, attention.size(),
                (int) attention.stream().filter(r -> r.status() == RunDeepStatus.PARTIAL).count(),
                (int) attention.stream().filter(r -> r.status() == RunDeepStatus.FAILED).count(),
                null, RunFx.pence(spend), runs.stream().allMatch(DeepRun::costComplete), null, average, vsAvg, null,
                slowest);
    }

    /**
     * The average daily spend over the {@value #AVERAGE_DAYS} UK days before a date, or over the days there are when
     * history is shorter.
     */
    private BigDecimal averageSpend(LocalDate date, LocalDate earliest) {
        LocalDate from = date.minusDays(AVERAGE_DAYS);
        if (from.isBefore(earliest)) {
            from = earliest;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, date);
        if (days <= 0) {
            return null;
        }
        Instant start = ForecastHorizon.ukDayStartUtc(from).toInstant(ZoneOffset.UTC);
        Instant end = ForecastHorizon.ukDayStartUtc(date).toInstant(ZoneOffset.UTC);
        double total = loader.load(start, end).rows().stream().mapToDouble(DeepRun::costGbp).sum();
        return RunFx.pence(total / days);
    }
}
