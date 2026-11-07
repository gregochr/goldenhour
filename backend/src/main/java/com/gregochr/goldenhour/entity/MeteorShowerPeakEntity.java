package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One meteor shower's peak night and rate for one IMO calendar year (V222).
 *
 * <p>The peaks live in a table, not in code, so the Phase 4 refresh job can write them. Read through
 * {@code MeteorPeakCalendar}, which falls back to the code's table for a year with no row.
 */
@Entity
@Table(name = "meteor_shower_peak")
@IdClass(MeteorShowerPeakId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MeteorShowerPeakEntity {

    /** The shower's IAU code (QUA, LYR, ...). */
    @Id
    @Column(name = "shower_code", nullable = false, length = 3)
    private String showerCode;

    /** The IMO calendar year the row came from. */
    @Id
    @Column(name = "calendar_year", nullable = false)
    private int calendarYear;

    /** The evening date of the night of maximum. */
    @Column(name = "peak_night", nullable = false)
    private LocalDate peakNight;

    /** The maximum time when IMO gives one; null for a date only. */
    @Column(name = "max_utc")
    private Instant maxUtc;

    /** IMO's zenithal hourly rate for that year. */
    @Column(name = "zhr", nullable = false)
    private int zhr;

    /** The IMO calendar PDF the row was read from. */
    @Column(name = "source_url", nullable = false, length = 255)
    private String sourceUrl;

    /** SHA-256 of the source PDF; null for migration-seeded rows. */
    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    /** When the row was written. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
