package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A user-declared "away" date range that gates the overnight forecast batch.
 *
 * <p>Each row is one inclusive {@code [startDate, endDate]} period during which
 * the operator is travelling (typically commuting to London) and cannot shoot at
 * any configured location. {@code ForecastTaskCollector} skips any forecast
 * candidate whose <em>target</em> date falls inside a range, so the batch avoids
 * spending on forecasts that will never be acted on. Travel is not fixed, so
 * ranges are added and removed ad-hoc via the admin UI rather than seeded.
 */
@Entity
@Table(name = "travel_day")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelDayEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** First day of the away period (inclusive). */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Last day of the away period (inclusive). */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Optional free-text note, e.g. "London — work". */
    @Column(name = "note", length = 200)
    private String note;

    /** When this range was created. */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
