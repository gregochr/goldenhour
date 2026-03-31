package com.gregochr.goldenhour.model;

/**
 * Risk classification when storm surge combines with astronomical tide.
 *
 * <p>Based on NTSLF skew surge analysis and Horsburgh &amp; Wilson (2007) tide-surge
 * interaction research. Risk increases when surge aligns with spring/king tides,
 * but note that tide-surge interaction means the surge peak typically occurs
 * 3–5 hours before high water (reducing the combined extreme).
 */
public enum TideRiskLevel {

    /**
     * Negligible surge (&lt;0.10m) or offshore wind negating setup.
     */
    NONE,

    /**
     * Minor surge (0.10–0.30m). Visible at high tide but not dramatic.
     * Typical of moderate low pressure with light onshore winds.
     */
    LOW,

    /**
     * Notable surge (0.30–0.60m). Photographic interest — water noticeably
     * higher than predicted. Combines well with spring tides for dramatic shots.
     */
    MODERATE,

    /**
     * Significant surge (&gt;0.60m). Storm conditions likely.
     * King/spring tide + high surge = rare dramatic opportunity but
     * exercise caution at exposed coastal locations.
     */
    HIGH
}
