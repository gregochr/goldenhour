package com.gregochr.goldenhour.controller;

import java.time.LocalDateTime;
import java.util.Optional;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The missing-input and refused-issue paths of {@code /api/auth/*}: a body that omits a required field is a 400
 * (or 401 for the refresh token) before any service is touched, and a token issuer that refuses is a 401 rather
 * than a 500.
 */
class AuthControllerInputGuardsTest extends AbstractControllerTest {

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
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(userRepository.lockAccount(1L)).thenReturn(Optional.of(new LockedAccount(true,
                adminUser.getPassword(), adminUser.getEmail(), "admin", UserRole.ADMIN,
                adminUser.getCredentialsVersion(), adminUser.getCredentialsChangedAt(), false,
                adminUser.isMarketingEmailOptIn(), adminUser.getTermsVersion())));
    }

    @Test
    @DisplayName("POST /api/auth/refresh without a refreshToken is a 401 asking for one")
    void refresh_missingToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Refresh token required"));
    }

    @Test
    @DisplayName("POST /api/auth/register without a username or an email is a 400 and registers nobody")
    void register_missingUsernameOrEmail_returns400() throws Exception {
        for (String body : new String[] {
                "{\"email\":\"a@example.com\"}",
                "{\"username\":\"   \",\"email\":\"a@example.com\"}",
                "{\"username\":\"newuser\"}",
                "{\"username\":\"newuser\",\"email\":\"  \"}"}) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Username and email are required"));
        }

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/resend-verification without an email is a 400 and sends nothing")
    void resendVerification_missingEmail_returns400() throws Exception {
        for (String body : new String[] {"{}", "{\"email\":\"  \"}"}) {
            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Email is required"));
        }

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/verify-email without a token is a 400 and verifies nothing")
    void verifyEmail_missingToken_returns400() throws Exception {
        for (String body : new String[] {"{}", "{\"token\":\"  \"}"}) {
            mockMvc.perform(post("/api/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Verification token is required"));
        }

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /api/auth/set-password with a blank password is a 400 that counts nothing")
    void setPassword_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"verify-token\",\"password\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("token and password are required"));

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("PUT /api/auth/marketing-emails without optIn is a 400 and writes nothing")
    void marketingEmails_missingOptIn_returns400() throws Exception {
        String jwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/auth/marketing-emails")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("optIn is required"));

        verify(userRepository, never()).updateMarketingEmailOptIn(1L, true);
        verify(userRepository, never()).updateMarketingEmailOptIn(1L, false);
    }

    @Test
    @DisplayName("PUT /api/auth/marketing-emails is a 401 when the signed-in account no longer exists")
    void marketingEmails_accountGone_returns401() throws Exception {
        String jwt = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser), Optional.empty());

        mockMvc.perform(put("/api/auth/marketing-emails")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optIn\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("User not found"));

        verify(userRepository, never()).updateMarketingEmailOptIn(1L, true);
    }

    @Test
    @DisplayName("an authentication that is not authenticated is never treated as a user: change-password is a "
            + "401 and the gate is not reached")
    void changePassword_unauthenticatedToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .with(authentication(UsernamePasswordAuthenticationToken.unauthenticated("admin", "x")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"golden2026\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Not authenticated"));

        verifyNoInteractions(turnstileGate);
    }
}
