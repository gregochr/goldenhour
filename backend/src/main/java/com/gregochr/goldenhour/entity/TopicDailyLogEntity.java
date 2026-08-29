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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One night's presence/intensity reading for one topic in one region (V151).
 *
 * <p>Append-only — nothing updates or prunes a row once written. Keyed by {@code region_id}, never
 * region name, per the V145 precedent (a rename must not orphan history). {@code topicType} is a
 * bare string matching the existing {@code HotTopic.type} convention (e.g. {@code "DUST"},
 * {@code "SPRING_TIDE"}) rather than a new enum, since nothing in this codebase enum-types hot-topic
 * kinds.
 *
 * <p>{@code band} is written by no phase yet — it is the prior-band store a future hysteresis rule
 * (D4) will read back and populate; P7 only starts the presence/intensity clock.
 */
@Entity
@Table(name = "topic_daily_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicDailyLogEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bare topic identifier, e.g. {@code "DUST"}, {@code "SPRING_TIDE"}, {@code "AURORA"}. */
    @Column(name = "topic_type", nullable = false, length = 20)
    private String topicType;

    /** The Europe/London civil date this row reports on — normally "yesterday" at job run time. */
    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    /** The region this presence reading applies to. Never joined by name (V145 precedent). */
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    /** Whether the topic's proxy condition fired anywhere in the region on this date. */
    @Column(name = "present", nullable = false)
    private boolean present;

    /**
     * The topic's own-scale magnitude reading, or {@code null} when no per-occurrence magnitude is
     * ever recorded for this topic (AURORA, NLC — §1 of the plan) or none was measurable this night.
     */
    @Column(name = "intensity", precision = 10, scale = 3)
    private BigDecimal intensity;

    /**
     * Whether the qualifying reading landed within a light window (D5's peak-gate reasoning),
     * or {@code null} when not applicable to this topic or not computed this night.
     */
    @Column(name = "landed_on_window")
    private Boolean landedOnWindow;

    /** Prior-night band for a future hysteresis rule (D4); {@code null} until that phase lands. */
    @Column(name = "band", length = 20)
    private String band;

    /** When this row was written. */
    @Column(name = "logged_at", nullable = false)
    private OffsetDateTime loggedAt;
}
