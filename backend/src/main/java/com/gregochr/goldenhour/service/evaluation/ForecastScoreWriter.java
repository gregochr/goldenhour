package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.service.evaluation.visitor.ComponentScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Pass 2 dual-write: persists the per-component scores of a single scored forecast evaluation
 * to {@code forecast_score}, alongside (never instead of) the live {@code results_json} serving
 * path.
 *
 * <p>⚠️ <b>{@code forecast_score} is read in production — this is no longer a proving surface.</b>
 * This javadoc said "nothing reads {@code forecast_score} yet" until 2026-08-27, and that sentence
 * was load-bearing: it is what justified the caller swallowing a write failure as harmless. Two
 * live readers have since appeared. {@link com.gregochr.goldenhour.model.ForecastDtoMapper} takes
 * the Claude BLUEBELL rating for the API DTO straight from this table, and
 * {@code SlotSignalReader} reads components for the hot-topic surfaces. A lost write is
 * therefore user-visible, not merely an unproven record.
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
 * is logged loudly at ERROR (with the component key) and the evaluation proceeds. Swallowing is
 * still the right call — the serving path is the live product and must not fail because a
 * secondary write did — but the consequence is no longer nil.
 *
 * <p><b>What a lost write actually costs.</b> Rows UPSERT on the component unique key with
 * latest-evaluation-wins semantics, so a lost write is repaired by <em>the next successful
 * evaluation of that same slot</em> — but nothing guarantees there will be one.
 *
 * <p>⚠️ <b>Recovery is conditional, not scheduled.</b> An earlier draft of this javadoc claimed a
 * T+3 slot "gets three further attempts as it ages to T+0", with T+0 as the sole permanent case.
 * That is wrong, and the correction came from review. Two gates can drop a later cycle's attempt:
 * {@code ForecastTaskCollector} skips any slot its weather triage stands down, which applies at
 * <em>every</em> horizon including T+0 and T+1; and {@code NightlyEligibilityPolicy} additionally
 * rejects T+2 unless SETTLED or TRANSITIONAL, and T+3 unless SETTLED.
 *
 * <p>The failure modes are also <b>correlated</b>, which is what makes this more than a
 * theoretical gap: triage stands a slot down at &gt;80% low cloud, and that weather persists — so
 * the days following a triaged slot are likely to be triaged too. A stale row can therefore
 * outlive its event. Treat reconciliation as an open trade-off on this evidence, not as something
 * already dismissed.
 *
 * <p><b>Feature flag.</b> {@code photocast.forecast-score.dual-write} (default {@code true}).
 * Flag off = no rows written; the rollback path for the whole pass is the flag, no redeploy.
 *
 * <p>⚠️ <b>Round 13, "gap 2": {@link #upsert} must not let an EARLIER pipeline run's component
 * replace a LATER run's, and until this round it always did.</b> The unconditional
 * find-or-create-then-overwrite {@link #upsert} shared the identical unordered-write weakness
 * round 12 fixed on {@code cached_evaluation} — a batch delayed past a later cycle's own
 * evaluation of the same slot could overwrite the newer component with a stale one, so
 * {@code ForecastDtoMapper}'s BLUEBELL rating and {@code SlotSignalReader}'s hot-topic
 * components could disagree with the cache the Plan card and map already serve for the identical
 * cycle. The fix compares the stored row's {@code pipelineRunId} against the incoming one: since
 * {@link com.gregochr.goldenhour.entity.PipelineRunEntity#getId()} is an autoincrement primary key
 * assigned in strict cycle-trigger order, comparing the two ids IS comparing the runs' trigger
 * times — no join to {@code pipeline_run} is needed here the way {@code BriefingEvaluationService}
 * needs one for its own disposition-based check, because this table already stores the producing
 * run's id directly. An incoming id strictly LESS than the stored one is rejected (logged once at
 * INFO, nothing written); equal ids overwrite as before (the same evaluation re-landing, or two
 * components of the one run); and a {@code null} on EITHER side — a legacy row from before this
 * column existed, or an incoming sync/admin write, which the class javadoc above already documents
 * as always carrying {@code null} — is treated as "unknown, cannot be shown to be older", so the
 * incoming write proceeds exactly as it always has. This mirrors, rather than reuses,
 * {@code BriefingEvaluationService}'s round-12/13 staleness rules: same "unknown is safe"
 * philosophy and the same ordering question, but a different comparison key, because this table's
 * own {@code pipelineRunId} column makes an ordinal comparison sufficient where the cache's
 * JSON-only {@code submittedAt} instant needed a full disposition join.
 */
@Component
public class ForecastScoreWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastScoreWriter.class);

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
        // Cloud inversion (V114): a standalone 0–10 likelihood, written only when this scored
        // evaluation carried one — i.e. an inversion-eligible location for which Claude returned a
        // score. Ineligible locations have no inversion in the eval, so the null guard keeps the
        // table free of spurious NONE rows. The classification (NONE/MODERATE/STRONG) rides the
        // summary so the inversion hot topic's band needs no re-derivation. Fires from BOTH the
        // batch survivor path (so the detector reads survivors) and the sync/admin path.
        if (eval.inversionScore() != null) {
            upsert(ForecastType.INVERSION, eval.inversionScore(), eval.inversionPotential(),
                    location, date, eventType, pipelineRunId, now);
        }
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
     * summary, provenance, and timestamp — unless the incoming write is from a strictly EARLIER
     * pipeline run than the one already stored (round 13, "gap 2" — see the class javadoc), in
     * which case the stored row is left untouched and the write is dropped.
     */
    private void upsert(ForecastType type, Integer score, String summary, LocationEntity location,
            LocalDate date, TargetType eventType, Long pipelineRunId, Instant now) {
        Optional<ForecastScoreEntity> existing = forecastScoreRepository
                .findComponent(type, location.getId(), date, eventType);
        if (isSupersededByLaterRun(existing, pipelineRunId)) {
            LOG.info("[STALE FORECAST_SCORE] Rejected incoming {} component for location='{}' "
                            + "date={} event={}: incoming pipelineRunId={} is older than the "
                            + "stored row's pipelineRunId={} — keeping the stored component",
                    type, location.getName(), date, eventType, pipelineRunId,
                    existing.get().getPipelineRunId());
            return;
        }
        ForecastScoreEntity row = existing.orElseGet(ForecastScoreEntity::new);
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

    /**
     * Whether the incoming write is from a strictly earlier pipeline run than the one already
     * stored for this component. {@code false} whenever either id is unknown (no stored row, a
     * legacy stored row with no {@code pipelineRunId}, or an incoming sync/admin write) — "unknown"
     * can never be shown to be older, so those cases proceed exactly as they always have.
     */
    private static boolean isSupersededByLaterRun(
            Optional<ForecastScoreEntity> existing, Long incomingPipelineRunId) {
        if (existing.isEmpty() || incomingPipelineRunId == null) {
            return false;
        }
        Long storedPipelineRunId = existing.get().getPipelineRunId();
        return storedPipelineRunId != null && incomingPipelineRunId < storedPipelineRunId;
    }
}
