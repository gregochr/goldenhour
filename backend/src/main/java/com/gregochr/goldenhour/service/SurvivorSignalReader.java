package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The unified survivor read model — the ONE read path the survivor-signal hot-topic detectors use.
 *
 * <p>"Unified" is a single READ surface over correctly-shaped STORAGE, not a single physical table.
 * It joins the two survivor-only tables — {@code forecast_score} (scores: inversion, bluebell) and
 * {@code survivor_atmosphere} (readings: dust, surge, snow) — by their shared
 * {@code (location, date, event_type)} key into one {@link SurvivorSignals} composite per key.
 * Scores and readings stay in their own sub-records (never flattened), and every composite is a
 * survivor by construction (both backing tables are survivor-only), so a detector reading through
 * this model structurally cannot sample the triaged rejects that broke the legacy
 * {@code forecast_evaluation} reads.
 *
 * <p>⚠️ <b>A {@code forecast_score} component is evidence exactly like a {@code cached_evaluation}
 * rating, and a nightly Gate 4 stability skip retracts it the same way</b> (a Codex review of #940,
 * the day after the stability-skip retraction feature landed on the other two stores — see
 * {@code EvaluationViewService}'s "Where a rating lives" javadoc). {@code forecast_score} rows are
 * UPSERTed only when a Claude call actually happens, so a slot the pipeline later declines to
 * re-score (a stability skip) leaves its old INVERSION/BLUEBELL row standing as "the latest" with
 * nothing to mark it stale — exactly the {@code forecast_evaluation}/{@code cached_evaluation} defect
 * the earlier fix closed, one store over. {@link #read} therefore drops (never assigns to a
 * composite's {@link SurvivorSignals.Scores}) an INVERSION or BLUEBELL row whose own
 * {@link ForecastScoreEntity#getEvaluatedAt()} predates the slot's most recent stability skip,
 * via the same low-level primitive the other two stores use,
 * {@code EvaluationViewService.isRetractedByStabilitySkip} — never a second, hand-written condition.
 * {@code survivor_atmosphere} readings are untouched: they are measured or forecast atmospheric
 * INPUT (dust, surge, snow, humidity), never Claude's opinion, so a skip — which retracts an
 * evaluation the pipeline declined to redo, not a measurement — has nothing to say about them.
 *
 * <p>⚠️ <b>The skip lookup is loaded ONCE per hot-topic aggregation, never once per strategy.</b> Six
 * strategies each call {@link #read} independently for the same window, which would mean six
 * {@code loadStabilitySkips} queries per {@code GET /api/briefing} if this class simply loaded its
 * own copy every call. {@link #withStabilityWindow} is the fix: {@code HotTopicAggregator} opens one
 * window around its whole strategies pass, {@link #read} shares that one loaded map across every
 * call made from inside it (matched on the exact {@code (from, to)} pair), and the window is cleared
 * in a {@code finally} block the instant the pass ends — never a time-based cache with a staleness
 * window of its own, just call-scoped sharing with a hard, deterministic boundary. A {@link #read}
 * call made from OUTSIDE a window (this class's other caller, {@code ComingUpConditionsBuilder}, on
 * the separate "Coming up" almanac path) falls back to loading its own copy — one query, not shared,
 * and not part of the cost this rule was written to bound.
 */
@Service
public class SurvivorSignalReader {

    private final ForecastScoreRepository forecastScoreRepository;
    private final SurvivorAtmosphereRepository survivorAtmosphereRepository;
    private final EvaluationViewService evaluationViewService;
    private final ThreadLocal<StabilityWindow> stabilityWindow = new ThreadLocal<>();

    /** A shared, call-scoped stability-skip load — see {@link #withStabilityWindow}. */
    private record StabilityWindow(LocalDate from, LocalDate to, Map<String, Instant> skips) {
    }

    /**
     * Constructs the reader.
     *
     * @param forecastScoreRepository      the scores half ({@code forecast_score})
     * @param survivorAtmosphereRepository the readings half ({@code survivor_atmosphere})
     * @param evaluationViewService        source of the stability-skip lookup and its retraction
     *                                      primitive, shared with the {@code forecast_evaluation}/
     *                                      {@code cached_evaluation} retraction rule
     */
    public SurvivorSignalReader(ForecastScoreRepository forecastScoreRepository,
            SurvivorAtmosphereRepository survivorAtmosphereRepository,
            EvaluationViewService evaluationViewService) {
        this.forecastScoreRepository = forecastScoreRepository;
        this.survivorAtmosphereRepository = survivorAtmosphereRepository;
        this.evaluationViewService = evaluationViewService;
    }

    /**
     * Opens a shared stability-skip window for the duration of {@code action}, so every
     * {@link #read} call made from inside it — however many strategies call it, over whatever
     * dates they each ask for within {@code [from, to]} — shares ONE {@code loadStabilitySkips}
     * query rather than one per caller. Always cleared in a {@code finally} block, so a
     * {@link #read} call made after {@code action} returns (or from an unrelated concurrent
     * request on another thread — this is thread-local, not a shared mutable field) never sees a
     * stale window.
     *
     * @param from   first evaluation date the window covers (inclusive)
     * @param to     last evaluation date the window covers (inclusive)
     * @param action the strategies pass to run inside the window
     * @param <T>    the action's return type
     * @return whatever {@code action} returns
     */
    public <T> T withStabilityWindow(LocalDate from, LocalDate to, Supplier<T> action) {
        stabilityWindow.set(new StabilityWindow(from, to, evaluationViewService.loadStabilitySkips(from, to)));
        try {
            return action.get();
        } finally {
            stabilityWindow.remove();
        }
    }

    /**
     * Resolves the stability-skip map for a {@link #read} call: the shared window's map when one is
     * open and covers exactly this {@code (from, to)} pair, otherwise a fresh, unshared load.
     *
     * @param from first evaluation date (inclusive)
     * @param to   last evaluation date (inclusive)
     * @return {@code "locationName|date|targetType"} to that slot's most recent stability-skip instant
     */
    private Map<String, Instant> resolveStabilitySkips(LocalDate from, LocalDate to) {
        StabilityWindow window = stabilityWindow.get();
        if (window != null && window.from().equals(from) && window.to().equals(to)) {
            return window.skips();
        }
        return evaluationViewService.loadStabilitySkips(from, to);
    }

    /**
     * Whether a {@code forecast_score} component row must be treated as absent because a nightly
     * Gate 4 stability skip stands against its slot and postdates it.
     *
     * @param row             the component row (INVERSION or BLUEBELL)
     * @param stabilitySkips  the resolved stability-skip map for the read's window
     * @return true if the row predates its slot's most recent stability skip
     */
    private static boolean isComponentRetracted(
            ForecastScoreEntity row, Map<String, Instant> stabilitySkips) {
        if (stabilitySkips.isEmpty() || row.getLocation() == null) {
            return false;
        }
        Instant latestSkip = stabilitySkips.get(EvaluationViewService.stabilitySkipKey(
                row.getLocation().getName(), row.getEvaluationDate(), row.getEventType()));
        return EvaluationViewService.isRetractedByStabilitySkip(row.getEvaluatedAt(), latestSkip);
    }

    /**
     * Returns the survivor-signal composites for every survivor key in the window. A composite is
     * present for any key that has at least one score or reading; absent signals are left null in
     * their sub-record. The list is in no guaranteed order — detectors group/sort as they need.
     *
     * <p>An INVERSION or BLUEBELL row superseded by a nightly Gate 4 stability skip against its own
     * slot (see the class javadoc) is treated as though it were never written — its score never
     * reaches a composite's {@link SurvivorSignals.Scores}, exactly as a retracted
     * {@code cached_evaluation}/{@code forecast_evaluation} rating reads as never-rated on the Plan
     * and map surfaces. A {@code survivor_atmosphere} reading is never retracted this way.
     *
     * @param from inclusive start date
     * @param to   inclusive end date
     * @return one composite per survivor {@code (location, date, event_type)} in the window
     */
    public List<SurvivorSignals> read(LocalDate from, LocalDate to) {
        Map<String, Instant> stabilitySkips = resolveStabilitySkips(from, to);
        Map<String, Accumulator> byKey = new LinkedHashMap<>();

        for (ForecastScoreEntity s : forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), from, to)) {
            if (isComponentRetracted(s, stabilitySkips)) {
                continue;
            }
            Accumulator acc = accumulatorFor(
                    byKey, s.getLocation(), s.getEvaluationDate(), s.getEventType());
            acc.inversion = s.getScore();
            // The INVERSION row's summary is its NONE/MODERATE/STRONG classification, written by
            // ForecastScoreWriter — so the detector can label the band instead of assuming one.
            acc.inversionBand = s.getSummary();
        }
        for (ForecastScoreEntity s : forecastScoreRepository.findComponentsByType(
                ForecastType.BLUEBELL.getId(), from, to)) {
            if (isComponentRetracted(s, stabilitySkips)) {
                continue;
            }
            Accumulator acc = accumulatorFor(
                    byKey, s.getLocation(), s.getEvaluationDate(), s.getEventType());
            acc.bluebell = s.getScore();
            acc.bluebellSummary = s.getSummary();
        }
        for (SurvivorAtmosphereEntity a : survivorAtmosphereRepository.findInDateRange(from, to)) {
            accumulatorFor(byKey, a.getLocation(), a.getEvaluationDate(), a.getEventType())
                    .readings = a;
        }

        List<SurvivorSignals> result = new ArrayList<>(byKey.size());
        for (Accumulator acc : byKey.values()) {
            result.add(acc.build());
        }
        return result;
    }

    private Accumulator accumulatorFor(Map<String, Accumulator> byKey, LocationEntity location,
            LocalDate date, TargetType eventType) {
        String key = location.getId() + "|" + date + "|" + eventType;
        return byKey.computeIfAbsent(key, k -> new Accumulator(location, date, eventType));
    }

    /** Mutable per-key accumulator that folds the two surfaces into one composite. */
    private static final class Accumulator {
        private final LocationEntity location;
        private final LocalDate date;
        private final TargetType eventType;
        private Integer inversion;
        private String inversionBand;
        private Integer bluebell;
        private String bluebellSummary;
        private SurvivorAtmosphereEntity readings;

        private Accumulator(LocationEntity location, LocalDate date, TargetType eventType) {
            this.location = location;
            this.date = date;
            this.eventType = eventType;
        }

        private SurvivorSignals build() {
            boolean noScores = inversion == null && inversionBand == null
                    && bluebell == null && bluebellSummary == null;
            SurvivorSignals.Scores scores = noScores
                    ? SurvivorSignals.Scores.EMPTY
                    : new SurvivorSignals.Scores(
                            inversion, inversionBand, bluebell, bluebellSummary);
            SurvivorSignals.Readings r = readings == null
                    ? SurvivorSignals.Readings.EMPTY
                    : new SurvivorSignals.Readings(
                            readings.getAerosolOpticalDepth(), readings.getDust(),
                            readings.getPm25(), readings.getSurgeRiskLevel(),
                            readings.getSnowDepthMetres(), readings.getFreezingLevelMetres(),
                            readings.getHumidity(), readings.getSurgeTotalMetres(),
                            readings.getSurgeWindSpeedMs(), readings.getSurgeWindDirectionDegrees(),
                            readings.getTemperatureCelsius());
            return new SurvivorSignals(location, date, eventType, scores, r);
        }
    }
}
