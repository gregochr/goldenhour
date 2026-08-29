package com.gregochr.goldenhour.service.comingup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code coming-up.scoring} section of {@code application.yml}.
 *
 * <p>Every number the surprise model (plan §3, D4) uses lives here — {@link SurpriseScore} is pure
 * maths over whatever these knobs supply, and the UI never hardcodes a bit value. The band edges
 * were <b>set by P5's pre-ship census</b> ({@code ComingUpAnnualBadgeCensusTest}, plan §11.13),
 * which runs a synthetic year of the assembled feed on every build; the two deterministic rarity
 * gaps that were candidates for a census-time re-derivation (supermoon, eclipse) were measured
 * and deliberately kept as documented estimates — each field's own Javadoc records why.
 */
@Component
@ConfigurationProperties(prefix = "coming-up.scoring")
@Getter
@Setter
public class ComingUpScoringProperties {

    /** The surprise-score band edges. */
    private Bands bands = new Bands();

    /** Magnitude reference points and the cold-start floor. */
    private Magnitude magnitude = new Magnitude();

    /** Mean inter-arrival gap, in days, per deterministic (ephemeris) topic. */
    private Rarity rarity = new Rarity();

    /** Recurrent-topic knobs shared by dust and inversion (plan P4, D4). */
    private RecurrentTopics recurrent = new RecurrentTopics();

    /** Each standing condition's authored cadence word (plan §7, §11.16). */
    private Cadence cadence = new Cadence();

    /**
     * The peak gate's own bound, in minutes (plan D4/D5) — a third alignment window beside the
     * existing tide-in-light (60) and surge-peak-in-light (90) constants, scoped to
     * standing-condition peaks only. Not read arithmetically by {@code ComingUpConditionsBuilder
     * #passesPeakGate} yet: no forward candidate carries a clock time to compare against a light
     * window's edges, so the v1 proxy is SUNRISE/SUNSET typing — every survivor row keyed to
     * either is already inside a light window by construction (D5: "in v1 the gate cannot fail").
     * This is the documented bound that proxy stands in for, routed through config rather than a
     * hardcoded literal, ready for P7's {@code landed_on_window} to make it a real comparison.
     */
    private int peakLightWindowMinutes = 90;

    /**
     * The surprise-score band edges (plan §13), lower-inclusive: a score of exactly {@link #list}
     * already clears the "in the list" band.
     *
     * <p><b>Set by P5's census</b> ({@code ComingUpAnnualBadgeCensusTest}, plan D4/§11.13), not
     * placeholders any more: over a synthetic year of daily feed assemblies the v1 inventory is a
     * discrete rarity ladder (supermoon 6.9 · solar turning point 8.5 · shower/NLC boundary 9.5 ·
     * eclipse 11.6 bits, every magnitude at the 1.0 default), so the edges pick rungs.
     * {@code announce} at 7.5 admits everything above the supermoon rung — 11 badge arrivals a
     * year against the design's "roughly 10". {@code interrupt} moved 9.5 → 10.0: at 9.5 every
     * annual-rate topic sat exactly on the interrupt contour (7 interrupts/year against the
     * design's "one or two"); at 10.0 only the eclipse clears it (1/year), and a mature tide-run
     * magnitude can still reach it at roughly the once-in-200-runs mark. The census test re-runs
     * the year on every build, so a topic addition or rarity retune fails there rather than
     * silently shifting the rate.
     */
    @Getter
    @Setter
    public static class Bands {

        /** The "in the list" band's lower edge, in bits. */
        private double list = 5.0;

        /** The "announced" band's lower edge, in bits. */
        private double announce = 7.5;

        /** The "interrupt" band's lower edge, in bits — census-set, see the class Javadoc. */
        private double interrupt = 10.0;
    }

    /**
     * Magnitude reference points (README §3) and the cold-start floor.
     *
     * <p>{@code p90Rank}/{@code p95Rank}/{@code p97Rank} are the exceedance-probability cutoffs a
     * cold-start occurrence is bucketed against instead of trusting its own small-sample
     * percentile; {@code p90Bits}/{@code p95Bits}/{@code p97Bits} are the fixed reference values
     * assigned once it clears each cutoff ({@code -log2(rank)}, rounded to the README's own worked
     * figures) rather than the noisier value a thin sample would compute exactly.
     */
    @Getter
    @Setter
    public static class Magnitude {

        /** Magnitude assigned at or below the median — no distribution says otherwise. */
        private double medianBits = 1.0;

        /** Exceedance-probability cutoff for the p90 bucket. */
        private double p90Rank = 0.10;

        /** Reference magnitude assigned at the p90 bucket. */
        private double p90Bits = 3.3;

        /** Exceedance-probability cutoff for the p95 bucket. */
        private double p95Rank = 0.05;

        /** Reference magnitude assigned at the p95 bucket. */
        private double p95Bits = 4.3;

        /** Exceedance-probability cutoff for the p97 bucket. */
        private double p97Rank = 0.03;

        /** Reference magnitude assigned at the p97 bucket. */
        private double p97Bits = 5.1;

        /**
         * Below this many stored observations, a magnitude claim is bucketed rather than computed
         * exactly (plan D4) — "assume high rarity, list it, do not badge".
         */
        private int coldStartMinObservations = 60;
    }

    /**
     * Mean inter-arrival gap, in days, for each deterministic (ephemeris) topic (plan D4). Rarity
     * is {@code log2(meanGapDays)}, computed by {@link SurpriseScore#rarity}, not stored as a
     * pre-computed bits value — the formula stays the only place the transform happens, so a config
     * edit here can never drift from what the number means.
     */
    @Getter
    @Setter
    public static class Rarity {

        /**
         * Spring tides recur every 14.8 days — half the mean synodic month, "the rate is exact
         * rather than estimated" (README §3). King runs share this rate: a king tide is a big
         * spring, not a rarer event of its own (plan D4) — its rarity is never the near-annual
         * perigee rate.
         */
        private double springTideMeanGapDays = 14.8;

        /** Each named shower peaks once a year, regardless of how many other showers also do. */
        private double meteorShowerMeanGapDays = 365.25;

        /** Two equinoxes and two solstices a year — each turning point recurs twice yearly. */
        private double solarTurningPointMeanGapDays = 182.625;

        /** One noctilucent cloud season, once a year. */
        private double nlcSeasonMeanGapDays = 365.25;

        /**
         * A full moon within {@code SupermoonAlmanacSource}'s own perigee window — empirically a
         * little under every other lunar month, chosen so {@code log2(60)} ≈ the README's own
         * worked reference (~5.9 bits). P5's census measured the ephemeris-derived alternative
         * (~3–4 supermoons a year → a ~91–122-day gap → 7.5–7.9 bits with the default magnitude)
         * and deliberately kept this figure: the measured rate lands supermoons on the announce
         * contour's knife edge and pushes the badge rate to ~14/year, the wrong side of the
         * design's target, so re-deriving it is a re-censusing decision, not a constant swap —
         * {@code ComingUpAnnualBadgeCensusTest.supermoonsArriveWithoutBadging} is the tripwire.
         */
        private double supermoonMeanGapDays = 60.0;

        /**
         * Deliberately NOT the catalogue-derived figure. The five catalogued UK-visible eclipses
         * (2026–2030) average a 347-day gap — but that is four gaps from an unusually dense run,
         * and adopting it (8.4 + 1.0 = 9.4 bits) would drop eclipses below the annual-rate topics
         * and leave the interrupt band structurally unreachable by anything (0/year against the
         * design's "one or two"). Kept sized past the README's "eclipse ≥ 10 bits" note; revisit
         * with a long-run visibility catalogue, not a 4-gap sample (P5 census, plan §11.13).
         */
        private double eclipseMeanGapDays = 1500.0;
    }

    /**
     * Knobs for the standing-conditions strip's two recurrent/persistent topics — Saharan dust and
     * valley inversions (plan §7 P4, D4). Both keep magnitude on a config-defined bucketed mapping
     * forever at first ship (never a real distribution — see {@code ComingUpConditionsBuilder}'s
     * class Javadoc), so {@link Dust#magnitudeAboveBits}/{@link Inversion#magnitudeAboveBits} are
     * the fixed value an occurrence gets once it clears its own threshold, and
     * {@link SurpriseScore#DEFAULT_MAGNITUDE_BITS} otherwise.
     */
    @Getter
    @Setter
    public static class RecurrentTopics {

        /**
         * Minimum arrivals in the trailing 60-day window before a topic's rate is trusted over the
         * config fallback (README §3's evidentiary bar; plan D4).
         */
        private int evidentiaryBarArrivals = 5;

        /** Length of the trailing window arrivals are counted over, in days (plan D4/§7). */
        private int trailingWindowDays = 60;

        /** Dust-specific knobs. */
        private Dust dust = new Dust();

        /** Inversion-specific knobs. */
        private Inversion inversion = new Inversion();
    }

    /**
     * Saharan dust knobs. Rarity is upgraded to an observed rate once the evidentiary bar is met
     * (plan D4); {@link #fallbackMeanGapDays} is the config floor below it. Magnitude stays
     * interim/config forever: {@link #magnitudeThresholdAod} is the aerosol optical depth an
     * occurrence must clear to be scored as a "big" plume rather than a routine one, matching the
     * scale {@code DustHotTopicStrategy}/{@code TopicDailyLogJob} already measure dust on (AOD),
     * not an invented 0–10 load figure with no reading behind it.
     */
    @Getter
    @Setter
    public static class Dust {

        /** Rarity fallback below the evidentiary bar — about a week between plumes. */
        private double fallbackMeanGapDays = 7.0;

        /** AOD at or above which a dust occurrence counts as magnitude-worthy (interim). */
        private double magnitudeThresholdAod = 0.5;

        /** Magnitude assigned once {@link #magnitudeThresholdAod} is cleared. */
        private double magnitudeAboveBits = 5.0;
    }

    /**
     * Valley inversion knobs. Rarity stays on {@link #fallbackMeanGapDays} unconditionally until
     * P7's {@code topic_daily_log} accrues an unbiased population (plan D4/§1) — never upgraded
     * from the survivor-biased {@code forecast_score} table. Magnitude reuses the same 0–10 scale
     * {@code InversionScoreCalculator}/{@code InversionHotTopicStrategy} already score on, so
     * {@link #magnitudeThresholdScore} defaults to the STRONG band's own floor.
     */
    @Getter
    @Setter
    public static class Inversion {

        /** Rarity fallback — a mild UK autumn/winter estimate, revisited once P7's log matures. */
        private double fallbackMeanGapDays = 4.0;

        /** Inversion score (0–10) at or above which an occurrence counts as magnitude-worthy. */
        private double magnitudeThresholdScore = 9.0;

        /** Magnitude assigned once {@link #magnitudeThresholdScore} is cleared. */
        private double magnitudeAboveBits = 5.0;
    }

    /**
     * Each standing condition's cadence word (design README §2: {@code persistent} — present most
     * days; {@code recurrent} — arrives in bursts on a stable rate; {@code deterministic} — on an
     * ephemeris), authored here rather than derived (plan §7/§11.16) — there is no presence series
     * to derive it from until P7's {@code topic_daily_log} matures.
     */
    @Getter
    @Setter
    public static class Cadence {

        /** Coastal tides recur on a fixed ephemeris (plan D4: 14.8-day spring rate). */
        private String coastalTides = "deterministic";

        /** Saharan dust arrives in bursts (plan D4: trailing-60-day arrival count). */
        private String dust = "recurrent";

        /** Valley inversions are present on most qualifying mornings (plan D4). */
        private String inversion = "persistent";
    }
}
