package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A reservation of a lower-cased email address (V179, login-registration plan §5 #6).
 *
 * <p>Owned by no one account and with no cascade: it is the database's answer to "is this normalised
 * address taken", because {@code app_user.email} is unique but case-sensitive. The key is assigned, not
 * generated, which makes Spring Data's {@code save()} a {@code merge} (a SELECT, then a deferred
 * INSERT) — so a claim is ALWAYS written through {@code AppUserEmailClaimRepository#insertClaim}, an
 * immediate native INSERT, never {@code save()} (plan §2 #14 rule 3). The entity exists to give the
 * test schema its table and the locking query its row type.
 */
@Entity
@Table(name = "app_user_email_claim")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUserEmailClaimEntity {

    /** The trimmed, {@code Locale.ROOT} lower-cased address. */
    @Id
    @Column(name = "email_lower", nullable = false, length = 255)
    private String emailLower;

    /** When the address was first reserved. */
    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;
}
