package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One precomputed Ready answer (V165): the validated, user-less answer to one Ready question for
 * one scope, as it stood when the last precompute wrote it. Unique on {@code (scope_key,
 * question_id)}, so a precompute replaces the row rather than adding one.
 *
 * <p>The answer is stored with each pick's rating and verdict as they were at answer time, because
 * a serve withholds the whole question when live data no longer agrees with them. Never serialised
 * to an API response as it stands: {@code AskReadyService} re-decorates the answer from the live
 * snapshot first.
 */
@Entity
@Table(name = "ask_ready_answer",
        uniqueConstraints = @UniqueConstraint(name = "uq_ask_ready_answer_scope_question",
                columnNames = {"scope_key", "question_id"}))
@Getter
@Setter
@NoArgsConstructor
public class AskReadyAnswerEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code ALL}, or a region id as text. */
    @Column(name = "scope_key", nullable = false, length = 20)
    private String scopeKey;

    /** The Ready question's name, e.g. {@code BEST_NEXT}. */
    @Column(name = "question_id", nullable = false, length = 30)
    private String questionId;

    /** The question text the answer was written for, fixed at precompute time. */
    @Column(name = "question_text", nullable = false, length = 200)
    private String questionText;

    /** The windows the question was asked about, comma-separated; empty when it names none. */
    @Column(name = "window_ids", nullable = false, columnDefinition = "TEXT")
    private String windowIds;

    /** When the briefing the answer was built from was generated (naive UTC). */
    @Column(name = "briefing_generated_at", nullable = false)
    private LocalDateTime briefingGeneratedAt;

    /** The pipeline run that triggered the precompute, or null for an on-demand one. */
    @Column(name = "pipeline_run_id")
    private Long pipelineRunId;

    /** The validated answer as JSON, picks carrying their rating and verdict at answer time. */
    @Column(name = "answer_json", nullable = false, columnDefinition = "TEXT")
    private String answerJson;

    /** When the row was last written. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
