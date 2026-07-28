package com.gregochr.goldenhour.entity;

/**
 * Photography type tags for a location.
 *
 * <p>A location can have multiple types simultaneously (e.g. a coastal cliff that is
 * both {@code SEASCAPE} and {@code LANDSCAPE}). Stored as a join table via
 * {@code @ElementCollection} on {@link LocationEntity}.
 */
public enum LocationType {

    /** Good for landscape and scenic photography. */
    LANDSCAPE,

    /** Good for wildlife and animal photography. */
    WILDLIFE,

    /** Good for seascape and coastal photography — tide data displayed prominently. */
    SEASCAPE,

    /** Good for waterfall photography — colour forecasts, no tidal data. */
    WATERFALL,

    /** Known bluebell site — scored for bluebell conditions during season (April–May). */
    BLUEBELL,

    /**
     * Under a canopy: the sky is not in the frame.
     *
     * <p>Structural and year-round, unlike {@link #BLUEBELL}, which is a seasonal subject that
     * happens to occur in woods. A site can be both (an enclosed bluebell wood), or one without
     * the other — Rannerdale Knotts is BLUEBELL on an open fell; a gorge is WOODLAND with no
     * bluebells. Keeping them apart is what lets a wood be forecast in October.
     *
     * <p>Routes verdicts to {@code WoodlandVerdictEvaluator} instead of the sky chain, because the
     * two invert: flat overcast is a stand-down for a sunset and the ideal for a canopy.
     */
    WOODLAND
}
