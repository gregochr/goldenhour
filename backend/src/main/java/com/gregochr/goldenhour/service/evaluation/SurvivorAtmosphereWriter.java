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
 * Writer for atmospheric readings ({@code survivor_atmosphere}, V115) — the readings half of the
 * unified hot-topic read surface, counterpart to {@link ForecastScoreWriter} (the scores half).
 *
 * <p>⚠️ <b>The class and table names are historical and now inaccurate — a rename is pending, not
 * done here.</b> Through 2026-09-30 a row was written only for a candidate that survived weather
 * triage and the Gate 4 stability gate ("survivor-by-construction"), which is exactly the product
 * rule the "record conditions for every place" change (Phase 1, owner decision 2026-09-30) reversed:
 * knowing there is dust, snow or a storm surge at a place is interesting on its own terms (the "what
 * is happening" question hot topics and the Coming up feed answer), independent of whether the same
 * place is forecast to be blocked (the separate "where is worth going" question stars and verdicts
 * answer). {@link #write} is now called for every candidate a batch cycle, a hand-started admin run,
 * or the synchronous engine fetched weather for — triaged-out and Gate-4-stood-down candidates
 * included — so "survivor" no longer describes this table's population. See
 * {@code ForecastTaskCollector}'s call site for exactly which dispositions now carry a row and which
 * still do not (a candidate that never had its weather fetched this cycle — past date, unknown
 * location, travel day, a collection-time error — still writes nothing, because there is no reading
 * to record). Renaming the table and every class named after it needs its own migration and is
 * deliberately out of scope for this change — see the changelog entry for the file list a rename
 * would touch.
 *
 * <p><b>Why submission time, not result time.</b> The atmospheric readings (aerosol, surge,
 * snow, humidity) live on the {@link AtmosphericData} computed at collection/submission and are
 * rendered into the Claude prompt text — they are NOT carried across the async Anthropic batch
 * boundary, so by the time the eval returns they are gone (only the score-shaped signals survive,
 * via {@link ForecastScoreWriter}). This writer therefore captures them where they still exist:
 * when a candidate's weather is fetched, before any triage verdict or Gate 4 decision is applied.
 *
 * <p><b>Upsert.</b> Rows are UPSERTed against {@code (location_id, evaluation_date, event_type)} —
 * latest submission wins, so intraday re-runs and sync re-evaluations overwrite the same key,
 * matching {@code forecast_score} / {@code cached_evaluation} semantics. A later triage or
 * stability-skip decision for the same key does not retract an already-written row — this surface
 * carries no retraction of any kind, matching {@link com.gregochr.goldenhour.service.SurvivorSignalReader}'s
 * own rule.
 *
 * <p><b>Failure isolation.</b> Runs in its own {@link Propagation#REQUIRES_NEW} transaction so a
 * write failure rolls back only this write — never the caller's submission/evaluation. Callers
 * additionally wrap the call so a thrown exception is logged and the pipeline proceeds.
 *
 * <p><b>Feature flag.</b> {@code photocast.survivor-atmosphere.write} (default {@code true}).
 * Flag off = no rows written; the additive-table rollback path, no redeploy. There is deliberately
 * no separate flag gating "every candidate" vs "survivors only" — owner decision 2026-09-30 is that
 * every place is recorded from the first deploy, with no staged rollout.
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
     * Upserts the candidate's atmospheric readings for {@code (location, date, eventType)}. No-op
     * when the flag is off or the event is {@code HOURLY} (wildlife comfort, never colour-evaluated).
     *
     * <p>Called for every candidate whose weather was fetched this cycle, whatever the triage
     * verdict or Gate 4 stability decision that follows — see the class javadoc's "record
     * conditions for every place" note. The name "the survivor's readings" is historical: this is
     * no longer restricted to candidates that survived triage and gating.
     *
     * @param location  the candidate location (must have an id)
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
        if (data.surge() != null) {
            row.setSurgeRiskLevel(data.surge().riskLevel().name());
            row.setSurgeTotalMetres(data.surge().totalSurgeMetres());
            row.setSurgeWindSpeedMs(data.surge().windSpeedMs());
            row.setSurgeWindDirectionDegrees(data.surge().windDirectionDegrees());
        } else {
            row.setSurgeRiskLevel(null);
            row.setSurgeTotalMetres(null);
            row.setSurgeWindSpeedMs(null);
            row.setSurgeWindDirectionDegrees(null);
        }
        if (data.weather() != null) {
            row.setSnowDepthMetres(data.weather().snowDepthMetres());
            row.setFreezingLevelMetres(data.weather().freezingLevelMetres());
            row.setHumidity(data.weather().humidityPercent());
        }
        if (data.comfort() != null) {
            // 2 m air temperature lives on comfort, not weather; gates the freezing-fog / hoar-frost
            // SNOW_MIST facts (sub-zero mist over lying snow).
            row.setTemperatureCelsius(data.comfort().temperatureCelsius());
        }
        row.setEvaluatedAt(Instant.now(clock));
        repository.save(row);
    }
}
