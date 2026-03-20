package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Pre-Claude triage evaluator that checks whether tide conditions align with location
 * preferences during the golden/blue hour window around a solar event.
 *
 * <p>Only applies to SEASCAPE locations with at least one tide preference. If tide data
 * is absent or preferences are empty the evaluator fails open (returns empty), allowing
 * evaluation to proceed to Claude.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link TideType#HIGH} — the nearest high tide (within ±12 h) must fall within
 *       the golden/blue hour window</li>
 *   <li>{@link TideType#LOW} — the nearest low tide (within ±12 h) must fall within
 *       the golden/blue hour window</li>
 *   <li>{@link TideType#MID} — the current tide state must be MID at event time</li>
 * </ul>
 *
 * <p>If ANY preferred type is aligned the evaluator passes (returns empty) — the location
 * is a candidate for Claude evaluation.
 */
@Service
public class TideAlignmentEvaluator {

    /**
     * Evaluates whether tide alignment is satisfied for a SEASCAPE location.
     *
     * <p>Returns empty when:
     * <ul>
     *   <li>the location has no tide snapshot (fail open)</li>
     *   <li>the tide type set is null or empty (fail open)</li>
     *   <li>at least one preferred tide type falls within the window</li>
     * </ul>
     *
     * @param data        atmospheric data (may or may not contain tide snapshot)
     * @param tideTypes   tide type preferences for this location
     * @param windowStart start of the golden/blue hour window (inclusive)
     * @param windowEnd   end of the golden/blue hour window (inclusive)
     * @return a triage result if the tide is misaligned, or empty to proceed to Claude
     */
    public Optional<TriageResult> evaluate(AtmosphericData data, Set<TideType> tideTypes,
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        TideSnapshot tide = data.tide();

        // Fail open: no tide data or no preferences
        if (tide == null || tideTypes == null || tideTypes.isEmpty()) {
            return Optional.empty();
        }

        for (TideType preferred : tideTypes) {
            if (isAligned(preferred, tide, windowStart, windowEnd)) {
                return Optional.empty();
            }
        }

        String preferenceLabel = tideTypes.size() == 1
                ? tideTypes.iterator().next().name().toLowerCase() + " tide"
                : "preferred tide";
        return Optional.of(new TriageResult(
                "No " + preferenceLabel + " aligned with golden/blue hour window",
                TriageRule.TIDE_MISALIGNED));
    }

    /**
     * Returns true if the given tide type is aligned with the solar event window.
     *
     * @param tideType    the preferred tide type to check
     * @param tide        current tide snapshot for the event
     * @param windowStart start of the golden/blue hour window (inclusive)
     * @param windowEnd   end of the golden/blue hour window (inclusive)
     * @return true if the tide type is aligned
     */
    private boolean isAligned(TideType tideType, TideSnapshot tide,
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return switch (tideType) {
            case HIGH -> isWithinWindow(tide.nearestHighTideTime(), windowStart, windowEnd);
            case LOW -> isWithinWindow(tide.nearestLowTideTime(), windowStart, windowEnd);
            case MID -> tide.tideState() != null
                    && tide.tideState().name().equals("MID");
        };
    }

    /**
     * Returns true if the given time falls within [windowStart, windowEnd] (inclusive).
     * Returns false if the time is null.
     *
     * @param time        the time to check, or null
     * @param windowStart window start (inclusive)
     * @param windowEnd   window end (inclusive)
     * @return true if time is non-null and within the window
     */
    private boolean isWithinWindow(LocalDateTime time, LocalDateTime windowStart,
            LocalDateTime windowEnd) {
        if (time == null) {
            return false;
        }
        return !time.isBefore(windowStart) && !time.isAfter(windowEnd);
    }
}
