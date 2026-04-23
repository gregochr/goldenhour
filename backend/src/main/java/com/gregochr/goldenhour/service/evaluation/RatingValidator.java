package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Defensive guardrail for Claude-emitted numeric fields.
 *
 * <p>Even when the prompt and JSON schema both bound a field, older cached
 * responses or schema-non-compliant models can slip out-of-range values
 * ({@code 491}, {@code -3}) into persistence and onward into UI rollups.
 * This class is the single point that decides whether a raw integer is
 * acceptable, logging {@code [RATING GUARDRAIL]} at WARN when it is not.
 *
 * <p>Out-of-range values are <em>rejected</em> (returned as {@code null})
 * rather than clamped to the nearest valid value — clamping an erroneous
 * {@code 491} to {@code 5} would hide the upstream bug and let silently
 * corrupt averages leak into the UI. {@code null} propagates safely through
 * the rollup path (counted as &ldquo;unscored&rdquo;).
 */
public final class RatingValidator {

    private static final Logger LOG = LoggerFactory.getLogger(RatingValidator.class);

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    private RatingValidator() {
    }

    /**
     * Validates a Claude 1-5 star rating.
     *
     * @param raw          rating as parsed from Claude; may be {@code null}
     * @param regionName   region display name (for WARN context); may be {@code null}
     * @param date         forecast date (for WARN context); may be {@code null}
     * @param targetType   sunrise/sunset (for WARN context); may be {@code null}
     * @param locationName location display name (for WARN context); may be {@code null}
     * @param model        model id used for the evaluation (for WARN context); may be {@code null}
     * @return the rating if {@code null} or in {@code [1, 5]}; otherwise {@code null}
     */
    public static Integer validateRating(Integer raw, String regionName,
            LocalDate date, TargetType targetType, String locationName, String model) {
        if (raw == null) {
            return null;
        }
        if (raw >= MIN_RATING && raw <= MAX_RATING) {
            return raw;
        }
        LOG.warn("[RATING GUARDRAIL] Out-of-range rating rejected: "
                + "region={}, date={}, event={}, location={}, model={}, rating={}",
                regionName, date, targetType, locationName, model, raw);
        return null;
    }

    /**
     * Validates a Claude bounded score (e.g. fiery_sky, golden_hour, inversion_score).
     *
     * <p>Rejects rather than clamps — a value outside the declared range indicates
     * a schema compliance failure upstream, not a soft overrun we should silently
     * round off.
     *
     * @param raw          value as parsed from Claude; may be {@code null}
     * @param min          inclusive lower bound
     * @param max          inclusive upper bound
     * @param fieldName    field name for WARN log (e.g. {@code "fiery_sky"})
     * @param locationName location display name (for WARN context); may be {@code null}
     * @param model        model id used for the evaluation (for WARN context); may be {@code null}
     * @return the value if {@code null} or in {@code [min, max]}; otherwise {@code null}
     */
    public static Integer validateScore(Integer raw, int min, int max, String fieldName,
            String locationName, String model) {
        if (raw == null) {
            return null;
        }
        if (raw >= min && raw <= max) {
            return raw;
        }
        LOG.warn("[RATING GUARDRAIL] Out-of-range {} rejected: "
                + "location={}, model={}, value={}, range=[{},{}]",
                fieldName, locationName, model, raw, min, max);
        return null;
    }
}
