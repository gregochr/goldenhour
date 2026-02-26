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

import java.time.LocalDateTime;

/**
 * Stores the currently active evaluation model for forecast runs.
 * Single-row table that persists across restarts.
 */
@Entity
@Table(name = "model_selection")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelSelectionEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Currently active evaluation model (HAIKU or SONNET). */
    @Enumerated(EnumType.STRING)
    @Column(name = "active_model", nullable = false, length = 10)
    private EvaluationModel activeModel;

    /** UTC timestamp when this selection was last updated. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
