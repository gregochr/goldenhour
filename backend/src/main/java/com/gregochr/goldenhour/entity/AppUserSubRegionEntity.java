package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One row of {@code app_user_sub_region} (V192): a sub-region a reader has picked.
 *
 * <p>No association to {@link AppUserEntity} or {@link AreaEntity}, deliberately: saving either can never
 * rewrite a pick, and the database cascades both foreign keys. Rows are written only by
 * {@code AppUserSubRegionRepository}, which takes a user and an area id.
 */
@Entity
@Table(name = "app_user_sub_region")
@IdClass(AppUserSubRegionEntity.Key.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserSubRegionEntity {

    /** The reader. */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The {@code SUB_REGION} area picked. */
    @Id
    @Column(name = "area_id", nullable = false)
    private Long areaId;

    /** When the pick was made (UTC wall clock); the order "the first n picks" follows. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** The composite key. */
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        /** The reader. */
        private Long userId;

        /** The sub-region. */
        private Long areaId;
    }
}
