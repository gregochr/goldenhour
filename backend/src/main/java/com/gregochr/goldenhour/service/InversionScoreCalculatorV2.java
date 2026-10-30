package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes the v2 cloud inversion score (0-10) from a valley-floor profile (inversion-v2-plan Phase 1b).
 *
 * <p>The old {@link InversionScoreCalculator} compares the 925 hPa temperature with the viewpoint cell's own
 * 2 m temperature, which on a fell top is the fell, not the valley the cloud sits in. This calculator walks
 * the temperature profile UP from the valley floor and asks where the warm layer starts and ends and whether
 * the viewpoint stands above it. It is pure: the inputs are the profile slice carried on
 * {@link AtmosphericData#inversionV2()}, the viewpoint cell's own cloud and visibility, and the viewpoint
 * height.
 *
 * <p><b>The walk.</b> Per hour: start at (h0, T0), the valley floor height and the valley cell's 2 m
 * temperature, then take the pressure levels whose geopotential height is at least
 * {@value #MIN_LEVEL_ABOVE_FLOOR_M} m above h0 (the summit trap: below that a level is under the ground),
 * skipping any level with a null reading. The profile is kept if at least one level is valid. The first
 * point that is warmer than the one below it starts a rise; the rise ends at the first local maximum.
 * Equal adjacent temperatures are not a rise. Strength is the top's temperature minus the base's, rounded
 * to 0.1 degC.
 * <ul>
 *   <li><b>Surface-based:</b> the rise starts at the valley floor and its top is the FIRST valid level (a
 *       shallow radiation-fog layer). Base is h0.</li>
 *   <li><b>Elevated:</b> anything else. A rise that starts at the floor and keeps going past the first level
 *       is elevated with base = the coldest valid LEVEL below the top; otherwise base is the last level
 *       before the rise.</li>
 * </ul>
 *
 * <p><b>Hours examined</b> are anchored to the event SLOT: for a sunrise the slot and the four hours before
 * it, for a sunset the slot and the two hours after it. The hour with the strongest layer supplies the
 * layer; a tie goes to the hour nearest the slot. Surface terms and fog evidence come from the valley cell at
 * the event slot or its window as stated below, whichever hour supplied the layer.
 *
 * <p><b>Clearance</b> (E is the viewpoint height):
 * <ul>
 *   <li>Elevated: E below the base is inside (cap {@value InversionScoreCalculator#VETOED_CEILING}); E from
 *       the base up to the next level above it is the edge (cap
 *       {@value InversionScoreCalculator#MARGINAL_CEILING}); E at or above that level is clear.</li>
 *   <li>Surface-based: needs fog evidence in the same window (valley visibility under
 *       {@value #FOG_VISIBILITY_METRES} m at any hour, or a gap of at most {@value #FOG_GAP_CELSIUS} degC with
 *       wind of at most {@value #FOG_CALM_WIND_MS} m/s in the SAME hour). With it, E at least
 *       {@value #SURFACE_EDGE_MIN_ABOVE_FLOOR_M} m above h0 is the edge (cap 8), else cap 6. Without it,
 *       cap 6.</li>
 *   <li>Viewpoint in cloud: the viewpoint's own cell at the event slot with low cloud of at least
 *       {@value #VIEWPOINT_CLOUD_PERCENT} percent AND visibility under {@value #FOG_VISIBILITY_METRES} m caps
 *       at 6.</li>
 * </ul>
 *
 * <p><b>Surface terms</b> come from the valley cell at the event slot: moisture
 * ({@link MoistureScoring#gapScore}, called and not changed), wind 0-3 (the old steps), and sea evidence 0-2
 * (2 for valley low cloud of at least {@value #SEA_LOW_CLOUD_PERCENT} percent at the event or night-side
 * visibility under {@value #DENSE_FOG_VISIBILITY_METRES} m; 1 for night-side visibility under
 * {@value #FOG_VISIBILITY_METRES} m; else 0). A layer of at least
 * {@value InversionScoreCalculator#STRONG_REVERSAL_CELSIUS} degC allows 10, a weaker one caps at 8, no layer
 * caps at 6.
 *
 * <p>Every term and cap is a whole number and the score is a {@code Math.min} of them, so the score is whole
 * and the prompt's rounding and a reader's raw {@code >= 9} cannot disagree.
 *
 * <p><b>Consequence for the roster.</b> An elevated "clear" needs E at or above the level above the base,
 * which is never below about 640 m, so no site below 600 m can exceed 8 here; the STRONG band comes only from
 * summits. That is intended: a low site cannot see a deep sea.
 *
 * <p><b>Known fragile thresholds</b> (priors from four mornings, not tuned): the {@value #FOG_VISIBILITY_METRES}
 * m visibility test (Thirlmere's 4,960 m sits 40 m under the line); the 975 hPa level
 * height against a 451 m viewpoint such as Cat Bells (the level sat at 432-471 m, so whether the level is
 * under or over the viewpoint flips with the model run); and Blencathra at 868 m against the 925 hPa level
 * at 861-910 m (inside the edge band one run and clear the next).
 */
public final class InversionScoreCalculatorV2 {

    /** A level must sit at least this far above the valley floor to count (the summit trap). */
    static final double MIN_LEVEL_ABOVE_FLOOR_M = 50.0;

    /** Visibility in metres under which valley fog is taken as present. */
    static final double FOG_VISIBILITY_METRES = 5000.0;

    /** Visibility in metres under which valley fog is taken as dense, the strongest sea evidence. */
    static final double DENSE_FOG_VISIBILITY_METRES = 1000.0;

    /** Valley low cloud percentage at the event slot that counts as a sea in place. */
    static final int SEA_LOW_CLOUD_PERCENT = 50;

    /** Temperature-dew point gap in degC at or under which a calm valley hour counts as fog evidence. */
    static final double FOG_GAP_CELSIUS = 1.0;

    /** Wind speed in m/s at or under which a saturated valley hour counts as fog evidence. */
    static final double FOG_CALM_WIND_MS = 2.0;

    /** A surface-based layer lets the viewpoint stand at its edge only this far above the floor. */
    static final double SURFACE_EDGE_MIN_ABOVE_FLOOR_M = 100.0;

    /** Viewpoint-cell low cloud percentage at or over which, with poor visibility, the viewpoint is in cloud. */
    static final int VIEWPOINT_CLOUD_PERCENT = 80;

    /** Wind speed in m/s at or under which the stability term scores 3. */
    static final double WIND_STILL_MS = 1.5;

    /** Wind speed in m/s at or under which the stability term scores 2. */
    static final double WIND_LIGHT_MS = 3.0;

    /** Wind speed in m/s at or under which the stability term scores 1. */
    static final double WIND_BREEZE_MS = 5.0;

    /** Top of the score range, allowed only by a layer of at least the strong threshold. */
    static final double MAX_SCORE = 10.0;

    /** Sea evidence points for dense fog or an established low cloud deck. */
    static final double SEA_FULL = 2.0;

    /** Sea evidence points for fog in the valley. */
    static final double SEA_PARTIAL = 1.0;

    /** Wind points at the stillest step. */
    static final double WIND_FULL = 3.0;

    /** Wind points at the light step. */
    static final double WIND_LIGHT = 2.0;

    /** Wind points at the breezy step. */
    static final double WIND_BREEZY = 1.0;

    private static final double TENTH = 10.0;

    private InversionScoreCalculatorV2() {
    }

    /**
     * One point on the walk.
     */
    private record Point(double height, double temperature) {
    }

    /**
     * The warm layer found at one hour.
     */
    private record Layer(InversionProfile.Hour hour, InversionRegime regime, Point base, Point top,
            double strength, Double nextLevelHeight) {
    }

    /**
     * Scores the slot from the profile carried on {@code data}.
     *
     * @param data            the slot's atmospheric data, carrying the v2 profile and the viewpoint cell's
     *                        cloud and visibility
     * @param elevationMetres the viewpoint elevation E in metres
     * @return the result with {@code v2Raw} set, or null when there is no usable profile or the event slot
     *         lacks the valley readings the surface terms need
     */
    public static InversionV2Result calculate(AtmosphericData data, Integer elevationMetres) {
        if (data == null || elevationMetres == null || data.inversionV2() == null
                || data.inversionV2().profile() == null) {
            return null;
        }
        InversionProfile profile = data.inversionV2().profile();
        InversionProfile.Hour slot = profile.slotHour();
        if (slot == null || data.targetType() == null || slot.temperatureCelsius() == null
                || slot.dewPointCelsius() == null || slot.windSpeedMs() == null) {
            return null;
        }
        int h0 = profile.valleyFloorMetres();

        boolean usable = false;
        Layer best = null;
        for (InversionProfile.Hour hour : profile.hours()) {
            Layer layer = walk(hour, h0);
            if (layer == null) {
                continue;
            }
            usable = true;
            if (layer.regime() != InversionRegime.NONE && isBetter(layer, best, profile)) {
                best = layer;
            }
        }
        if (!usable) {
            return null;
        }

        double moisture = MoistureScoring.gapScore(slot.temperatureCelsius() - slot.dewPointCelsius());
        double wind = windScore(slot.windSpeedMs());
        Double minVisibility = minVisibility(profile);
        double sea = seaScore(slot, minVisibility);
        double score = moisture + wind + sea;

        List<String> caps = new ArrayList<>();
        InversionClearance clearance;
        if (best == null) {
            clearance = InversionClearance.NO_LAYER;
            score = applyCap(score, InversionScoreCalculator.VETOED_CEILING, "none", caps);
        } else {
            clearance = clearanceFor(best, elevationMetres, h0, profile);
            score = applyClearanceCap(score, clearance, caps);
            if (best.strength() < InversionScoreCalculator.STRONG_REVERSAL_CELSIUS) {
                score = applyCap(score, InversionScoreCalculator.MARGINAL_CEILING, "weak", caps);
            }
        }
        if (viewpointInCloud(data)) {
            score = applyCap(score, InversionScoreCalculator.VETOED_CEILING, "in-cloud", caps);
        }
        score = Math.min(score, MAX_SCORE);

        return new InversionV2Result(profile, null, score, null,
                best == null ? InversionRegime.NONE : best.regime(),
                best == null ? null : best.base().height(),
                best == null ? null : best.top().height(),
                best == null ? null : best.strength(),
                h0, elevationMetres, clearance, caps.isEmpty() ? "-" : String.join(",", caps), false);
    }

    /**
     * Walks one hour from the valley floor upward.
     *
     * @return the layer (regime {@link InversionRegime#NONE} when the profile never rises), or null when the
     *         hour has no 2 m temperature or no valid level
     */
    private static Layer walk(InversionProfile.Hour hour, int h0) {
        if (hour.temperatureCelsius() == null) {
            return null;
        }
        List<Point> points = new ArrayList<>();
        points.add(new Point(h0, hour.temperatureCelsius()));
        hour.levels().stream()
                .filter(l -> l.temperatureCelsius() != null && l.heightMetres() != null
                        && l.heightMetres() >= h0 + MIN_LEVEL_ABOVE_FLOOR_M)
                .sorted(Comparator.comparingDouble(InversionProfile.Level::heightMetres))
                .forEach(l -> points.add(new Point(l.heightMetres(), l.temperatureCelsius())));
        if (points.size() < 2) {
            return null;
        }

        int rise = -1;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).temperature() > points.get(i - 1).temperature()) {
                rise = i;
                break;
            }
        }
        if (rise < 0) {
            return new Layer(hour, InversionRegime.NONE, null, null, 0.0, null);
        }
        int top = rise;
        while (top + 1 < points.size() && points.get(top + 1).temperature() > points.get(top).temperature()) {
            top++;
        }

        int baseIndex;
        InversionRegime regime;
        if (rise == 1 && top == 1) {
            regime = InversionRegime.SURFACE_BASED;
            baseIndex = 0;
        } else {
            regime = InversionRegime.ELEVATED;
            baseIndex = rise == 1 ? coldestLevelBelow(points, top) : rise - 1;
        }
        Point base = points.get(baseIndex);
        Point topPoint = points.get(top);
        double strength = Math.round((topPoint.temperature() - base.temperature()) * TENTH) / TENTH;
        Double next = baseIndex + 1 < points.size() ? points.get(baseIndex + 1).height() : null;
        return new Layer(hour, regime, base, topPoint, strength, next);
    }

    /** The coldest level (never the valley floor point) below the top; the lowest on a tie. */
    private static int coldestLevelBelow(List<Point> points, int top) {
        int coldest = 1;
        for (int i = 2; i < top; i++) {
            if (points.get(i).temperature() < points.get(coldest).temperature()) {
                coldest = i;
            }
        }
        return coldest;
    }

    /** Strongest layer wins; a tie goes to the hour nearest the event slot. */
    private static boolean isBetter(Layer candidate, Layer current, InversionProfile profile) {
        if (current == null) {
            return true;
        }
        int byStrength = Double.compare(candidate.strength(), current.strength());
        if (byStrength != 0) {
            return byStrength > 0;
        }
        return distanceToSlot(candidate, profile).compareTo(distanceToSlot(current, profile)) < 0;
    }

    private static Duration distanceToSlot(Layer layer, InversionProfile profile) {
        return Duration.between(layer.hour().time(), profile.slotTime()).abs();
    }

    private static InversionClearance clearanceFor(Layer layer, int elevation, int h0, InversionProfile profile) {
        if (layer.regime() == InversionRegime.SURFACE_BASED) {
            if (!fogEvidence(profile)) {
                return InversionClearance.UNCONFIRMED;
            }
            return elevation >= h0 + SURFACE_EDGE_MIN_ABOVE_FLOOR_M
                    ? InversionClearance.EDGE : InversionClearance.INSIDE;
        }
        if (elevation < layer.base().height()) {
            return InversionClearance.INSIDE;
        }
        if (layer.nextLevelHeight() != null && elevation >= layer.nextLevelHeight()) {
            return InversionClearance.CLEAR;
        }
        return InversionClearance.EDGE;
    }

    private static double applyClearanceCap(double score, InversionClearance clearance, List<String> caps) {
        return switch (clearance) {
            case INSIDE -> applyCap(score, InversionScoreCalculator.VETOED_CEILING, "inside", caps);
            case UNCONFIRMED -> applyCap(score, InversionScoreCalculator.VETOED_CEILING, "unconfirmed", caps);
            case EDGE -> applyCap(score, InversionScoreCalculator.MARGINAL_CEILING, "edge", caps);
            default -> score;
        };
    }

    private static double applyCap(double score, double cap, String name, List<String> caps) {
        caps.add(name + "(" + (int) cap + ")");
        return Math.min(score, cap);
    }

    /** Valley fog in the window: poor visibility at any hour, or a saturated calm valley in one hour. */
    private static boolean fogEvidence(InversionProfile profile) {
        Double min = minVisibility(profile);
        if (min != null && min < FOG_VISIBILITY_METRES) {
            return true;
        }
        return profile.hours().stream().anyMatch(h -> h.temperatureCelsius() != null
                && h.dewPointCelsius() != null && h.windSpeedMs() != null
                && h.temperatureCelsius() - h.dewPointCelsius() <= FOG_GAP_CELSIUS
                && h.windSpeedMs() <= FOG_CALM_WIND_MS);
    }

    private static Double minVisibility(InversionProfile profile) {
        return profile.hours().stream()
                .map(InversionProfile.Hour::visibilityMetres)
                .filter(v -> v != null)
                .min(Double::compare)
                .orElse(null);
    }

    private static double seaScore(InversionProfile.Hour slot, Double minVisibility) {
        boolean lowCloud = slot.lowCloudPercent() != null && slot.lowCloudPercent() >= SEA_LOW_CLOUD_PERCENT;
        if (lowCloud || (minVisibility != null && minVisibility < DENSE_FOG_VISIBILITY_METRES)) {
            return SEA_FULL;
        }
        if (minVisibility != null && minVisibility < FOG_VISIBILITY_METRES) {
            return SEA_PARTIAL;
        }
        return 0.0;
    }

    private static double windScore(double windSpeed) {
        if (windSpeed <= WIND_STILL_MS) {
            return WIND_FULL;
        }
        if (windSpeed <= WIND_LIGHT_MS) {
            return WIND_LIGHT;
        }
        if (windSpeed <= WIND_BREEZE_MS) {
            return WIND_BREEZY;
        }
        return 0.0;
    }

    private static boolean viewpointInCloud(AtmosphericData data) {
        return data.cloud() != null && data.weather() != null
                && data.cloud().lowCloudPercent() >= VIEWPOINT_CLOUD_PERCENT
                && data.weather().visibilityMetres() < FOG_VISIBILITY_METRES;
    }
}
