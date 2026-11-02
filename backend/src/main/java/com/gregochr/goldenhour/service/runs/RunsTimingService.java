package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.model.runs.RunTimingResponse;
import com.gregochr.goldenhour.model.runs.RunTimingResponse.Extreme;
import com.gregochr.goldenhour.model.runs.RunTimingResponse.TimingRun;
import com.gregochr.goldenhour.model.runs.RunTimingResponse.TimingStats;
import com.gregochr.goldenhour.model.runs.RunTimingResponse.Trigger;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SolarReferencePoint;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the Runs "Timing" view: how long one run type takes over the last 30, 60 or 120 UK days, the statistics over
 * those runs, the median of every timing type over the same range, and the numbers behind the sunset planner (the
 * Afternoon refresh) and the "when results are ready" card (the fixed-time cycles).
 *
 * <p>One pass of the shared {@link RunsLoader} serves all of it. Only finished (done or partial) scheduled behaviour
 * is measured: failed runs are left out, so are manual runs wherever the type records a manual flag, and a briefing
 * counts only when it belongs to a cycle. A cycle measured "until answers ready" runs from its start to the end of
 * the ask-ready run linked to it; a cycle with no such link is left out of that measure and counted in
 * {@code withoutAnswersLink}. Every "today" comes from the injected clock.
 */
@Service
public class RunsTimingService {

    /** The ranges that can be asked for, in days. */
    static final List<Integer> RANGES = List.of(30, 60, 120);

    /** The range used when none is asked for. */
    static final int DEFAULT_RANGE = 30;

    /** The measure name for start to end of the run itself. */
    static final String CYCLE = "cycle";

    /** The measure name for start to the end of the linked ask-ready run. */
    static final String READY = "ready";

    /** The 90th percentile over the median above which the shape is called skewed. */
    static final double SKEW_RATIO = 1.35;

    private static final double P90 = 0.9;
    private static final double PERCENT = 100.0;
    private static final int HALVES = 2;
    private static final Set<RunDeepType> MANUAL_FLAGGED = EnumSet.of(RunDeepType.ASK_READY, RunDeepType.SKY_EVAL);

    private final RunsLoader loader;
    private final RunsBounds bounds;
    private final SolarService solar;
    private final SolarReferencePoint referencePoint;
    private final SchedulerJobConfigRepository configs;
    private final DynamicSchedulerService scheduler;

    /**
     * Constructs the service.
     *
     * @param loader         loads the range's rows in one pass
     * @param bounds         the UK civil today
     * @param solar          the sunrise and sunset calculator
     * @param referencePoint the point sunrise and sunset are computed at
     * @param configs        scheduler job store, for the Afternoon trigger and the fixed start times
     * @param scheduler      steps a job's fires
     */
    RunsTimingService(RunsLoader loader, RunsBounds bounds, SolarService solar, SolarReferencePoint referencePoint,
            SchedulerJobConfigRepository configs, DynamicSchedulerService scheduler) {
        this.loader = loader;
        this.bounds = bounds;
        this.solar = solar;
        this.referencePoint = referencePoint;
        this.configs = configs;
        this.scheduler = scheduler;
    }

    /** One measured run and its duration under the measure asked for. */
    private record Sample(DeepRun run, LocalDate date, long seconds) {
    }

    /**
     * Serves the timing of one type.
     *
     * @param typeText    the type name (not OTHER)
     * @param requestDays the range in days (30, 60 or 120), or null for {@value #DEFAULT_RANGE}
     * @param measureText {@code cycle} (default) or {@code ready}; {@code ready} only for the three cycle types
     * @return the runs, statistics, type medians and the planner or ready-by facts
     * @throws RunsRequestException if the type, range or measure is not allowed
     */
    public RunTimingResponse timing(String typeText, Integer requestDays, String measureText) {
        RunDeepType type = parseType(typeText);
        int days = requestDays == null ? DEFAULT_RANGE : requestDays;
        if (!RANGES.contains(days)) {
            throw new RunsRequestException("days must be one of " + RANGES);
        }
        String measure = measureText == null || measureText.isBlank() ? CYCLE
                : measureText.trim().toLowerCase(Locale.ROOT);
        if (!CYCLE.equals(measure) && !READY.equals(measure)) {
            throw new RunsRequestException("measure must be cycle or ready");
        }
        if (READY.equals(measure) && !type.isCycle()) {
            throw new RunsRequestException("measure=ready applies only to the cycle types");
        }

        LocalDate today = bounds.today();
        LocalDate firstDay = today.minusDays(days - 1L);
        LocalDate midDay = firstDay.plusDays(days / HALVES);
        Instant from = ForecastHorizon.ukDayStartUtc(firstDay).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(today.plusDays(1)).toInstant(ZoneOffset.UTC);
        List<DeepRun> rows = loader.load(from, to).rows();

        Map<String, Long> typeMedians = new LinkedHashMap<>();
        for (RunDeepType t : RunDeepType.values()) {
            if (t != RunDeepType.OTHER) {
                typeMedians.put(t.name(), median(durations(samples(rows, t, CYCLE))));
            }
        }

        List<Sample> samples = samples(rows, type, measure);
        int withoutLink = READY.equals(measure) ? (int) measurable(rows, type).stream()
                .filter(r -> r.answersReadyAt() == null).count() : 0;
        Map<LocalDate, Instant> sunsets = new HashMap<>();
        List<TimingRun> runs = samples.stream().map(s -> new TimingRun(s.run().id(), s.run().startedAt(), s.date(),
                s.seconds(), sunsets.computeIfAbsent(s.date(), this::sunset))).toList();
        TimingStats stats = stats(samples, midDay, MANUAL_FLAGGED.contains(type));

        Trigger trigger = null;
        Instant todaySunset = null;
        if (type == RunDeepType.AFTERNOON) {
            trigger = afternoonTrigger();
            todaySunset = sunset(today);
        }
        Instant startsAt = null;
        Instant typical = null;
        Instant p90 = null;
        Instant worst = null;
        String basis = null;
        Instant sunrise = null;
        Long slack = null;
        if (type == RunDeepType.OVERNIGHT || type == RunDeepType.EVENING) {
            List<Long> ready = durations(samples(rows, type, READY));
            basis = ready.isEmpty() ? CYCLE : READY;
            List<Long> basisDurations = ready.isEmpty() ? durations(samples(rows, type, CYCLE)) : ready;
            startsAt = startsToday(type, today).orElse(null);
            if (startsAt != null && !basisDurations.isEmpty()) {
                typical = startsAt.plusSeconds(median(basisDurations));
                Long p90Seconds = p90(basisDurations);
                p90 = p90Seconds == null ? null : startsAt.plusSeconds(p90Seconds);
                worst = startsAt.plusSeconds(basisDurations.get(basisDurations.size() - 1));
            }
            if (type == RunDeepType.OVERNIGHT) {
                sunrise = solar.sunriseUtc(referencePoint.lat(), referencePoint.lon(), today)
                        .toInstant(ZoneOffset.UTC);
                slack = p90 == null ? null : Duration.between(p90, sunrise).toMinutes();
            }
        }
        return new RunTimingResponse(type, type.label(), days, measure, runs, stats, typeMedians, withoutLink,
                trigger, todaySunset, startsAt, typical, p90, worst, basis, sunrise, slack);
    }

    private static RunDeepType parseType(String text) {
        if (text == null || text.isBlank()) {
            throw new RunsRequestException("type is required");
        }
        RunDeepType type;
        try {
            type = RunDeepType.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RunsRequestException("unknown type");
        }
        if (type == RunDeepType.OTHER) {
            throw new RunsRequestException("OTHER is not a timing type");
        }
        return type;
    }

    // ── Populations ───────────────────────────────────────────────────────────

    private static List<DeepRun> measurable(List<DeepRun> rows, RunDeepType type) {
        return rows.stream()
                .filter(r -> r.type() == type && !r.manual() && r.durationSeconds() != null
                        && (r.status() == RunDeepStatus.DONE || r.status() == RunDeepStatus.PARTIAL)
                        && (type != RunDeepType.BRIEFING || r.parentCycleId() != null))
                .toList();
    }

    private static List<Sample> samples(List<DeepRun> rows, RunDeepType type, String measure) {
        List<Sample> out = new ArrayList<>();
        for (DeepRun r : measurable(rows, type)) {
            long seconds;
            if (READY.equals(measure)) {
                if (r.answersReadyAt() == null) {
                    continue;
                }
                seconds = Duration.between(r.startedAt(), r.answersReadyAt()).toSeconds();
            } else {
                seconds = r.durationSeconds();
            }
            out.add(new Sample(r, ForecastHorizon.civilDate(r.startedAt()), seconds));
        }
        out.sort(Comparator.comparing((Sample s) -> s.run().startedAt()).thenComparing(s -> s.run().id()));
        return out;
    }

    private static List<Long> durations(List<Sample> samples) {
        List<Long> out = new ArrayList<>(samples.stream().map(Sample::seconds).toList());
        Collections.sort(out);
        return out;
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    private static TimingStats stats(List<Sample> samples, LocalDate midDay, boolean manualExcluded) {
        if (samples.isEmpty()) {
            return new TimingStats(0, null, null, null, null, null, null, null, false, manualExcluded);
        }
        List<Long> all = durations(samples);
        Long median = median(all);
        Long p90 = p90(all);
        Sample slowest = samples.stream().max(Comparator.comparingLong(Sample::seconds)).orElseThrow();
        Sample fastest = samples.stream().min(Comparator.comparingLong(Sample::seconds)).orElseThrow();
        Long first = median(durations(samples.stream().filter(s -> s.date().isBefore(midDay)).toList()));
        Long second = median(durations(samples.stream().filter(s -> !s.date().isBefore(midDay)).toList()));
        Integer change = first == null || second == null || first == 0 ? null
                : (int) Math.round((second - first) / (double) first * PERCENT);
        boolean skewed = p90 != null && median != null && median > 0 && p90 / (double) median > SKEW_RATIO;
        return new TimingStats(samples.size(), median, p90, new Extreme(slowest.seconds(), slowest.date()),
                new Extreme(fastest.seconds(), fastest.date()), first, second, change, skewed, manualExcluded);
    }

    /**
     * The median of an ascending list.
     *
     * @param ascending the values, ascending
     * @return the median, null for an empty list
     */
    static Long median(List<Long> ascending) {
        return AdminRunsService.median(ascending);
    }

    /**
     * The 90th percentile, served only when there are at least two values to take it over.
     *
     * @param ascending the values, ascending
     * @return the nearest-rank 90th percentile, null with fewer than two values
     */
    static Long p90(List<Long> ascending) {
        return ascending.size() < HALVES ? null : RunStats.percentile(ascending, P90);
    }

    // ── Sun and schedule ──────────────────────────────────────────────────────

    private Instant sunset(LocalDate date) {
        return solar.sunsetUtc(referencePoint.lat(), referencePoint.lon(), date).toInstant(ZoneOffset.UTC);
    }

    private Trigger afternoonTrigger() {
        return configs.findByJobKey(ForecastCycleJobs.INTRADAY_JOB)
                .filter(c -> c.getScheduleType() == ScheduleType.SOLAR_OFFSET && c.getSolarEvent() != null
                        && c.getSolarOffsetMinutes() != null)
                .map(c -> new Trigger(c.getSolarEvent().name(), c.getSolarOffsetMinutes()))
                .orElse(null);
    }

    private Optional<Instant> startsToday(RunDeepType type, LocalDate today) {
        String key = type == RunDeepType.OVERNIGHT ? ForecastCycleJobs.NIGHTLY_JOB : ForecastCycleJobs.EVENING_JOB;
        Optional<SchedulerJobConfigEntity> config = configs.findByJobKey(key);
        if (config.isEmpty()) {
            return Optional.empty();
        }
        Instant dayStart = ForecastHorizon.ukDayStartUtc(today).toInstant(ZoneOffset.UTC);
        Instant dayEnd = ForecastHorizon.ukDayStartUtc(today.plusDays(1)).toInstant(ZoneOffset.UTC);
        return scheduler.firesBetween(config.get(), dayStart.minusSeconds(1), dayEnd).stream().findFirst();
    }
}
