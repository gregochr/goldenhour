package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.service.evaluation.visitor.ComponentScore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Pass 2 dual-write: persists the per-component scores of a single scored forecast evaluation
 * to {@code forecast_score}, alongside (never instead of) the live {@code results_json} serving
 * path. Nothing reads {@code forecast_score} yet — this writer exists to prove, via the
 * reconciliation query in {@code docs/engineering/forecast-score-reconciliation.md}, that the
 * normalised record matches the serving payload before Pass 4 migrates the read side.
 *
 * <p><b>What it writes</b>, per scored evaluation (location, date, SUNRISE/SUNSET):
 * <ul>
 *   <li>the combiner's component scores — {@link ForecastType#SKY} (pre-combine sky score) and,
 *       for coastal locations whose tide visitor applied and did not abstain,
 *       {@link ForecastType#TIDAL} (with its deterministic state clause);</li>
 *   <li>{@link ForecastType#FIERY_SKY} and {@link ForecastType#GOLDEN_HOUR} from the evaluation's
 *       0–100 potentials (display products, never combiner peers).</li>
 * </ul>
 * Rows are UPSERTed against the component unique key
 * {@code (forecast_type_id, location_id, evaluation_date, event_type)} — latest evaluation wins,
 * matching {@code cached_evaluation} semantics, so intraday re-runs and sync re-evaluations
 * overwrite the same key.
 *
 * <p><b>Failure isolation.</b> The method runs in its own {@link Propagation#REQUIRES_NEW}
 * transaction so a write failure rolls back only the dual-write — never the caller's evaluation.
 * The caller ({@link ForecastResultHandler}) additionally wraps the call so any thrown exception
 * is logged loudly at ERROR (with the component key) and the evaluation proceeds. The serving
 * path is the live product; {@code forecast_score} is the record being proven.
 *
 * <p><b>Feature flag.</b> {@code photocast.forecast-score.dual-write} (default {@code true}).
 * Flag off = no rows written; the rollback path for the whole pass is the flag, no redeploy.
 */
@Component
public class ForecastScoreWriter {

    private final ForecastScoreRepository forecastScoreRepository;
    private final Clock clock;
    private final boolean dualWriteEnabled;

    /**
     * Constructs the writer.
     *
     * @param forecastScoreRepository the component-row repository (V108)
     * @param clock                   injectable clock for {@code evaluated_at = now()}
     * @param dualWriteEnabled        {@code photocast.forecast-score.dual-write} (default true);
     *                                when false the writer is a no-op, the whole-pass rollback
     */
    public ForecastScoreWriter(ForecastScoreRepository forecastScoreRepository,
            Clock clock,
            @Value("${photocast.forecast-score.dual-write:true}") boolean dualWriteEnabled) {
        this.forecastScoreRepository = forecastScoreRepository;
        this.clock = clock;
        this.dualWriteEnabled = dualWriteEnabled;
    }

    /**
     * Dual-writes the component rows for one scored evaluation. No-op when the flag is off or the
     * event is {@code HOURLY} (wildlife comfort, never colour-evaluated — defensive, such tasks
     * do not reach this seam). Throws on a persistence failure so the caller can log and isolate;
     * the {@link Propagation#REQUIRES_NEW} boundary guarantees the rollback is confined here.
     *
     * @param location      the evaluated location (must have an id)
     * @param date          the forecast date
     * @param eventType     SUNRISE or SUNSET
     * @param eval          the parsed Claude evaluation (source of the 0–100 potentials)
     * @param components    the combiner's exposed component scores (SKY and, if applicable, TIDAL)
     * @param pipelineRunId the orchestrated cycle that produced this evaluation, or {@code null}
     *                      on the sync/admin path
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(LocationEntity location, LocalDate date, TargetType eventType,
            SunsetEvaluation eval, List<ComponentScore> components, Long pipelineRunId) {
        if (!dualWriteEnabled) {
            return;
        }
        if (eventType == TargetType.HOURLY) {
            // Wildlife comfort runs are never colour-evaluated and should not reach this seam.
            return;
        }
        Instant now = Instant.now(clock);

        for (ComponentScore component : components) {
            upsert(component.type(), component.score(), component.summary(),
                    location, date, eventType, pipelineRunId, now);
        }
        upsert(ForecastType.FIERY_SKY, eval.fierySkyPotential(), null,
                location, date, eventType, pipelineRunId, now);
        upsert(ForecastType.GOLDEN_HOUR, eval.goldenHourPotential(), null,
                location, date, eventType, pipelineRunId, now);
    }

    /**
     * Dual-writes the component rows for a bluebell-prompt evaluation, which has no sky sub-scores.
     *
     * <p>Unlike {@link #write}, this writes ONLY the supplied components (the BLUEBELL row, and any
     * applicable TIDAL row) — it does NOT write {@link ForecastType#FIERY_SKY} or
     * {@link ForecastType#GOLDEN_HOUR}, because an in-season WOODLAND bluebell site is evaluated by
     * the bluebell prompt alone (no sky call, so no 0–100 potentials exist). Same no-op flag, same
     * HOURLY guard, same {@link Propagation#REQUIRES_NEW} isolation as {@link #write}.
     *
     * @param location      the evaluated location (must have an id)
     * @param date          the forecast date
     * @param eventType     SUNRISE or SUNSET
     * @param components    the combiner's exposed component scores (BLUEBELL and, if applicable, TIDAL)
     * @param pipelineRunId the orchestrated cycle that produced this evaluation, or {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeComponents(LocationEntity location, LocalDate date, TargetType eventType,
            List<ComponentScore> components, Long pipelineRunId) {
        if (!dualWriteEnabled) {
            return;
        }
        if (eventType == TargetType.HOURLY) {
            return;
        }
        Instant now = Instant.now(clock);
        for (ComponentScore component : components) {
            upsert(component.type(), component.score(), component.summary(),
                    location, date, eventType, pipelineRunId, now);
        }
    }

    /**
     * Inserts or updates the single component row for the unique key, setting the latest score,
     * summary, provenance, and timestamp.
     */
    private void upsert(ForecastType type, Integer score, String summary, LocationEntity location,
            LocalDate date, TargetType eventType, Long pipelineRunId, Instant now) {
        ForecastScoreEntity row = forecastScoreRepository
                .findComponent(type, location.getId(), date, eventType)
                .orElseGet(ForecastScoreEntity::new);
        row.setForecastType(type);
        row.setLocation(location);
        row.setEvaluationDate(date);
        row.setEventType(eventType);
        row.setScore(score);
        row.setSummary(summary);
        row.setPipelineRunId(pipelineRunId);
        row.setEvaluatedAt(now);
        forecastScoreRepository.save(row);
    }
}
