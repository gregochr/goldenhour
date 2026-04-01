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
 * JPA entity storing a per-user drive duration from their home location to a forecast location.
 *
 * <p>Duration is stored in seconds (the native ORS unit) for precision. Use
 * {@link #getDriveMinutes()} for display-ready values.
 */
@Entity
@Table(name = "user_drive_time")
@IdClass(UserDriveTimeId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserDriveTimeEntity {

    /** The owning user's primary key. */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /** The destination location's primary key. */
    @Id
    @Column(name = "location_id")
    private Long locationId;

    /** Drive duration in seconds from the user's home to this location. */
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
