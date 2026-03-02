package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.TurnstileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuthController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppUserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private TurnstileService turnstileService;

    private AppUserEntity adminUser;

    @BeforeEach
    void setUp() {
        adminUser = AppUserEntity.builder()
                .id(1L)
                .username("admin")
                .password(passwordEncoder.encode("golden2026"))
                .role(UserRole.ADMIN)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();

        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Default: Turnstile always passes in tests
        when(turnstileService.verify(any())).thenReturn(true);
    }

    @Test
    @DisplayName("POST /api/auth/login returns 200 and tokens for valid credentials")
    void login_validCredentials_returns200WithTokens() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.refreshExpiresAt").exists());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 401 for wrong password")
    void login_wrongPassword_returns401() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 401 for unknown username")
    void login_unknownUser_returns401() throws Exception {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 401 for disabled user")
    void login_disabledUser_returns401() throws Exception {
        AppUserEntity disabled = AppUserEntity.builder()
                .id(2L)
                .username("inactive")
                .password(passwordEncoder.encode("password"))
                .role(UserRole.LITE_USER)
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .build();
        when(userRepository.findByUsername("inactive")).thenReturn(Optional.of(disabled));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inactive\",\"password\":\"password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 200 with new access and refresh tokens")
    void refresh_validToken_returns200() throws Exception {
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity stored = RefreshTokenEntity.builder()
                .id(1L)
                .tokenHash(hash)
                .userId(1L)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .revoked(false)
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshExpiresAt").exists());
    }

    @Test
    @DisplayName("POST /api/auth/refresh rotates token — old token is revoked")
    void refresh_validToken_revokesOldToken() throws Exception {
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity stored = RefreshTokenEntity.builder()
                .id(1L)
                .tokenHash(hash)
                .userId(1L)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .revoked(false)
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(refreshTokenRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(RefreshTokenEntity::isRevoked));
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 for unknown refresh token")
    void refresh_unknownToken_returns401() throws Exception {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 for revoked refresh token")
    void refresh_revokedToken_returns401() throws Exception {
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity revoked = RefreshTokenEntity.builder()
                .id(2L)
                .tokenHash(hash)
                .userId(1L)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .revoked(true)
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 200 for authenticated user with valid password")
    void changePassword_validPassword_returns200() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenReturn(adminUser);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated"));
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 400 when password fails complexity")
    void changePassword_weakPassword_returns400() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 401 without authentication")
    void changePassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Registration endpoint tests ---

    @Test
    @DisplayName("POST /api/auth/register returns 200 for valid registration")
    void register_valid_returns200() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(registrationService.register("newuser", "new@example.com", true)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"))
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 409 for duplicate username")
    void register_duplicateUsername_returns409() throws Exception {
        when(registrationService.register("taken", "new@example.com", true))
                .thenThrow(new IllegalArgumentException("Username already exists"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"taken\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already exists"));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 for invalid username format")
    void register_invalidUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"email\":\"valid@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 when Turnstile verification fails")
    void register_turnstileFailed_returns400() throws Exception {
        when(turnstileService.verify(any())).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"turnstileToken\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 for invalid email")
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validuser\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid email address"));
    }

    @Test
    @DisplayName("POST /api/auth/resend-verification returns 200 (always)")
    void resendVerification_returns200() throws Exception {
        doNothing().when(registrationService).resendVerification(anyString());

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"any@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /api/auth/resend-verification returns 429 when rate limited")
    void resendVerification_rateLimited_returns429() throws Exception {
        doThrow(new IllegalStateException("Too many verification requests"))
                .when(registrationService).resendVerification(anyString());

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"any@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/verify-email returns 200 with userId for valid token")
    void verifyEmail_valid_returns200() throws Exception {
        when(registrationService.verifyEmail("valid-token")).thenReturn(42L);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"valid-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("POST /api/auth/verify-email returns 400 for invalid token")
    void verifyEmail_invalid_returns400() throws Exception {
        when(registrationService.verifyEmail("bad-token"))
                .thenThrow(new IllegalArgumentException("Invalid verification token"));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid verification token"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 200 with tokens for valid request")
    void setPassword_valid_returns200WithTokens() throws Exception {
        AppUserEntity user = AppUserEntity.builder()
                .id(42L).username("newuser").password("hashed").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build();
        doNothing().when(registrationService).setPasswordAndActivate(42L, "MyP@ssw0rd!");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":42,\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.role").value("LITE_USER"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 for weak password")
    void setPassword_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":42,\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 when userId is missing")
    void setPassword_missingUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/login records lastActiveAt on successful login")
    void login_validCredentials_setsLastActiveAt() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(
                user -> user.getLastActiveAt() != null));
    }

    @Test
    @DisplayName("POST /api/auth/login response includes marketingEmailOptIn")
    void login_validCredentials_includesMarketingEmailOptIn() throws Exception {
        adminUser.setMarketingEmailOptIn(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingEmailOptIn").value(true));
    }

    @Test
    @DisplayName("PUT /api/auth/marketing-emails toggles opt-in preference")
    void marketingEmails_toggleOptIn_returns200() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenReturn(adminUser);

        mockMvc.perform(put("/api/auth/marketing-emails")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optIn\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingEmailOptIn").value(false));
    }

    @Test
    @DisplayName("PUT /api/auth/marketing-emails returns 401 without authentication")
    void marketingEmails_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/auth/marketing-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optIn\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/register passes marketing opt-in to service")
    void register_withMarketingOptInFalse_passesPreference() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now())
                .marketingEmailOptIn(false).build();
        when(registrationService.register("newuser", "new@example.com", false)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"marketingEmailOptIn\":\"false\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"));
    }

    @Test
    @DisplayName("POST /api/auth/logout returns 200")
    void logout_validRequest_returns200() throws Exception {
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity stored = RefreshTokenEntity.builder()
                .id(1L)
                .tokenHash(hash)
                .userId(1L)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .revoked(false)
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.save(any())).thenReturn(stored);

        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));
    }
}
