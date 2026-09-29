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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>⚠️ <b>The owner's two-question rule (2026-09-29) — hot topics answer a different question from
 * a rating, and a stability skip or a triage stand-down must never silence the first.</b> "What is
 * happening?" is answered by hot topics and the Coming up panel. "Where is worth going?" is answered
 * by stars, verdicts and picks — {@code cached_evaluation}, {@code forecast_evaluation},
 * {@code GET /api/briefing}, {@code GET /api/briefing/evaluate/scores} and the map's forecast rows.
 * A nightly Gate 4 stability skip or a weather-triage stand-down is a decision against the SECOND
 * question, so it retracts a rating (still true, unchanged, in {@code EvaluationViewService}) but has
 * nothing to say about the first: knowing there is snow, dust or a likely inversion is interesting on
 * its own terms, independent of whether the pipeline currently judges anywhere worth the drive to
 * photograph it. {@link #read} therefore applies NO retraction of any kind — it returns every
 * {@code forecast_score} component (INVERSION, BLUEBELL) and every {@code survivor_atmosphere}
 * reading in the window exactly as stored, whatever the pipeline has since decided about the rating
 * for the same slot.
 *
 * <p>This reverses part of a fix (#940, commit c6e14cc8) that briefly made this class drop an
 * INVERSION or BLUEBELL component older than its slot's latest nightly stability skip, on the theory
 * that a {@code forecast_score} component is "evidence exactly like a rating". The owner's decision
 * is that this was the wrong analogy for the hot-topic/Coming-up surface: those panels report
 * conditions, not verdicts, so a component being superseded on the rating side is not a reason to
 * silence it here. See {@code changelog.d/20260929-hot-topics-report-conditions.md} and
 * {@code EvaluationViewService}'s own "Where a rating lives" javadoc for the (unchanged) rating-side
 * rule this class deliberately does not apply.
 *
 * <p>⚠️ <b>A consequence, stated so it is not later filed as an inconsistency.</b> The bluebell hot
 * topic ({@code BluebellHotTopicStrategy}) reads the identical BLUEBELL component
 * {@link com.gregochr.goldenhour.model.ForecastDtoMapper} serves as the API DTO's bluebell RATING —
 * but {@code ForecastDtoMapper} answers the second question (is this place worth going to) and keeps
 * its own stability-skip retraction unchanged. So after a stability skip, a slot's bluebell hot-topic
 * chip can keep showing while the same slot's DTO bluebell rating reads null. That is not a bug: the
 * chip says bluebells are (or were) out, the rating says whether the pipeline currently judges that
 * place worth the drive — two different questions, deliberately answered from the same underlying
 * component by two different rules.
 */
@Service
public class SurvivorSignalReader {

    private final ForecastScoreRepository forecastScoreRepository;
    private final SurvivorAtmosphereRepository survivorAtmosphereRepository;

    /**
     * Constructs the reader.
     *
     * @param forecastScoreRepository      the scores half ({@code forecast_score})
     * @param survivorAtmosphereRepository the readings half ({@code survivor_atmosphere})
     */
    public SurvivorSignalReader(ForecastScoreRepository forecastScoreRepository,
            SurvivorAtmosphereRepository survivorAtmosphereRepository) {
        this.forecastScoreRepository = forecastScoreRepository;
        this.survivorAtmosphereRepository = survivorAtmosphereRepository;
    }

    /**
     * Returns the survivor-signal composites for every survivor key in the window. A composite is
     * present for any key that has at least one score or reading; absent signals are left null in
     * their sub-record. The list is in no guaranteed order — detectors group/sort as they need.
     *
     * <p>No retraction of any kind is applied here — see the class javadoc. Every INVERSION and
     * BLUEBELL {@code forecast_score} row and every {@code survivor_atmosphere} reading in the
     * window is returned exactly as stored, whatever a later nightly stability skip or triage
     * stand-down has since decided about the RATING for the same slot.
     *
     * @param from inclusive start date
     * @param to   inclusive end date
     * @return one composite per survivor {@code (location, date, event_type)} in the window
     */
    public List<SurvivorSignals> read(LocalDate from, LocalDate to) {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();

        for (ForecastScoreEntity s : forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), from, to)) {
            Accumulator acc = accumulatorFor(
                    byKey, s.getLocation(), s.getEvaluationDate(), s.getEventType());
            acc.inversion = s.getScore();
            // The INVERSION row's summary is its NONE/MODERATE/STRONG classification, written by
            // ForecastScoreWriter — so the detector can label the band instead of assuming one.
            acc.inversionBand = s.getSummary();
        }
        for (ForecastScoreEntity s : forecastScoreRepository.findComponentsByType(
                ForecastType.BLUEBELL.getId(), from, to)) {
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
