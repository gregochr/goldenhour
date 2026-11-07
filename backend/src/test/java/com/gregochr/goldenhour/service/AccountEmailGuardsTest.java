package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guard tests for the three account-link writers that must mint nothing for an account with no usable address:
 * {@link PasswordResetIssuer}, {@link VerificationTokenIssuer} and {@link PendingRegistrationWriter}. Also pins
 * the writer's refusal to keep a stage advance when no link could be minted.
 */
class AccountEmailGuardsTest {

    private static final long ID = 42L;
    private static final Instant INSTANT = Instant.parse("2026-10-16T12:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    private AppUserRepository users;
    private PasswordResetTokenRepository resetTokens;
    private EmailVerificationTokenRepository verificationTokens;
    private JwtService jwtService;
    private PasswordResetIssuer resetIssuer;
    private VerificationTokenIssuer verificationIssuer;
    private PendingRegistrationWriter writer;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        resetTokens = mock(PasswordResetTokenRepository.class);
        verificationTokens = mock(EmailVerificationTokenRepository.class);
        jwtService = mock(JwtService.class);
        resetIssuer = new PasswordResetIssuer(users, resetTokens, Clock.fixed(INSTANT, ZoneOffset.UTC));
        verificationIssuer = new VerificationTokenIssuer(users, verificationTokens, jwtService,
                Clock.fixed(INSTANT, ZoneOffset.UTC));
        writer = new PendingRegistrationWriter(users, verificationTokens, verificationIssuer, jwtService);
    }

    private static LockedAccount account(boolean enabled, String password, String email, UserRole role) {
        return new LockedAccount(enabled, password, email, "reader", role, 1L, null, false, false, null);
    }

    @Test
    @DisplayName("a password reset is not issued for an active account whose address is null or blank")
    void passwordReset_noUsableAddress_empty() {
        when(users.lockAccount(ID)).thenReturn(Optional.of(account(true, "hash", null, UserRole.PRO_USER)));
        assertThat(resetIssuer.issue(ID, "h1")).isEmpty();

        when(users.lockAccount(ID)).thenReturn(Optional.of(account(true, "hash", "   ", UserRole.PRO_USER)));
        assertThat(resetIssuer.issue(ID, "h2")).isEmpty();

        verifyNoInteractions(resetTokens);
    }

    @Test
    @DisplayName("a verification link is not issued for a pending account whose address is null or blank")
    void verificationToken_noUsableAddress_empty() {
        when(users.lockAccount(ID)).thenReturn(Optional.of(account(false, "", null, UserRole.LITE_USER)));
        assertThat(verificationIssuer.issue(ID)).isEmpty();

        when(users.lockAccount(ID)).thenReturn(Optional.of(account(false, "", "", UserRole.LITE_USER)));
        assertThat(verificationIssuer.issue(ID)).isEmpty();

        verifyNoInteractions(verificationTokens);
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("a reminder claim is skipped for a pending account whose address is null or blank")
    void claim_noUsableAddress_skipped() {
        when(users.lockAccount(ID)).thenReturn(Optional.of(account(false, "", null, UserRole.LITE_USER)));
        assertThat(writer.claim(ID, 0, 1, NOW).outcome()).isEqualTo(PendingRegistrationWriter.Outcome.SKIPPED);

        when(users.lockAccount(ID)).thenReturn(Optional.of(account(false, "", " ", UserRole.LITE_USER)));
        assertThat(writer.claim(ID, 0, 1, NOW).outcome()).isEqualTo(PendingRegistrationWriter.Outcome.SKIPPED);

        verifyNoInteractions(verificationTokens);
    }

    @Test
    @DisplayName("a claim whose link cannot be minted throws, so the stage advance rolls back")
    void claim_linkNotMinted_throws() {
        // The account is pending when the claim locks it, but the issuer's own lock finds it gone.
        when(users.lockAccount(ID)).thenReturn(
                Optional.of(account(false, "", "reader@example.com", UserRole.LITE_USER)),
                Optional.empty());
        when(verificationTokens.existsByUserIdAndVerifiedFalseAndExpiresAtAfter(ID, NOW)).thenReturn(false);
        when(users.findPendingStage(ID)).thenReturn(Optional.empty());
        when(users.advanceReminderStage(ID, 0, 1, NOW)).thenReturn(1);

        assertThatThrownBy(() -> writer.claim(ID, 0, 1, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No link could be minted for pending user 42");
        verify(users).advanceReminderStage(ID, 0, 1, NOW);
    }
}
