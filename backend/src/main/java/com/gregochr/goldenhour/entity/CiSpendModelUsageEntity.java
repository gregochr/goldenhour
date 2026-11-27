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

/**
 * One model's token counts within a {@link CiSpendRunEntity} (V232), with the rate snapshot the cost was priced at
 * (null when the model is unpriced). Owned by its run: the foreign key cascades on delete.
 */
@Entity
@Table(name = "ci_spend_model_usage")
@Getter
@Setter
@NoArgsConstructor
public class CiSpendModelUsageEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** The requested model id the suite recorded (equals an {@code EvaluationModel} id when priced). */
    @Column(name = "model", nullable = false, length = 64)
    private String model;

    /** Calls recorded. */
    @Column(name = "calls", nullable = false)
    private Integer calls = 0;

    /** Calls that threw. */
    @Column(name = "errored_calls", nullable = false)
    private Integer erroredCalls = 0;

    /** Standard input tokens. */
    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    /** Output tokens. */
    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    /** Cache-read tokens. */
    @Column(name = "cache_read_tokens", nullable = false)
    private Long cacheReadTokens = 0L;

    /** Cache-write tokens of either lifetime (the total). */
    @Column(name = "cache_write_tokens", nullable = false)
    private Long cacheWriteTokens = 0L;

    /** The 1-hour subset of {@link #cacheWriteTokens}. */
    @Column(name = "cache_write_1h_tokens", nullable = false)
    private Long cacheWrite1hTokens = 0L;

    /** Input rate snapshot, USD per million tokens. */
    @Column(name = "input_usd_per_mtok")
    private Double inputUsdPerMtok;

    /** Output rate snapshot, USD per million tokens. */
    @Column(name = "output_usd_per_mtok")
    private Double outputUsdPerMtok;

    /** 5-minute cache-write rate snapshot, USD per million tokens. */
    @Column(name = "cache_write_usd_per_mtok")
    private Double cacheWriteUsdPerMtok;

    /** 1-hour cache-write rate snapshot, USD per million tokens. */
    @Column(name = "cache_write_1h_usd_per_mtok")
    private Double cacheWrite1hUsdPerMtok;

    /** Cache-read rate snapshot, USD per million tokens. */
    @Column(name = "cache_read_usd_per_mtok")
    private Double cacheReadUsdPerMtok;

    /** This model's cost in micro-dollars, null when unpriced. */
    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;
}
