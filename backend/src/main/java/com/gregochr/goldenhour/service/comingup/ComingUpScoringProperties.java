package com.gregochr.goldenhour.service.comingup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code coming-up.scoring} section of {@code application.yml}.
 *
 * <p>Every number the surprise model (plan §3, D4) uses lives here — {@link SurpriseScore} is pure
 * maths over whatever these knobs supply, and the UI never hardcodes a bit value. Everything below
 * is a <b>placeholder at ship</b> (plan §11.13): P5's pre-ship census, run over a synthetic year of
 * the assembled feed, re-sets {@link #bands} and the deterministic-type rarity gaps before the
 * badge goes live.
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

    /**
     * The surprise-score band edges (plan §13), lower-inclusive: a score of exactly {@link #list}
     * already clears the "in the list" band.
     */
    @Getter
    @Setter
    public static class Bands {

        /** The "in the list" band's lower edge, in bits. */
        private double list = 5.0;

        /** The "announced" band's lower edge, in bits. */
        private double announce = 7.5;

        /** The "interrupt" band's lower edge, in bits. */
        private double interrupt = 9.5;
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
         * worked reference (~5.9 bits), pending a catalogue-derived figure at P5's census.
         */
        private double supermoonMeanGapDays = 60.0;

        /**
         * UK-visible catalogued eclipses are rare enough that the exact figure barely matters this
         * side of P5's census (only five are catalogued at all, 2026–2030) — placeholder sized to
         * land comfortably past the README's "eclipse from EclipseCatalog spacing (≥10 [bits])"
         * note, pending a real catalogue-derived mean gap.
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
}
