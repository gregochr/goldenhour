package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One reader's record of how one past sunrise or sunset at one location actually looked (V177): a
 * 1–5 star rating, or {@code null} for "didn't see it". Unique per {@code (user, location, event
 * date, event type)} and upsert-only — a later rating replaces the earlier.
 *
 * <p>Never changed by loading, setting and saving: every column is {@code updatable = false} with no
 * setter, and is written only by {@code OutcomeRatingRepository}'s native {@code insertRow} and
 * conditional {@code updateRow}, exactly as {@code AskUsageEntity}'s counters and {@code app_user}'s
 * settings columns are. A loaded row saved by a second writer could otherwise overwrite a
 * concurrent upsert with a stale copy. The builder exists to construct rows to INSERT (a fixture, a
 * test); it cannot change one that exists.
 *
 * <p>The user and location are plain id columns, as on every other per-user table; the database's
 * foreign keys ({@code ON DELETE CASCADE} for the user) do the referential work.
 */
@Entity
@Table(name = "outcome_rating",
        uniqueConstraints = @UniqueConstraint(name = "uq_outcome_rating_slot",
                columnNames = {"user_id", "location_id", "event_date", "event_type"}))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OutcomeRatingEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The rating user's id. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** The location the sunrise or sunset was rated at. */
    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    /** The event's date. */
    @Column(name = "event_date", nullable = false, updatable = false)
    private LocalDate eventDate;

    /** SUNRISE or SUNSET (never HOURLY — the database CHECK says so). */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10, updatable = false)
    private TargetType eventType;

    /** 1–5 stars, or {@code null} for "didn't see it" — a row's existence is the answer. */
    @Column(name = "rating", updatable = false)
    private Integer rating;

    /** The solar event instant the server computed for THIS location. */
    @Column(name = "event_time", nullable = false, updatable = false)
    private Instant eventTime;

    /** The pipeline cycle whose trigger instant equals the served result's, or {@code null}. */
    @Column(name = "pipeline_run_id", updatable = false)
    private Long pipelineRunId;

    /** The served forecast's own submission instant (its age), or {@code null} with none served. */
    @Column(name = "forecast_run_at", updatable = false)
    private Instant forecastRunAt;

    /** The {@code forecast_evaluation} row available by the event and unretracted at it, or null. */
    @Column(name = "forecast_evaluation_id", updatable = false)
    private Long forecastEvaluationId;

    /** The predicted star the reader saw, or {@code null} when it is not recoverable. */
    @Column(name = "forecast_rating", updatable = false)
    private Integer forecastRating;

    /** When the first rating was recorded. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the rating was last recorded. */
    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;
}
