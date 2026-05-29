package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.gregochr.goldenhour.model.Relationship;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per persisted best-bet pick on a pipeline run — the cycle's
 * single Plan A (rank 1) and single Plan B (rank 2) as produced by the
 * briefing phase's {@code BriefingBestBetAdvisor}.
 *
 * <p>This is the value-proving record for the intraday refresh: each cycle
 * snapshots its picks here so consecutive runs can be compared. A change in
 * Plan A or Plan B between an intraday run and the same day's nightly run is
 * the "did anything actionable move?" signal intraday exists to surface.
 *
 * <p>{@link #claudeAverageRating} is the cross-run comparison primitive:
 * numeric and snapshot-at-persist-time, so two consecutive picks for the
 * same (region, date, event_type) can be compared directly even if the
 * advisor's headline prose differs cosmetically.
 *
 * <p>Persisted rows are immutable in normal operation — a re-run creates a
 * new {@link PipelineRunEntity} with its own picks rather than mutating
 * existing rows.
 */
@Entity
@Table(name = "pipeline_run_pick")
public class PipelineRunPickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_run_id", nullable = false)
    private Long pipelineRunId;

    @Column(name = "pick_rank", nullable = false)
    private int pickRank;

    @Column(name = "headline", length = 500)
    private String headline;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "event_id", length = 50)
    private String eventId;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "event_type", length = 20)
    private String eventType;

    @Column(name = "region", length = 100)
    private String region;

    @Column(name = "confidence", length = 20)
    private String confidence;

    @Column(name = "claude_average_rating")
    private Double claudeAverageRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", length = 20)
    private Relationship relationship;

    /**
     * CSV of {@code differs_by} dimensions (e.g. {@code "DATE,EVENT"}) for a
     * rank-2 {@code DIFFERENT_SLOT} pick. Persisted as a flat string to keep
     * the row a single-table lookup; parsed back to {@code List<DiffersBy>}
     * by callers when needed.
     */
    @Column(name = "differs_by", length = 50)
    private String differsBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Default constructor for JPA. */
    public PipelineRunPickEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineRunId() {
        return pipelineRunId;
    }

    public void setPipelineRunId(Long pipelineRunId) {
        this.pipelineRunId = pipelineRunId;
    }

    public int getPickRank() {
        return pickRank;
    }

    public void setPickRank(int pickRank) {
        this.pickRank = pickRank;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public Double getClaudeAverageRating() {
        return claudeAverageRating;
    }

    public void setClaudeAverageRating(Double claudeAverageRating) {
        this.claudeAverageRating = claudeAverageRating;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public void setRelationship(Relationship relationship) {
        this.relationship = relationship;
    }

    public String getDiffersBy() {
        return differsBy;
    }

    public void setDiffersBy(String differsBy) {
        this.differsBy = differsBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }
}
