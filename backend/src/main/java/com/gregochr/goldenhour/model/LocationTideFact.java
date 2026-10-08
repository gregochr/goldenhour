package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One coastal location's tide for one window, served on {@link BriefingWindow#tideFacts()}.
 *
 * <p><b>Why it exists.</b> The same facts ride {@link BriefingSlot#tide()}, but slots are the
 * <em>scored</em> population: {@code BriefingHonestyFilter} empties a zero-coverage region's slot
 * list, and zero coverage is the designed state of every window Gate 4 does not score and of every
 * travel day. Tide comes from stored tide tables and has nothing to do with Claude, so it must not
 * live inside a structure whose visibility depends on Claude coverage. See
 * {@code docs/engineering/window-tide-facts-plan.md}.
 *
 * <p><b>Derived at serve time and never persisted</b>, like the window that carries it. The only
 * construction path outside tests is {@link #from(BriefingSlot)}, a pure reshape of the slot's own
 * {@link BriefingSlot.TideInfo}; nothing here is re-derived, so the two cannot disagree. Every
 * component other than the two identity fields maps to a same-named {@code TideInfo} accessor, and
 * a reflection test keeps it so. Only fields with a named client reader are carried: the size and
 * lunar fields stay on the slot.
 *
 * @param locationId               the location's id, or null for a legacy slot written before ids
 * @param locationName             the location's name, the join fallback when the id is absent
 * @param tideState                the served tide state at the window; never null on a fact
 * @param tideAligned              whether the state is one the location wants
 * @param tideAlignmentQuality     how well the state fits what the location wants, or null
 * @param tideOnTheLight           whether an extreme lands on the light, or null
 * @param nearestSolarOffsetPhrase the offset of the nearest extreme from the light, in words, or null
 * @param tideLevel                the tide level at the light as a fraction, or null
 * @param tideDirection            the tide direction at the light, or null
 * @param tideHeight               the tide height band at the light, or null
 * @param tideShortfall            the shortfall direction when the wanted water is missed, or null
 * @param tideFitPhrase            the served fit sentence, or null
 */
public record LocationTideFact(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long locationId,
        String locationName,
        String tideState,
        boolean tideAligned,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double tideAlignmentQuality,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean tideOnTheLight,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nearestSolarOffsetPhrase,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double tideLevel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tideDirection,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tideHeight,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tideShortfall,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tideFitPhrase) {

    /**
     * Reshapes a slot's tide into a fact, copying every field verbatim.
     *
     * @param slot the slot to read (may be null)
     * @return the fact, or {@code null} when the slot is null or carries no tide state (an inland
     *         or canopy location, or one whose tide could not be derived)
     */
    public static LocationTideFact from(BriefingSlot slot) {
        if (slot == null || slot.tide() == null || slot.tide().tideState() == null) {
            return null;
        }
        BriefingSlot.TideInfo t = slot.tide();
        return new LocationTideFact(slot.locationId(), slot.locationName(), t.tideState(),
                t.tideAligned(), t.tideAlignmentQuality(), t.tideOnTheLight(),
                t.nearestSolarOffsetPhrase(), t.tideLevel(), t.tideDirection(), t.tideHeight(),
                t.tideShortfall(), t.tideFitPhrase());
    }
}
