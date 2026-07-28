package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.Verdict;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Verdicts for locations under a canopy, where the sky is not in the frame.
 *
 * <p><strong>Why this is a separate class rather than a branch in
 * {@link BriefingVerdictEvaluator}.</strong> The two subjects do not merely weigh conditions
 * differently — they invert. Four of the sky evaluator's rules fire on exactly the morning a
 * woodland photographer sets an alarm for:
 *
 * <table>
 *   <caption>The same morning, judged twice</caption>
 *   <tr><th>Condition</th><th>Sky verdict</th><th>Woodland reading</th></tr>
 *   <tr><td>low cloud &gt; 80%</td><td>STANDDOWN "Heavy cloud"</td><td>even light, no blown highlights</td></tr>
 *   <tr><td>mid cloud &ge; 80%</td><td>STANDDOWN "Overcast"</td><td>the softbox you wanted</td></tr>
 *   <tr><td>visibility &lt; 5 km</td><td>STANDDOWN "Poor visibility"</td><td>mist — the reason to be there</td></tr>
 *   <tr><td>humidity &gt; 90%</td><td>flag "Mist risk"</td><td>mist <em>hoped for</em>, not risked</td></tr>
 * </table>
 *
 * <p>Putting woodland polarity inside the class that defines sky polarity would mean every future
 * verdict rule has to be reasoned about twice with opposite signs, in one place, forever. Keeping
 * them apart costs a second class and buys a single sign per file.
 *
 * <p><strong>What it deliberately does not do.</strong> It produces no rating and calls no model.
 * The thresholds below are photographic priors, not measurements — nothing in this project can yet
 * score them, because {@code actual_outcome} has no rows. They are therefore set to be *cheap to
 * be wrong about*: deterministic, free to run, and revertible with one migration. A woodland
 * subject rated by Claude is a separate, later decision that should wait for evidence.
 */
@Service
public class WoodlandVerdictEvaluator {

    /** Combined low+mid cover at or above which light is properly diffuse — the ideal. */
    static final int DIFFUSE_COVER_GOOD = 70;

    /**
     * Below this combined cover, with the sun up, the canopy dapples. The 30–70% band is the worst
     * of both worlds — broken sun through leaves gives contrast no exposure can hold — so it reads
     * MARGINAL rather than GO, and clear skies read worse still.
     */
    static final int DIFFUSE_COVER_BROKEN = 30;

    /** Visibility below which mist is present and helping. Above ~10 km there is none. */
    static final int MIST_VISIBILITY_M = 5000;

    /**
     * Below this, mist is thick enough that nothing resolves — a down-weight, not a bonus. Fog
     * that hides the trees is not atmosphere, it is a white frame.
     */
    static final int MIST_TOO_THICK_M = 100;

    /** Gust speed (m/s) above which a wood is unsafe: falling branches. ~35 mph. */
    static final double UNSAFE_GUST_MS = 15.3;

    /**
     * Gust speed (m/s) above which long exposures and macro are off — the leaves will not sit
     * still. Roughly 16 km/h.
     */
    static final double MOTION_GUST_MS = 4.4;

    /** Precipitation (mm/h) above which the visit is unpleasant rather than merely damp. */
    static final BigDecimal HEAVY_RAIN_MM = new BigDecimal("1.0");

    /**
     * Measurements a woodland verdict turns on.
     *
     * @param lowCloudPercent  low cloud cover (%)
     * @param midCloudPercent  mid cloud cover (%)
     * @param visibilityMetres surface visibility (m) — the mist proxy
     * @param precipitationMm  precipitation at the slot (mm/h)
     * @param windSpeedMs      wind speed (m/s); a gust proxy until gusts are fetched
     */
    public record WoodlandMetrics(
            int lowCloudPercent,
            int midCloudPercent,
            int visibilityMetres,
            BigDecimal precipitationMm,
            BigDecimal windSpeedMs) {

        /** Combined low+mid cover, capped at 100 — high cirrus does not soften light at ground level. */
        int diffuseCover() {
            return Math.min(100, lowCloudPercent + midCloudPercent);
        }

        /** True when visibility indicates mist that is present and still resolving detail. */
        boolean hasWorkingMist() {
            return visibilityMetres < MIST_VISIBILITY_M && visibilityMetres >= MIST_TOO_THICK_M;
        }

        double windMs() {
            return windSpeedMs == null ? 0.0 : windSpeedMs.doubleValue();
        }
    }

    /**
     * Determines the verdict for a woodland slot.
     *
     * <p>Order matters: the safety veto is absolute and is applied before anything else, because a
     * wood in a gale is not a photographic judgement. Everything after that is about light.
     *
     * @param m the slot's measurements
     * @return GO, MARGINAL or STANDDOWN
     */
    public Verdict determineVerdict(WoodlandMetrics m) {
        // The one legitimate woodland stand-down, and the only rule here that outranks light.
        if (m.windMs() > UNSAFE_GUST_MS) {
            return Verdict.STANDDOWN;
        }
        if (m.precipitationMm != null && m.precipitationMm.compareTo(HEAVY_RAIN_MM) > 0) {
            return Verdict.STANDDOWN;
        }

        int cover = m.diffuseCover();

        // Thick fog: atmospheric in principle, but nothing resolves. Not a stand-down — a wood in
        // fog can still be worth it as it lifts — but never a GO.
        if (m.visibilityMetres < MIST_TOO_THICK_M) {
            return Verdict.MARGINAL;
        }

        // The good morning: diffuse light, and mist is a bonus rather than a requirement.
        if (cover >= DIFFUSE_COVER_GOOD) {
            return Verdict.GO;
        }

        // Mist can carry a partly-clear morning on its own — light through it is the subject.
        if (m.hasWorkingMist()) {
            return Verdict.GO;
        }

        // Broken cloud with sun behind it is the dapple zone: the hardest light a wood gets.
        if (cover < DIFFUSE_COVER_BROKEN) {
            return Verdict.STANDDOWN;
        }
        return Verdict.MARGINAL;
    }

    /**
     * Human-readable flags describing why a woodland slot reads as it does.
     *
     * <p>Worded from the subject's point of view, which is the whole point: the sky evaluator would
     * describe the same morning as "Heavy cloud" and "Poor visibility", and a photographer reading
     * those would stay in bed.
     *
     * @param m the slot's measurements
     * @return flag labels, most decisive first; empty when nothing is notable
     */
    public List<String> buildFlags(WoodlandMetrics m) {
        List<String> flags = new ArrayList<>();
        if (m.windMs() > UNSAFE_GUST_MS) {
            flags.add("Unsafe — falling branches");
            return flags;
        }
        if (m.visibilityMetres < MIST_TOO_THICK_M) {
            flags.add("Fog too thick");
        } else if (m.hasWorkingMist()) {
            flags.add("Mist");
        }
        if (m.diffuseCover() >= DIFFUSE_COVER_GOOD) {
            flags.add("Soft even light");
        } else if (m.diffuseCover() < DIFFUSE_COVER_BROKEN) {
            flags.add("Harsh dapple");
        }
        if (m.windMs() > MOTION_GUST_MS) {
            flags.add("Breezy — long exposures off");
        }
        if (m.precipitationMm != null && m.precipitationMm.compareTo(HEAVY_RAIN_MM) > 0) {
            flags.add("Heavy rain");
        }
        return flags;
    }

    /**
     * The reason a woodland slot stood down, for the briefing's stand-down line.
     *
     * @param m the slot's measurements
     * @return a short reason, or {@code null} when the slot did not stand down
     */
    public String deriveStanddownReason(WoodlandMetrics m) {
        if (m.windMs() > UNSAFE_GUST_MS) {
            return "Unsafe — falling branches";
        }
        if (m.precipitationMm != null && m.precipitationMm.compareTo(HEAVY_RAIN_MM) > 0) {
            return "Heavy rain";
        }
        if (m.visibilityMetres >= MIST_TOO_THICK_M
                && m.diffuseCover() < DIFFUSE_COVER_BROKEN && !m.hasWorkingMist()) {
            return "Harsh dapple — no cloud to soften it";
        }
        return null;
    }
}
