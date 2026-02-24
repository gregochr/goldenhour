package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST controller for authentication operations.
 *
 * <p>Handles login, token refresh, and logout. All endpoints under
 * {@code /api/auth} are public (no JWT required).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    /**
     * Authenticates the user and returns a JWT access token plus a refresh token.
     *
     * @param body map containing {@code username} and {@code password}
     * @return 200 with tokens, or 401 if credentials are invalid
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        AppUserEntity user = userRepository.findByUsername(username).orElse(null);

        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            LOG.warn("Login failed: user='{}' — bad credentials", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        if (!user.isEnabled()) {
            LOG.warn("Login failed: user='{}' — account disabled", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Account is disabled"));
        }

        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        String rawRefresh = jwtService.generateRefreshToken();
        String refreshHash = jwtService.hashToken(rawRefresh);
        Date expiresAt = jwtService.extractExpiry(accessToken);

        LocalDateTime refreshExpiresAt = LocalDateTime.now()
                .plusDays(jwtProperties.getRefreshTokenExpiryDays());
        RefreshTokenEntity tokenEntity = RefreshTokenEntity.builder()
                .tokenHash(refreshHash)
                .userId(user.getId())
                .expiresAt(refreshExpiresAt)
                .revoked(false)
                .build();
        refreshTokenRepository.save(tokenEntity);

        LOG.info("Login: user='{}' role={}", user.getUsername(), user.getRole());
        return ResponseEntity.ok(Map.of(
                "accessToken", accessToken,
                "refreshToken", rawRefresh,
                "role", user.getRole().name(),
                "expiresAt", expiresAt.toInstant().toString(),
                "refreshExpiresAt", refreshExpiresAt.toInstant(java.time.ZoneOffset.UTC).toString(),
                "passwordChangeRequired", user.isPasswordChangeRequired()));
    }

    /**
     * Changes the authenticated user's password and clears the {@code passwordChangeRequired} flag.
     *
     * <p>Enforces complexity: min 8 chars, upper, lower, digit, special character.
     *
     * @param body map containing {@code newPassword}
     * @return 200 on success, 400 if the password fails complexity rules, 401 if unauthenticated
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body) {
        String username = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String newPassword = body.get("newPassword");
        String complexityError = validatePasswordComplexity(newPassword);
        if (complexityError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", complexityError));
        }

        AppUserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        userRepository.save(user);

        LOG.info("Password changed: user='{}'", username);
        return ResponseEntity.ok(Map.of("message", "Password updated"));
    }

    /**
     * Returns {@code null} if the password meets all complexity requirements, or an error message.
     *
     * @param password the candidate password
     * @return error message, or {@code null} if valid
     */
    private String validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8) {
            return "Password must be at least 8 characters.";
        }
        if (!Pattern.compile("[A-Z]").matcher(password).find()) {
            return "Password must contain at least one uppercase letter.";
        }
        if (!Pattern.compile("[a-z]").matcher(password).find()) {
            return "Password must contain at least one lowercase letter.";
        }
        if (!Pattern.compile("[0-9]").matcher(password).find()) {
            return "Password must contain at least one number.";
        }
        if (!Pattern.compile("[^A-Za-z0-9]").matcher(password).find()) {
            return "Password must contain at least one special character.";
        }
        return null;
    }

    /**
     * Issues a new access token and rotates the refresh token.
     *
     * <p>The supplied refresh token is revoked and a fresh one is issued, extending
     * the session by another {@code jwt.refresh-token-expiry-days} days.
     *
     * @param body map containing {@code refreshToken}
     * @return 200 with new access and refresh tokens, or 401 if the token is invalid
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String rawRefresh = body.get("refreshToken");
        if (rawRefresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token required"));
        }

        String hash = jwtService.hashToken(rawRefresh);
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(hash).orElse(null);

        if (stored == null || stored.isRevoked()
                || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token is invalid or expired"));
        }

        AppUserEntity user = userRepository.findById(stored.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found or disabled"));
        }

        // Revoke old token (rotation — prevents reuse)
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        // Issue new access token
        String newAccessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        Date accessExpiresAt = jwtService.extractExpiry(newAccessToken);

        // Issue new refresh token
        String newRawRefresh = jwtService.generateRefreshToken();
        String newRefreshHash = jwtService.hashToken(newRawRefresh);
        LocalDateTime refreshExpiresAt = LocalDateTime.now()
                .plusDays(jwtProperties.getRefreshTokenExpiryDays());
        RefreshTokenEntity newToken = RefreshTokenEntity.builder()
                .tokenHash(newRefreshHash)
                .userId(user.getId())
                .expiresAt(refreshExpiresAt)
                .revoked(false)
                .build();
        refreshTokenRepository.save(newToken);

        return ResponseEntity.ok(Map.of(
                "accessToken", newAccessToken,
                "refreshToken", newRawRefresh,
                "expiresAt", accessExpiresAt.toInstant().toString(),
                "refreshExpiresAt", refreshExpiresAt.toInstant(java.time.ZoneOffset.UTC).toString()));
    }

    /**
     * Revokes the caller's refresh token, effectively logging them out.
     *
     * @param body map containing {@code refreshToken}
     * @return 200 on success
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody Map<String, String> body) {
        String rawRefresh = body.get("refreshToken");
        if (rawRefresh != null) {
            String hash = jwtService.hashToken(rawRefresh);
            refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
