package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Writer for atmospheric readings ({@code slot_atmosphere}, V115) — the readings half of the
 * unified hot-topic read surface, counterpart to {@link ForecastScoreWriter} (the scores half).
 *
 * <p>⚠️ <b>This class and table were named {@code SurvivorAtmosphereWriter}/{@code
 * survivor_atmosphere} until V159 (2026-09-30).</b> Through 2026-09-29 a row was written only for a
 * candidate that survived weather triage and the Gate 4 stability gate ("survivor-by-construction"),
 * which is exactly the product rule the "record conditions for every place" change (Phase 1, owner
 * decision 2026-09-30) reversed: knowing there is dust, snow or a storm surge at a place is
 * interesting on its own terms (the "what is happening" question hot topics and the Coming up feed
 * answer), independent of whether the same place is forecast to be blocked (the separate "where is
 * worth going" question stars and verdicts answer). So "survivor" no longer described this table's
 * population, which is why V159 renamed it — see that migration's header comment.
 *
 * <p>⚠️ <b>{@link #write} is called from exactly ONE place: inside
 * {@link com.gregochr.goldenhour.service.ForecastService#fetchWeatherAndTriage
 * ForecastService.fetchWeatherAndTriage} itself</b> — immediately after the atmospheric data is
 * assembled and before that method's own triage checks. The first cut of this phase (commit
 * 9c01491f) instead called this writer from three separate call sites (the batch collector's
 * scheduled path, and both of {@code ForceSubmitBatchService}'s entry points); a Codex review
 * found two OTHER real callers of {@code fetchWeatherAndTriage} — the batch collector's own
 * {@code collectRegionFilteredBatches} and the synchronous engine's
 * {@code ForecastCommandExecutor.runTriagePhase} — reached it and got no write at all. Moving the
 * call inside {@code fetchWeatherAndTriage} covers every present and future caller with one seam:
 * the scheduled batch collector, its admin region-filtered sibling, {@code ForceSubmitBatchService}
 * (JFDI and admin force-submit), {@code BatchRetryService}'s failed-request reconstruction, and the
 * synchronous engine, all in one place. See that method's own javadoc for exactly which
 * dispositions now carry a row and which still do not (a candidate that never had its weather
 * fetched this cycle — past date, unknown location, travel day, a collection-time error — still
 * writes nothing, because there is no reading to record). This class, {@link SlotAtmosphereRepository}
 * and {@link com.gregochr.goldenhour.entity.SlotAtmosphereEntity} were renamed from their
 * {@code Survivor*} originals in V159 (2026-09-30) — a mechanical rename, no behaviour change; see
 * that migration's header comment and the changelog entry for the full file list.
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
 * carries no retraction of any kind, matching {@link com.gregochr.goldenhour.service.SlotSignalReader}'s
 * own rule.
 *
 * <p><b>Failure isolation.</b> Runs in its own {@link Propagation#REQUIRES_NEW} transaction so a
 * write failure rolls back only this write — never the fetch/triage in progress. The one call site
 * additionally wraps the call so a thrown exception is logged and {@code fetchWeatherAndTriage}
 * proceeds to its triage checks regardless.
 *
 * <p><b>Feature flag.</b> {@code photocast.slot-atmosphere.write} (default {@code true}).
 * Flag off = no rows written; the additive-table rollback path, no redeploy. There is deliberately
 * no separate flag gating "every candidate" vs "survivors only" — owner decision 2026-09-30 is that
 * every place is recorded from the first deploy, with no staged rollout. ⚠️ <b>The key itself was
 * renamed from {@code photocast.survivor-atmosphere.write} in V159 (2026-09-30).</b> For the rest
 * of that day the old key was also read as a legacy alias, winning when a deployment's config still
 * set it; production carries no config file or environment variable for either key, so that alias
 * is dropped from this change on — {@code photocast.slot-atmosphere.write} is read alone.
 */
@Component
public class SlotAtmosphereWriter {

    private final SlotAtmosphereRepository repository;
    private final Clock clock;
    private final boolean writeEnabled;

    /**
     * Constructs the writer.
     *
     * @param repository   the slot-atmosphere repository (V115)
     * @param clock        injectable clock for {@code evaluated_at = now()}
     * @param writeEnabled {@code photocast.slot-atmosphere.write} (default true); when false the
     *                     writer is a no-op, the additive-table rollback
     */
    public SlotAtmosphereWriter(SlotAtmosphereRepository repository, Clock clock,
            @Value("${photocast.slot-atmosphere.write:true}") boolean writeEnabled) {
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
     * conditions for every place" note. This has not been restricted to candidates that survived
     * triage and gating since Phase 1 (2026-09-30); the table and this class carried the old
     * {@code Survivor*} name for the rest of that day, until V159 renamed both.
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

        SlotAtmosphereEntity row = repository
                .findByLocationIdAndEvaluationDateAndEventType(location.getId(), date, eventType)
                .orElseGet(SlotAtmosphereEntity::new);
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
        // Cloud inversion likelihood (V158, Phase 2 of "record conditions for every place"):
        // ForecastDataAugmentor.augmentWithInversionScore already ran InversionScoreCalculator for
        // every inversion-eligible candidate before this write, so data.inversionScore() is already
        // populated here exactly like every other reading on this row — null for an ineligible
        // location OR for an eligible one the calculator itself could not score (missing weather
        // inputs), whatever the triage verdict or Gate 4 decision that follows turns out to be.
        // inversionScored is set true unconditionally, WITH a score and WITH a null one alike: this
        // write ran the eligibility check this cycle, so a null score here is an authoritative
        // answer, not an absent one — see SlotAtmosphereEntity.inversionScored's own javadoc for
        // why a reader must not treat this null the same as a pre-column row's null (round 4, a
        // Codex P1 against round 3's own unify-onto-one-rule fix).
        row.setInversionScore(data.inversionScore());
        row.setInversionScored(true);
        row.setEvaluatedAt(Instant.now(clock));
        repository.save(row);
    }
}
