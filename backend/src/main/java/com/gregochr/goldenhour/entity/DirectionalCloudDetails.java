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
 * antisolar horizon points (113 km) and the far-field strip-vs-blanket sample (226 km). The far
 * point also records mid and high cloud and a dark-side hour (low, mid and high); these are
 * recorded, never prompted (far-cloud-lid-plan P2). It also records pressure-level cloud
 * (850 to 200 hPa) at the far point's event hour (V176). All
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

    /** Mid cloud at 226 km along the solar azimuth, at the event's hour (recorded as data only). */
    @Column(name = "far_solar_mid_cloud")
    private Integer farSolarMid;

    /** High cloud at 226 km along the solar azimuth, at the event's hour (recorded as data only). */
    @Column(name = "far_solar_high_cloud")
    private Integer farSolarHigh;

    /** Low cloud at the far point on the dark side of the event (after a sunset, before a sunrise). */
    @Column(name = "far_solar_low_cloud_dark")
    private Integer farSolarLowDark;

    /** Mid cloud at the far point on the dark side of the event. */
    @Column(name = "far_solar_mid_cloud_dark")
    private Integer farSolarMidDark;

    /** High cloud at the far point on the dark side of the event. */
    @Column(name = "far_solar_high_cloud_dark")
    private Integer farSolarHighDark;

    /** Pressure-level cloud at the far point at the event's hour, 850 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_850hpa")
    private Integer farSolarCloud850;

    /** Pressure-level cloud at the far point at the event's hour, 700 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_700hpa")
    private Integer farSolarCloud700;

    /** Pressure-level cloud at the far point at the event's hour, 600 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_600hpa")
    private Integer farSolarCloud600;

    /** Pressure-level cloud at the far point at the event's hour, 500 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_500hpa")
    private Integer farSolarCloud500;

    /** Pressure-level cloud at the far point at the event's hour, 400 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_400hpa")
    private Integer farSolarCloud400;

    /** Pressure-level cloud at the far point at the event's hour, 300 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_300hpa")
    private Integer farSolarCloud300;

    /** Pressure-level cloud at the far point at the event's hour, 250 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_250hpa")
    private Integer farSolarCloud250;

    /** Pressure-level cloud at the far point at the event's hour, 200 hPa (recorded as data only). */
    @Column(name = "far_solar_cloud_200hpa")
    private Integer farSolarCloud200;

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
                .farSolarMid(dc.farSolarMidCloudPercent())
                .farSolarHigh(dc.farSolarHighCloudPercent())
                .farSolarLowDark(dc.farSolarLowCloudPercentDarkHour())
                .farSolarMidDark(dc.farSolarMidCloudPercentDarkHour())
                .farSolarHighDark(dc.farSolarHighCloudPercentDarkHour())
                .farSolarCloud850(dc.farSolarCloud850hPa())
                .farSolarCloud700(dc.farSolarCloud700hPa())
                .farSolarCloud600(dc.farSolarCloud600hPa())
                .farSolarCloud500(dc.farSolarCloud500hPa())
                .farSolarCloud400(dc.farSolarCloud400hPa())
                .farSolarCloud300(dc.farSolarCloud300hPa())
                .farSolarCloud250(dc.farSolarCloud250hPa())
                .farSolarCloud200(dc.farSolarCloud200hPa())
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
