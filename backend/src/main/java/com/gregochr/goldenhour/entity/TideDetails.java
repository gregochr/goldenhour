package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.TideSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tide context columns of a forecast evaluation — the tide state and next extremes at the
 * solar event. All fields are null for inland locations, in which case Hibernate materialises
 * the whole embeddable as {@code null} on read (use {@link #orEmpty(TideDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TideDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final TideDetails EMPTY = new TideDetails();

    /** Tide state at solar event time (HIGH, LOW, MID). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tide_state", length = 10)
    private TideState state;

    /** UTC time of the next high tide. */
    @Column(name = "next_high_tide_time")
    private LocalDateTime nextHighTime;

    /** Height of the next high tide in metres. */
    @Column(name = "next_high_tide_height_m", precision = 5, scale = 2)
    private BigDecimal nextHighHeightMetres;

    /** UTC time of the next low tide. */
    @Column(name = "next_low_tide_time")
    private LocalDateTime nextLowTime;

    /** Height of the next low tide in metres. */
    @Column(name = "next_low_tide_height_m", precision = 5, scale = 2)
    private BigDecimal nextLowHeightMetres;

    /** True if tide state matches location preference. */
    @Column(name = "tide_aligned")
    private Boolean aligned;

    /**
     * Builds the embeddable from the pipeline's tide snapshot.
     *
     * @param tide the tide snapshot from atmospheric data, or {@code null} for inland locations
     * @return the embeddable, or {@code null} when there is no tide context
     */
    public static TideDetails from(TideSnapshot tide) {
        if (tide == null) {
            return null;
        }
        return TideDetails.builder()
                .state(tide.tideState())
                .nextHighTime(tide.nextHighTideTime())
                .nextHighHeightMetres(tide.nextHighTideHeightMetres())
                .nextLowTime(tide.nextLowTideTime())
                .nextLowHeightMetres(tide.nextLowTideHeightMetres())
                .aligned(tide.tideAligned())
                .build();
    }

    /**
     * Null-safe view for readers: an all-null instance when the row has no tide context.
     *
     * @param tide the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static TideDetails orEmpty(TideDetails tide) {
        return tide != null ? tide : EMPTY;
    }
}
