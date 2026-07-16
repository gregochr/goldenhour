package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Storm surge columns of a forecast evaluation — weather-driven surge components and the
 * surge-adjusted tidal range for coastal tidal locations. All fields are null for inland
 * locations, in which case Hibernate materialises the whole embeddable as {@code null} on
 * read (use {@link #orEmpty(StormSurgeDetails)}).
 */
@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StormSurgeDetails {

    /** Shared all-null view (see orEmpty). Safe to share: the class has no setters. */
    private static final StormSurgeDetails EMPTY = new StormSurgeDetails();

    /** Total storm surge in metres (pressure + wind components). */
    @Column(name = "surge_total_metres")
    private Double totalMetres;

    /** Pressure-driven surge component in metres (inverse barometer effect). */
    @Column(name = "surge_pressure_metres")
    private Double pressureMetres;

    /** Wind-driven surge component in metres (quadratic wind stress). */
    @Column(name = "surge_wind_metres")
    private Double windMetres;

    /** Storm surge risk classification (NONE, LOW, MODERATE, HIGH). */
    @Column(name = "surge_risk_level", length = 10)
    private String riskLevel;

    /** Tidal range adjusted for surge — upper bound estimate in metres. */
    @Column(name = "surge_adjusted_range_metres")
    private Double adjustedRangeMetres;

    /** Predicted astronomical tidal range in metres. */
    @Column(name = "surge_astronomical_range_metres")
    private Double astronomicalRangeMetres;

    /**
     * Builds the embeddable from the pipeline's surge breakdown and range figures. The two
     * range figures live outside the breakdown and may be present without it.
     *
     * @param surge                   the surge breakdown, or {@code null} when not computed
     * @param adjustedRangeMetres     surge-adjusted tidal range, or {@code null}
     * @param astronomicalRangeMetres predicted astronomical tidal range, or {@code null}
     * @return the embeddable, or {@code null} when every input is {@code null}
     */
    public static StormSurgeDetails from(StormSurgeBreakdown surge,
            Double adjustedRangeMetres, Double astronomicalRangeMetres) {
        if (surge == null && adjustedRangeMetres == null && astronomicalRangeMetres == null) {
            return null;
        }
        return StormSurgeDetails.builder()
                .totalMetres(surge != null ? surge.totalSurgeMetres() : null)
                .pressureMetres(surge != null ? surge.pressureRiseMetres() : null)
                .windMetres(surge != null ? surge.windRiseMetres() : null)
                .riskLevel(surge != null ? surge.riskLevel().name() : null)
                .adjustedRangeMetres(adjustedRangeMetres)
                .astronomicalRangeMetres(astronomicalRangeMetres)
                .build();
    }

    /**
     * Null-safe view for readers: an all-null instance when the row has no surge data.
     *
     * @param surge the embeddable loaded from the entity, possibly {@code null}
     * @return the given instance, or a shared empty one
     */
    public static StormSurgeDetails orEmpty(StormSurgeDetails surge) {
        return surge != null ? surge : EMPTY;
    }
}
