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

/**
 * JPA entity storing the drive duration from a region's base town to a forecast location.
 *
 * <p><b>Shared, user-independent data</b> — the counterpart of {@link UserDriveTimeEntity}, and
 * deliberately a separate table rather than a nullable user id on that one. "How far is this from
 * Keswick" is the same answer for every reader, so it rides the shared, ETag-revalidated payload;
 * "how far is this from your house" cannot, because a revalidated body persists to a browser HTTP
 * cache JavaScript cannot evict on logout ({@code HttpCachingConfig}). One table on each side of
 * that seam keeps it structural.
 *
 * <p>Duration is stored in seconds (the native ORS unit); {@link #getDriveMinutes()} is the
 * display-ready value, rounded exactly as {@link UserDriveTimeEntity} rounds it so the two figures
 * cannot disagree about the same journey by a minute.
 */
@Entity
@Table(name = "region_drive_time")
@IdClass(RegionDriveTimeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegionDriveTimeEntity {

    /** The origin region's primary key. */
    @Id
    @Column(name = "region_id")
    private Long regionId;

    /** The destination location's primary key. */
    @Id
    @Column(name = "location_id")
    private Long locationId;

    /** Drive duration in seconds from the region's base town to this location. */
    @Column(name = "drive_duration_seconds", nullable = false)
    private int driveDurationSeconds;

    /**
     * Returns the drive duration rounded to the nearest whole minute.
     *
     * @return drive time in minutes
     */
    public int getDriveMinutes() {
        return (int) Math.round(driveDurationSeconds / 60.0);
    }
}
