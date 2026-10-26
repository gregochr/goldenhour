package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.model.CalibrationBucket;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.CalibrationReport;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
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
 * Scores forecasts against the ratings readers gave the sky they saw.
 *
 * <p>This is the project's only non-self-referential accuracy measure. The prompt-regression
 * tests compare Claude to hand-written expectations, the sky-rating eval harness compares band
 * assignments to fixtures, and the model-comparison harness compares models to each other — all
 * three can stay green while the forecast drifts away from reality. This service closes that gap
 * by joining {@code forecast_evaluation} to readers' per-user
 * {@code outcome_rating} records, and reporting error by forecast horizon, so a change to the
 * scoring rules can be justified against observation.
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

    /** Bucket key of the mist-prone bucket: pairs whose forecast carried the LIKELY mist band. */
    static final String MIST_PRONE_KEY = "MIST_LIKELY";

    /** {@code seen} count key for a rating given with no tag. */
    static final String UNTAGGED_KEY = "UNTAGGED";

    /** Bucket key used when a forecast row carried no recorded build or prompt fingerprint. */
    private static final String UNKNOWN_KEY = "UNKNOWN";

    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final OutcomeRatingRepository outcomeRatingRepository;

    /**
     * Creates the calibration service.
     *
     * @param forecastEvaluationRepository repository supplying forecast/rating pairs
     * @param outcomeRatingRepository      repository used to report rating coverage
     */
    public ForecastCalibrationService(ForecastEvaluationRepository forecastEvaluationRepository,
            OutcomeRatingRepository outcomeRatingRepository) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.outcomeRatingRepository = outcomeRatingRepository;
    }

    /**
     * Builds the calibration report for a window of recorded outcomes.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (inclusive)
     * @return accuracy overall, per forecast horizon, per model, per release and per prompt
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

        List<CalibrationPair> raw = new ArrayList<>(
                forecastEvaluationRepository.findCalibrationPairsFromRatings(from, to));
        List<CalibrationPair> pairs = latestPerSlotHorizon(raw);
        // Every rating in the window, "didn't see it" ratings included.
        int outcomesInRange = Math.toIntExact(outcomeRatingRepository.countByEventDateBetween(from, to));

        List<CalibrationPair> mistPairs = pairs.stream().filter(CalibrationPair::mistLikely).toList();

        CalibrationReport report = new CalibrationReport(
                from,
                to,
                outcomesInRange,
                pairs.size(),
                CalibrationBucket.of(OVERALL_KEY, pairs),
                bucketsByDaysAhead(pairs),
                bucketsByModel(pairs),
                bucketsByKey(pairs, CalibrationPair::appBuild),
                bucketsByKey(pairs, CalibrationPair::promptHash),
                mistPairs.isEmpty() ? null : CalibrationBucket.of(MIST_PRONE_KEY, mistPairs),
                seenCounts(mistPairs));

        LOG.info("[CALIBRATION] window={}..{} outcomes={} scoredPairs={} "
                + "meanAbsError={} missedOpportunities={} wastedTrips={} mistPronePairs={} "
                + "mistProneMissedOpportunities={}",
                from, to, outcomesInRange, pairs.size(),
                report.overall().meanAbsoluteRatingError(),
                report.overall().missedOpportunities(),
                report.overall().wastedTrips(),
                mistPairs.size(),
                report.mistProne() == null ? 0 : report.mistProne().missedOpportunities());
        return report;
    }

    /**
     * Keeps only the newest evaluation run within each (slot, horizon) group.
     *
     * <p>A slot at a given horizon can be evaluated more than once — the nightly batch and the
     * intraday refresh both write rows — and counting both would weight that slot twice. The key
     * includes the rating's identity, so several readers' ratings of one slot each stay a pair;
     * only runs of one forecast collapse.
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
     * Tallies what readers said they saw across a set of pairs: tag name to count, {@code UNTAGGED}
     * for a pair with no tag. Insertion order is the enum's, then {@code UNTAGGED}, so reports diff
     * cleanly; a tag nobody gave is absent rather than zero.
     *
     * @param pairs the pairs to tally
     * @return tag name to number of pairs, empty for no pairs
     */
    private Map<String, Integer> seenCounts(List<CalibrationPair> pairs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SeenTag tag : SeenTag.values()) {
            long n = pairs.stream().filter(pair -> pair.seen() == tag).count();
            if (n > 0) {
                counts.put(tag.name(), Math.toIntExact(n));
            }
        }
        long untagged = pairs.stream().filter(pair -> pair.seen() == null).count();
        if (untagged > 0) {
            counts.put(UNTAGGED_KEY, Math.toIntExact(untagged));
        }
        return counts;
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

    /**
     * Groups pairs by a recorded string of the forecast row — its build or its prompt fingerprint.
     * A pair whose row recorded none lands in {@code UNKNOWN}, so history from before the column
     * existed stays visible as its own bucket rather than vanishing from the report.
     *
     * @param pairs the scored pairs
     * @param key   reads the grouping value off a pair
     * @return one bucket per distinct value present, ordered by value
     */
    private List<CalibrationBucket> bucketsByKey(List<CalibrationPair> pairs,
            java.util.function.Function<CalibrationPair, String> key) {
        return pairs.stream()
                .collect(Collectors.groupingBy(pair -> {
                    String value = key.apply(pair);
                    return value == null || value.isBlank() ? UNKNOWN_KEY : value;
                }))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> CalibrationBucket.of(entry.getKey(), entry.getValue()))
                .toList();
    }
}
