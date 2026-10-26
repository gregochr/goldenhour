package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.RegistrationClosedException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.VerificationTokenIssuer.IssuedVerification;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegistrationService}.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserEmailService userEmailService;

    @Mock
    private RegistrationProperties registrationProperties;

    @Mock
    private AuthAttemptLimiter attemptLimiter;

    @Mock
    private VerificationTokenIssuer verificationTokenIssuer;

    @Mock
    private CredentialChangeWriter credentialChangeWriter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private RegistrationService registrationService;

    /** Stubs the registration cap and default role for tests that call {@code register()}. */
    private void stubRegistrationDefaults() {
        when(registrationProperties.getMaxUsers()).thenReturn(10);
        when(registrationProperties.getDefaultRole()).thenReturn("PRO_USER");
        when(userRepository.countByRoleNot(UserRole.ADMIN)).thenReturn(0L);
    }

    @Test
    @DisplayName("register creates pending user with opt-in, saves token, and sends verification email")
    void register_success() {
        stubRegistrationDefaults();
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userService.createPendingUser("alice", "alice@example.com", true, UserRole.PRO_USER))
                .thenReturn(pending);
        when(jwtService.generateRefreshToken()).thenReturn("raw-token-uuid");
        when(jwtService.hashToken("raw-token-uuid")).thenReturn("hashed-token");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserEntity result = registrationService.register("alice", "alice@example.com", true);

        assertThat(result.getUsername()).isEqualTo("alice");
        verify(userService).createPendingUser("alice", "alice@example.com", true, UserRole.PRO_USER);
        verify(tokenRepository).save(any(EmailVerificationTokenEntity.class));
        verify(userEmailService).sendVerificationEmail("alice@example.com", "alice", "raw-token-uuid");
    }

    @Test
    @DisplayName("register passes marketing opt-out to createPendingUser")
    void register_optOut_passesMarketingOptInFalse() {
        stubRegistrationDefaults();
        AppUserEntity pending = buildPendingUser(1L, "bob", "bob@example.com");
        when(userService.createPendingUser("bob", "bob@example.com", false, UserRole.PRO_USER))
                .thenReturn(pending);
        when(jwtService.generateRefreshToken()).thenReturn("raw-token-uuid");
        when(jwtService.hashToken("raw-token-uuid")).thenReturn("hashed-token");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrationService.register("bob", "bob@example.com", false);

        verify(userService).createPendingUser("bob", "bob@example.com", false, UserRole.PRO_USER);
    }

    @Test
    @DisplayName("register throws RegistrationClosedException when cap is reached")
    void register_capReached_throws() {
        when(registrationProperties.getMaxUsers()).thenReturn(3);
        when(userRepository.countByRoleNot(UserRole.ADMIN)).thenReturn(3L);

        assertThatThrownBy(() -> registrationService.register("alice", "alice@example.com", true))
                .isInstanceOf(RegistrationClosedException.class)
                .hasMessage("Early access is currently full");
    }

    @Test
    @DisplayName("register allows signup when count is one below the cap")
    void register_oneBelowCap_succeeds() {
        when(registrationProperties.getMaxUsers()).thenReturn(3);
        when(registrationProperties.getDefaultRole()).thenReturn("PRO_USER");
        when(userRepository.countByRoleNot(UserRole.ADMIN)).thenReturn(2L);

        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userService.createPendingUser("alice", "alice@example.com", true, UserRole.PRO_USER))
                .thenReturn(pending);
        when(jwtService.generateRefreshToken()).thenReturn("raw-token");
        when(jwtService.hashToken("raw-token")).thenReturn("hashed");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserEntity result = registrationService.register("alice", "alice@example.com", true);

        assertThat(result.getUsername()).isEqualTo("alice");
        verify(userService).createPendingUser("alice", "alice@example.com", true, UserRole.PRO_USER);
    }

    @Test
    @DisplayName("register rejects signup when count exceeds the cap")
    void register_overCap_throws() {
        when(registrationProperties.getMaxUsers()).thenReturn(3);
        when(userRepository.countByRoleNot(UserRole.ADMIN)).thenReturn(5L);

        assertThatThrownBy(() -> registrationService.register("alice", "alice@example.com", true))
                .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("register uses configured default role (e.g. LITE_USER)")
    void register_usesConfiguredRole() {
        when(registrationProperties.getMaxUsers()).thenReturn(100);
        when(registrationProperties.getDefaultRole()).thenReturn("LITE_USER");
        when(userRepository.countByRoleNot(UserRole.ADMIN)).thenReturn(0L);

        AppUserEntity pending = buildPendingUser(1L, "carol", "carol@example.com");
        when(userService.createPendingUser("carol", "carol@example.com", true, UserRole.LITE_USER))
                .thenReturn(pending);
        when(jwtService.generateRefreshToken()).thenReturn("tok");
        when(jwtService.hashToken("tok")).thenReturn("hash");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrationService.register("carol", "carol@example.com", true);

        verify(userService).createPendingUser("carol", "carol@example.com", true, UserRole.LITE_USER);
    }

    @Test
    @DisplayName("register propagates exception when username already exists")
    void register_duplicateUsername_throws() {
        stubRegistrationDefaults();
        when(userService.createPendingUser("alice", "alice@example.com", true, UserRole.PRO_USER))
                .thenThrow(new IllegalArgumentException("Username already exists"));

        assertThatThrownBy(() -> registrationService.register("alice", "alice@example.com", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username already exists");
    }

    @Test
    @DisplayName("register propagates exception when email already registered")
    void register_duplicateEmail_throws() {
        stubRegistrationDefaults();
        when(userService.createPendingUser("bob", "alice@example.com", true, UserRole.PRO_USER))
                .thenThrow(new IllegalArgumentException("Email already registered"));

        assertThatThrownBy(() -> registrationService.register("bob", "alice@example.com", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");
    }

    private void admitResend(Long userId) {
        when(attemptLimiter.tryAcquire("resend:user:" + userId, 3, AuthAttemptLimiter.Window.QUARTER_HOUR))
                .thenReturn(true);
    }

    @Test
    @DisplayName("resendVerification sends new token when pending user exists and not rate limited")
    void resendVerification_success() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));
        admitResend(1L);
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(1L), any())).thenReturn(0L);
        when(verificationTokenIssuer.issue(1L))
                .thenReturn(Optional.of(new IssuedVerification("alice@example.com", "alice", "new-token")));

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService).sendVerificationEmail("alice@example.com", "alice", "new-token");
    }

    @Test
    @DisplayName("resendVerification mails the address and name the ISSUER read under the row lock, never "
            + "the address the typed text or the earlier lookup resolved (an admin moved the account between "
            + "the two)")
    void resendVerification_mailsWhatTheIssuerReadUnderTheLock() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "old@example.com");
        when(userRepository.findByEmail("old@example.com")).thenReturn(Optional.of(pending));
        admitResend(1L);
        when(verificationTokenIssuer.issue(1L))
                .thenReturn(Optional.of(new IssuedVerification("new@example.com", "alice", "new-token")));

        registrationService.resendVerification("old@example.com");

        verify(userEmailService).sendVerificationEmail("new@example.com", "alice", "new-token");
        verify(userEmailService, never()).sendVerificationEmail(eq("old@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("resendVerification sends nothing when the issuer finds the account no longer pending "
            + "(activated or disabled between the lookup and the lock)")
    void resendVerification_issuerEmpty_sendsNothing() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));
        admitResend(1L);
        when(verificationTokenIssuer.issue(1L)).thenReturn(Optional.empty());

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("resendVerification is not transactional itself: the issuer's own transaction holds the lock")
    void resendVerification_isNotTransactional() throws NoSuchMethodException {
        assertThat(RegistrationService.class.getMethod("resendVerification", String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(RegistrationService.class.getMethod("setPasswordAndActivate", String.class, String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("resendVerification finds a pending user by a case-insensitive ASCII match and mails the "
            + "STORED address, not the typed one")
    void resendVerification_caseInsensitiveMatch_mailsTheStoredAddress() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "Alice@Example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(userRepository.findPendingByEmailLower("alice@example.com")).thenReturn(List.of(pending));
        admitResend(1L);
        when(verificationTokenIssuer.issue(1L))
                .thenReturn(Optional.of(new IssuedVerification("Alice@Example.com", "alice", "new-token")));

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService).sendVerificationEmail("Alice@Example.com", "alice", "new-token");
        verify(userEmailService, never()).sendVerificationEmail(eq("alice@example.com"), anyString(),
                anyString());
    }

    @Test
    @DisplayName("resendVerification does not scan every user: the pending lookup is two indexed queries")
    void resendVerification_neverListsAllUsers() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(userRepository.findPendingByEmailLower("nobody@example.com")).thenReturn(List.of());

        registrationService.resendVerification("nobody@example.com");

        verify(userService, never()).listAllUsers();
    }

    @Test
    @DisplayName("resendVerification ignores an ambiguous case-insensitive match (two pending accounts)")
    void resendVerification_twoCaseInsensitiveMatches_isSilent() {
        AppUserEntity first = buildPendingUser(1L, "alice", "Alice@Example.com");
        AppUserEntity second = buildPendingUser(2L, "alice2", "ALICE@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(userRepository.findPendingByEmailLower("alice@example.com")).thenReturn(List.of(first, second));

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
        verifyNoInteractions(attemptLimiter);
    }

    @Test
    @DisplayName("resendVerification does not resend to an ACTIVE account that has the address")
    void resendVerification_activeAccount_isSilent() {
        AppUserEntity active = buildPendingUser(1L, "alice", "alice@example.com");
        active.setEnabled(true);
        active.setPassword("{bcrypt}hash");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(active));

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("resendVerification is silent when email does not match any pending user")
    void resendVerification_unknownEmail_silent() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        when(userRepository.findPendingByEmailLower("unknown@example.com")).thenReturn(List.of());

        registrationService.resendVerification("unknown@example.com");

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
        verifyNoInteractions(attemptLimiter);
    }

    @Test
    @DisplayName("resendVerification throws when the database count shows too many recent tokens")
    void resendVerification_rateLimited_throws() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));
        admitResend(1L);
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(1L), any())).thenReturn(3L);

        assertThatThrownBy(() -> registrationService.resendVerification("alice@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many");

        verifyNoInteractions(verificationTokenIssuer);
    }

    @Test
    @DisplayName("resendVerification throws when the limiter refuses, before any token is deleted or mailed")
    void resendVerification_limiterRefuses_throwsAndTouchesNothing() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));
        when(attemptLimiter.tryAcquire("resend:user:1", 3, AuthAttemptLimiter.Window.QUARTER_HOUR))
                .thenReturn(false);

        assertThatThrownBy(() -> registrationService.resendVerification("alice@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Too many verification requests. Please try again later.");

        verifyNoInteractions(verificationTokenIssuer);
        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("four real resend requests through a real limiter: the first three mail, the fourth sends "
            + "nothing and is refused")
    void resendVerification_fourRequestsThroughARealLimiter_fourthSendsNothing() {
        RegistrationService real = new RegistrationService(userService, userRepository, tokenRepository,
                jwtService, userEmailService, registrationProperties, new AuthAttemptLimiter(),
                verificationTokenIssuer, credentialChangeWriter, passwordEncoder);
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));
        when(verificationTokenIssuer.issue(1L))
                .thenReturn(Optional.of(new IssuedVerification("alice@example.com", "alice", "new-token")));

        real.resendVerification("alice@example.com");
        real.resendVerification("alice@example.com");
        real.resendVerification("alice@example.com");
        assertThatThrownBy(() -> real.resendVerification("alice@example.com"))
                .isInstanceOf(IllegalStateException.class);

        verify(userEmailService, org.mockito.Mockito.times(3))
                .sendVerificationEmail("alice@example.com", "alice", "new-token");
    }

    private EmailVerificationTokenEntity unverified(LocalDateTime expiresAt) {
        return EmailVerificationTokenEntity.builder()
                .id(1L)
                .tokenHash("hash123")
                .userId(42L)
                .expiresAt(expiresAt)
                .verified(false)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    @Test
    @DisplayName("verifyEmail rotates the token by compare-and-set and returns the userId and a fresh setup "
            + "token that is not the mailed one")
    void verifyEmail_validToken_rotatesAndReturnsSetupToken() {
        EmailVerificationTokenEntity token = unverified(LocalDateTime.now().plusHours(12));
        when(jwtService.hashToken("raw-token")).thenReturn("hash123");
        when(jwtService.generateRefreshToken()).thenReturn("setup-raw");
        when(jwtService.hashToken("setup-raw")).thenReturn("setup-hash");
        when(tokenRepository.findByTokenHash("hash123")).thenReturn(Optional.of(token));
        when(tokenRepository.rotateOnVerify(eq(1L), eq("hash123"), eq("setup-hash"), any())).thenReturn(1);

        RegistrationService.VerifiedEmail verified = registrationService.verifyEmail("raw-token");

        assertThat(verified.userId()).isEqualTo(42L);
        assertThat(verified.setupToken()).isEqualTo("setup-raw").isNotEqualTo("raw-token");
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("the setup token lives at most 30 minutes from now, and never longer than the link had left")
    void verifyEmail_expiryIsTheEarlierOfThirtyMinutesAndTheLinksOwn() {
        when(jwtService.hashToken(anyString())).thenReturn("hash123");
        when(jwtService.generateRefreshToken()).thenReturn("setup-raw");
        when(tokenRepository.rotateOnVerify(any(), any(), any(), any())).thenReturn(1);
        org.mockito.ArgumentCaptor<LocalDateTime> expiry = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);

        when(tokenRepository.findByTokenHash("hash123"))
                .thenReturn(Optional.of(unverified(LocalDateTime.now().plusHours(12))));
        LocalDateTime before = LocalDateTime.now();
        registrationService.verifyEmail("raw-token");
        verify(tokenRepository).rotateOnVerify(any(), any(), any(), expiry.capture());
        assertThat(expiry.getValue()).isBetween(before.plusMinutes(30), LocalDateTime.now().plusMinutes(30));

        LocalDateTime soon = LocalDateTime.now().plusMinutes(5);
        when(tokenRepository.findByTokenHash("hash123")).thenReturn(Optional.of(unverified(soon)));
        registrationService.verifyEmail("raw-token");
        verify(tokenRepository, org.mockito.Mockito.times(2))
                .rotateOnVerify(any(), any(), any(), expiry.capture());
        assertThat(expiry.getValue()).isEqualTo(soon);
    }

    @Test
    @DisplayName("a second verify of the mailed link, which the rotation made unknown, is 'already been used' "
            + "-- also for a never-issued token, replacing 'Invalid verification token'")
    void verifyEmail_unknownToken_isAlreadyUsed() {
        when(jwtService.hashToken("bad-token")).thenReturn("bad-hash");
        when(tokenRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.verifyEmail("bad-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RegistrationService.LINK_USED)
                .hasMessageContaining("already been used");
        verify(tokenRepository, never()).rotateOnVerify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("verifyEmail throws when token is expired")
    void verifyEmail_expiredToken_throws() {
        when(jwtService.hashToken("expired-token")).thenReturn("hash123");
        when(tokenRepository.findByTokenHash("hash123"))
                .thenReturn(Optional.of(unverified(LocalDateTime.now().minusHours(1))));

        assertThatThrownBy(() -> registrationService.verifyEmail("expired-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        verify(tokenRepository, never()).rotateOnVerify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("verifyEmail throws 'already been used' for a row already verified")
    void verifyEmail_alreadyVerified_throws() {
        EmailVerificationTokenEntity token = unverified(LocalDateTime.now().plusHours(12));
        token.setVerified(true);
        when(jwtService.hashToken("used-token")).thenReturn("hash123");
        when(tokenRepository.findByTokenHash("hash123")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> registrationService.verifyEmail("used-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RegistrationService.LINK_USED);
        verify(tokenRepository, never()).rotateOnVerify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("losing the compare-and-set to a concurrent verify (zero rows) is 'already been used' and "
            + "no setup token is returned")
    void verifyEmail_lostTheCompareAndSet_isAlreadyUsed() {
        when(jwtService.hashToken("raw-token")).thenReturn("hash123");
        when(jwtService.generateRefreshToken()).thenReturn("setup-raw");
        when(tokenRepository.findByTokenHash("hash123"))
                .thenReturn(Optional.of(unverified(LocalDateTime.now().plusHours(12))));
        when(tokenRepository.rotateOnVerify(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> registrationService.verifyEmail("raw-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(RegistrationService.LINK_USED);
    }

    @Test
    @DisplayName("set-password with the MAILED token fails once the row has been rotated: the old hash no "
            + "longer exists, so nothing is activated")
    void setPassword_withTheMailedTokenAfterRotation_fails() {
        when(jwtService.hashToken("mailed")).thenReturn("mailed-hash");
        when(tokenRepository.findByTokenHash("mailed-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.setPasswordAndActivate("mailed", "MyP@ssw0rd!"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(credentialChangeWriter);
    }

    @Test
    @DisplayName("adminResendVerification goes through the issuer and mails the STORED address it read under "
            + "the lock, not the address on the entity the admin's list held")
    void adminResendVerification_success() {
        AppUserEntity user = buildPendingUser(1L, "alice", "stale@example.com");
        when(verificationTokenIssuer.issue(1L))
                .thenReturn(Optional.of(new IssuedVerification("moved@example.com", "alice", "new-admin-token")));

        boolean sent = registrationService.adminResendVerification(user);

        assertThat(sent).isTrue();
        verify(userEmailService).sendVerificationEmail("moved@example.com", "alice", "new-admin-token");
        verify(userEmailService, never()).sendVerificationEmail(eq("stale@example.com"), anyString(), anyString());
        verify(tokenRepository, never()).deleteAll(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("adminResendVerification reports false and mails nothing for an account that is no longer "
            + "pending")
    void adminResendVerification_notPending_sendsNothing() {
        AppUserEntity user = buildPendingUser(1L, "alice", "alice@example.com");
        when(verificationTokenIssuer.issue(1L)).thenReturn(Optional.empty());

        assertThat(registrationService.adminResendVerification(user)).isFalse();

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("setPasswordAndActivate encodes BEFORE the one transactional writer call, passes the token's "
            + "own ids and the activation change, and returns the id and the hash just written")
    void setPasswordAndActivate_verifiedToken_encodesThenActivatesThroughTheWriter() {
        EmailVerificationTokenEntity token = buildToken(42L, true, LocalDateTime.now().plusHours(12));
        when(jwtService.hashToken("raw-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("MyP@ssw0rd!")).thenReturn("encoded");
        when(credentialChangeWriter.consumeVerificationAndApply(1L, 42L, "encoded", Change.activation()))
                .thenReturn(new CredentialChangeOutcome(42L, "alice", "alice@example.com", 1L));

        RegistrationService.Activation activation =
                registrationService.setPasswordAndActivate("raw-token", "MyP@ssw0rd!");

        assertThat(activation).isEqualTo(new RegistrationService.Activation(42L, "encoded"));
        InOrder order = inOrder(passwordEncoder, credentialChangeWriter);
        order.verify(passwordEncoder).encode("MyP@ssw0rd!");
        order.verify(credentialChangeWriter).consumeVerificationAndApply(1L, 42L, "encoded", Change.activation());
        verify(tokenRepository, never()).delete(any());
        verify(userService, never()).listAllUsers();
    }

    @Test
    @DisplayName("a second redemption of one token answers 'already used': the writer's refusal propagates")
    void setPasswordAndActivate_secondRedemption_isAlreadyUsed() {
        EmailVerificationTokenEntity token = buildToken(42L, true, LocalDateTime.now().plusHours(12));
        when(jwtService.hashToken("raw-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("MyP@ssw0rd!")).thenReturn("encoded");
        when(credentialChangeWriter.consumeVerificationAndApply(1L, 42L, "encoded", Change.activation()))
                .thenThrow(new IllegalArgumentException("This verification link has already been used"));

        assertThatThrownBy(() -> registrationService.setPasswordAndActivate("raw-token", "MyP@ssw0rd!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This verification link has already been used");
    }

    @Test
    @DisplayName("setPasswordAndActivate throws for an unknown token and activates nobody")
    void setPasswordAndActivate_unknownToken_throws() {
        when(jwtService.hashToken("bad-token")).thenReturn("bad-hash");
        when(tokenRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.setPasswordAndActivate("bad-token", "MyP@ssw0rd!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired verification token");
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(credentialChangeWriter);
    }

    @Test
    @DisplayName("setPasswordAndActivate throws when the email link was never clicked")
    void setPasswordAndActivate_unverifiedToken_throws() {
        EmailVerificationTokenEntity token = buildToken(42L, false, LocalDateTime.now().plusHours(12));
        when(jwtService.hashToken("raw-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> registrationService.setPasswordAndActivate("raw-token", "MyP@ssw0rd!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired verification token");
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(credentialChangeWriter);
    }

    @Test
    @DisplayName("setPasswordAndActivate throws for an expired token")
    void setPasswordAndActivate_expiredToken_throws() {
        EmailVerificationTokenEntity token = buildToken(42L, true, LocalDateTime.now().minusHours(1));
        when(jwtService.hashToken("raw-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> registrationService.setPasswordAndActivate("raw-token", "MyP@ssw0rd!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired verification token");
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(credentialChangeWriter);
    }

    private EmailVerificationTokenEntity buildToken(Long userId, boolean verified, LocalDateTime expiresAt) {
        return EmailVerificationTokenEntity.builder()
                .id(1L)
                .tokenHash("hash")
                .userId(userId)
                .expiresAt(expiresAt)
                .verified(verified)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private AppUserEntity buildPendingUser(Long id, String username, String email) {
        return AppUserEntity.builder()
                .id(id)
                .username(username)
                .password("")
                .role(UserRole.LITE_USER)
                .email(email)
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .passwordChangeRequired(false)
                .build();
    }
}
