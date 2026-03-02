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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity representing a single external API call made during a job run.
 *
 * <p>Captures request and response details for debugging, replay, and metrics aggregation.
 */
@Entity
@Table(name = "api_call_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiCallLogEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Foreign key to the parent job run. */
    @Column(name = "job_run_id", nullable = false)
    private Long jobRunId;

    /** Name of the external service called. */
    @Enumerated(EnumType.STRING)
    @Column(name = "service", nullable = false, length = 50)
    private ServiceName service;

    /** UTC timestamp when the API call was initiated. */
    @Column(name = "called_at", nullable = false)
    private LocalDateTime calledAt;

    /** UTC timestamp when the API call completed (null if still pending). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Duration of the API call in milliseconds (null if still pending). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** HTTP request method (GET, POST, etc.) or null for non-HTTP services. */
    @Column(name = "request_method", length = 10)
    private String requestMethod;

    /** Full URL including query parameters (for replay purposes). */
    @Column(name = "request_url", length = 2048)
    private String requestUrl;

    /** JSON payload for POST/PATCH requests, or null for GET/non-HTTP. */
    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    /** HTTP status code (200, 429, 500, etc.) or null for non-HTTP services. */
    @Column(name = "status_code")
    private Integer statusCode;

    /** Response body on error (truncated for storage), or null on success. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    /** True if the API call succeeded (status < 400 or non-HTTP success). */
    @Column(name = "succeeded", nullable = false)
    private Boolean succeeded;

    /** Brief error message on failure, truncated to fit. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Cost of this API call in pence (e.g., 130 for Sonnet, 20 for WorldTides). */
    @Column(name = "cost_pence")
    private Integer costPence;

    /** UTC timestamp when this log entry was created. */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Evaluation model (HAIKU or SONNET) for Anthropic API calls; null for other services. */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_model", length = 10)
    private EvaluationModel evaluationModel;

    /** Target date for forecast evaluations; null for non-forecast API calls. */
    @Column(name = "target_date")
    private LocalDate targetDate;

    /** Target type (SUNRISE or SUNSET) for forecast evaluations; null for non-forecast API calls. */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 10)
    private TargetType targetType;

    /** Number of standard input tokens consumed (Anthropic calls only). */
    @Column(name = "input_tokens")
    private Long inputTokens;

    /** Number of output tokens generated (Anthropic calls only). */
    @Column(name = "output_tokens")
    private Long outputTokens;

    /** Number of tokens written to the prompt cache (Anthropic calls only). */
    @Column(name = "cache_creation_input_tokens")
    private Long cacheCreationInputTokens;

    /** Number of tokens read from the prompt cache (Anthropic calls only). */
    @Column(name = "cache_read_input_tokens")
    private Long cacheReadInputTokens;

    /** Whether this call used the Anthropic batch API (50% discount). */
    @Column(name = "is_batch", nullable = false)
    @Builder.Default
    private Boolean isBatch = false;

    /** Cost of this API call in micro-dollars (1 dollar = 1,000,000 micro-dollars). */
    @Column(name = "cost_micro_dollars")
    private Long costMicroDollars;
}
