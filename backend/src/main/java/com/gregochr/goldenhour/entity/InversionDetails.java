package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cloud inversion columns of a forecast evaluation — Claude's inversion likelihood for
 * valley/lake locations. Both fields are null for ineligible locations, in which case
 * Hibernate materialises the whole embeddable as {@code null} on read (use
 * {@link #orEmpty(InversionDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InversionDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final InversionDetails EMPTY = new InversionDetails();

    /** Cloud inversion score returned by Claude (0–10). */
    @Column(name = "inversion_score")
    private Integer score;

    /** Cloud inversion potential classification (NONE, MODERATE, STRONG). */
    @Column(name = "inversion_potential", length = 10)
    private String potential;

    /**
     * Builds the embeddable from Claude's inversion outputs, gated on the location being
     * inversion-eligible (a non-null pipeline inversion score).
     *
     * @param eligible  whether the location carried an inversion score into the evaluation
     * @param score     Claude's inversion score (0–10), or {@code null}
     * @param potential Claude's classification (NONE, MODERATE, STRONG), or {@code null}
     * @return the embeddable, or {@code null} when the location is ineligible
     */
    public static InversionDetails from(boolean eligible, Integer score, String potential) {
        if (!eligible) {
            return null;
        }
        return new InversionDetails(score, potential);
    }

    /**
     * Null-safe view for readers: an all-null instance when the row has no inversion data.
     *
     * @param inversion the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static InversionDetails orEmpty(InversionDetails inversion) {
        return inversion != null ? inversion : EMPTY;
    }
}
