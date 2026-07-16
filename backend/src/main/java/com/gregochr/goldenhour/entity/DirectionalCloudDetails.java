package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.DirectionalCloudData;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Directional cloud sampling columns of a forecast evaluation — cloud cover at the solar and
 * antisolar horizon points (113 km) and the far-field strip-vs-blanket sample (226 km). All
 * fields are null when directional sampling was unavailable, in which case Hibernate
 * materialises the whole embeddable as {@code null} on read (use
 * {@link #orEmpty(DirectionalCloudDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectionalCloudDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final DirectionalCloudDetails EMPTY = new DirectionalCloudDetails();

    /** Low cloud cover at the solar horizon point (113 km toward sun). */
    @Column(name = "solar_low_cloud")
    private Integer solarLow;

    /** Mid cloud cover at the solar horizon point (113 km toward sun). */
    @Column(name = "solar_mid_cloud")
    private Integer solarMid;

    /** High cloud cover at the solar horizon point (113 km toward sun). */
    @Column(name = "solar_high_cloud")
    private Integer solarHigh;

    /** Low cloud cover at the antisolar horizon point (113 km away from sun). */
    @Column(name = "antisolar_low_cloud")
    private Integer antisolarLow;

    /** Mid cloud cover at the antisolar horizon point (113 km away from sun). */
    @Column(name = "antisolar_mid_cloud")
    private Integer antisolarMid;

    /** High cloud cover at the antisolar horizon point (113 km away from sun). */
    @Column(name = "antisolar_high_cloud")
    private Integer antisolarHigh;

    /** Low cloud at 226 km along the solar azimuth, for strip vs blanket detection. */
    @Column(name = "far_solar_low_cloud")
    private Integer farSolarLow;

    /**
     * Builds the embeddable from the pipeline's directional cloud data.
     *
     * @param dc the directional cloud data, or {@code null} when sampling was unavailable
     * @return the embeddable, or {@code null} when there is no directional data
     */
    public static DirectionalCloudDetails from(DirectionalCloudData dc) {
        if (dc == null) {
            return null;
        }
        return DirectionalCloudDetails.builder()
                .solarLow(dc.solarLowCloudPercent())
                .solarMid(dc.solarMidCloudPercent())
                .solarHigh(dc.solarHighCloudPercent())
                .antisolarLow(dc.antisolarLowCloudPercent())
                .antisolarMid(dc.antisolarMidCloudPercent())
                .antisolarHigh(dc.antisolarHighCloudPercent())
                .farSolarLow(dc.farSolarLowCloudPercent())
                .build();
    }

    /**
     * Null-safe view for readers: an all-null instance when the row has no directional data.
     *
     * @param dc the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static DirectionalCloudDetails orEmpty(DirectionalCloudDetails dc) {
        return dc != null ? dc : EMPTY;
    }
}
