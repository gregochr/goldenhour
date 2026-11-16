package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>Solar low cloud over the horizon's cut (falls back to observer low cloud if no directional
 *       data): &gt; 60% for today and tomorrow ({@code daysAhead <= 1}), &gt; 80% beyond. For
 *       today and tomorrow a thin strip on the horizon
 *       ({@link com.gregochr.goldenhour.model.DirectionalCloudData#isThinStrip()}) keeps the 80%
 *       line, since it is the one case where light gets under the cloud. This is the gate in front
 *       of Claude; {@code BriefingVerdictEvaluator} is a separate heuristic that does not mirror
 *       it.</li>
 *   <li>Precipitation &gt; 2 mm</li>
 *   <li>Visibility &lt; 5000 m &mdash; <b>not consulted</b> when the slot's valley-mist band is
 *       {@code LIKELY} (a {@code mist_prone} site, valley-mist-plan M2)</li>
 * </ol>
 */
@Service
public class WeatherTriageEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherTriageEvaluator.class);

    /** Last {@code daysAhead} (UK civil date) treated as near-term: today and tomorrow. */
    static final int NEAR_TERM_MAX_DAYS = 1;

    /** Rule 1 cut (%) for the near term: over this, a slot that is not a thin strip stands down. */
    static final int NEAR_TERM_CLOUD_THRESHOLD = 60;

    /** Rule 1 cut (%) for the far horizon, and for a near-term thin strip. */
    static final int CLOUD_THRESHOLD = 80;
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
     * @param data      the atmospheric data to evaluate
     * @param daysAhead days from today to the event (UK civil date); a negative value (a retried
     *                  past slot) counts as near-term
     * @return a triage result if conditions are unsuitable, or empty if evaluation should proceed
     */
    public Optional<TriageResult> evaluate(AtmosphericData data, int daysAhead) {
        boolean mistCandidate = data.mistPotential() == MistPotential.LIKELY;
        // Rule 1: Solar low cloud over the horizon's cut (prefer directional, fall back to observer)
        boolean usedDirectional = data.directionalCloud() != null;
        int lowCloud = usedDirectional
                ? data.directionalCloud().solarLowCloudPercent()
                : data.cloud().lowCloudPercent();
        int cut = cloudCut(data, daysAhead);
        if (lowCloud > cut) {
            String source = usedDirectional ? "Solar horizon low cloud" : "Low cloud cover";
            TriageResult refusal = new TriageResult(
                    source + " " + lowCloud + "% — sun blocked", TriageRule.HIGH_CLOUD);
            logMistCandidateRefused(data, mistCandidate, 1, refusal);
            return Optional.of(refusal);
        }

        // Rule 2: Precipitation > 2mm
        if (data.weather().precipitationMm() != null
                && data.weather().precipitationMm().compareTo(PRECIP_THRESHOLD) > 0) {
            TriageResult refusal = new TriageResult(
                    "Precipitation " + data.weather().precipitationMm() + " mm — active rain",
                    TriageRule.PRECIPITATION);
            logMistCandidateRefused(data, mistCandidate, 2, refusal);
            return Optional.of(refusal);
        }

        // Rule 3: Visibility < 5km. NOT CONSULTED for a mist-prone site whose valley-mist band is
        // LIKELY (never POSSIBLE, never FOGBOUND, never an unflagged site): the rule answers "can
        // anything be seen" and for such a slot the answer is the shot, the canopy lane's reasoning
        // (valley-mist-plan M2, owner decision Q3). Rules 1 and 2 above still apply unchanged.
        if (data.weather().visibilityMetres() < VISIBILITY_THRESHOLD) {
            if (mistCandidate) {
                LOG.info("[MIST CANDIDATE] {} {} {}: visibility {} m, mist LIKELY — rule 3 not consulted",
                        data.locationName(), dateOf(data), data.targetType(),
                        data.weather().visibilityMetres());
                return Optional.empty();
            }
            return Optional.of(new TriageResult(
                    LOW_VISIBILITY_PREFIX + data.weather().visibilityMetres()
                            + LOW_VISIBILITY_SUFFIX,
                    TriageRule.LOW_VISIBILITY));
        }

        return Optional.empty();
    }

    /**
     * Rule 1's cut for this slot: 60% for today and tomorrow, 80% beyond, and 80% for a near-term
     * thin strip (the prompt already softens the low-cloud penalty for one).
     */
    private static int cloudCut(AtmosphericData data, int daysAhead) {
        if (daysAhead > NEAR_TERM_MAX_DAYS) {
            return CLOUD_THRESHOLD;
        }
        if (data.directionalCloud() != null && data.directionalCloud().isThinStrip()) {
            return CLOUD_THRESHOLD;
        }
        return NEAR_TERM_CLOUD_THRESHOLD;
    }

    /**
     * Records, once, a mist-prone LIKELY slot that rule 1 or rule 2 still stood down, so the
     * question "does the 2 km model ever show a mist morning as blocked solar cloud" can be
     * re-read from the logs (valley-mist-plan M2). Silent for every other slot.
     */
    private static void logMistCandidateRefused(AtmosphericData data, boolean mistCandidate,
            int rule, TriageResult refusal) {
        if (mistCandidate) {
            LOG.info("[BATCH DIAG] MIST CANDIDATE {} | date={} event={} | refused by rule {} ({}): {}",
                    data.locationName(), dateOf(data), data.targetType(), rule,
                    refusal.rule(), refusal.reason());
        }
    }

    private static Object dateOf(AtmosphericData data) {
        return data.solarEventTime() == null ? "?" : data.solarEventTime().toLocalDate();
    }
}
