package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * One past forecast's cloud claims paired with the analysed (reanalysis) cloud for the same
 * point and hour.
 *
 * <p>The forecast makes two separable claims, and this pairs both:
 * <ul>
 *   <li><strong>The gap</strong> — low cloud at the solar horizon (113 km along the solar
 *       azimuth), which decides whether the low sun gets through at all.</li>
 *   <li><strong>The canvas</strong> — mid and high cloud overhead at the observer, the screen
 *       that light lands on.</li>
 * </ul>
 * A forecast can be wrong about either independently, and they fail differently: a gap that
 * closed is a missed blocker, a canvas that never materialised is light with nothing to light up.
 *
 * <p>Also carries both cloud-approach veto triggers plus the wind and solar bearings, so the
 * report can ask whether the veto's firings were justified, and whether the angle between wind
 * and sun predicts them being wrong — the upwind sample is anchored at the observer (measuring
 * canvas arrival) while the veto reasons about the horizon gap, so the two diverge most when
 * wind and sun are aligned.
 *
 * @param locationName          the location
 * @param targetDate            date of the solar event
 * @param targetType            SUNRISE or SUNSET
 * @param daysAhead             forecast horizon (0 = same day)
 * @param rating                the rating given, or {@code null} if triaged
 * @param forecastGapLow        predicted coned solar-horizon low cloud (%)
 * @param observedGapLow        analysed low cloud at the solar horizon (%)
 * @param forecastCanvasMid     predicted mid cloud overhead (%)
 * @param forecastCanvasHigh    predicted high cloud overhead (%)
 * @param observedCanvasMid     analysed mid cloud overhead (%)
 * @param observedCanvasHigh    analysed high cloud overhead (%)
 * @param solarTrendBuilding    whether the [BUILDING] trigger fired
 * @param upwindCurrentLowCloud current low cloud at the upwind point (%), or {@code null}
 * @param upwindDistanceKm      how far upwind the sample was taken, or {@code null}
 * @param windDirection         wind-from bearing in degrees, or {@code null}
 * @param azimuthDeg            solar azimuth in degrees, or {@code null}
 * @param forecastFarLow        predicted low cloud at the 226 km far-solar point (%), or
 *                              {@code null}
 * @param observedGapLowMin     lowest analysed low cloud across the cone bearings (%), or
 *                              {@code null}
 * @param observedGapLowMax     highest analysed low cloud across the cone bearings (%), or
 *                              {@code null}
 * @param observedFarLow        analysed low cloud at the 226 km far-solar point (%), or
 *                              {@code null}
 */
public record CloudVerificationPair(
        String locationName,
        LocalDate targetDate,
        TargetType targetType,
        Integer daysAhead,
        Integer rating,
        Integer forecastGapLow,
        Integer observedGapLow,
        Integer forecastCanvasMid,
        Integer forecastCanvasHigh,
        Integer observedCanvasMid,
        Integer observedCanvasHigh,
        Boolean solarTrendBuilding,
        Integer upwindCurrentLowCloud,
        Integer upwindDistanceKm,
        Integer windDirection,
        Integer azimuthDeg,
        Integer forecastFarLow,
        Integer observedGapLowMin,
        Integer observedGapLowMax,
        Integer observedFarLow) {

    /** Upwind low cloud (%) at or above which the veto's second trigger is satisfied. */
    private static final int UPWIND_TRIGGER_PERCENT = 60;

    /**
     * Upwind distance (km) at which {@code MAX_UPWIND_DISTANCE_M} has clamped the sample.
     *
     * <p>Below the cap the offset equals {@code windSpeed × timeToEvent}, so the sampled parcel
     * really is the one that arrives at event time. At the cap that identity breaks and the
     * reading is merely "cloud 200 km upwind right now" — which is what the prompt nonetheless
     * describes as arriving, and what the veto acts on.
     */
    private static final int UPWIND_CAP_KM = 200;

    /** Half-circle in degrees, for normalising a bearing difference. */
    private static final int HALF_CIRCLE_DEG = 180;

    /** Full circle in degrees. */
    private static final int FULL_CIRCLE_DEG = 360;

    /**
     * Returns whether both cloud-approach veto triggers fired, forcing the rating to 1–2.
     *
     * @return true when a building trend coincided with a high upwind reading
     */
    public boolean vetoFired() {
        return Boolean.TRUE.equals(solarTrendBuilding)
                && upwindCurrentLowCloud != null
                && upwindCurrentLowCloud >= UPWIND_TRIGGER_PERCENT;
    }

    /**
     * Returns whether the upwind sample was clamped by the 200 km cap.
     *
     * <p>The single most important split in the report. Below the cap the upwind reading is a
     * genuine advection nowcast of the event-time horizon; at the cap it is not, yet the veto
     * treats both identically. If the veto only tracks observed cloud in the uncapped subset,
     * the cap — not the anchoring — is the dominant defect.
     *
     * @return true when the sample sat at the cap
     */
    public boolean upwindCapped() {
        return upwindDistanceKm != null && upwindDistanceKm >= UPWIND_CAP_KM;
    }

    /**
     * Returns the angle between the wind-from bearing and the solar azimuth, 0–180°.
     *
     * @return the separation in degrees, or {@code null} if either bearing is unknown
     */
    public Integer windSunAngle() {
        if (windDirection == null || azimuthDeg == null) {
            return null;
        }
        int diff = Math.abs(windDirection - azimuthDeg) % FULL_CIRCLE_DEG;
        return diff > HALF_CIRCLE_DEG ? FULL_CIRCLE_DEG - diff : diff;
    }

    /**
     * Returns the signed gap error — positive when the forecast over-predicted the blocker.
     *
     * @return forecast minus observed solar-horizon low cloud, or {@code null} if either is missing
     */
    public Integer gapError() {
        if (forecastGapLow == null || observedGapLow == null) {
            return null;
        }
        return forecastGapLow - observedGapLow;
    }

    /**
     * Returns the signed canvas error — positive when the forecast over-predicted the canvas.
     *
     * <p>Canvas strength is the stronger of the mid and high layers, since either alone can catch
     * colour — the same rule {@code SolarCloudTrend.SolarCloudSlot.canvasPercent()} applies.
     *
     * @return forecast minus observed canvas strength, or {@code null} if either is missing
     */
    public Integer canvasError() {
        Integer forecast = strongerLayer(forecastCanvasMid, forecastCanvasHigh);
        Integer observed = strongerLayer(observedCanvasMid, observedCanvasHigh);
        if (forecast == null || observed == null) {
            return null;
        }
        return forecast - observed;
    }

    /**
     * Returns the stronger of two cloud layers, or {@code null} if either is absent.
     *
     * @param mid  mid cloud percent
     * @param high high cloud percent
     * @return the greater value, or {@code null}
     */
    private static Integer strongerLayer(Integer mid, Integer high) {
        if (mid == null || high == null) {
            return null;
        }
        return Math.max(mid, high);
    }

    /**
     * Returns the analysed cone's low-cloud spread — the structure a 3-point mean cannot carry.
     *
     * <p>Near zero means the horizon really was uniform and the mean described it faithfully; a
     * large spread means one bearing was clear while another was blocked (a wall with a gap),
     * which the forecast's persisted mean renders identically to a uniform mid-level deck.
     * Computed within one reanalysis baseline, so the forecast-vs-reanalysis offset cancels.
     *
     * @return max minus min analysed cone low cloud, or {@code null} if the extremes are missing
     */
    public Integer coneSpread() {
        if (observedGapLowMin == null || observedGapLowMax == null) {
            return null;
        }
        return observedGapLowMax - observedGapLowMin;
    }

    /**
     * Returns how much the analysed low cloud drops from the 113 km point to the 226 km point.
     *
     * <p>Positive means the corridor clears with distance (a near strip — the 113 km gate reads
     * blocked while a high canvas could still be underlit); negative means it thickens (the gate
     * reads clear while the high-canvas corridor is blanketed). Both readings share the reanalysis
     * baseline, so the comparison is offset-immune.
     *
     * @return near minus far analysed low cloud, or {@code null} if either reading is missing
     */
    public Integer farDrop() {
        if (observedGapLow == null || observedFarLow == null) {
            return null;
        }
        return observedGapLow - observedFarLow;
    }

    /**
     * Returns the signed far-corridor error — positive when the forecast over-predicted it.
     *
     * <p>The forecast's own 226 km claim (the strip-vs-blanket input) has never been verified;
     * this is its counterpart to {@link #gapError()}.
     *
     * @return forecast minus observed far-solar low cloud, or {@code null} if either is missing
     */
    public Integer farError() {
        if (forecastFarLow == null || observedFarLow == null) {
            return null;
        }
        return forecastFarLow - observedFarLow;
    }

    /**
     * Returns whether the analysed canvas was high-cloud dominant.
     *
     * <p>Cirrus above the observer is underlit through low-cloud altitude 206–432 km sunward,
     * which the 113 km gate never reads. That corridor is centred at ~319 km, so the 226 km
     * far-solar point samples only its near <em>edge</em> — synoptic-scale proxy evidence rather
     * than a direct measurement, which is why {@link #midCanvasDominant()}, whose corridor the
     * point does centre, is bucketed alongside it. A strict comparison so an empty sky (0/0) does
     * not count as high-dominant. Layer dominance is compared within the reanalysis, not against
     * an absolute threshold.
     *
     * @return true when analysed high cloud exceeds analysed mid cloud, or {@code null} if either
     *         layer is missing
     */
    public Boolean highCanvasDominant() {
        if (observedCanvasMid == null || observedCanvasHigh == null) {
            return null;
        }
        return observedCanvasHigh > observedCanvasMid;
    }

    /**
     * Returns whether the analysed canvas was mid-cloud dominant.
     *
     * <p>Mid dominance is what puts the 226 km far-solar point near the <em>centre</em> of the
     * relevant blocking corridor rather than at its near edge: a 4 km mid canvas is underlit
     * through low-cloud altitude over 113–339 km, centred at 226 km = sqrt(2R × 4 km). So for
     * mid-dominant skies the far reading measures the corridor that matters, where under
     * {@link #highCanvasDominant()} it only proxies it. Geometric figures — refraction stretches
     * them 7–10%, moving that centre to ~242–247 km, which the near-centre-not-near-edge reading
     * survives but a calibrated-distance one would not. A strict comparison so an empty sky (0/0)
     * counts as neither dominant.
     *
     * @return true when analysed mid cloud exceeds analysed high cloud, or {@code null} if either
     *         layer is missing
     */
    public Boolean midCanvasDominant() {
        if (observedCanvasMid == null || observedCanvasHigh == null) {
            return null;
        }
        return observedCanvasMid > observedCanvasHigh;
    }
}
