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
 * An email address submitted to the waitlist when the early-access registration cap is reached.
 */
@Entity
@Table(name = "waitlist_email")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistEmailEntity {

    /** Auto-generated primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The email address submitted by the prospective user. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Timestamp when the email was submitted. */
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    /**
     * SHA-256 hex of the invitation token mailed to this address, or null if never invited. Written only by
     * {@code WaitlistEmailRepository.markInvited}; never serialised.
     */
    @Column(name = "invite_token_hash", length = 64, unique = true, insertable = false, updatable = false)
    private String inviteTokenHash;

    /** When the current invitation was sent, or null. Scoped updates only. */
    @Column(name = "invited_at", insertable = false, updatable = false)
    private LocalDateTime invitedAt;

    /** When the current invitation stops working, or null. Scoped updates only. */
    @Column(name = "invite_expires_at", insertable = false, updatable = false)
    private LocalDateTime inviteExpiresAt;

    /** When the current invitation was redeemed at registration, or null. Scoped updates only. */
    @Column(name = "invite_used_at", insertable = false, updatable = false)
    private LocalDateTime inviteUsedAt;
}
