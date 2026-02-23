package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JwtService}.
 */
class JwtServiceTest {

    /** A deterministic 256-bit Base64-encoded secret for tests. */
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("test-secret-key-for-testing-only-32".getBytes());

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(TEST_SECRET);
        props.setAccessTokenExpiryHours(24);
        props.setRefreshTokenExpiryDays(30);
        jwtService = new JwtService(props);
    }

    @Test
    @DisplayName("Generated access token contains correct subject claim")
    void generateAccessToken_containsCorrectSubject() {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER);
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
    }

    @Test
    @DisplayName("Generated access token contains correct role claim")
    void generateAccessToken_containsCorrectRole() {
        String token = jwtService.generateAccessToken("alice", UserRole.ADMIN);
        assertThat(jwtService.extractRole(token)).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("Valid token passes validation")
    void validateToken_validToken_returnsTrue() {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER);
        assertThat(jwtService.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("Malformed token fails validation")
    void validateToken_malformedToken_returnsFalse() {
        assertThat(jwtService.validateToken("this.is.not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("Empty string fails validation")
    void validateToken_emptyString_returnsFalse() {
        assertThat(jwtService.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("Token signed with a different key fails validation")
    void validateToken_wrongKey_returnsFalse() {
        String wrongSecret = Base64.getEncoder()
                .encodeToString("completely-different-secret-key!!".getBytes());
        JwtProperties wrongProps = new JwtProperties();
        wrongProps.setSecret(wrongSecret);
        wrongProps.setAccessTokenExpiryHours(1);
        wrongProps.setRefreshTokenExpiryDays(1);
        JwtService otherService = new JwtService(wrongProps);

        String tokenFromOther = otherService.generateAccessToken("alice", UserRole.LITE_USER);
        assertThat(jwtService.validateToken(tokenFromOther)).isFalse();
    }

    @Test
    @DisplayName("hashToken produces a 64-character lowercase hex string")
    void hashToken_producesCorrectLength() {
        String hash = jwtService.hashToken("some-refresh-token-value");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("hashToken is deterministic — same input produces same output")
    void hashToken_isDeterministic() {
        String token = "my-refresh-token";
        assertThat(jwtService.hashToken(token)).isEqualTo(jwtService.hashToken(token));
    }

    @Test
    @DisplayName("generateRefreshToken produces a non-blank string")
    void generateRefreshToken_producesNonBlankValue() {
        String refresh = jwtService.generateRefreshToken();
        assertThat(refresh).isNotBlank();
    }

    @Test
    @DisplayName("extractExpiry returns a non-null Date")
    void extractExpiry_returnsNonNullDate() {
        String token = jwtService.generateAccessToken("bob", UserRole.LITE_USER);
        assertThat(jwtService.extractExpiry(token)).isNotNull();
    }
}
