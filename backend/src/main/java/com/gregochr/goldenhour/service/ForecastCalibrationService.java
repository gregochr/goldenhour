package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.CalibrationBucket;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.CalibrationReport;
import com.gregochr.goldenhour.repository.ActualOutcomeRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scores forecasts against the outcomes photographers actually recorded.
 *
 * <p>This is the project's only non-self-referential accuracy measure. The prompt-regression
 * tests compare Claude to hand-written expectations, the sky-rating eval harness compares band
 * assignments to fixtures, and the model-comparison harness compares models to each other — all
 * three can stay green while the forecast drifts away from reality. This service closes that gap
 * by joining {@code forecast_evaluation} to {@code actual_outcome} and reporting error by forecast
 * horizon, so a change to the scoring rules can be justified against observation.
 *
 * <p>Intended use is a fixed window measured before and after a change. The aggregation is
 * deterministic for a given window, so the two reports diff directly.
 */
@Service
public class ForecastCalibrationService {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastCalibrationService.class);

    /** Bucket key for the aggregate across every pair. */
    private static final String OVERALL_KEY = "ALL";

    /** Bucket key used when an evaluation carried no model. */
    private static final String UNKNOWN_MODEL_KEY = "UNKNOWN";

    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final ActualOutcomeRepository actualOutcomeRepository;

    /**
     * Creates the calibration service.
     *
     * @param forecastEvaluationRepository repository supplying forecast/outcome pairs
     * @param actualOutcomeRepository      repository used to report outcome coverage
     */
    public ForecastCalibrationService(ForecastEvaluationRepository forecastEvaluationRepository,
            ActualOutcomeRepository actualOutcomeRepository) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.actualOutcomeRepository = actualOutcomeRepository;
    }

    /**
     * Builds the calibration report for a window of recorded outcomes.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (inclusive)
     * @return accuracy overall, per forecast horizon, and per model
     * @throws IllegalArgumentException if the window is null or inverted
     */
    @Transactional(readOnly = true)
    public CalibrationReport report(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }

        List<CalibrationPair> pairs = latestPerSlotHorizon(
                forecastEvaluationRepository.findCalibrationPairs(from, to));
        int outcomesInRange = actualOutcomeRepository.findAllByOutcomeDateBetween(from, to).size();

        CalibrationReport report = new CalibrationReport(
                from,
                to,
                outcomesInRange,
                pairs.size(),
                CalibrationBucket.of(OVERALL_KEY, pairs),
                bucketsByDaysAhead(pairs),
                bucketsByModel(pairs));

        LOG.info("[CALIBRATION] window={}..{} outcomes={} scoredPairs={} "
                + "meanAbsError={} missedOpportunities={} wastedTrips={}",
                from, to, outcomesInRange, pairs.size(),
                report.overall().meanAbsoluteRatingError(),
                report.overall().missedOpportunities(),
                report.overall().wastedTrips());
        return report;
    }

    /**
     * Keeps only the newest evaluation run within each (slot, horizon) group.
     *
     * <p>A slot at a given horizon can be evaluated more than once — the nightly batch and the
     * intraday refresh both write rows — and counting both would weight that slot twice.
     *
     * @param pairs the raw joined pairs
     * @return one pair per slot and horizon, newest run winning
     */
    private List<CalibrationPair> latestPerSlotHorizon(List<CalibrationPair> pairs) {
        Map<String, CalibrationPair> newest = new LinkedHashMap<>();
        for (CalibrationPair pair : pairs) {
            newest.merge(pair.slotHorizonKey(), pair,
                    (existing, candidate) -> isNewer(candidate, existing) ? candidate : existing);
        }
        return new ArrayList<>(newest.values());
    }

    /**
     * Returns whether the candidate ran later than the incumbent, treating a null run time as
     * older so a timestamped row always wins.
     *
     * @param candidate the challenger
     * @param existing  the incumbent
     * @return true if the candidate should replace the incumbent
     */
    private boolean isNewer(CalibrationPair candidate, CalibrationPair existing) {
        if (candidate.forecastRunAt() == null) {
            return false;
        }
        return existing.forecastRunAt() == null
                || candidate.forecastRunAt().isAfter(existing.forecastRunAt());
    }

    /**
     * Groups pairs by forecast horizon, ordered T+0 upward.
     *
     * @param pairs the scored pairs
     * @return one bucket per horizon present in the data
     */
    private List<CalibrationBucket> bucketsByDaysAhead(List<CalibrationPair> pairs) {
        return pairs.stream()
                .collect(Collectors.groupingBy(
                        pair -> pair.daysAhead() == null ? Integer.MAX_VALUE : pair.daysAhead()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> CalibrationBucket.of(
                        entry.getKey() == Integer.MAX_VALUE ? "T+?" : "T+" + entry.getKey(),
                        entry.getValue()))
                .toList();
    }

    /**
     * Groups pairs by the model that produced the evaluation.
     *
     * @param pairs the scored pairs
     * @return one bucket per model present in the data, ordered by name
     */
    private List<CalibrationBucket> bucketsByModel(List<CalibrationPair> pairs) {
        return pairs.stream()
                .collect(Collectors.groupingBy(pair -> pair.evaluationModel() == null
                        ? UNKNOWN_MODEL_KEY : pair.evaluationModel().name()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> CalibrationBucket.of(entry.getKey(), entry.getValue()))
                .toList();
    }
}
