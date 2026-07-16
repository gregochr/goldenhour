package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.TriageReason;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Triage stand-down columns of a forecast evaluation. Both fields are null when the row was
 * scored by Claude rather than triaged, in which case Hibernate materialises the whole
 * embeddable as {@code null} on read (use {@link #orEmpty(TriageDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriageDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final TriageDetails EMPTY = new TriageDetails();

    /** Categorised user-facing triage reason. */
    @Enumerated(EnumType.STRING)
    @Column(name = "triage_reason", length = 40)
    private TriageReason reason;

    /** Formatted explanation text for the triage stand-down (with concrete numbers). */
    @Column(name = "triage_message", columnDefinition = "TEXT")
    private String message;

    /**
     * Null-safe view for readers: an all-null instance when the row was not triaged.
     *
     * @param triage the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static TriageDetails orEmpty(TriageDetails triage) {
        return triage != null ? triage : EMPTY;
    }
}
