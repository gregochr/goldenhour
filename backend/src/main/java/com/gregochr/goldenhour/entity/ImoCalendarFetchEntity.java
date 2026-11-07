package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One calendar year's attempt in a run of the IMO calendar refresh (V223). The alert rules read these rows, so
 * nothing about "what was already reported" lives in memory.
 */
@Entity
@Table(name = "imo_calendar_fetch")
@Getter
@Setter
@NoArgsConstructor
public class ImoCalendarFetchEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The IMO calendar year fetched. */
    @Column(name = "calendar_year", nullable = false)
    private int calendarYear;

    /** When the attempt was made. */
    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** How the attempt ended. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private ImoCalendarFetchOutcome outcome;

    /** The HTTP status seen, or null when there was no response. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** SHA-256 of the PDF fetched, or null when none was. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** A short account of the attempt (at most 500 characters), or null. */
    @Column(name = "detail", length = 500)
    private String detail;
}
