package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SolarEvent;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import com.gregochr.goldenhour.service.SkyRatingEvalService;
import com.gregochr.goldenhour.service.SolarOffsetTrigger;
import com.gregochr.goldenhour.service.SolarTriggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lists what the scheduler will still run before the UK day ends, for the Day view's "still to run today".
 *
 * <p>Only the scheduler jobs whose runs the Day view can show are listed: the three cycle jobs, the tide, wildlife
 * and astro refreshes, the aurora batch and the weekly sky eval. Fixed-delay pollers are left out (they would flood
 * the list and most write no run). Fires are stepped by the scheduler's own arithmetic
 * ({@link DynamicSchedulerService#firesBetween}). Each cycle fire chains a briefing run at its end and an ask-ready
 * run after it, and the astro job chains an ask-ready run; their times are estimates from the medians of the last 30
 * runs and are listed only when those medians exist.
 */
@Component
class ScheduledFiresLister {

    /** The wildlife comfort refresh job's key. */
    static final String WILDLIFE_JOB = "wildlife_comfort_refresh";

    /** The astro conditions refresh job's key. */
    static final String ASTRO_JOB = "astro_conditions_refresh";

    /** Fewer earlier runs than this and no estimate is made from them. */
    static final int MIN_ESTIMATE = 3;

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);
    private static final int MINUTES_PER_HOUR = 60;

    /** What the Day view shows for a job, and how its estimate is made. */
    private record Def(String name, StatKey key, Role role, CycleType cycle) {
    }

    /** What a fire chains. */
    enum Role {
        /** A forecast cycle: chains a briefing at its end and an ask-ready run after. */
        CYCLE,
        /** The astro job: chains an ask-ready run. */
        ASTRO,
        /** Chains nothing. */
        PLAIN
    }

    private static final Map<String, Def> DEFS = Map.of(
            ForecastCycleJobs.NIGHTLY_JOB, new Def(RunClassifier.cycleName(CycleType.NIGHTLY),
                    StatKey.ofCycle(CycleType.NIGHTLY), Role.CYCLE, CycleType.NIGHTLY),
            ForecastCycleJobs.INTRADAY_JOB, new Def(RunClassifier.cycleName(CycleType.INTRADAY),
                    StatKey.ofCycle(CycleType.INTRADAY), Role.CYCLE, CycleType.INTRADAY),
            ForecastCycleJobs.EVENING_JOB, new Def(RunClassifier.cycleName(CycleType.EVENING),
                    StatKey.ofCycle(CycleType.EVENING), Role.CYCLE, CycleType.EVENING),
            SchedulerRunsService.TIDE_JOB, new Def("Tide data", StatKey.ofJob(RunType.TIDE, null), Role.PLAIN, null),
            WILDLIFE_JOB, new Def("Wildlife comfort", StatKey.ofJob(RunType.WEATHER, EvaluationModel.WILDLIFE),
                    Role.PLAIN, null),
            ASTRO_JOB, new Def("Astro conditions", StatKey.ofJob(RunType.WEATHER, null), Role.ASTRO, null),
            // Aurora batches share a run type with the cycles' batch runs, so there is no population to estimate from.
            SchedulerRunsService.AURORA_BATCH_JOB, new Def("Aurora batch", null, Role.PLAIN, null),
            SkyRatingEvalService.JOB_KEY, new Def(RunDeepType.SKY_EVAL.label(), StatKey.SKY_EVAL, Role.PLAIN, null));

    /**
     * One scheduler fire still to come, before its estimate and chained children are worked out.
     *
     * @param jobKey  the scheduler job key
     * @param name    the display name
     * @param key     the population its estimate comes from, or null for none
     * @param role    what the fire chains
     * @param at      when it fires
     * @param subline how it is triggered
     * @param cycle   the cycle type for a cycle job, else null
     */
    record Fire(String jobKey, String name, StatKey key, Role role, Instant at, String subline, CycleType cycle) {
    }

    /**
     * A row still to run, with its estimates.
     *
     * @param id              {@code sched-JOBKEY-EPOCH}, with {@code -briefing} or {@code -ask} for a chained child
     * @param type            the display type
     * @param name            the display name
     * @param subline         the one-line context
     * @param start           when it starts (or is expected to)
     * @param estimated       true when the start itself is an estimate (a chained child)
     * @param durationSeconds the median duration of the type's last 30 runs, or null
     * @param costMicro       the median cost of those runs, or null
     * @param finish          when it should finish, or null when there is no duration estimate
     */
    record Upcoming(String id, RunDeepType type, String name, String subline, Instant start, boolean estimated,
            Long durationSeconds, Long costMicro, Instant finish) {
    }

    private final SchedulerJobConfigRepository configs;
    private final DynamicSchedulerService scheduler;
    private final SolarTriggerFactory solarTriggers;

    /**
     * Constructs the lister.
     *
     * @param configs       scheduler job store
     * @param scheduler     steps a job's fires
     * @param solarTriggers builds a solar job's trigger, for its sun event time
     */
    ScheduledFiresLister(SchedulerJobConfigRepository configs, DynamicSchedulerService scheduler,
            SolarTriggerFactory solarTriggers) {
        this.configs = configs;
        this.scheduler = scheduler;
        this.solarTriggers = solarTriggers;
    }

    /**
     * The fires between two instants of the listed jobs, in time order.
     *
     * @param after  the instant after which fires count (now)
     * @param before the instant before which fires count (the end of the UK day)
     * @return the fires
     */
    List<Fire> fires(Instant after, Instant before) {
        List<Fire> out = new ArrayList<>();
        for (SchedulerJobConfigEntity config : configs.findAllByOrderByIdAsc()) {
            Def def = DEFS.get(config.getJobKey());
            if (def == null || config.getStatus() != SchedulerJobStatus.ACTIVE) {
                continue;
            }
            for (Instant at : scheduler.firesBetween(config, after, before)) {
                out.add(new Fire(config.getJobKey(), def.name(), def.key(), def.role(), at, subline(config, at),
                        def.cycle()));
            }
        }
        out.sort(java.util.Comparator.comparing(Fire::at));
        return out;
    }

    /**
     * The populations the fires and their chained children need estimates from.
     *
     * @param fires the fires
     * @return the keys, each once
     */
    static java.util.Set<StatKey> keysNeeded(List<Fire> fires) {
        java.util.Set<StatKey> keys = new java.util.LinkedHashSet<>();
        for (Fire fire : fires) {
            if (fire.key() != null) {
                keys.add(fire.key());
            }
            if (fire.role() == Role.CYCLE) {
                keys.add(StatKey.ofJob(RunType.BRIEFING, null));
            }
            if (fire.role() != Role.PLAIN) {
                keys.add(StatKey.ofJob(RunType.ASK_READY, null));
            }
        }
        return keys;
    }

    /**
     * Turns fires into rows, adding the chained children of cycle and astro fires.
     *
     * @param fires   the fires
     * @param stats   the statistics to estimate from
     * @param gap     the usual seconds between a cycle ending and its ask-ready run starting, or null when unknown
     * @param now     the moment estimates are made from
     * @param before  the end of the day; children estimated to start after it are left out
     * @return the rows, ascending by start
     */
    List<Upcoming> rows(List<Fire> fires, RunStats stats, Long gap, Instant now, Instant before) {
        List<Upcoming> out = new ArrayList<>();
        for (Fire fire : fires) {
            RunStats.Window own = fire.key() == null ? RunStats.Window.EMPTY : stats.window(fire.key(), now);
            boolean estimate = own.size() >= MIN_ESTIMATE;
            Long duration = estimate ? own.medianSeconds() : null;
            Long cost = estimate ? own.medianCost() : null;
            String id = "sched-" + fire.jobKey() + "-" + fire.at().getEpochSecond();
            out.add(new Upcoming(id, typeOf(fire), fire.name(), fire.subline(), fire.at(), false, duration, cost,
                    duration == null ? null : fire.at().plusSeconds(duration)));
            if (fire.role() == Role.CYCLE && duration != null) {
                briefingChild(fire, id, duration, stats, now).ifPresent(out::add);
            }
            if (fire.role() != Role.PLAIN && (fire.role() == Role.ASTRO || duration != null)) {
                askChild(fire, id, duration == null ? 0 : duration, gap, stats, now).ifPresent(out::add);
            }
        }
        out.removeIf(u -> u.estimated() && !u.start().isBefore(before));
        out.sort(java.util.Comparator.comparing(Upcoming::start).thenComparing(Upcoming::id));
        return out;
    }

    private static RunDeepType typeOf(Fire fire) {
        if (fire.key() == null) {
            return RunDeepType.OTHER;
        }
        return fire.key().type();
    }

    private Optional<Upcoming> briefingChild(Fire fire, String parentId, long cycleSeconds, RunStats stats,
            Instant now) {
        RunStats.Window briefing = stats.window(StatKey.ofJob(RunType.BRIEFING, null), now);
        if (briefing.size() < MIN_ESTIMATE || briefing.medianSeconds() == null) {
            return Optional.empty();
        }
        Instant start = fire.at().plusSeconds(Math.max(0, cycleSeconds - briefing.medianSeconds()));
        return Optional.of(new Upcoming(parentId + "-briefing", RunDeepType.BRIEFING,
                RunDeepType.BRIEFING.label(), "Runs at the end of " + fire.name(), start, true,
                briefing.medianSeconds(), briefing.medianCost(), start.plusSeconds(briefing.medianSeconds())));
    }

    private Optional<Upcoming> askChild(Fire fire, String parentId, long afterSeconds, Long gap, RunStats stats,
            Instant now) {
        RunStats.Window ask = stats.window(StatKey.ofJob(RunType.ASK_READY, null), now);
        if (ask.size() < MIN_ESTIMATE || ask.medianSeconds() == null) {
            return Optional.empty();
        }
        Instant start = fire.at().plusSeconds(afterSeconds + (gap == null ? 0 : gap));
        String subline = fire.role() == Role.ASTRO ? "After " + fire.name() : "Runs after " + fire.name();
        return Optional.of(new Upcoming(parentId + "-ask", RunDeepType.ASK_READY, RunDeepType.ASK_READY.label(),
                subline, start, true, ask.medianSeconds(), ask.medianCost(),
                start.plusSeconds(ask.medianSeconds())));
    }

    // ── Trigger text ──────────────────────────────────────────────────────────

    private String subline(SchedulerJobConfigEntity config, Instant fire) {
        if (config.getScheduleType() == ScheduleType.SOLAR_OFFSET && config.getSolarEvent() != null
                && config.getSolarOffsetMinutes() != null) {
            return solarSubline(config, fire);
        }
        return "Fixed time · " + HHMM.format(fire.atZone(UK));
    }

    private String solarSubline(SchedulerJobConfigEntity config, Instant fire) {
        SolarEvent event = config.getSolarEvent();
        int offset = config.getSolarOffsetMinutes();
        String what = event == SolarEvent.SUNRISE ? "sunrise" : "sunset";
        String lead = offset == 0 ? "at " + what : offsetText(offset) + " before " + what;
        try {
            SolarOffsetTrigger trigger = solarTriggers.create(config.getJobKey(), event, offset,
                    config.getCronExpression());
            Instant eventAt = trigger.fireFor(fire.atZone(UK).toLocalDate()).eventInstant();
            if (eventAt != null) {
                return "Sun-relative · " + lead + " (" + HHMM.format(eventAt.atZone(UK)) + ")";
            }
        } catch (IllegalArgumentException e) {
            // fall through to the text without a time
        }
        return "Sun-relative · " + lead;
    }

    /**
     * Words for an offset: {@code 250} is "4h 10m", {@code 60} "1h", {@code 45} "45m".
     *
     * @param minutes the offset in minutes
     * @return the text
     */
    static String offsetText(int minutes) {
        int hours = minutes / MINUTES_PER_HOUR;
        int rest = minutes % MINUTES_PER_HOUR;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }
}
