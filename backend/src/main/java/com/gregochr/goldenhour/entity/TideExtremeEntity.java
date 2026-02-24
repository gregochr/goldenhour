package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity representing a single tide extreme (high or low tide peak) for a location.
 *
 * <p>Rows are fetched from the WorldTides API on a weekly schedule and used by
 * {@code ForecastService} to classify tide state at solar event times without
 * making a live API call per evaluation.
 */
@Entity
@Table(name = "tide_extreme")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TideExtremeEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK to the {@code locations} table.
     * Rows cascade-delete when the parent location is removed.
     */
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** UTC time of the tide extreme (high or low). */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** Sea level height in metres at the extreme (may be negative at low tide). */
    @Column(name = "height_metres", nullable = false, precision = 6, scale = 3)
    private BigDecimal heightMetres;

    /** Whether this extreme is a HIGH tide or LOW tide. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private TideExtremeType type;

    /** UTC timestamp when this row was fetched from the WorldTides API. */
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
