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
 * <p><strong>Read the separations, not the absolute numbers.</strong> The reanalysis baseline sits
 * systematically below the forecast model — measured at ~15pp at the observer and ~25pp at the
 * horizon, and flat across forecast horizons, which is a model offset rather than forecast error.
 * Any single bucket's mean therefore carries that offset. Differences between buckets do not,
 * because it applies to all of them equally.
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
 *   <li>{@code byConeStructure} — the cone-aggregation question. The forecast collapses its three
 *       cone samples to a mean; these buckets measure how often the analysed horizon is a
 *       wall-with-gap (large spread) rather than a uniform deck, and whether the forecast's gap
 *       error grows exactly there.</li>
 *   <li>{@code byCorridor} — the canvas-height gate question. A high-cloud canvas is underlit
 *       through low cloud 206–432 km out, not the 113 km the gate reads; these buckets measure
 *       how often near and far corridor readings structurally diverge. The 226 km sample centres
 *       a 4 km mid canvas's corridor, so the {@code &midCanvas} variants measure that geometry
 *       directly, while the {@code &highCanvas} ones sit at the near edge of the cirrus corridor
 *       (centred ~319 km) and are proxy evidence.</li>
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
 * @param byConeStructure all pairs bucketed by analysed cone spread (uniform / mixed / gapped)
 * @param byCorridor      all pairs bucketed by near-vs-far corridor divergence, with high- and
 *                        mid-canvas-dominant sub-buckets
 * @param vetoSeparation  mean observed horizon cloud in {@code vetoFired} minus
 *                        {@code vetoNotFired}. Positive means vetoed slots really were cloudier,
 *                        i.e. the veto discriminates. Near zero means it fires on nothing real.
 * @param capSeparation   the same measure for {@code vetoUncapped} minus {@code vetoCapped}.
 *                        Positive means the veto only tracks reality below the 200 km cap — the
 *                        D7 signature.
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
        List<CloudVerificationBucket> byWindSunAngle,
        List<CloudVerificationBucket> byConeStructure,
        List<CloudVerificationBucket> byCorridor,
        Double vetoSeparation,
        Double capSeparation) {

    /**
     * Compact constructor — defensive copies to satisfy SpotBugs EI_EXPOSE_REP.
     *
     * @param from            start of the window
     * @param to              end of the window
     * @param verifiedCount   evaluations verified
     * @param overall         overall metrics
     * @param vetoFired       veto-fired metrics
     * @param vetoNotFired    veto-not-fired metrics
     * @param vetoUncapped    uncapped veto-fired metrics
     * @param vetoCapped      capped veto-fired metrics
     * @param byWindSunAngle  per-alignment metrics
     * @param byConeStructure per-cone-spread metrics
     * @param byCorridor      per-corridor-divergence metrics
     * @param vetoSeparation  fired-minus-not-fired observed cloud
     * @param capSeparation   uncapped-minus-capped observed cloud
     */
    public CloudVerificationReport {
        byWindSunAngle = byWindSunAngle == null ? List.of() : List.copyOf(byWindSunAngle);
        byConeStructure = byConeStructure == null ? List.of() : List.copyOf(byConeStructure);
        byCorridor = byCorridor == null ? List.of() : List.copyOf(byCorridor);
    }
}
