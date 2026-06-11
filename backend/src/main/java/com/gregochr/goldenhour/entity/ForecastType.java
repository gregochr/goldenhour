package com.gregochr.goldenhour.entity;

import java.util.Arrays;

/**
 * The component-score taxonomy — one constant per score PRODUCT the system
 * emits, not one per prompt. Mirrors the {@code forecast_type} lookup table
 * seeded in V107 exactly: {@code id}, {@code name()} (= the table's
 * {@code code}) and {@code scaleMax} are load-bearing pairs, guarded by
 * {@code ForecastTypeSeedDriftTest}. The enum is the source of truth in
 * code; the table exists for FK integrity and reporting joins.
 *
 * <p>Scale semantics:
 * <ul>
 *   <li>1–5 types ({@link #SKY}, {@link #TIDAL}, {@link #BLUEBELL}) are
 *       combiner peers — the values {@code RatingCombiner} averages into
 *       the headline rating.</li>
 *   <li>0–100 types ({@link #FIERY_SKY}, {@link #GOLDEN_HOUR}) are display
 *       products with deliberately finer granularity. They are never
 *       combiner inputs.</li>
 * </ul>
 *
 * <p>{@link #SKY} stores the PRE-COMBINE sky visitor score; the combined
 * rating remains a serving-path product with no type row. AURORA and
 * INVERSION are deliberately absent — they fold in via their own future
 * work (a future type is one seed row + one constant + a writer). BASIC_*
 * tier variants are not types; their product decision is deferred to
 * Pass 4. See docs/engineering/forecast-score-schema-investigation.md.
 */
public enum ForecastType {

    /** Pre-combine sky visitor score from the standard Claude evaluation. */
    SKY(1L, "Sky Forecast", 5),

    /** Dramatic colour potential — premium display product. */
    FIERY_SKY(2L, "Fiery Sky Forecast", 100),

    /** Light quality / softness — premium display product. */
    GOLDEN_HOUR(3L, "Golden Hour Forecast", 100),

    /** Deterministic tide-alignment score from TideVisitor — no Claude call. */
    TIDAL(4L, "Tidal Forecast", 5),

    /** Bluebell conditions score — own prompt from Pass 3, seasonal. */
    BLUEBELL(5L, "Bluebell Forecast", 5);

    private final long id;
    private final String displayName;
    private final int scaleMax;

    ForecastType(long id, String displayName, int scaleMax) {
        this.id = id;
        this.displayName = displayName;
        this.scaleMax = scaleMax;
    }

    /**
     * @return the {@code forecast_type.id} this constant pairs with
     */
    public long getId() {
        return id;
    }

    /**
     * @return the {@code forecast_type.display_name} seed value
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return native scale ceiling; scores are 0..scaleMax (1..5 for rating peers)
     */
    public int getScaleMax() {
        return scaleMax;
    }

    /**
     * Resolves a constant from its lookup-table id.
     *
     * @param id the {@code forecast_type.id} value
     * @return the matching constant
     * @throws IllegalArgumentException if no constant carries the id
     */
    public static ForecastType fromId(long id) {
        return Arrays.stream(values())
                .filter(type -> type.id == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ForecastType with id " + id));
    }
}
