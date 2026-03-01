package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
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
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserEmailService userEmailService;

    @InjectMocks
    private RegistrationService registrationService;

    @Test
    @DisplayName("register creates pending user, saves token, and sends verification email")
    void register_success() {
        AppUserEntity pending = buildPendingUser(1L, "alice", "alice@example.com");
        when(userService.createPendingUser("alice", "alice@example.com")).thenReturn(pending);
        when(jwtService.generateRefreshToken()).thenReturn("raw-token-uuid");
        when(jwtService.hashToken("raw-token-uuid")).thenReturn("hashed-token");
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserEntity result = registrationService.register("alice", "alice@example.com");

        assertThat(result.getUsername()).isEqualTo("alice");
        verify(userService).createPendingUser("alice", "alice@example.com");
        verify(tokenRepository).save(any(EmailVerificationTokenEntity.class));
        verify(userEmailService).sendVerificationEmail("alice@example.com", "alice", "raw-token-uuid");
    }

    @Test
    @DisplayName("register propagates exception when username already exists")
    void register_duplicateUsername_throws() {
        when(userService.createPendingUser("alice", "alice@example.com"))
                .thenThrow(new IllegalArgumentException("Username already exists"));

        assertThatThrownBy(() -> registrationService.register("alice", "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username already exists");
    }

    @Test
    @DisplayName("register propagates exception when email already registered")
    void register_duplicateEmail_throws() {
        when(userService.createPendingUser("bob", "alice@example.com"))
                .thenThrow(new IllegalArgumentException("Email already registered"));

        assertThatThrownBy(() -> registrationService.register("bob", "alice@example.com"))
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
