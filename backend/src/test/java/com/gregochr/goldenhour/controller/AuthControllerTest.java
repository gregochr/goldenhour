package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.RegistrationClosedException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.RegistrationService.Activation;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.TurnstileGate.Endpoint;
import com.gregochr.goldenhour.service.TurnstileGate.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuthController}.
 */
class AuthControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

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
        known(adminUser);

        // Defaults: Turnstile always passes (register verifies directly; login and change-password go
        // through the gate), and every attempt bound admits. Tests that exercise a refusal restub.
        when(turnstileService.verify(any())).thenReturn(true);
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_VERIFIED);
        when(attemptLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    /**
     * Makes {@code user} a row the database "holds": found by name for the sign-in and the JWT filter, and
     * readable under the row lock (the scalar projection the issuer and the writer re-read).
     */
    private void known(AppUserEntity user) {
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        when(userRepository.lockAccount(user.getId())).thenReturn(Optional.of(lockedRow(user)));
    }

    /**
     * Makes the compare-and-set of {@code applyCredentialChange} succeed and the row, read under the lock
     * afterwards, hold the hash it was handed — as the database would — so the issuer's "the hash I was
     * given is the stored one" check passes.
     *
     * @return the hash the row currently holds
     */
    private AtomicReference<String> theRowHoldsWhatTheWriterWrites() {
        AtomicReference<String> written = new AtomicReference<>(adminUser.getPassword());
        when(userRepository.applyCredentialChange(eq(1L), anyString(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any())).thenAnswer(inv -> {
                    written.set(inv.getArgument(1));
                    return 1;
                });
        when(userRepository.lockAccount(1L)).thenAnswer(inv -> Optional.of(new LockedAccount(true, written.get(),
                adminUser.getEmail(), adminUser.getUsername(), adminUser.getRole(),
                adminUser.getCredentialsVersion(), adminUser.getCredentialsChangedAt(), false,
                adminUser.isMarketingEmailOptIn(), adminUser.getTermsVersion())));
        return written;
    }

    private static LockedAccount lockedRow(AppUserEntity user) {
        return new LockedAccount(user.isEnabled(), user.getPassword(), user.getEmail(), user.getUsername(),
                user.getRole(), user.getCredentialsVersion(), user.getCredentialsChangedAt(),
                user.isPasswordChangeRequired(), user.isMarketingEmailOptIn(), user.getTermsVersion());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 200 and tokens for valid credentials")
    void login_validCredentials_returns200WithTokens() throws Exception {
        known(adminUser);

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
        known(adminUser);

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
        known(disabled);

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
        when(refreshTokenRepository.revokeIfActive(hash)).thenReturn(1);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshExpiresAt").exists());
    }

    @Test
    @DisplayName("POST /api/auth/refresh rotates token — old token is consumed atomically")
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
        when(refreshTokenRepository.revokeIfActive(hash)).thenReturn(1);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(refreshTokenRepository).revokeIfActive(hash);
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 when the token was concurrently consumed")
    void refresh_concurrentlyConsumedToken_returns401AndRevokesFamily() throws Exception {
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
        when(refreshTokenRepository.revokeIfActive(hash)).thenReturn(0);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token is invalid or expired"));

        org.mockito.Mockito.verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
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
    @DisplayName("POST /api/auth/refresh returns 401 for expired refresh token")
    void refresh_expiredToken_returns401() throws Exception {
        String rawRefresh = jwtService.generateRefreshToken();
        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity expired = RefreshTokenEntity.builder()
                .id(3L)
                .tokenHash(hash)
                .userId(1L)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .revoked(false)
                .build();
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 for revoked refresh token and revokes the token family")
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

        org.mockito.Mockito.verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 200 for authenticated user with valid password")
    void changePassword_validPassword_returns200() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        AtomicReference<String> written = theRowHoldsWhatTheWriterWrites();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated"))
                // Fresh tokens for THIS device: the same body as /login.
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshExpiresAt").exists())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        assertThat(passwordEncoder.matches("NewPass1!", written.get())).isTrue();
    }

    @Test
    @DisplayName("POST /api/auth/change-password from a token with NO cv (issued before the counter) asks the "
            + "writer to accept only a never-changed row: clear the gate, active account, version 0 checked")
    void changePassword_legacyToken_asksForTheLegacyExpectation() throws Exception {
        String legacyJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        theRowHoldsWhatTheWriterWrites();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + legacyJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk());

        verify(userRepository).applyCredentialChange(eq(1L), anyString(), eq(1), eq(0), eq(1), eq(true),
                eq(0L), any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password from a token carrying cv asks the writer for EXACTLY that "
            + "version — read from the filter's own details, so the compare-and-set holds the row to it")
    void changePassword_tokenWithCv_asksForTheExactVersion() throws Exception {
        adminUser.setCredentialsVersion(3L);
        known(adminUser);
        String jwt = jwtService.generateAccessToken("admin", UserRole.ADMIN, 1L, 3L);
        theRowHoldsWhatTheWriterWrites();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk());

        verify(userRepository).applyCredentialChange(eq(1L), anyString(), eq(1), eq(0), eq(1), eq(true),
                eq(3L), any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password from a session that was superseded while the new password "
            + "was being hashed is a 401 'Your session has changed. Sign in again.', and issues NO tokens")
    void changePassword_staleSession_returns401WithoutTokens() throws Exception {
        adminUser.setCredentialsVersion(3L);
        known(adminUser);
        String jwt = jwtService.generateAccessToken("admin", UserRole.ADMIN, 1L, 3L);
        // The filter loaded the row at version 3; by the time the writer locks it, another change moved it.
        when(userRepository.lockAccount(1L)).thenReturn(Optional.of(
                new LockedAccount(true, adminUser.getPassword(), null, "admin", UserRole.ADMIN, 4L, null,
                        false, true, null)));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Your session has changed. Sign in again."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(userRepository, never()).applyCredentialChange(any(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 400 when password fails complexity")
    void changePassword_weakPassword_returns400() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/change-password with a missing or blank currentPassword is a 400 and spends "
            + "no attempt budget or credential write")
    void changePassword_missingCurrentPassword_returns400() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        for (String body : new String[] {"{\"newPassword\":\"NewPass1!\"}",
                "{\"currentPassword\":\"   \",\"newPassword\":\"NewPass1!\"}"}) {
            mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer " + validJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Current password is required"));
        }

        verify(attemptLimiter, never()).tryAcquire(eq("change-password:user:1"), eq(5), eq(Window.QUARTER_HOUR));
        verify(userRepository, never()).applyCredentialChange(any(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password with the WRONG current password is a 400 (never a 401, which "
            + "the client would refresh and retry) and changes nothing")
    void changePassword_wrongCurrentPassword_returns400() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Current password is incorrect"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(userRepository, never()).applyCredentialChange(any(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password with a new password equal to the current one is a 400")
    void changePassword_sameAsCurrent_returns400() throws Exception {
        adminUser.setPassword(passwordEncoder.encode("NewPass1!"));
        known(adminUser);
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"NewPass1!\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("New password must differ from the current one"));

        verify(userRepository, never()).applyCredentialChange(any(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password is a 429 with the shared sentence when the account's "
            + "change-password budget is spent, and changes nothing")
    void changePassword_accountBudgetSpent_returns429() throws Exception {
        when(attemptLimiter.tryAcquire(eq("change-password:user:1"), eq(5), eq(Window.QUARTER_HOUR)))
                .thenReturn(false);
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(userRepository, never()).applyCredentialChange(any(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 401 without authentication")
    void changePassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Registration endpoint tests ---

    @Test
    @DisplayName("POST /api/auth/register returns 200 for valid registration")
    void register_valid_returns200() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(registrationService.register("newuser", "new@example.com", false, null)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"))
                .andExpect(jsonPath("$.email").value("new@example.com"));

        // The field is absent, and an absent field is not consent (plan §1 #15).
        verify(registrationService).register("newuser", "new@example.com", false, null);
        verify(registrationService, never()).register("newuser", "new@example.com", true, null);
    }

    @Test
    @DisplayName("POST /api/auth/register hands the invite token to the service verbatim, with Turnstile still "
            + "verified")
    void register_withInviteToken_forwardsIt() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(registrationService.register("newuser", "new@example.com", false, "invite-raw")).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\",\"inviteToken\":\"invite-raw\","
                                + "\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk());

        verify(turnstileService).verify("tok");
        verify(registrationService).register("newuser", "new@example.com", false, "invite-raw");
    }

    @Test
    @DisplayName("POST /api/auth/register with an invite token and a failing Turnstile is a 400: the invite "
            + "does not waive the CAPTCHA, and is not redeemed")
    void register_withInviteTokenAndBadCaptcha_returns400() throws Exception {
        when(turnstileService.verify("bad")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\",\"inviteToken\":\"invite-raw\","
                                + "\"turnstileToken\":\"bad\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/register with an unusable invite is the ordinary 403, naming nothing about the "
            + "token")
    void register_withUnusableInvite_isTheOrdinary403() throws Exception {
        when(registrationService.register("newuser", "new@example.com", false, "stale"))
                .thenThrow(new RegistrationClosedException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\",\"inviteToken\":\"stale\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Early access is currently full"));
    }

    @Test
    @DisplayName("POST /api/auth/register stores opt-in only for an explicit true")
    void register_marketingOptInTrue_passesTrue() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(registrationService.register("newuser", "new@example.com", true, null)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"marketingEmailOptIn\":\"true\",\"termsAccepted\":\"true\"}"))
                .andExpect(status().isOk());

        verify(registrationService).register("newuser", "new@example.com", true, null);
    }

    @Test
    @DisplayName("POST /api/auth/register treats an unrecognised opt-in value as no consent")
    void register_marketingOptInGarbage_passesFalse() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(registrationService.register("newuser", "new@example.com", false, null)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"marketingEmailOptIn\":\"yes\",\"termsAccepted\":\"true\"}"))
                .andExpect(status().isOk());

        verify(registrationService).register("newuser", "new@example.com", false, null);
    }

    @Test
    @DisplayName("POST /api/auth/register refuses the anonymous principal's name, in any case")
    void register_anonymousUserName_returns409() throws Exception {
        for (String name : new String[] {"anonymousUser", "ANONYMOUSUSER"}) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + name + "\",\"email\":\"anon@example.com\","
                                    + "\"termsAccepted\":\"true\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Username already exists"));
        }

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/register is not served by the Turnstile gate: no token is still a 400")
    void register_neverUsesTheGate_andDoesNotFallBack() throws Exception {
        // A missing token fails the service's own check; there is no fallback on registration.
        when(turnstileService.verify(null)).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));

        verifyNoInteractions(turnstileGate);
        verify(registrationService, never()).register("newuser", "new@example.com", false, null);
    }

    @Test
    @DisplayName("POST /api/auth/register returns 409 for duplicate username")
    void register_duplicateUsername_returns409() throws Exception {
        when(registrationService.register("taken", "new@example.com", false, null))
                .thenThrow(new IllegalArgumentException("Username already exists"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"taken\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already exists"));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 for invalid username format")
    void register_invalidUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"email\":\"valid@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 when terms not accepted")
    void register_termsNotAccepted_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "You must accept the Terms & Conditions and Privacy Policy to register."));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 when termsAccepted is explicitly false")
    void register_termsExplicitlyFalse_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"false\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "You must accept the Terms & Conditions and Privacy Policy to register."));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 when Turnstile verification fails")
    void register_turnstileFailed_returns400() throws Exception {
        when(turnstileService.verify(any())).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\",\"turnstileToken\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));
    }

    @Test
    @DisplayName("POST /api/auth/register returns 400 for invalid email")
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"validuser\",\"email\":\"not-an-email\","
                                + "\"termsAccepted\":\"true\"}"))
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
        when(registrationService.verifyEmail("valid-token")).thenReturn(
                new RegistrationService.VerifiedEmail(42L, "setup-raw"));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"valid-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.setupToken").value("setup-raw"));
    }

    @Test
    @DisplayName("POST /api/auth/verify-email returns 400 for invalid token")
    void verifyEmail_invalid_returns400() throws Exception {
        when(registrationService.verifyEmail("bad-token"))
                .thenThrow(new IllegalArgumentException("This link has already been used"));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This link has already been used"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 200 with tokens for valid request")
    void setPassword_valid_returns200WithTokens() throws Exception {
        AppUserEntity user = AppUserEntity.builder()
                .id(42L).username("newuser").password("activated-hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(registrationService.setPasswordAndActivate("verify-token", "MyP@ssw0rd!"))
                .thenReturn(new Activation(42L, user.getPassword()));
        known(user);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.role").value("LITE_USER"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password response includes termsVersion from user record")
    void setPassword_valid_includesTermsVersion() throws Exception {
        AppUserEntity user = AppUserEntity.builder()
                .id(42L).username("newuser").password("activated-hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now())
                .termsVersion("April 2026").build();
        when(registrationService.setPasswordAndActivate("verify-token", "MyP@ssw0rd!"))
                .thenReturn(new Activation(42L, user.getPassword()));
        known(user);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsVersion").value("April 2026"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password ignores a caller-supplied userId")
    void setPassword_userIdInBody_isNotUsedToSelectAccount() throws Exception {
        AppUserEntity pending = AppUserEntity.builder()
                .id(42L).username("newuser").password("activated-hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(registrationService.setPasswordAndActivate("verify-token", "MyP@ssw0rd!"))
                .thenReturn(new Activation(42L, pending.getPassword()));
        known(pending);

        // userId 1 is the seeded ADMIN row — the token, not the body, decides whose password is set.
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"1\",\"token\":\"verify-token\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("LITE_USER"));

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findById(1L);
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 when the token is rejected")
    void setPassword_rejectedToken_returns400() throws Exception {
        when(registrationService.setPasswordAndActivate("stale-token", "MyP@ssw0rd!"))
                .thenThrow(new IllegalArgumentException("Invalid or expired verification token"));

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"stale-token\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or expired verification token"));
    }

    @Test
    @DisplayName("POST /api/auth/login response includes null termsVersion for pre-existing user")
    void login_preExistingUser_returnsNullTermsVersion() throws Exception {
        // Pre-existing users (registered before terms feature) have no termsVersion
        AppUserEntity preExisting = AppUserEntity.builder()
                .id(2L).username("olduser")
                .password(passwordEncoder.encode("OldP@ss1!"))
                .role(UserRole.LITE_USER).enabled(true)
                .createdAt(LocalDateTime.now()).build();
        known(preExisting);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"olduser\",\"password\":\"OldP@ss1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsVersion").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 for weak password")
    void setPassword_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 when the token is missing")
    void setPassword_missingToken_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("token and password are required"));
    }

    @Test
    @DisplayName("POST /api/auth/set-password returns 400 when the token is blank")
    void setPassword_blankToken_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"  \",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("token and password are required"));
    }

    @Test
    @DisplayName("POST /api/auth/login response includes termsVersion")
    void login_validCredentials_includesTermsVersion() throws Exception {
        adminUser.setTermsVersion("April 2026");
        known(adminUser);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsVersion").value("April 2026"));
    }

    @Test
    @DisplayName("POST /api/auth/login records lastActiveAt on successful login")
    void login_validCredentials_setsLastActiveAt() throws Exception {
        known(adminUser);

        LocalDateTime before = LocalDateTime.now();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<LocalDateTime> stamped = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).touchLastActiveAt(eq(1L), stamped.capture());
        assertThat(stamped.getValue()).isAfterOrEqualTo(before);
        // A scoped update: login never saves the entity it loaded before the BCrypt check.
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/login response includes marketingEmailOptIn")
    void login_validCredentials_includesMarketingEmailOptIn() throws Exception {
        adminUser.setMarketingEmailOptIn(true);
        known(adminUser);

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
        known(adminUser);

        mockMvc.perform(put("/api/auth/marketing-emails")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optIn\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingEmailOptIn").value(false));

        // The scoped update, never a save of an entity that may hold a stale role or hash.
        verify(userRepository).updateMarketingEmailOptIn(1L, false);
        verify(userRepository, never()).save(any());
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
        when(registrationService.register("newuser", "new@example.com", false, null)).thenReturn(pending);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"marketingEmailOptIn\":\"false\",\"termsAccepted\":\"true\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"));
    }

    // --- Turnstile gate: login ---

    private static final String TOO_MANY_SENTENCE =
            "Too many attempts. Please wait a few minutes and try again.";

    @Test
    @DisplayName("POST /api/auth/login returns 400 when the gate reports a failed CAPTCHA, and checks nothing else")
    void login_captchaFailed_returns400() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.CAPTCHA_FAILED);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\","
                                + "\"turnstileToken\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));

        verify(turnstileGate).admit("bad-token", "admin", "127.0.0.1", Endpoint.LOGIN);
        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 429 with the shared sentence when the gate rate-limits")
    void login_rateLimited_returns429() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.RATE_LIMITED);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    @DisplayName("POST /api/auth/login returns 429 for a verified token when the account has ten failed "
            + "sign-ins, without comparing the password")
    void login_accountFailureLimitSpent_returns429() throws Exception {
        when(attemptLimiter.isSpent("login:failures:user:1", 10, Window.QUARTER_HOUR)).thenReturn(true);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\","
                                + "\"turnstileToken\":\"good-token\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").exists());

        verify(attemptLimiter, never()).recordFailure("login:failures:user:1", Window.QUARTER_HOUR);
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("POST /api/auth/login hands the gate the CF-Connecting-IP address, then X-Forwarded-For's first hop")
    void login_clientAddress_followsTheHeaderRule() throws Exception {
        known(adminUser);

        mockMvc.perform(post("/api/auth/login")
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "198.51.100.1, 10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "198.51.100.1, 10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk());

        verify(turnstileGate).admit(null, "admin", "203.0.113.7", Endpoint.LOGIN);
        verify(turnstileGate).admit(null, "admin", "198.51.100.1", Endpoint.LOGIN);
    }

    @Test
    @DisplayName("POST /api/auth/login on the fallback takes the account's own budget once the user resolves")
    void login_fallback_takesThePerAccountBudget() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_FALLBACK);
        known(adminUser);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        verify(attemptLimiter).tryAcquire("fallback:login:user:1", 5, Window.QUARTER_HOUR);
    }

    @Test
    @DisplayName("POST /api/auth/login on the fallback returns 429, before the password is checked, "
            + "once the account's budget is spent")
    void login_fallback_accountBudgetSpent_returns429() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_FALLBACK);
        known(adminUser);
        when(attemptLimiter.tryAcquire("fallback:login:user:1", 5, Window.QUARTER_HOUR)).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        // Even the CORRECT password signs nobody in once the budget is spent.
        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/login on the fallback takes no account budget when no user resolves")
    void login_fallback_unknownUser_takesNoAccountBudget() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_FALLBACK);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());

        verify(attemptLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("POST /api/auth/login with a verified token never touches the account budget")
    void login_verified_takesNoAccountBudget() throws Exception {
        known(adminUser);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"golden2026\","
                                + "\"turnstileToken\":\"good\"}"))
                .andExpect(status().isOk());

        verify(turnstileGate).admit("good", "admin", "127.0.0.1", Endpoint.LOGIN);
        verify(attemptLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }

    // --- Turnstile gate: change-password ---

    @Test
    @DisplayName("POST /api/auth/change-password returns 400 when the gate reports a failed CAPTCHA")
    void changePassword_captchaFailed_returns400() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.CAPTCHA_FAILED);
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\","
                                + "\"turnstileToken\":\"bad-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));

        verify(turnstileGate).admit("bad-token", "admin", "127.0.0.1", Endpoint.CHANGE_PASSWORD);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password returns 429 with the shared sentence when rate-limited")
    void changePassword_rateLimited_returns429() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.RATE_LIMITED);
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/auth/change-password succeeds on the fallback with no token at all")
    void changePassword_fallback_returns200() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_FALLBACK);
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        theRowHoldsWhatTheWriterWrites();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated"));

        verify(turnstileGate).admit(null, "admin", "127.0.0.1", Endpoint.CHANGE_PASSWORD);
    }

    // --- The anonymous principal is not a user (plan §2 #14 rule 5) ---

    private AppUserEntity userNamedAnonymous() {
        return AppUserEntity.builder()
                .id(99L).username("anonymousUser").password(passwordEncoder.encode("Original1!"))
                .role(UserRole.LITE_USER).enabled(true).createdAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("POST /api/auth/change-password with no token returns 401 and never reaches the gate or a user "
            + "named anonymousUser")
    void changePassword_anonymousPrincipal_returns401() throws Exception {
        // Even if an account called "anonymousUser" existed, an unauthenticated call is not it.
        when(userRepository.findByUsername("anonymousUser")).thenReturn(Optional.of(userNamedAnonymous()));

        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Not authenticated"));

        verifyNoInteractions(turnstileGate);
        verify(userRepository, never()).findByUsername("anonymousUser");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /api/auth/marketing-emails with no token returns 401 and never reads or writes a user "
            + "named anonymousUser")
    void marketingEmails_anonymousPrincipal_returns401() throws Exception {
        when(userRepository.findByUsername("anonymousUser")).thenReturn(Optional.of(userNamedAnonymous()));

        mockMvc.perform(put("/api/auth/marketing-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optIn\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Not authenticated"));

        verify(userRepository, never()).findByUsername("anonymousUser");
        verify(userRepository, never()).save(any());
    }

    // --- set-password: no Turnstile, three bounds ---

    private static final String SET_PASSWORD_BODY = "{\"token\":\"verify-token\",\"password\":\"MyP@ssw0rd!\"}";

    private String tokenKeyOf(String rawToken) {
        return jwtService.hashToken(rawToken).substring(0, 16);
    }

    private void stubSetPasswordSuccess() {
        AppUserEntity user = AppUserEntity.builder()
                .id(42L).username("newuser").password("activated-hash").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(registrationService.setPasswordAndActivate("verify-token", "MyP@ssw0rd!"))
                .thenReturn(new Activation(42L, user.getPassword()));
        known(user);
    }

    @Test
    @DisplayName("POST /api/auth/set-password ignores a supplied turnstileToken: no gate, no verify, still 200")
    void setPassword_suppliedTurnstileToken_isIgnored() throws Exception {
        stubSetPasswordSuccess();
        // The old client's token would have failed verification; it must not matter any more.
        when(turnstileService.verify(any())).thenReturn(false);
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.CAPTCHA_FAILED);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"MyP@ssw0rd!\","
                                + "\"turnstileToken\":\"stale-client-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        verifyNoInteractions(turnstileGate);
        verify(turnstileService, never()).verify(any());
    }

    @Test
    @DisplayName("POST /api/auth/set-password counts the global, client and token bounds in that order, "
            + "with the token keyed on its hash")
    void setPassword_takesThreeBoundsOutermostFirst() throws Exception {
        stubSetPasswordSuccess();

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_PASSWORD_BODY))
                .andExpect(status().isOk());

        InOrder order = inOrder(attemptLimiter);
        order.verify(attemptLimiter).tryAcquire("set-password:global", 200, Window.HOUR);
        order.verify(attemptLimiter).tryAcquire("set-password:client:127.0.0.1", 20, Window.QUARTER_HOUR);
        order.verify(attemptLimiter).tryAcquire(
                "set-password:token:" + tokenKeyOf("verify-token"), 10, Window.QUARTER_HOUR);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("POST /api/auth/set-password keys the client bound on the forwarded address")
    void setPassword_clientBound_usesForwardedAddress() throws Exception {
        stubSetPasswordSuccess();

        mockMvc.perform(post("/api/auth/set-password")
                        .header("CF-Connecting-IP", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_PASSWORD_BODY))
                .andExpect(status().isOk());

        verify(attemptLimiter).tryAcquire("set-password:client:203.0.113.9", 20, Window.QUARTER_HOUR);
    }

    @Test
    @DisplayName("POST /api/auth/set-password: a refused global bound is a 429 and touches nothing after it")
    void setPassword_globalRefused_returns429AndStopsThere() throws Exception {
        when(attemptLimiter.tryAcquire("set-password:global", 200, Window.HOUR)).thenReturn(false);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_PASSWORD_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(attemptLimiter, never()).tryAcquire(eq("set-password:client:127.0.0.1"), anyInt(), any());
        verify(attemptLimiter, never()).tryAcquire(
                eq("set-password:token:" + tokenKeyOf("verify-token")), anyInt(), any());
        verify(registrationService, never()).setPasswordAndActivate(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/set-password: a refused client bound is a 429 and never reaches the token bound")
    void setPassword_clientRefused_returns429AndSkipsTheTokenBound() throws Exception {
        when(attemptLimiter.tryAcquire("set-password:client:127.0.0.1", 20, Window.QUARTER_HOUR))
                .thenReturn(false);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_PASSWORD_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(attemptLimiter, never()).tryAcquire(
                eq("set-password:token:" + tokenKeyOf("verify-token")), anyInt(), any());
        verify(registrationService, never()).setPasswordAndActivate(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/set-password: a refused token bound is a 429 and the token is never redeemed")
    void setPassword_tokenRefused_returns429() throws Exception {
        when(attemptLimiter.tryAcquire(
                "set-password:token:" + tokenKeyOf("verify-token"), 10, Window.QUARTER_HOUR))
                .thenReturn(false);

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_PASSWORD_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

        verify(registrationService, never()).setPasswordAndActivate(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/set-password: a malformed body is a 400 and counts against no bound")
    void setPassword_missingFields_countNothing() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(attemptLimiter);
    }

    @Test
    @DisplayName("POST /api/auth/set-password: a weak password is still a 400, after the bounds have counted")
    void setPassword_weakPassword_isCountedThenRefused() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());

        verify(attemptLimiter).tryAcquire("set-password:global", 200, Window.HOUR);
        verify(registrationService, never()).setPasswordAndActivate(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/logout with a live refresh token revokes it and bumps the credential version")
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

        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out"));

        verify(refreshTokenRepository).revokeIfActive(hash);
        verify(userRepository).bumpCredentialsVersion(1L);
    }

    @Test
    @DisplayName("POST /api/auth/logout with an authenticated Bearer and no refresh token bumps the version "
            + "for that principal")
    void logout_bearerOnly_bumpsForThePrincipal() throws Exception {
        String validJwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + validJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(userRepository).bumpCredentialsVersion(1L);
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("POST /api/auth/logout anonymously with an unknown token is a 200 that changes nothing -- "
            + "the anonymous principal is never a user")
    void logout_anonymousUnknownToken_changesNothing() throws Exception {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"nobody\"}"))
                .andExpect(status().isOk());

        verify(userRepository, never()).bumpCredentialsVersion(1L);
        verify(userRepository, never()).findByUsername("anonymousUser");
    }

    @Test
    @DisplayName("POST /api/auth/register returns 403 when registration cap is reached")
    void register_capReached_returns403() throws Exception {
        when(registrationService.register("newuser", "new@example.com", false, null))
                .thenThrow(new RegistrationClosedException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Early access is currently full"));
    }

    // --- login by username or email (plan §2 #10) ---

    @Test
    @DisplayName("POST /api/auth/login accepts the account's email in the username field "
            + "and hands the gate that identifier")
    void login_byEmail_returns200() throws Exception {
        AppUserEntity reader = AppUserEntity.builder()
                .id(7L).username("reader").email("Reader@Example.com")
                .password(passwordEncoder.encode("MyP@ssw0rd!")).role(UserRole.PRO_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(userRepository.findByUsername("Reader@Example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("Reader@Example.com")).thenReturn(Optional.of(reader));
        when(userRepository.lockAccount(7L)).thenReturn(Optional.of(lockedRow(reader)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Reader@Example.com\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.role").value("PRO_USER"));

        verify(turnstileGate).admit(null, "Reader@Example.com", "127.0.0.1", Endpoint.LOGIN);
    }

    @Test
    @DisplayName("POST /api/auth/login by an email nothing matches is the generic 401")
    void login_byUnknownEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost@example.com\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    // --- register: ASCII-only addresses and the 409 translation (plan §4 #21, §2 #14 rule 10) ---

    @Test
    @DisplayName("POST /api/auth/register refuses a non-ASCII email with its own sentence and registers nobody")
    void register_nonAsciiEmail_returns400() throws Exception {
        for (String email : new String[] {"bob@gmaıl.com", "josé@example.com"}) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"newuser\",\"email\":\"" + email + "\","
                                    + "\"termsAccepted\":\"true\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            "Please use an email address without accented or non-Latin characters"));
        }

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/register: a lost race on the email reservation is a 409 'Email already registered'")
    void register_emailReservationViolation_returns409() throws Exception {
        when(registrationService.register("newuser", "new@example.com", false, null))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: duplicate key value violates unique constraint "
                                + "\"pk_app_user_email_claim\"")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
    }

    @Test
    @DisplayName("POST /api/auth/register: a lost race on the username key is a 409 'Username already taken'")
    void register_usernameViolation_returns409() throws Exception {
        when(registrationService.register("newuser", "new@example.com", false, null))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: duplicate key value violates unique constraint "
                                + "\"app_user_username_key\"")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already taken"));
    }

    @Test
    @DisplayName("POST /api/auth/register: a violation of any other key is not dressed up as a 409")
    void register_otherViolation_isNotAConflict() throws Exception {
        when(registrationService.register("newuser", "new@example.com", false, null))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("null value in column \"role\" violates not-null constraint")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\","
                                + "\"termsAccepted\":\"true\"}"))
                .andExpect(status().is5xxServerError());
    }

    // --- forgot-password (plan §2 #1, §4 #8) ---

    private static final String FORGOT_SENTENCE = "If that email has an account, we've sent a reset link.";

    @Test
    @DisplayName("POST /api/auth/forgot-password answers the generic 200 and asks the service for a link "
            + "with the trimmed address")
    void forgotPassword_validEmail_returnsGeneric200() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  Reader@Example.com \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_SENTENCE));

        verify(passwordResetService).requestReset("Reader@Example.com");
        verify(turnstileGate).admit(null, "  Reader@Example.com ", "127.0.0.1", Endpoint.FORGOT);
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password answers the identical 200 whether or not the service sent anything")
    void forgotPassword_unknownEmail_sameBodyAsKnown() throws Exception {
        String known = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"known@example.com\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        doThrow(new IllegalStateException("database down")).when(passwordResetService)
                .requestReset("unknown@example.com");
        String unknown = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(unknown).isEqualTo(known);
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password returns 400 for a blank or malformed address and never reaches "
            + "the gate")
    void forgotPassword_badEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Email is required"));
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid email address"));

        verifyNoInteractions(turnstileGate);
        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password: a failed CAPTCHA is a 400 and sends nothing")
    void forgotPassword_captchaFailed_returns400() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.CAPTCHA_FAILED);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\",\"turnstileToken\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));

        verify(turnstileGate).admit("bad", "reader@example.com", "127.0.0.1", Endpoint.FORGOT);
        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password: a refused fallback bound is the generic 200, never a 429, "
            + "and sends nothing")
    void forgotPassword_rateLimited_isTheGeneric200() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.RATE_LIMITED);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_SENTENCE));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password on the fallback path still sends")
    void forgotPassword_fallbackAdmitted_sends() throws Exception {
        when(turnstileGate.admit(any(), any(), any(), any())).thenReturn(Outcome.OK_FALLBACK);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\"}"))
                .andExpect(status().isOk());

        verify(passwordResetService).requestReset("reader@example.com");
    }

    // --- reset-password (plan §2 #1, §2 #4) ---

    private static final String RESET_BODY = "{\"token\":\"reset-token\",\"password\":\"MyP@ssw0rd!\"}";

    private AuthTokens signedInTokens() {
        return new AuthTokens("access-jwt", "refresh-raw", Instant.parse("2026-10-17T10:00:00Z"),
                Instant.parse("2026-11-16T10:00:00Z"), "reader", "PRO_USER", false, false, "April 2026");
    }

    @Test
    @DisplayName("POST /api/auth/reset-password returns the /login-shaped body, signed in, with "
            + "passwordChangeRequired false")
    void resetPassword_validLink_returns200WithTokens() throws Exception {
        when(passwordResetService.resetPassword("reset-token", "MyP@ssw0rd!")).thenReturn(signedInTokens());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESET_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-raw"))
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.role").value("PRO_USER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.termsVersion").value("April 2026"));

        verifyNoInteractions(turnstileGate);
    }

    @Test
    @DisplayName("POST /api/auth/reset-password is a 401, not a 500, when the account changed again between "
            + "the committed reset and the auto-login")
    void resetPassword_issuerRefusesAfterTheReset_returns401() throws Exception {
        when(passwordResetService.resetPassword("reset-token", "MyP@ssw0rd!"))
                .thenThrow(new com.gregochr.goldenhour.exception.InvalidCredentialsException(
                        "Invalid username or password"));

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token\",\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    @DisplayName("POST /api/auth/reset-password counts the global, client and token bounds in that order, "
            + "under the reset: prefix, the token keyed on its hash")
    void resetPassword_takesThreeBoundsOutermostFirst() throws Exception {
        when(passwordResetService.resetPassword(any(), any())).thenReturn(signedInTokens());

        mockMvc.perform(post("/api/auth/reset-password")
                        .header("CF-Connecting-IP", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESET_BODY))
                .andExpect(status().isOk());

        InOrder order = inOrder(attemptLimiter);
        order.verify(attemptLimiter).tryAcquire("reset:global", 200, Window.HOUR);
        order.verify(attemptLimiter).tryAcquire("reset:client:203.0.113.9", 20, Window.QUARTER_HOUR);
        order.verify(attemptLimiter).tryAcquire("reset:token:" + tokenKeyOf("reset-token"), 10,
                Window.QUARTER_HOUR);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("POST /api/auth/reset-password: each refused bound is a 429 with the shared sentence and the "
            + "link is never redeemed")
    void resetPassword_anyBoundRefused_returns429() throws Exception {
        String[][] bounds = {
            {"reset:global", "HOUR"},
            {"reset:client:127.0.0.1", "QUARTER_HOUR"},
            {"reset:token:" + tokenKeyOf("reset-token"), "QUARTER_HOUR"}};
        for (String[] bound : bounds) {
            when(attemptLimiter.tryAcquire(eq(bound[0]), anyInt(), any())).thenReturn(false);

            mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESET_BODY))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error").value(TOO_MANY_SENTENCE));

            when(attemptLimiter.tryAcquire(eq(bound[0]), anyInt(), any())).thenReturn(true);
        }

        verify(passwordResetService, never()).resetPassword(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/reset-password: a missing field is a 400 and counts against no bound")
    void resetPassword_missingFields_countNothing() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"MyP@ssw0rd!\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(attemptLimiter);
        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/auth/reset-password: a weak password is a 400 with the complexity sentence "
            + "before the link is looked up")
    void resetPassword_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password must be at least 8 characters."));

        verify(passwordResetService, never()).resetPassword(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/reset-password: an unknown, used or expired link is a 400 carrying the "
            + "service's sentence")
    void resetPassword_refusedLink_returns400WithItsSentence() throws Exception {
        for (String sentence : new String[] {"This reset link is invalid",
                "This reset link has already been used", "This reset link has expired"}) {
            doThrow(new IllegalArgumentException(sentence)).when(passwordResetService)
                    .resetPassword("reset-token", "MyP@ssw0rd!");

            mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RESET_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(sentence));
        }
    }
}
