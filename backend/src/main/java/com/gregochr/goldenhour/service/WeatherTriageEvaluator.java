package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Stateless heuristic evaluator that determines whether weather conditions are
 * obviously unsuitable for colour photography, allowing the forecast pipeline to
 * skip the expensive Claude evaluation.
 *
 * <p>Rules are checked in priority order (first match wins):
 * <ol>
 *   <li>Solar low cloud &gt; 80% (falls back to observer low cloud if no directional data)</li>
 *   <li>Precipitation &gt; 2 mm</li>
 *   <li>Visibility &lt; 5000 m</li>
 * </ol>
 */
@Service
public class WeatherTriageEvaluator {

    private static final int CLOUD_THRESHOLD = 80;
    private static final BigDecimal PRECIP_THRESHOLD = new BigDecimal("2.0");
    private static final int VISIBILITY_THRESHOLD = 5000;

    /** Start of rule 3's reason text; {@code forecast_run_disposition.detail} keeps it verbatim. */
    static final String LOW_VISIBILITY_PREFIX = "Visibility ";

    /** End of rule 3's reason text. */
    static final String LOW_VISIBILITY_SUFFIX = " m — poor visibility";

    /**
     * Whether a stored triage reason text is rule 3's (visibility under 5 km). The disposition
     * table keeps the reason as free text and no enum, so the honesty filter's "Fog forecast"
     * wording (valley-mist-plan M1b) recognises it through the same two constants that build it;
     * a test round-trips the evaluator's own output so the pair cannot drift apart.
     *
     * @param detail the {@code forecast_run_disposition.detail} text, or null
     * @return true when the text is the low-visibility reason
     */
    static boolean isLowVisibilityReason(String detail) {
        return detail != null && detail.startsWith(LOW_VISIBILITY_PREFIX)
                && detail.endsWith(LOW_VISIBILITY_SUFFIX);
    }

    /**
     * Evaluates atmospheric data against triage heuristics.
     *
     * @param data the atmospheric data to evaluate
     * @return a triage result if conditions are unsuitable, or empty if evaluation should proceed
     */
    public Optional<TriageResult> evaluate(AtmosphericData data) {
        // Rule 1: Solar low cloud > 80% (prefer directional, fall back to observer)
        boolean usedDirectional = data.directionalCloud() != null;
        int lowCloud = usedDirectional
                ? data.directionalCloud().solarLowCloudPercent()
                : data.cloud().lowCloudPercent();
        if (lowCloud > CLOUD_THRESHOLD) {
            String source = usedDirectional ? "Solar horizon low cloud" : "Low cloud cover";
            return Optional.of(new TriageResult(
                    source + " " + lowCloud + "% — sun blocked", TriageRule.HIGH_CLOUD));
        }

        // Rule 2: Precipitation > 2mm
        if (data.weather().precipitationMm() != null
                && data.weather().precipitationMm().compareTo(PRECIP_THRESHOLD) > 0) {
            return Optional.of(new TriageResult(
                    "Precipitation " + data.weather().precipitationMm() + " mm — active rain",
                    TriageRule.PRECIPITATION));
        }

        // Rule 3: Visibility < 5km
        if (data.weather().visibilityMetres() < VISIBILITY_THRESHOLD) {
            return Optional.of(new TriageResult(
                    LOW_VISIBILITY_PREFIX + data.weather().visibilityMetres()
                            + LOW_VISIBILITY_SUFFIX,
                    TriageRule.LOW_VISIBILITY));
        }

        return Optional.empty();
    }
}
