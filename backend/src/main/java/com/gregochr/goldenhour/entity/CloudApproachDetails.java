package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.CloudApproachData;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cloud approach risk columns of a forecast evaluation — the solar-horizon temporal trend
 * (T-3h to event) and the upwind sample along the wind vector. All fields are null when
 * approach sampling was unavailable, in which case Hibernate materialises the whole
 * embeddable as {@code null} on read (use {@link #orEmpty(CloudApproachDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudApproachDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final CloudApproachDetails EMPTY = new CloudApproachDetails();

    /** Low cloud % at the solar horizon at event time, from the temporal trend. */
    @Column(name = "solar_trend_event_low_cloud")
    private Integer solarTrendEventLowCloud;

    /** Low cloud % at the solar horizon at the earliest trend slot (T-3h). */
    @Column(name = "solar_trend_earliest_low_cloud")
    private Integer solarTrendEarliestLowCloud;

    /** True if the solar horizon low cloud trend is building (increase >= 20pp). */
    @Column(name = "solar_trend_building")
    private Boolean solarTrendBuilding;

    /** Low cloud % at the upwind sample point at current time. */
    @Column(name = "upwind_current_low_cloud")
    private Integer upwindCurrentLowCloud;

    /** Low cloud % at the upwind sample point at event time. */
    @Column(name = "upwind_event_low_cloud")
    private Integer upwindEventLowCloud;

    /** Distance in km to the upwind sample point. */
    @Column(name = "upwind_distance_km")
    private Integer upwindDistanceKm;

    /**
     * Builds the embeddable from the pipeline's cloud approach data, flattening the trend
     * slots (earliest and event-time low cloud) and the upwind sample.
     *
     * @param ca the cloud approach data, or {@code null} when approach sampling was unavailable
     * @return the embeddable, or {@code null} when there is no approach data
     */
    public static CloudApproachDetails from(CloudApproachData ca) {
        if (ca == null) {
            return null;
        }
        boolean hasTrendSlots = ca.solarTrend() != null && !ca.solarTrend().slots().isEmpty();
        return CloudApproachDetails.builder()
                .solarTrendEventLowCloud(hasTrendSlots
                        ? ca.solarTrend().slots().getLast().lowCloudPercent() : null)
                .solarTrendEarliestLowCloud(hasTrendSlots
                        ? ca.solarTrend().slots().getFirst().lowCloudPercent() : null)
                .solarTrendBuilding(ca.solarTrend() != null ? ca.solarTrend().isBuilding() : null)
                .upwindCurrentLowCloud(ca.upwindSample() != null
                        ? ca.upwindSample().currentLowCloudPercent() : null)
                .upwindEventLowCloud(ca.upwindSample() != null
                        ? ca.upwindSample().eventLowCloudPercent() : null)
                .upwindDistanceKm(ca.upwindSample() != null
                        ? ca.upwindSample().distanceKm() : null)
                .build();
    }

    /**
     * Null-safe view for readers: an all-null instance when the row has no approach data.
     *
     * @param ca the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static CloudApproachDetails orEmpty(CloudApproachDetails ca) {
        return ca != null ? ca : EMPTY;
    }
}
