package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One weekly check of WorldTides' predicted high waters against an Environment Agency gauge
 * (tide-source-integrity-plan P4).
 *
 * <p>Only the aggregates are stored. The gauge readings are Environment Agency data under the Open
 * Government Licence v3.0 and are read, used and discarded within a single check.
 */
@Entity
@Table(name = "tide_gauge_check")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TideGaugeCheckEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Environment Agency measure id the readings came from. */
    @Column(name = "gauge_measure_id", nullable = false, length = 128)
    private String gaugeMeasureId;

    /** FK to {@code locations}; rows cascade-delete with the location. */
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** UTC time the check ran. */
    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    /** UTC time of the first reading used. */
    @Column(name = "window_from", nullable = false)
    private LocalDateTime windowFrom;

    /** UTC time of the last reading used. */
    @Column(name = "window_to", nullable = false)
    private LocalDateTime windowTo;

    /** Number of measured high waters that found a stored predicted high water within the pairing limit. */
    @Column(name = "paired_highs", nullable = false)
    private int pairedHighs;

    /** Median of (predicted - observed) high-water time, minutes. Positive means predicted late. */
    @Column(name = "median_timing_error_min", nullable = false)
    private double medianTimingErrorMinutes;

    /** Median of (predicted - observed) high-water height, metres. Includes any datum offset. */
    @Column(name = "median_height_error_m", nullable = false)
    private double medianHeightErrorMetres;

    /** True when the median timing error moved more than the flag threshold from its trailing median. */
    @Column(nullable = false)
    private boolean flagged;
}
