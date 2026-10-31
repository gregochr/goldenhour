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

    /** Recurrent-topic knobs: the trailing window the slot-atmosphere retention is sized from. */
    private RecurrentTopics recurrent = new RecurrentTopics();

    /** Near-miss "shoulder" days drawn beside a tide run (tide-source-integrity-plan.md P3). */
    private Shoulder shoulder = new Shoulder();

    /**
     * Which non-qualifying days beside a spring or king run are shown as outlined shoulder bars.
     */
    @Getter
    @Setter
    public static class Shoulder {

        /**
         * Lowest roster-best ratio ({@code 1.25 x dayMaxHigh / springThreshold}) for a day to count as
         * a shoulder; spring itself starts above 1.25. A starting calibration, not a measured fact.
         */
        private double minRatio = 1.20;

        /** The most shoulder days drawn on each side of a run. */
        private int maxPerSide = 1;
    }

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
     * design's "one or two"); at 10.0 only the eclipse clears it (1/year). The census does not
     * exercise a mature tide-run magnitude at all — {@code TideAlmanacSource} is not wired into
     * it (see the census's own class Javadoc) — but the arithmetic is checkable directly:
     * {@link SurpriseScore#magnitudeFromHistory} scores an unmatched-high run at
     * {@code log2(n+1)} bits, so a record run needs a {@code n ≥ 69}-observation history to clear
     * interrupt (9 past {@link Magnitude#coldStartMinObservations}, roughly 4–5 months of accrued
     * spring/king runs once a port first matures at n = 60) — not the coarser "once-in-200-runs"
     * figure an earlier draft of this note gave, which conflated this exact-percentile mechanism
     * with the bucketed cold-start one. The census test re-runs the year on every build, so a
     * topic addition or rarity retune fails there rather than silently shifting the rate; it does
     * NOT, however, re-check this tide-run figure, which is unvalidated once a real port matures —
     * see {@code ComingUpAnnualBadgeCensusTest}'s own Javadoc for the open follow-on.
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
        // Wording: this figure feeds the score only; the note never states it as a frequency
        // (ComingUpAssembler.markScoreNotes), because it is a guess, not a measured rate.
        private double eclipseMeanGapDays = 1500.0;

        /**
         * A documented long-run estimate for UK-visible umbral lunar eclipses, deliberately NOT the
         * mean of the six gaps between {@code LunarEclipseCatalog}'s seven seeded entries (March
         * 2025 – 2030): those gaps are 177, 355, 502, 354, 177 and 177 days, averaging ~290 days —
         * but that window is an unusually dense run (the same shape {@link #eclipseMeanGapDays}'s
         * own javadoc records for the solar catalogue's five-eclipse sample), and adopting the raw
         * mean (log2(290) + 1 ≈ 9.2 bits) would land a lunar eclipse on the announce band rather
         * than the interrupt one — the plan's own worked figure (plan {@code lunar-eclipse-plan.md}
         * §1 row 12) depends on {@code log2(900) + 1 ≈ 10.8} bits clearing interrupt. Lowering this
         * to match the seeded window's own mean is a re-censusing decision, not a constant edit —
         * the same treatment {@link #eclipseMeanGapDays} and {@link #supermoonMeanGapDays} already
         * get. The census year (1 Sep 2026 – 31 Aug 2027) holds three penumbral eclipses (2027 Feb
         * 20, 2027 Jul 18, 2027 Aug 17), none seeded (a penumbral shading raises no umbral topic at
         * all), which is why adding this constant does not move
         * {@code ComingUpAnnualBadgeCensusTest}'s pinned result.
         */
        private double lunarEclipseMeanGapDays = 900.0;
    }

    /**
     * Recurrent-topic knobs. Only the trailing window survives the Recurring conditions panel's
     * removal: {@code SlotAtmosphereCleanupJob} sizes the slot-atmosphere retention window from it.
     */
    @Getter
    @Setter
    public static class RecurrentTopics {

        /** Length of the trailing window the retention readers look back over, in days. */
        private int trailingWindowDays = 60;
    }
}
