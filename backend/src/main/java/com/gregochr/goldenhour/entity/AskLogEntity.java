package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One answered Ask PhotoCast request (V167, plan §2.5): who asked (until they are deleted), what
 * scope and view, how it was answered, and how long it took.
 *
 * <p><b>Privacy, by construction.</b> {@code normalisedQuestion} holds the question's normalised
 * form <em>only</em> for the two outcomes the engine answered ({@code CLAUDE_OK},
 * {@code CLAUDE_CANT}); every other outcome — Ready match, pre-filter, cache hit, failure — stores
 * none, because it is a question the reader made no new thing of. The database says so too: a check
 * constraint refuses a question on any other outcome. {@code userId} is a foreign key
 * {@code ON DELETE SET NULL}, so deleting a user keeps the aggregate and drops the link to a person.
 * The metrics endpoint never reads {@code normalisedQuestion} at all.
 *
 * <p>Rows are only ever <em>inserted</em>, by one native statement on {@code AskLogRepository}
 * (never {@code save}: a failed insert must not leave a half-persisted entity in the request's
 * session, where it would be flushed again by the next repository call). The entity exists to define
 * the schema for local H2 and to give the aggregate queries a type. The outcome is plain text, not a
 * Java enum column, so adding an outcome later cannot meet the local H2 enum trap.
 */
@Entity
@Table(name = "ask_log")
@Getter
@Setter
@NoArgsConstructor
public class AskLogEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** When the request was answered. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The asker, or null once the user has been deleted. A plain column; the database owns the FK. */
    @Column(name = "user_id")
    private Long userId;

    /** {@code ALL}, or the single region's id as text. */
    @Column(name = "scope_key", nullable = false, length = 20)
    private String scopeKey;

    /** {@code map}, {@code plan} or {@code coming-up}. */
    @Column(name = "view", nullable = false, length = 20)
    private String view;

    /** An {@code AskLog.Outcome} name. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    /** The normalised question; only for {@code CLAUDE_OK} and {@code CLAUDE_CANT}; at most 200. */
    @Column(name = "normalised_question", length = 200)
    private String normalisedQuestion;

    /** What the answer said PhotoCast does not have; only for a can't-answer; at most 60. */
    @Column(name = "missing", length = 60)
    private String missing;

    /** How long the request took, in milliseconds. */
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
}
