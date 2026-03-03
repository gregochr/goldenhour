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
 * JPA entity representing a configurable cost optimisation strategy for a specific run type.
 *
 * <p>One row per (runType, strategyType) combination. Toggled on/off via the admin UI.
 * Some strategies accept an optional integer parameter (e.g. minimum star rating threshold).
 */
@Entity
@Table(name = "optimisation_strategy")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimisationStrategyEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which run type this strategy applies to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 20)
    private RunType runType;

    /** The type of optimisation strategy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 30)
    private OptimisationStrategyType strategyType;

    /** Whether this strategy is currently active. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Optional integer parameter (e.g. minimum star rating for SKIP_LOW_RATED). */
    @Column(name = "param_value")
    private Integer paramValue;

    /** UTC timestamp when this row was last updated. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
