package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Submission-time writer for survivor atmospheric readings ({@code survivor_atmosphere}, V115) —
 * the readings half of the unified survivor read surface, counterpart to
 * {@link ForecastScoreWriter} (the scores half).
 *
 * <p><b>Why submission time, not result time.</b> The atmospheric readings (aerosol, surge,
 * snow, humidity) live on the {@link AtmosphericData} computed at collection/submission and are
 * rendered into the Claude prompt text — they are NOT carried across the async Anthropic batch
 * boundary, so by the time the eval returns they are gone (only the score-shaped signals survive,
 * via {@link ForecastScoreWriter}). This writer therefore captures them where they still exist:
 * when a survivor is submitted (batch) or evaluated (sync). Survivor-by-construction — a row is
 * written only for a candidate that survived triage and gating, so detectors reading the carrier
 * cannot sample the triaged rejects.
 *
 * <p><b>Upsert.</b> Rows are UPSERTed against {@code (location_id, evaluation_date, event_type)} —
 * latest submission wins, so intraday re-runs and sync re-evaluations overwrite the same key,
 * matching {@code forecast_score} / {@code cached_evaluation} semantics.
 *
 * <p><b>Failure isolation.</b> Runs in its own {@link Propagation#REQUIRES_NEW} transaction so a
 * write failure rolls back only this write — never the caller's submission/evaluation. Callers
 * additionally wrap the call so a thrown exception is logged and the pipeline proceeds.
 *
 * <p><b>Feature flag.</b> {@code photocast.survivor-atmosphere.write} (default {@code true}).
 * Flag off = no rows written; the additive-table rollback path, no redeploy.
 */
@Component
public class SurvivorAtmosphereWriter {

    private final SurvivorAtmosphereRepository repository;
    private final Clock clock;
    private final boolean writeEnabled;

    /**
     * Constructs the writer.
     *
     * @param repository   the survivor-atmosphere repository (V115)
     * @param clock        injectable clock for {@code evaluated_at = now()}
     * @param writeEnabled {@code photocast.survivor-atmosphere.write} (default true); when false
     *                     the writer is a no-op, the additive-table rollback
     */
    public SurvivorAtmosphereWriter(SurvivorAtmosphereRepository repository, Clock clock,
            @Value("${photocast.survivor-atmosphere.write:true}") boolean writeEnabled) {
        this.repository = repository;
        this.clock = clock;
        this.writeEnabled = writeEnabled;
    }

    /**
     * Upserts the survivor's atmospheric readings for {@code (location, date, eventType)}. No-op
     * when the flag is off or the event is {@code HOURLY} (wildlife comfort, never colour-evaluated).
     *
     * @param location  the survivor location (must have an id)
     * @param date      the forecast date
     * @param eventType SUNRISE or SUNSET
     * @param data      the atmospheric snapshot to capture
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(LocationEntity location, LocalDate date, TargetType eventType,
            AtmosphericData data) {
        if (!writeEnabled) {
            return;
        }
        if (eventType == TargetType.HOURLY) {
            return;
        }
        if (data == null) {
            return;
        }

        SurvivorAtmosphereEntity row = repository
                .findByLocationIdAndEvaluationDateAndEventType(location.getId(), date, eventType)
                .orElseGet(SurvivorAtmosphereEntity::new);
        row.setLocation(location);
        row.setEvaluationDate(date);
        row.setEventType(eventType);

        if (data.aerosol() != null) {
            row.setAerosolOpticalDepth(data.aerosol().aerosolOpticalDepth());
            row.setDust(data.aerosol().dustUgm3());
            row.setPm25(data.aerosol().pm25());
        }
        row.setSurgeRiskLevel(data.surge() != null ? data.surge().riskLevel().name() : null);
        if (data.weather() != null) {
            row.setSnowDepthMetres(data.weather().snowDepthMetres());
            row.setFreezingLevelMetres(data.weather().freezingLevelMetres());
            row.setHumidity(data.weather().humidityPercent());
        }
        row.setEvaluatedAt(Instant.now(clock));
        repository.save(row);
    }
}
