package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of {@code snow_state} (V231): the modelled snowpack at one place or summit for one sunrise or sunset
 * (snow-topics-truth-plan §3.2).
 *
 * <p>{@link #pointKey} is {@code loc:<location id>} or {@code summit:<key>}: a text key rather than a foreign
 * key, because a summit has no row to point at. Written only by {@code SnowStateWriter}; read through
 * {@code SnowStateReader}, which applies the freshness rule.
 */
@Entity
@Table(name = "snow_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_snow_state_point_date_event",
                columnNames = {"point_key", "evaluation_date", "event_type"}),
        indexes = @Index(name = "idx_snow_state_evaluation_date", columnList = "evaluation_date"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnowStateEntity {

    /** Prefix of a location's point key. */
    public static final String LOCATION_KEY_PREFIX = "loc:";

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code loc:<location id>} or {@code summit:<key>}. */
    @Column(name = "point_key", nullable = false, length = 64)
    private String pointKey;

    /** The UK civil date of the sunrise or sunset. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10)
    private TargetType eventType;

    /** Modelled snow water equivalent at the slot hour, mm; null when never computed. */
    @Column(name = "snow_water_mm")
    private Double snowWaterMm;

    /** Snow added in the 24 hours before the slot, cm; null when never computed. */
    @Column(name = "snowfall_24h_cm")
    private Double snowfall24hCm;

    /** Low cloud cover at the slot hour, percent; null when the response carried none. */
    @Column(name = "low_cloud_pct")
    private Integer lowCloudPct;

    /** When the row was last written (UTC wall clock); the reader's freshness rule measures from it. */
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    /**
     * The point key of a location.
     *
     * @param locationId the location id
     * @return {@code loc:<id>}
     */
    public static String locationKey(long locationId) {
        return LOCATION_KEY_PREFIX + locationId;
    }
}
