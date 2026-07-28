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
        Integer azimuthDeg) {

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
}
