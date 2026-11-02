package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A model an admin has switched off for the weekly Sky-Rating Eval. The table is an opt-out list:
 * a selectable model with no row here is scheduled, so a newly added model joins by default.
 */
@Entity
@Table(name = "sky_rating_eval_model_exclusion")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SkyRatingEvalModelExclusionEntity {

    /**
     * The excluded model's {@link EvaluationModel} name (also the primary key). Held as a plain string so a
     * row for a constant that was later removed from the enum is still readable (and then ignored) rather
     * than failing the load.
     */
    @Id
    @Column(name = "model", nullable = false, length = 10)
    private String model;

    /** When the model was switched off (UTC). */
    @Column(name = "excluded_at", nullable = false)
    private LocalDateTime excludedAt;
}
