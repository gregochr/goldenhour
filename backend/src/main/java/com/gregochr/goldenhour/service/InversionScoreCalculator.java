package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.WeatherData;

/**
 * Computes a cloud inversion likelihood score (0–10) from atmospheric data.
 *
 * <p>Cloud inversions form when warm air traps cooler, moist air near the surface.
 * For elevated viewpoints overlooking water, this produces a dramatic "sea of clouds"
 * effect. The score composes three <em>independent</em> signals:
 * <ul>
 *   <li>Moisture — temperature–dew point gap, 0–5 (smaller gap → nearer saturation)</li>
 *   <li>Stability — wind speed, 0–3 (light winds let a stable layer hold)</li>
 *   <li>The sea itself — low cloud cover, 0–2, <em>and a hard gate</em> (see below)</li>
 * </ul>
 *
 * <p><b>Why there is no humidity term.</b> An earlier version awarded 4 points for the
 * temp–dew gap and 2 more for relative humidity, but those are the same measurement: RH is
 * a function of the gap, so the two always fired together and 6 of the 10 points came from
 * one reading. Checked with Magnus over −5 °C to 25 °C:
 * <pre>
 *   gap ≤ 1.0 °C → RH 92.7–94.2 %   (always earned the full humidity bonus)
 *   gap = 2.0 °C → RH 85.9–88.7 %
 *   gap = 6.0 °C → RH 62.8–69.4 %
 * </pre>
 * The consequence was that any calm, saturated dawn — the ordinary UK autumn radiative-cooling
 * morning — scored 9 on moisture and wind alone, before cloud was even considered, so the
 * STRONG band fired constantly. Moisture is now counted once.
 *
 * <p><b>Why low cloud is a gate, not a garnish.</b> Low cloud used to be worth a single point,
 * so both of these reached the STRONG band and contradicted each other:
 * <ul>
 *   <li>100 % low cloud — the viewpoint is <em>inside</em> the murk, not above it</li>
 *   <li>0 % low cloud — there is no cloud to form a sea at all</li>
 * </ul>
 * A low cloud fraction outside {@value #MIN_CLOUD_PERCENT}–{@value #MAX_CLOUD_PERCENT} % now
 * caps the score at {@value #VETOED_CEILING}, below the MODERATE band, so neither can produce
 * an inversion prompt block or a hot topic.
 *
 * <p><b>Two further gates decide whether an inversion exists at all.</b> The three terms above
 * are surface readings: they say how good a sea of clouds would look, not whether there is one.
 * A temperature inversion is temperature <em>increasing</em> with height, so it takes a vertical
 * profile to confirm:
 * <ul>
 *   <li><b>Lapse gate</b> — the 925 hPa temperature (≈ 760 m, falling back to 850 hPa ≈ 1500 m)
 *       against the 2 m temperature. No reversal means no inversion, whatever the surface looks
 *       like, and the score is capped at {@value #VETOED_CEILING}. A reversal weaker than
 *       {@value #STRONG_REVERSAL_CELSIUS} °C caps at {@value #MARGINAL_CEILING} — real, but not
 *       the well-defined cap the STRONG band claims.</li>
 *   <li><b>Clearance gate</b> — the viewpoint must stand proud of the stable layer. When the
 *       boundary layer is deeper than the location's elevation the photographer is inside the
 *       murk rather than above it, and the score is capped at {@value #VETOED_CEILING}.</li>
 * </ul>
 *
 * <p><b>Missing profile data caps at MODERATE rather than failing either way.</b> If Open-Meteo
 * returns no pressure-level temperature the score cannot exceed {@value #MARGINAL_CEILING}, so
 * the STRONG band — and the inversion hot topic that rides it — can only ever fire on a measured
 * reversal. Claiming STRONG without a profile is what made every inversion read "9/10 · strong";
 * dropping to zero instead would silently retire the feature. The caller logs the gap.
 *
 * <p><b>Known approximation in the clearance gate.</b> Open-Meteo reports boundary layer height
 * above the model grid cell's ground, and elevation is above sea level, so the comparison is not
 * exact for a viewpoint whose grid cell sits well above the valley floor it overlooks. It is
 * directionally right for the case that matters — a nocturnal stable layer collapses to tens or
 * low hundreds of metres, and a daytime mixed layer runs past a kilometre — and it is the best
 * the available data supports without a terrain model.
 *
 * <p>Only meaningful for locations with {@code elevation >= 200m} and
 * {@code overlooksWater = true}. The caller gates on those conditions.
 */
public final class InversionScoreCalculator {

    /** Minimum elevation in metres for inversion relevance. */
    public static final int MIN_ELEVATION_METRES = 200;

    /**
     * Low cloud percentage below which there is no cloud to form a sea, so the score is vetoed.
     */
    static final int MIN_CLOUD_PERCENT = 15;

    /**
     * Low cloud percentage above which the viewpoint is in the cloud rather than above it, so
     * the score is vetoed.
     */
    static final int MAX_CLOUD_PERCENT = 80;

    /** Lower bound of the well-defined cloud deck that earns the full cloud score. */
    static final int IDEAL_CLOUD_MIN_PERCENT = 25;

    /** Upper bound of the well-defined cloud deck that earns the full cloud score. */
    static final int IDEAL_CLOUD_MAX_PERCENT = 65;

    /**
     * Ceiling applied to a vetoed score. Sits below the MODERATE band (7) so a vetoed
     * location can reach neither the prompt's inversion block nor the inversion hot topic,
     * while still ordering vetoed locations against each other by moisture and wind.
     */
    static final double VETOED_CEILING = 6.0;

    /**
     * Ceiling applied when an inversion is real but unconfirmed as well-defined — a weak measured
     * reversal, or no pressure-level data at all. Top of the MODERATE band, so the STRONG band
     * stays reserved for a measured, well-defined cap.
     */
    static final double MARGINAL_CEILING = 8.0;

    /**
     * Temperature reversal in °C, between the surface and the 925 hPa level, at or above which
     * the capping inversion counts as well-defined rather than merely present.
     */
    static final double STRONG_REVERSAL_CELSIUS = 2.0;

    private InversionScoreCalculator() {
    }

    /**
     * Computes the inversion score from atmospheric data.
     *
     * @param data            the atmospheric data at event time
     * @param elevationMetres the viewpoint's elevation above sea level in metres, or null when
     *                        unknown — the clearance gate is skipped when it is absent
     * @return a score between 0 (no inversion) and 10 (near-certain inversion), or null
     *         if required weather fields are missing
     */
    public static Double calculate(AtmosphericData data, Integer elevationMetres) {
        if (data == null || data.weather() == null || data.cloud() == null) {
            return null;
        }
        WeatherData w = data.weather();
        if (w.dewPointCelsius() == null || data.comfort() == null
                || data.comfort().temperatureCelsius() == null) {
            return null;
        }

        double surfaceTemp = data.comfort().temperatureCelsius();
        double tempDewGap = surfaceTemp - w.dewPointCelsius();
        double windSpeed = w.windSpeedMs().doubleValue();
        int lowCloud = data.cloud().lowCloudPercent();

        double score = moistureScore(tempDewGap) + windScore(windSpeed);

        // Cloud veto: too little cloud to form a sea, or so much that the viewpoint is inside it.
        if (lowCloud < MIN_CLOUD_PERCENT || lowCloud > MAX_CLOUD_PERCENT) {
            return Math.min(score, VETOED_CEILING);
        }
        score += cloudScore(lowCloud);

        // Clearance gate: a stable layer deeper than the viewpoint puts the photographer in it.
        if (!viewpointClearsStableLayer(data, elevationMetres)) {
            return Math.min(score, VETOED_CEILING);
        }
        return Math.min(score, lapseCeiling(reversalCelsius(w, surfaceTemp)));
    }

    /**
     * Returns the measured temperature reversal — how much warmer the air aloft is than the
     * surface. Positive means temperature rises with height, which is what an inversion is.
     *
     * <p>Prefers the 925 hPa level (≈ 760 m), the scale of a UK valley inversion. Falls back to
     * 850 hPa (≈ 1500 m), which is a stricter test of the same thing: a reversal still present at
     * 1500 m is a deeper, more certain one.
     *
     * @param w           the event-slot weather readings
     * @param surfaceTemp the 2 m temperature in °C
     * @return the reversal in °C, or null when no pressure-level temperature is available
     */
    public static Double reversalCelsius(WeatherData w, double surfaceTemp) {
        Double aloft = w.temperature925hPaCelsius() != null
                ? w.temperature925hPaCelsius()
                : w.temperature850hPaCelsius();
        return aloft == null ? null : aloft - surfaceTemp;
    }

    /**
     * Maps a measured reversal to the highest score it can justify.
     *
     * @param reversal the reversal in °C, or null when no profile was available
     * @return 10 for a well-defined reversal, {@link #MARGINAL_CEILING} for a weak or unmeasured
     *         one, {@link #VETOED_CEILING} when the profile shows no reversal at all
     */
    private static double lapseCeiling(Double reversal) {
        if (reversal == null) {
            // No profile: an inversion cannot be confirmed, so STRONG cannot be claimed.
            return MARGINAL_CEILING;
        }
        if (reversal < 0.0) {
            // Temperature falls with height — an ordinary lapse. There is no inversion.
            return VETOED_CEILING;
        }
        return reversal >= STRONG_REVERSAL_CELSIUS ? 10.0 : MARGINAL_CEILING;
    }

    /**
     * Returns whether the viewpoint stands above the stable layer rather than inside it.
     *
     * <p>Skipped (returns true) when elevation or boundary layer height is unavailable — the
     * lapse gate is the primary evidence and this one should not veto on absent data.
     *
     * @param data            the atmospheric data, carrying boundary layer height
     * @param elevationMetres the viewpoint's elevation above sea level, or null
     * @return true when the viewpoint clears the layer, or when the check cannot be made
     */
    private static boolean viewpointClearsStableLayer(AtmosphericData data,
            Integer elevationMetres) {
        if (elevationMetres == null || data.aerosol() == null) {
            return true;
        }
        return elevationMetres > data.aerosol().boundaryLayerHeightMetres();
    }

    /**
     * Scores available moisture from the temperature–dew point gap (0–5). This is the only
     * moisture term — see the class note on why relative humidity is not counted separately.
     *
     * @param tempDewGap temperature minus dew point, in °C
     * @return 0–5, higher the nearer the surface air is to saturation
     */
    private static double moistureScore(double tempDewGap) {
        if (tempDewGap <= 0.5) {
            return 5.0;
        }
        if (tempDewGap <= 1.0) {
            return 4.0;
        }
        if (tempDewGap <= 2.0) {
            return 3.0;
        }
        if (tempDewGap <= 3.0) {
            return 2.0;
        }
        if (tempDewGap <= 5.0) {
            return 1.0;
        }
        return 0.0;
    }

    /**
     * Scores layer stability from wind speed (0–3). Light winds let a stable boundary layer
     * hold; anything above 5 m/s mixes it out.
     *
     * @param windSpeed 10 m wind speed in m/s
     * @return 0–3, higher the lighter the wind
     */
    private static double windScore(double windSpeed) {
        if (windSpeed <= 1.5) {
            return 3.0;
        }
        if (windSpeed <= 3.0) {
            return 2.0;
        }
        if (windSpeed <= 5.0) {
            return 1.0;
        }
        return 0.0;
    }

    /**
     * Scores the cloud deck itself (0–2). Only called for a low cloud fraction inside the
     * un-vetoed range, so the floor here is 1, not 0: some cloud is present either way.
     *
     * @param lowCloud low cloud cover percentage, within the un-vetoed range
     * @return 2 for a well-defined deck, 1 for a thin or near-overcast one
     */
    private static double cloudScore(int lowCloud) {
        if (lowCloud >= IDEAL_CLOUD_MIN_PERCENT && lowCloud <= IDEAL_CLOUD_MAX_PERCENT) {
            return 2.0;
        }
        return 1.0;
    }
}
