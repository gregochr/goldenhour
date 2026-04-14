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
 * Stores the active evaluation model and extended-thinking flag for a specific run type.
 * One row per forecast run type (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM).
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

    /** Currently active evaluation model (HAIKU, SONNET, or OPUS). */
    @Enumerated(EnumType.STRING)
    @Column(name = "active_model", nullable = false, length = 10)
    private EvaluationModel activeModel;

    /** Which run type this configuration applies to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 20)
    private RunType runType;

    /**
     * Whether extended thinking is enabled for this run type.
     * Currently only surfaced in the UI for BRIEFING_BEST_BET.
     * Silently ignored if the active model does not support extended thinking (e.g. HAIKU).
     */
    @Builder.Default
    @Column(name = "extended_thinking", nullable = false)
    private boolean extendedThinking = false;

    /** UTC timestamp when this selection was last updated. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
