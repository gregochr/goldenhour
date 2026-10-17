package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * The exact user message a batch sky forecast request sent for one {@code forecast_evaluation}
 * row (V164), kept so a production prompt can be replayed. The system prompt is deliberately not
 * stored: it is identical per run.
 *
 * <p>A side table rather than a column on {@code forecast_evaluation}, which is read whole-entity
 * on hot serve paths. The primary key is the evaluation id (assigned, not generated); the
 * production FK is {@code ON DELETE CASCADE}. Rows are pruned nightly by
 * {@code ForecastPromptCleanupJob}. Never serialised to any API response.
 */
@Entity
@Table(name = "forecast_evaluation_prompt",
        indexes = @Index(name = "idx_forecast_evaluation_prompt_created_at", columnList = "created_at"))
@Getter
@Setter
@NoArgsConstructor
public class ForecastEvaluationPromptEntity implements Persistable<Long> {

    /** Primary key — the id of the {@code forecast_evaluation} row this message belongs to. */
    @Id
    @Column(name = "evaluation_id")
    private Long evaluationId;

    /** The user message exactly as placed in the batch request. */
    @Column(name = "user_message", columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    /** When the message was stored; the retention cutoff compares against it. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newRow = true;

    /**
     * Creates a new, not-yet-persisted prompt row.
     *
     * @param evaluationId the owning {@code forecast_evaluation} id
     * @param userMessage  the user message that was sent
     * @param createdAt    the storage instant
     */
    public ForecastEvaluationPromptEntity(Long evaluationId, String userMessage, Instant createdAt) {
        this.evaluationId = evaluationId;
        this.userMessage = userMessage;
        this.createdAt = createdAt;
    }

    @Override
    public Long getId() {
        return evaluationId;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    /** Marks the row as no longer new once it has been inserted or loaded. */
    @PostPersist
    @PostLoad
    public void markNotNew() {
        this.newRow = false;
    }
}
