package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Durable persistence for briefing evaluation cache entries.
 *
 * <p>Each row stores the JSON-serialised evaluation results for a
 * region/date/targetType cache key. The in-memory {@code ConcurrentHashMap} in
 * {@link com.gregochr.goldenhour.service.BriefingEvaluationService} is the primary
 * read source; this table provides durability across backend restarts.
 */
@Entity
@Table(name = "cached_evaluation")
@Getter
@Setter
@NoArgsConstructor
public class CachedEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Cache key in the format "regionName|date|targetType". */
    @Column(name = "cache_key", nullable = false, unique = true)
    private String cacheKey;

    /** Region name extracted from the cache key. */
    @Column(name = "region_name", nullable = false, length = 100)
    private String regionName;

    /** Forecast date extracted from the cache key. */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** SUNRISE or SUNSET. */
    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    /** JSON-serialised list of {@link com.gregochr.goldenhour.model.BriefingEvaluationResult}. */
    @Column(name = "results_json", nullable = false, columnDefinition = "TEXT")
    private String resultsJson;

    /** How this entry was produced: BATCH or SSE. */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * When this cache key was first created — <b>not</b> when the results below were evaluated.
     *
     * <p>⚠️ {@code BriefingEvaluationService.persistToDb} sets this only on insert; every
     * subsequent write to the same key moves {@link #updatedAt} alone. A slot re-evaluated for
     * three days running still carries its day-one value here, so this column is a row-creation
     * stamp in everything but name.
     *
     * <p><b>For "how fresh is this evaluation", read {@link #updatedAt}.</b> Both
     * {@code EvaluationViewService} and {@code BriefingEvaluationService.rehydrateCacheOnStartup}
     * deliberately do, each with its own comment saying why — and an ad-hoc diagnostic query that
     * read this column instead once mis-dated a slot by 31 hours during an investigation. If you
     * are about to use this field to decide whether something is stale, you want the other one.
     */
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    /** When this row was last updated. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
