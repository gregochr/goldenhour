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
     * @param from inclusive start date
     * @param to   inclusive end date
     * @return one composite per survivor {@code (location, date, event_type)} in the window
     */
    public List<SurvivorSignals> read(LocalDate from, LocalDate to) {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();

        for (ForecastScoreEntity s : forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), from, to)) {
            accumulatorFor(byKey, s.getLocation(), s.getEvaluationDate(), s.getEventType())
                    .inversion = s.getScore();
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
        private Integer bluebell;
        private String bluebellSummary;
        private SurvivorAtmosphereEntity readings;

        private Accumulator(LocationEntity location, LocalDate date, TargetType eventType) {
            this.location = location;
            this.date = date;
            this.eventType = eventType;
        }

        private SurvivorSignals build() {
            SurvivorSignals.Scores scores =
                    (inversion == null && bluebell == null && bluebellSummary == null)
                            ? SurvivorSignals.Scores.EMPTY
                            : new SurvivorSignals.Scores(inversion, bluebell, bluebellSummary);
            SurvivorSignals.Readings r = readings == null
                    ? SurvivorSignals.Readings.EMPTY
                    : new SurvivorSignals.Readings(
                            readings.getAerosolOpticalDepth(), readings.getDust(),
                            readings.getPm25(), readings.getSurgeRiskLevel(),
                            readings.getSnowDepthMetres(), readings.getFreezingLevelMetres(),
                            readings.getHumidity());
            return new SurvivorSignals(location, date, eventType, scores, r);
        }
    }
}
