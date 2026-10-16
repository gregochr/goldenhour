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

/**
 * JPA entity for the emailed password-reset link (V179, login-registration plan §2 #1).
 *
 * <p>The raw token is never stored; only its SHA-256 hex digest is, as for
 * {@link EmailVerificationTokenEntity} and {@link RefreshTokenEntity}. Unlike a verification token a
 * reset token is KEPT when redeemed, with {@code usedAt} set, because it proves a request rather than an
 * address; {@code usedAt} is written only by the repository's compare-and-set
 * ({@code PasswordResetTokenRepository#markUsed}), so two concurrent redemptions of one link cannot both
 * succeed.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetTokenEntity {

    /** Primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex digest of the raw reset token. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** FK to {@code app_user.id} — not mapped as a relation, like the other token tables. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** When the link stops working (one hour after issue). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When the link was redeemed, or {@code null} while it is still unused. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** When the link was issued. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
