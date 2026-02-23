package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockBean
    private AppUserRepository userRepository;

    @MockBean
    private RefreshTokenRepository refreshTokenRepository;

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
