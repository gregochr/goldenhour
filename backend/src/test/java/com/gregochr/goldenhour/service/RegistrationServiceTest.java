package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.RegistrationClosedException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("resendVerification sends new token when pending user exists and not rate limited")
    void resendVerification_success() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userService.listAllUsers()).thenReturn(List.of(pending));
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(1L), any())).thenReturn(0L);
        when(tokenRepository.findByUserIdAndVerifiedFalse(1L)).thenReturn(List.of());
        when(jwtService.generateRefreshToken()).thenReturn("new-token");
        when(jwtService.hashToken("new-token")).thenReturn("new-hash");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrationService.resendVerification("alice@example.com");

        verify(userEmailService).sendVerificationEmail("alice@example.com", "alice", "new-token");
    }

    @Test
    @DisplayName("resendVerification is silent when email does not match any pending user")
    void resendVerification_unknownEmail_silent() {
        when(userService.listAllUsers()).thenReturn(List.of());

        registrationService.resendVerification("unknown@example.com");

        verify(userEmailService, never()).sendVerificationEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("resendVerification throws when rate limit exceeded")
    void resendVerification_rateLimited_throws() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userService.listAllUsers()).thenReturn(List.of(pending));
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(1L), any())).thenReturn(3L);

        assertThatThrownBy(() -> registrationService.resendVerification("alice@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many");
    }

    @Test
    @DisplayName("verifyEmail marks token as verified and returns userId")
    void verifyEmail_validToken_returnsUserId() {
        EmailVerificationTokenEntity token = EmailVerificationTokenEntity.builder()
                .id(1L)
                .tokenHash("hash123")
                .userId(42L)
                .expiresAt(LocalDateTime.now().plusHours(12))
                .verified(false)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
        when(jwtService.hashToken("raw-token")).thenReturn("hash123");
        when(tokenRepository.findByTokenHash("hash123")).thenReturn(Optional.of(token));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Long userId = registrationService.verifyEmail("raw-token");

        assertThat(userId).isEqualTo(42L);
        assertThat(token.isVerified()).isTrue();
        verify(tokenRepository).save(token);
    }

    @Test
    @DisplayName("verifyEmail throws when token is invalid")
    void verifyEmail_invalidToken_throws() {
        when(jwtService.hashToken("bad-token")).thenReturn("bad-hash");
        when(tokenRepository.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.verifyEmail("bad-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    @DisplayName("verifyEmail throws when token is expired")
    void verifyEmail_expiredToken_throws() {
        EmailVerificationTokenEntity token = EmailVerificationTokenEntity.builder()
                .tokenHash("hash")
                .userId(1L)
                .expiresAt(LocalDateTime.now().minusHours(1))
                .verified(false)
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();
        when(jwtService.hashToken("expired-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> registrationService.verifyEmail("expired-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("verifyEmail throws when token has already been used")
    void verifyEmail_alreadyUsed_throws() {
        EmailVerificationTokenEntity token = EmailVerificationTokenEntity.builder()
                .tokenHash("hash")
                .userId(1L)
                .expiresAt(LocalDateTime.now().plusHours(12))
                .verified(true)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
        when(jwtService.hashToken("used-token")).thenReturn("hash");
        when(tokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> registrationService.verifyEmail("used-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("adminResendVerification deletes old tokens, creates new token, and sends email")
    void adminResendVerification_success() {
        AppUserEntity user = buildPendingUser(1L, "alice", "alice@example.com");
        EmailVerificationTokenEntity oldToken = EmailVerificationTokenEntity.builder()
                .id(1L).tokenHash("old-hash").userId(1L)
                .expiresAt(LocalDateTime.now().plusHours(12))
                .verified(false).createdAt(LocalDateTime.now().minusHours(1)).build();
        when(tokenRepository.findByUserIdAndVerifiedFalse(1L)).thenReturn(List.of(oldToken));
        when(jwtService.generateRefreshToken()).thenReturn("new-admin-token");
        when(jwtService.hashToken("new-admin-token")).thenReturn("new-admin-hash");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registrationService.adminResendVerification(user);

        verify(tokenRepository).deleteAll(List.of(oldToken));
        verify(tokenRepository).save(any(EmailVerificationTokenEntity.class));
        verify(userEmailService).sendVerificationEmail("alice@example.com", "alice", "new-admin-token");
    }

    @Test
    @DisplayName("setPasswordAndActivate delegates to userService.activateUser")
    void setPasswordAndActivate_delegates() {
        registrationService.setPasswordAndActivate(1L, "MyP@ssw0rd!");

        verify(userService).activateUser(1L, "MyP@ssw0rd!");
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
