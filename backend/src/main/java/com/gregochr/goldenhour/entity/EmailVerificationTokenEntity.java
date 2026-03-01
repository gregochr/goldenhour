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

import java.time.LocalDateTime;

/**
 * JPA entity for email verification tokens used during self-registration.
 *
 * <p>The raw token is never stored; only its SHA-256 hex digest is persisted,
 * following the same security pattern as {@link RefreshTokenEntity}.
 */
@Entity
@Table(name = "email_verification_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationTokenEntity {

    /** Primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex digest of the raw verification token UUID. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** FK to {@code app_user.id} — not mapped as a relation to keep it simple. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** When this token expires (24 hours after creation). */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Whether this token has been used to verify the email address. */
    @Column(nullable = false)
    private boolean verified;

    /** When this token was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
