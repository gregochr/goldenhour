package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Single-row persistence for the daily briefing cache.
 *
 * <p>The row with {@code id = 1} always holds the most recent generated briefing as a
 * JSON payload. This allows the {@link com.gregochr.goldenhour.service.BriefingService}
 * to restore the last briefing from the database on startup rather than serving nothing
 * until the first scheduled cron run.
 */
@Entity
@Table(name = "daily_briefing_cache")
@Getter
@Setter
@NoArgsConstructor
public class DailyBriefingCacheEntity {

    /** Singleton row identifier — always 1. */
    @Id
    @Column(name = "id", nullable = false)
    private Integer id;

    /** UTC timestamp when this briefing was generated. */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /** JSON-serialized {@link com.gregochr.goldenhour.model.DailyBriefingResponse}. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;
}
