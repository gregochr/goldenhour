package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthenticationService}.
 *
 * <p>The refresh tests focus on the race-safety of token rotation: the old token must be
 * consumed via the atomic {@code revokeIfActive} compare-and-set, and any refresh attempt
 * with an already-consumed token must revoke the user's whole token family.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final String ACCESS_TOKEN = "access-token";
    private static final String RAW_REFRESH = "raw-refresh-token";
    private static final String REFRESH_HASH = "refresh-hash";
    private static final int REFRESH_EXPIRY_DAYS = 30;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProperties jwtProperties;

    private AuthenticationService service;

    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userRepository, refreshTokenRepository,
                jwtService, passwordEncoder, jwtProperties);
        user = AppUserEntity.builder()
                .id(1L)
                .username("admin")
                .password("hashed-password")
                .role(UserRole.ADMIN)
                .enabled(true)
                .marketingEmailOptIn(true)
                .termsVersion("April 2026")
                .build();
    }

    private void stubTokenIssuance() {
        when(jwtService.generateAccessToken("admin", UserRole.ADMIN)).thenReturn(ACCESS_TOKEN);
        when(jwtService.generateRefreshToken()).thenReturn(RAW_REFRESH);
        when(jwtService.hashToken(RAW_REFRESH)).thenReturn(REFRESH_HASH);
        when(jwtService.extractExpiry(ACCESS_TOKEN))
                .thenReturn(Date.from(Instant.parse("2026-07-16T10:00:00Z")));
        when(jwtProperties.getRefreshTokenExpiryDays()).thenReturn(REFRESH_EXPIRY_DAYS);
    }

    @Nested
    class Login {

        @Test
        @DisplayName("valid credentials stamp lastActiveAt and persist a refresh token in one flow")
        void validCredentials_issuesTokensAndStampsLastActive() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            stubTokenIssuance();

            AuthTokens tokens = service.login("admin", "golden2026");

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(tokens.refreshToken()).isEqualTo(RAW_REFRESH);
            assertThat(tokens.accessTokenExpiresAt())
                    .isEqualTo(Instant.parse("2026-07-16T10:00:00Z"));
            assertThat(tokens.username()).isEqualTo("admin");
            assertThat(tokens.role()).isEqualTo("ADMIN");
            assertThat(tokens.marketingEmailOptIn()).isTrue();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");

            verify(userRepository).save(argThat(saved ->
                    "admin".equals(saved.getUsername()) && saved.getLastActiveAt() != null));

            ArgumentCaptor<RefreshTokenEntity> tokenCaptor =
                    ArgumentCaptor.forClass(RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(tokenCaptor.capture());
            assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(REFRESH_HASH);
            assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(1L);
            assertThat(tokenCaptor.getValue().isRevoked()).isFalse();
        }

        @Test
        @DisplayName("wrong password throws and writes nothing")
        void wrongPassword_throwsWithoutWrites() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashed-password")).thenReturn(false);

            assertThatThrownBy(() -> service.login("admin", "wrong"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("unknown username throws the same message as a wrong password")
        void unknownUser_throwsWithoutWrites() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login("ghost", "anything"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("disabled account throws even with correct credentials")
        void disabledUser_throws() {
            user.setEnabled(false);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);

            assertThatThrownBy(() -> service.login("admin", "golden2026"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Account is disabled");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }
    }

    @Nested
    class Refresh {

        private RefreshTokenEntity storedToken(boolean revoked, LocalDateTime expiresAt) {
            return RefreshTokenEntity.builder()
                    .id(10L)
                    .tokenHash("old-hash")
                    .userId(1L)
                    .expiresAt(expiresAt)
                    .revoked(revoked)
                    .build();
        }

        @Test
        @DisplayName("valid token is consumed via the atomic compare-and-set and a replacement issued")
        void validToken_rotates() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(refreshTokenRepository.revokeIfActive("old-hash")).thenReturn(1);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            stubTokenIssuance();

            AuthTokens tokens = service.refresh("old-raw");

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(tokens.refreshToken()).isEqualTo(RAW_REFRESH);

            verify(refreshTokenRepository).revokeIfActive("old-hash");
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(1L);

            ArgumentCaptor<RefreshTokenEntity> tokenCaptor =
                    ArgumentCaptor.forClass(RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(tokenCaptor.capture());
            RefreshTokenEntity issuedNew = tokenCaptor.getValue();
            assertThat(issuedNew.getTokenHash()).isEqualTo(REFRESH_HASH);
            assertThat(issuedNew.getUserId()).isEqualTo(1L);
            assertThat(issuedNew.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("unknown token throws and revokes nothing")
        void unknownToken_throws() {
            when(jwtService.hashToken("unknown")).thenReturn("unknown-hash");
            when(refreshTokenRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("unknown"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any());
        }

        @Test
        @DisplayName("expired token throws without being consumed")
        void expiredToken_throws() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().minusMinutes(1));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(1L);
        }

        @Test
        @DisplayName("already-revoked token throws and revokes the user's whole token family")
        void revokedToken_revokesWholeFamilyAndThrows() {
            RefreshTokenEntity stored = storedToken(true, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
            verify(refreshTokenRepository, never()).save(any());
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("losing the rotation race revokes the family and issues nothing")
        void lostRotationRace_revokesFamilyAndThrows() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(refreshTokenRepository.revokeIfActive("old-hash")).thenReturn(0);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("disabled user throws and the old token is NOT consumed")
        void disabledUser_throwsWithoutRevoking() {
            user.setEnabled(false);
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found or disabled");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
        }

        @Test
        @DisplayName("missing user throws")
        void missingUser_throws() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found or disabled");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
        }
    }

    @Nested
    class IssueTokensFor {

        @Test
        @DisplayName("maps user fields onto the issued tokens")
        void mapsUserFields() {
            user.setPasswordChangeRequired(true);
            stubTokenIssuance();

            AuthTokens tokens = service.issueTokensFor(user);

            assertThat(tokens.passwordChangeRequired()).isTrue();
            assertThat(tokens.marketingEmailOptIn()).isTrue();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");
            assertThat(tokens.refreshTokenExpiresAt())
                    .isAfter(Instant.now().plus(java.time.Duration.ofDays(REFRESH_EXPIRY_DAYS - 1)));
        }
    }

    @Nested
    class ChangePassword {

        @Test
        @DisplayName("encodes the new password and clears the change-required flag")
        void encodesAndClearsFlag() {
            user.setPasswordChangeRequired(true);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");

            service.changePassword("admin", "NewPass1!");

            verify(userRepository).save(argThat(saved ->
                    "encoded-new".equals(saved.getPassword()) && !saved.isPasswordChangeRequired()));
        }

        @Test
        @DisplayName("unknown user throws")
        void unknownUser_throws() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword("ghost", "NewPass1!"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    class RevokeRefreshToken {

        @Test
        @DisplayName("marks a known token revoked")
        void knownToken_revoked() {
            RefreshTokenEntity stored = RefreshTokenEntity.builder()
                    .id(10L).tokenHash("hash").userId(1L)
                    .expiresAt(LocalDateTime.now().plusDays(10)).revoked(false).build();
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(stored));

            service.revokeRefreshToken("raw");

            verify(refreshTokenRepository).save(argThat(RefreshTokenEntity::isRevoked));
        }

        @Test
        @DisplayName("unknown token is a silent no-op")
        void unknownToken_noop() {
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

            service.revokeRefreshToken("raw");

            verify(refreshTokenRepository, never()).save(any());
        }
    }

    @Nested
    class PasswordComplexity {

        @Test
        @DisplayName("null password fails the length rule")
        void nullPassword_failsLength() {
            assertThat(service.validatePasswordComplexity(null))
                    .isEqualTo("Password must be at least 8 characters.");
        }

        @Test
        @DisplayName("7 characters fails, 8 characters with all classes passes (boundary)")
        void lengthBoundary() {
            assertThat(service.validatePasswordComplexity("Abcde1!"))
                    .isEqualTo("Password must be at least 8 characters.");
            assertThat(service.validatePasswordComplexity("Abcdef1!")).isNull();
        }

        @Test
        @DisplayName("missing uppercase is rejected")
        void missingUppercase() {
            assertThat(service.validatePasswordComplexity("abcdefg1!"))
                    .isEqualTo("Password must contain at least one uppercase letter.");
        }

        @Test
        @DisplayName("missing lowercase is rejected")
        void missingLowercase() {
            assertThat(service.validatePasswordComplexity("ABCDEFG1!"))
                    .isEqualTo("Password must contain at least one lowercase letter.");
        }

        @Test
        @DisplayName("missing digit is rejected")
        void missingDigit() {
            assertThat(service.validatePasswordComplexity("Abcdefgh!"))
                    .isEqualTo("Password must contain at least one number.");
        }

        @Test
        @DisplayName("missing special character is rejected")
        void missingSpecial() {
            assertThat(service.validatePasswordComplexity("Abcdefg1"))
                    .isEqualTo("Password must contain at least one special character.");
        }
    }
}
