package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * How well past forecasts' cloud claims matched the cloud that was actually analysed.
 *
 * <p>The counterpart to {@link CalibrationReport} that needs no human. Where the calibration gate
 * scores predicted stars against a photographer's recorded rating, this scores predicted
 * <em>cloud</em> against reanalysis — so it works retroactively over every evaluation already in
 * the database, and it works while {@code actual_outcome} is still empty.
 *
 * <p>The buckets are chosen to answer the specific open questions about the cloud-approach veto:
 * <ul>
 *   <li>{@code vetoFired} vs {@code vetoNotFired} — when the veto forced a 1–2★, was the horizon
 *       really blocked? {@code gapActuallyOpen} inside {@code vetoFired} counts the skies it
 *       suppressed that were in fact clear.</li>
 *   <li>{@code vetoUncapped} vs {@code vetoCapped} — the D7 question. Below the 200 km cap the
 *       upwind sample is a genuine advection nowcast; at the cap it is not. If the veto only
 *       tracks reality in the uncapped subset, the cap is the dominant defect.</li>
 *   <li>{@code byWindSunAngle} — whether the observer-anchored upwind sample misfires when wind
 *       and sun are aligned, the regime where anchoring introduces a timing error.</li>
 * </ul>
 *
 * @param from            start of the verified window (inclusive)
 * @param to              end of the verified window (inclusive)
 * @param verifiedCount   evaluations verified in the window
 * @param overall         metrics across every verified pair
 * @param vetoFired       metrics where both veto triggers fired
 * @param vetoNotFired    metrics where they did not
 * @param vetoUncapped    veto-fired pairs whose upwind sample was below the 200 km cap
 * @param vetoCapped      veto-fired pairs whose upwind sample sat at the cap
 * @param byWindSunAngle  veto-fired pairs bucketed by wind-to-sun separation
 */
public record CloudVerificationReport(
        LocalDate from,
        LocalDate to,
        long verifiedCount,
        CloudVerificationBucket overall,
        CloudVerificationBucket vetoFired,
        CloudVerificationBucket vetoNotFired,
        CloudVerificationBucket vetoUncapped,
        CloudVerificationBucket vetoCapped,
        List<CloudVerificationBucket> byWindSunAngle) {

    /**
     * Compact constructor — defensive copy to satisfy SpotBugs EI_EXPOSE_REP.
     *
     * @param from           start of the window
     * @param to             end of the window
     * @param verifiedCount  evaluations verified
     * @param overall        overall metrics
     * @param vetoFired      veto-fired metrics
     * @param vetoNotFired   veto-not-fired metrics
     * @param vetoUncapped   uncapped veto-fired metrics
     * @param vetoCapped     capped veto-fired metrics
     * @param byWindSunAngle per-alignment metrics
     */
    public CloudVerificationReport {
        byWindSunAngle = byWindSunAngle == null ? List.of() : List.copyOf(byWindSunAngle);
    }
}
