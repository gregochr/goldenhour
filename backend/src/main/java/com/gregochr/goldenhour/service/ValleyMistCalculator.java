package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.WeatherData;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Computes a valley mist likelihood score (0–10) for a {@code mist_prone} site — mist seen from
 * <em>beside</em> it, as opposed to {@link InversionScoreCalculator}'s mist seen from above and the
 * woodland lane's mist seen from inside a wood (valley-mist-plan M1a).
 *
 * <p><b>It scores the radiative SET-UP, not the model's own saturation.</b> M0 (2026-10-21) showed
 * that over the UK Open-Meteo's {@code best_match} is already the Met Office 2 km model and that even
 * at 2 km a river corridor a few hundred metres wide does not saturate the cell: the River Brathay on
 * 17 Oct 2026 carried a 2–3 °C temperature–dew point gap, 81–87 % humidity and 37 km visibility all
 * night, under a clear sky, and was photographed in thick mist. So the model's visibility is evidence
 * of the set-up, never the answer, and the terms below are the predictors of radiation fog, each a
 * prior that is cheap to be wrong about until M3 verifies it:
 * <ul>
 *   <li><b>Clear night</b> (0–{@value #CLEAR_MAX}) — mean observer TOTAL cloud over the night-side
 *       slots. Radiative cooling needs an open sky. This is the inverse of the inversion's cloud
 *       veto, where the fog <em>is</em> the low cloud.</li>
 *   <li><b>Calm</b> (0–{@value #CALM_MAX}) — the night-side minimum 10 m wind; the calmest hour is
 *       the one that lets the surface layer stratify.</li>
 *   <li><b>Moisture</b> (0–{@value #MOISTURE_MAX}) — the night-side MINIMUM temp–dew gap through
 *       {@link MoistureScoring}, the one shared moisture scale. <b>The gap is a term, never a
 *       gate</b>: a 2–3 °C gap still scores, because the 2 km cell is drier than the river beside
 *       it. Relative humidity is deliberately not read — it is the same measurement as the gap.</li>
 *   <li><b>Cooling</b> (0–{@value #COOLING_MAX}) — the gap narrowing across the night-side slots
 *       while the air is not warming: temperature falling toward the dew point scores; flat or
 *       rising does not.</li>
 *   <li><b>Wet ground</b> (0–{@value #WET_MAX}) — preceding precipitation; rain then clearing is
 *       the classic set-up.</li>
 *   <li><b>Confirmation</b> (0–{@value #CONFIRM_MAX}) — the model's own event-hour visibility
 *       already in the haze band ({@value #CONFIRM_HAZE_VISIBILITY_M} m, full weight under
 *       {@value #CONFIRM_FULL_VISIBILITY_M} m): the subclass the model does saturate (Ingleton
 *       Waterfalls, 8,840 m, gap 0.1 °C). A term, not a gate.</li>
 * </ul>
 *
 * <p><b>Two hard caps, because a missing precondition cannot be bought back with moisture.</b> A mean
 * total cloud of {@value #OVERCAST_VETO_PERCENT} % or more (no radiative cooling) or a night-side
 * minimum wind above {@value #WINDY_VETO_MS} m/s (the surface layer is mixed) caps the score at
 * {@value #VETOED_CEILING}, below {@link MistPotential#POSSIBLE}, whatever the gap.
 *
 * <p><b>Night-side slots.</b> For a SUNRISE they are T−3h to the event (mist forms through the
 * night and is judged at first light); for a SUNSET the event to T+2h (where mist <em>forms</em>).
 * Season enters only through this: a long winter night gives more cooling than a short summer one
 * and the slots already show it — there is no calendar rule.
 *
 * <p><b>This class never reads {@code mist_prone}.</b> The gate is the caller's
 * ({@code ForecastDataAugmentor.augmentWithValleyMist}); an unflagged site is never scored. A null
 * return means the inputs were missing, which the writer records as an authoritative null.
 */
public final class ValleyMistCalculator {

    /** Maximum of the clear-night term. */
    static final double CLEAR_MAX = 2.0;

    /** Maximum of the calm term. */
    static final double CALM_MAX = 1.5;

    /** Maximum of the moisture term (the largest: the set-up is worthless without moisture). */
    static final double MOISTURE_MAX = 4.0;

    /** Maximum of the cooling term. */
    static final double COOLING_MAX = 1.5;

    /** Maximum of the wet-ground term. */
    static final double WET_MAX = 0.5;

    /** Maximum of the confirmation term. */
    static final double CONFIRM_MAX = 0.5;

    /** Mean total cloud (%) at or below which the clear-night term earns full marks. */
    static final double CLEAR_FULL_PERCENT = 10.0;

    /** Mean total cloud (%) at or above which the clear-night term earns nothing. */
    static final double CLEAR_ZERO_PERCENT = 60.0;

    /** Mean total cloud (%) at or above which radiative cooling is vetoed outright. */
    static final int OVERCAST_VETO_PERCENT = 70;

    /** Night-side minimum wind (m/s) at or below which the calm term earns full marks. */
    static final double CALM_FULL_MS = 2.0;

    /** Night-side minimum wind (m/s) above which the surface layer is mixed: zero marks AND a veto. */
    static final double WINDY_VETO_MS = 4.0;

    /** Gap narrowing (°C) across the night-side slots that earns the cooling term in full. */
    static final double COOLING_FULL_CELSIUS = 0.5;

    /** Preceding precipitation (mm) that earns the wet-ground term in full. */
    static final double WET_FULL_MM = 2.0;

    /** Event-hour visibility (m) under which the model's own haze begins to confirm the set-up. */
    static final int CONFIRM_HAZE_VISIBILITY_M = 10_000;

    /** Event-hour visibility (m) under which the confirmation is at full weight. */
    static final int CONFIRM_FULL_VISIBILITY_M = 5_000;

    /**
     * Ceiling applied to a vetoed score — just under {@link #POSSIBLE_MIN_SCORE}, so a windy or
     * overcast night can reach NONE only, while still ordering vetoed sites against each other.
     */
    static final double VETOED_CEILING = 3.9;

    /**
     * Score at or above which the band is {@link MistPotential#POSSIBLE}. Set above the 3.5 a
     * perfectly clear, calm but DRY night earns on those two terms alone, so that night reads NONE.
     */
    static final double POSSIBLE_MIN_SCORE = 4.0;

    /** Score at or above which the band is {@link MistPotential#LIKELY}. */
    static final double LIKELY_MIN_SCORE = 6.0;

    /**
     * Event-hour visibility (m) under which a site is fogbound rather than beside mist: a white
     * frame. ⚠️ Deliberately NOT {@code WoodlandVerdictEvaluator.MIST_TOO_THICK_M} (100 m): that
     * constant answers "can the trees still resolve", this one answers "is there a view through it
     * for a sky shot", and the plan's own white-frame case is 300 m (a 200 m morning must read
     * FOGBOUND, which a 100 m cut-off would call LIKELY).
     */
    static final int FOGBOUND_VISIBILITY_M = 300;

    /**
     * Fewest night-side slots a score will be given on. One hour is a truncated array (a sunrise
     * at the start of the data), not a night: it carries no cooling trajectory and a single
     * cloud and wind reading, and scoring it would be confidence the data does not support.
     */
    static final int MIN_NIGHT_SLOTS = 2;

    /** Ceiling of the 0-10 scale. */
    static final double MAX_SCORE = 10.0;

    /** Gap readings are rounded to this many steps per degree (0.1 C, the source's precision). */
    private static final double GAP_STEPS_PER_DEGREE = 10.0;

    private ValleyMistCalculator() {
    }

    /**
     * Computes the valley mist score.
     *
     * @param data the atmospheric data at event time, carrying the {@link MistTrend}
     * @return a score between 0 (no set-up) and 10 (textbook radiation-mist set-up), or null when
     *         the inputs are missing: no trend, no night-side slot, or no night-side total cloud or
     *         wind reading at all
     */
    public static Double calculate(AtmosphericData data) {
        if (data == null || data.weather() == null || data.mistTrend() == null) {
            return null;
        }
        List<MistTrend.MistSlot> night = nightSide(data.mistTrend(), data.targetType());
        if (night == null || night.size() < MIN_NIGHT_SLOTS) {
            return null;
        }
        OptionalDouble cloud = night.stream()
                .filter(s -> s.cloudCoverPercent() != null)
                .mapToInt(MistTrend.MistSlot::cloudCoverPercent)
                .average();
        OptionalDouble wind = night.stream()
                .filter(s -> s.windSpeedMs() != null)
                .mapToDouble(MistTrend.MistSlot::windSpeedMs)
                .min();
        if (cloud.isEmpty() || wind.isEmpty()) {
            return null;
        }
        double meanCloud = cloud.getAsDouble();
        double minWind = wind.getAsDouble();
        // Rounded to the 0.1 C the source reports: temperature minus dew point is a float
        // subtraction, and 4.4 - 3.9 = 0.5000000000000004 would otherwise tip a saturated
        // reading into the next step of the shared scale.
        double minGap = night.stream()
                .mapToDouble(ValleyMistCalculator::roundedGap)
                .min().orElseThrow();

        double moisture = MoistureScoring.gapScore(minGap) / MoistureScoring.MAX_SCORE * MOISTURE_MAX;
        double score = clearScore(meanCloud)
                + calmScore(minWind)
                + moisture
                + coolingScore(night, moisture > 0.0)
                + wetGroundScore(data.weather())
                + confirmationScore(data.weather().visibilityMetres());

        boolean vetoed = meanCloud >= OVERCAST_VETO_PERCENT || minWind > WINDY_VETO_MS;
        return Math.min(vetoed ? Math.min(score, VETOED_CEILING) : score, MAX_SCORE);
    }

    /**
     * Bands a score into a {@link MistPotential}.
     *
     * <p>{@link MistPotential#FOGBOUND} is decided by the model's own event-hour visibility, not by
     * the score: visibility under {@value #FOGBOUND_VISIBILITY_M} m with no later slot in the trend
     * clearing back above it is a white frame whatever the set-up looked like.
     *
     * @param data  the atmospheric data the score was computed from
     * @param score the score from {@link #calculate}, or null
     * @return the band, or null when {@code score} is null (a band is only ever as known as its
     *         score)
     */
    public static MistPotential classify(AtmosphericData data, Double score) {
        if (score == null) {
            return null;
        }
        if (isFogbound(data)) {
            return MistPotential.FOGBOUND;
        }
        if (score >= LIKELY_MIN_SCORE) {
            return MistPotential.LIKELY;
        }
        return score >= POSSIBLE_MIN_SCORE ? MistPotential.POSSIBLE : MistPotential.NONE;
    }

    /**
     * The night-side facts the calculator scored, for the prompt to state (valley-mist-plan M2).
     *
     * @param minGapCelsius       the night-side minimum temperature minus dew point, rounded to
     *                            the source's 0.1 C
     * @param minGapHour          the {@code hoursRelativeToEvent} of the slot where that minimum
     *                            falls (the earliest, when several tie)
     * @param minWindMs           the night-side minimum 10 m wind, or null when no slot carried one
     * @param meanCloudPercent    the night-side mean observer total cloud, or null when no slot
     *                            carried one
     */
    public record NightFacts(double minGapCelsius, int minGapHour, Double minWindMs,
            Double meanCloudPercent) {
    }

    /**
     * Reads back the night-side facts {@link #calculate} scored from, so the prompt can state them
     * rather than ask Claude to reproduce the number (the inversion precedent).
     *
     * @param data the atmospheric data carrying the {@link MistTrend}
     * @return the facts, or null when there is no trend or fewer than {@value #MIN_NIGHT_SLOTS}
     *         night-side slots (the cases in which {@link #calculate} returns null)
     */
    public static NightFacts nightFacts(AtmosphericData data) {
        if (data == null || data.mistTrend() == null) {
            return null;
        }
        List<MistTrend.MistSlot> night = nightSide(data.mistTrend(), data.targetType());
        if (night == null || night.size() < MIN_NIGHT_SLOTS) {
            return null;
        }
        MistTrend.MistSlot tightest = night.get(0);
        for (MistTrend.MistSlot slot : night) {
            if (roundedGap(slot) < roundedGap(tightest)) {
                tightest = slot;
            }
        }
        OptionalDouble cloud = night.stream()
                .filter(s -> s.cloudCoverPercent() != null)
                .mapToInt(MistTrend.MistSlot::cloudCoverPercent)
                .average();
        OptionalDouble wind = night.stream()
                .filter(s -> s.windSpeedMs() != null)
                .mapToDouble(MistTrend.MistSlot::windSpeedMs)
                .min();
        return new NightFacts(roundedGap(tightest), tightest.hoursRelativeToEvent(),
                wind.isPresent() ? wind.getAsDouble() : null,
                cloud.isPresent() ? cloud.getAsDouble() : null);
    }

    private static boolean isFogbound(AtmosphericData data) {
        if (data.weather().visibilityMetres() >= FOGBOUND_VISIBILITY_M) {
            return false;
        }
        // An improving trend: some later slot has cleared back above the fog threshold.
        return data.mistTrend().slots().stream()
                .noneMatch(s -> s.hoursRelativeToEvent() > 0
                        && s.visibilityMetres() >= FOGBOUND_VISIBILITY_M);
    }

    /**
     * The slots that bear on whether mist formed: before the event for a sunrise, after it for a
     * sunset. The event slot itself belongs to both.
     *
     * @return the slots in time order, or null for a target type that is neither
     */
    private static List<MistTrend.MistSlot> nightSide(MistTrend trend, TargetType type) {
        if (type == TargetType.SUNRISE) {
            return trend.slots().stream().filter(s -> s.hoursRelativeToEvent() <= 0)
                    .sorted(Comparator.comparingInt(MistTrend.MistSlot::hoursRelativeToEvent))
                    .toList();
        }
        if (type == TargetType.SUNSET) {
            return trend.slots().stream().filter(s -> s.hoursRelativeToEvent() >= 0)
                    .sorted(Comparator.comparingInt(MistTrend.MistSlot::hoursRelativeToEvent))
                    .toList();
        }
        return null;
    }

    /** Temperature minus dew point for one slot, rounded to the source's 0.1 C precision. */
    private static double roundedGap(MistTrend.MistSlot slot) {
        return Math.round((slot.temperatureCelsius() - slot.dewPointCelsius()) * GAP_STEPS_PER_DEGREE)
                / GAP_STEPS_PER_DEGREE;
    }

    private static double clearScore(double meanCloudPercent) {
        return CLEAR_MAX * ramp(CLEAR_ZERO_PERCENT - meanCloudPercent,
                CLEAR_ZERO_PERCENT - CLEAR_FULL_PERCENT);
    }

    private static double calmScore(double minWindMs) {
        return CALM_MAX * ramp(WINDY_VETO_MS - minWindMs, WINDY_VETO_MS - CALM_FULL_MS);
    }

    /**
     * Cooling only counts when saturation is within reach: air more than 5 C from its dew point
     * (a zero moisture term) is cooling toward nothing, and a clear calm night nearly always cools,
     * so without this a DRY night would read POSSIBLE on cooling alone.
     */
    private static double coolingScore(List<MistTrend.MistSlot> night, boolean saturationInReach) {
        if (!saturationInReach) {
            return 0.0;
        }
        MistTrend.MistSlot first = night.get(0);
        MistTrend.MistSlot last = night.get(night.size() - 1);
        if (last.temperatureCelsius() > first.temperatureCelsius()) {
            return 0.0;
        }
        double narrowing = (first.temperatureCelsius() - first.dewPointCelsius())
                - (last.temperatureCelsius() - last.dewPointCelsius());
        return COOLING_MAX * ramp(narrowing, COOLING_FULL_CELSIUS);
    }

    private static double wetGroundScore(WeatherData weather) {
        Double preceding = weather.precedingPrecipitationMm();
        return preceding == null ? 0.0 : WET_MAX * ramp(preceding, WET_FULL_MM);
    }

    private static double confirmationScore(int visibilityMetres) {
        if (visibilityMetres < CONFIRM_FULL_VISIBILITY_M) {
            return CONFIRM_MAX;
        }
        return visibilityMetres < CONFIRM_HAZE_VISIBILITY_M ? CONFIRM_MAX / 2 : 0.0;
    }

    /** Linear 0..1 fraction of {@code value} over {@code full}, clamped. */
    private static double ramp(double value, double full) {
        return Math.max(0.0, Math.min(1.0, value / full));
    }
}
