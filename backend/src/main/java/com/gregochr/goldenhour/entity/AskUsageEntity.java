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

import java.time.LocalDate;

/**
 * One user's Ask usage on one UK civil day (V166): how many typed questions they have been charged
 * ({@code used}) and how many times the engine has run for them ({@code engineCalls}).
 *
 * <p>Never changed by loading, setting and saving: both counters are {@code updatable = false} with
 * no setter, and are moved only by the conditional updates on {@code AskUsageRepository} (the row
 * itself is created by its native {@code insertRow}), so two requests can never overwrite each
 * other's count — the repository's rule for exactly this shape, as on {@code app_user}'s settings
 * columns. The user id is a plain column here, as on every other per-user table; the database's foreign key
 * ({@code ON DELETE CASCADE}) is what removes the rows with the user.
 */
@Entity
@Table(name = "ask_usage",
        uniqueConstraints = @UniqueConstraint(name = "uq_ask_usage_user_date",
                columnNames = {"user_id", "usage_date"}))
@Getter
@NoArgsConstructor
public class AskUsageEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The asking user's id. */
    @Setter
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The Europe/London civil date the usage counts against. */
    @Setter
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** Typed questions charged today (refunded on a failure or an honest "can't"). */
    @Column(name = "used", nullable = false, updatable = false)
    private int used;

    /** Engine runs today (never refunded). */
    @Column(name = "engine_calls", nullable = false, updatable = false)
    private int engineCalls;
}
