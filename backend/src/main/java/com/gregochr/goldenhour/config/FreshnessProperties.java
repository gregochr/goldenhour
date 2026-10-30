package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.freshness} section of {@code application.yml}.
 *
 * <p>Controls per-stability-level cache freshness thresholds for the overnight
 * batch CACHED gate and intraday refresh candidate selection. Thresholds are
 * anchored in operational meteorology — see inline comments for rationale.
 *
 * <p>Shared by both the batch gate and (future) intraday refresh via
 * {@link com.gregochr.goldenhour.service.FreshnessResolver}.
 */
@Component
@ConfigurationProperties(prefix = "photocast.freshness")
@Getter
@Setter
public class FreshnessProperties {

    /**
     * Max cache age (hours) for SETTLED weather (blocking highs, persistent ridges).
     * Atmospheric blocking highs persist 4-5+ days minimum — 36h captures a
     * once-daily refresh rhythm with headroom for pattern weakening.
     */
    private int settledHours = 36;

    /**
     * Max cache age (hours) for TRANSITIONAL weather (frontal approach, mixed signals).
     * 12h = half a synoptic update cycle, matching 2x the NWS 6h operational
     * forecast cadence — catches frontal transitions without thrashing.
     */
    private int transitionalHours = 12;

    /**
     * Max cache age (hours) for UNSETTLED weather (active fronts, convective systems).
     * 4h sits at the outer edge of the nowcasting regime where cloud advection
     * methods become unreliable — aggressive enough for convective evolution.
     */
    private int unsettledHours = 4;

    /**
     * Absolute minimum cache age (hours) below which no re-evaluation occurs,
     * regardless of stability classification. Prevents rapid successive triggers
     * from intraday + JFDI + admin actions.
     */
    private int safetyFloorHours = 2;

    /** Per-horizon caps applied on top of the stability thresholds above. */
    private final Horizon horizon = new Horizon();

    /**
     * Caps on cache age by forecast horizon, applied as
     * {@code max(safetyFloor, min(stabilityThreshold, horizonCap))}.
     *
     * <p>The stability thresholds above are calibrated on <em>synoptic pattern</em> persistence,
     * which is not the same quantity as <em>rating</em> persistence: the rating turns on low cloud
     * at the solar horizon, which moves freely underneath a stable pattern. Measured over 30 days
     * of {@code evaluation_delta_log}, a SETTLED slot re-evaluated after a 48h gap — a gap the 36h
     * threshold itself permits — moved by a full star 72% of the time.
     *
     * <p>Taking the tighter of cap and stability makes the caps monotone: no horizon can come out
     * <em>staler</em> than the stability threshold alone would allow, so a misconfigured cap
     * degrades to the previous behaviour rather than to something worse.
     */
    @Getter
    @Setter
    public static class Horizon {

        /**
         * Max cache age (hours) at T+0 — the day of the event.
         *
         * <p>Defaults to the safety floor because on the day of the event there is no horizon
         * left to trade against: the only reason not to re-evaluate is cost, and the floor
         * already exists to stop trigger thrash. Any larger value is an arbitrary amount of
         * staleness shown to someone deciding whether to drive out tonight.
         *
         * <p>Note this is <em>independent</em> of {@code safetyFloorHours} despite sharing its
         * default — raise the floor and the floor governs; raise this to 36 and the change rolls
         * back to stability-only behaviour at T+0.
         */
        private int t0Hours = 2;

        /**
         * Max cache age (hours) at T+1.
         *
         * <p>3h is chosen to survive no cycle boundary. The cap applies to both cycles through the
         * shared CACHED gate in {@code BriefingCandidateCollector} (the {@code maxAgeFor(daysAhead,
         * stability)} / {@code hasFreshEvaluation} check), which runs <em>before</em> eligibility,
         * so a T+1 slot is refreshed by both cycles rather than held by whichever ran first.
         *
         * <p>A cached entry's age runs from when its results <em>arrived</em>
         * ({@code BriefingEvaluationService} stamps the write with the current instant), not from
         * when its cycle fired. So the cap must sit below the shortest gap between two cycles
         * MINUS the slowest batch the pipeline still waits for. With sun-relative cycles the
         * shortest gap is midwinter morning to afternoon, about 7 h 24 min at Durham (morning
         * ~04:15 UK, afternoon ~11:39 UK), and a cycle waits at most its safety budget,
         * {@code photocast.pipeline.safety-timeout} (3 h 30 min), so a morning batch can land as
         * little as ~3 h 54 min before the afternoon trigger; 3h stays under that. (6h, the first
         * cut, would have let any morning batch slower than ~1 h 24 min — the record holds a
         * 181-minute one — hold its T+1 regions through the afternoon as "cached".) Raising the
         * safety budget, or moving the cycles closer, means re-deriving this. It also clears
         * today's fixed 01:00 / 14:00 schedule, whose smallest gap is 11 h.
         *
         * <p><b>A third cycle breaks the two-cycle derivation at T+0 in summer.</b> The evening
         * refresh (21:00 UK time, tomorrow's sunrise alone) bypasses this gate for its own slots,
         * but the overnight cycle does not: in June the overnight fires about 23:13 UTC, when
         * tomorrow's sunrise has become today's, T+0, whose cap is {@code t0Hours} (2 h). An evening
         * result that lands after about 21:13 UTC is then under that cap and the overnight marks the
         * slot cached, so the morning look <em>is</em> the evening's. That is accepted (owner
         * decision E-D8, evening-sunrise-refresh-plan): 2 h is what the cap already calls fresh
         * enough, and it saves the spend.
         * In winter (GMT) the 21:00 UK evening result lands about an hour later in UTC than it did
         * at the old 20:00 UTC cron, still well before the overnight cycle.
         */
        private int t1Hours = 3;
    }
}
