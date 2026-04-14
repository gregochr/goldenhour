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
 * Stores the result of evaluating the briefing rollup with a single Claude model
 * as part of a briefing model comparison test run.
 *
 * <p>Captures the raw response, parsed picks, validation stats, and token usage
 * for each of the three models (Haiku, Sonnet, Opus).
 */
@Entity
@Table(name = "briefing_model_test_result")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BriefingModelTestResultEntity {

    /** Auto-generated primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to the parent test run. */
    @Column(name = "test_run_id", nullable = false)
    private Long testRunId;

    /** Which Claude model was used (HAIKU, SONNET, or OPUS). */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", nullable = false, length = 10)
    private EvaluationModel evaluationModel;

    /** JSON array of the parsed picks returned by this model. */
    @Column(name = "picks_json", columnDefinition = "TEXT")
    private String picksJson;

    /** Number of picks returned by the model (before validation). */
    @Column(name = "picks_returned")
    private Integer picksReturned;

    /** Number of picks that passed validation. */
    @Column(name = "picks_valid")
    private Integer picksValid;

    /** The raw text response from Claude. */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    /** How long the Claude API call took in milliseconds. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Number of standard input tokens consumed. */
    @Column(name = "input_tokens")
    private Long inputTokens;

    /** Number of output tokens generated. */
    @Column(name = "output_tokens")
    private Long outputTokens;

    /** Number of tokens written to the prompt cache. */
    @Column(name = "cache_creation_input_tokens")
    private Long cacheCreationInputTokens;

    /** Number of tokens read from the prompt cache. */
    @Column(name = "cache_read_input_tokens")
    private Long cacheReadInputTokens;

    /** Cost of this evaluation in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;

    /** Whether the evaluation succeeded. */
    @Column(name = "succeeded", nullable = false)
    @Builder.Default
    private Boolean succeeded = false;

    /** Error message if the evaluation failed. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Extended thinking chain text from Claude (nullable — only present for ET variants). */
    @Column(name = "thinking_text", columnDefinition = "TEXT")
    private String thinkingText;

    /** UTC timestamp when this result was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
