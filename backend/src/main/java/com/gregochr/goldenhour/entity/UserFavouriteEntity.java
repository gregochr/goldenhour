package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row of {@code user_favourite} (V226): a place or an area a Pro or Admin reader has starred.
 *
 * <p>Exactly one of {@link #locationId} and {@link #areaId} is set (a database CHECK). No association to
 * {@link AppUserEntity}, {@link LocationEntity} or {@link AreaEntity}, deliberately: saving any of them can
 * never rewrite a favourite, and the database cascades all three foreign keys. Rows are written only by
 * {@code UserFavouriteRepository}.
 */
@Entity
@Table(name = "user_favourite")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserFavouriteEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The reader. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The starred location, or null when this row stars an area. */
    @Column(name = "location_id")
    private Long locationId;

    /** The starred area, or null when this row stars a location. */
    @Column(name = "area_id")
    private Long areaId;

    /** When it was starred (UTC wall clock); the order a reader's list follows. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
