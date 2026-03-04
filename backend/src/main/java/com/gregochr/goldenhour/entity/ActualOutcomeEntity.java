package com.gregochr.goldenhour.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity representing an actual observed outcome recorded by the photographer.
 *
 * <p>Outcomes are linked to a location and date for comparison against forecast evaluations,
 * enabling accuracy analysis over time.
 */
@Entity
@Table(name = "actual_outcome")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActualOutcomeEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Latitude of the location in decimal degrees. */
    @Column(name = "location_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal locationLat;

    /** Longitude of the location in decimal degrees. */
    @Column(name = "location_lon", nullable = false, precision = 9, scale = 6)
    private BigDecimal locationLon;

    /** The location this outcome was recorded for. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "location_id", nullable = false)
    @JsonIgnore
    private LocationEntity location;

    /** The date the event was observed. */
    @Column(name = "outcome_date", nullable = false)
    private LocalDate outcomeDate;

    /** Whether this outcome is for sunrise or sunset. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    /** Whether the photographer actually went out to shoot. */
    @Column(name = "went_out")
    private Boolean wentOut;

    /** The photographer's own 1-5 rating of the actual colour. Kept for backward compatibility. */
    @Column(name = "actual_rating")
    private Integer actualRating;

    /** Photographer's fiery sky score (0–100) for the actual event. */
    @Column(name = "fiery_sky_actual")
    private Integer fierySkyActual;

    /** Photographer's golden hour score (0–100) for the actual event. */
    @Column(name = "golden_hour_actual")
    private Integer goldenHourActual;

    /** Free-text observations about the shoot. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** UTC timestamp when this record was created. */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /**
     * Returns the location name for JSON serialisation, preserving the API contract.
     *
     * @return the location name, or {@code null} if the location is not set
     */
    @JsonProperty("locationName")
    public String getLocationName() {
        return location != null ? location.getName() : null;
    }
}
