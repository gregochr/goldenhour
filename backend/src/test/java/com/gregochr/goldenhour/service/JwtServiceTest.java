package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Pattern;

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

    @Test
    @DisplayName("extractIssuedAt returns an Instant close to now")
    void extractIssuedAt_returnsInstantCloseToNow() {
        Instant before = Instant.now().minusSeconds(1);
        String token = jwtService.generateAccessToken("alice", UserRole.ADMIN);
        Instant issuedAt = jwtService.extractIssuedAt(token);

        assertThat(issuedAt).isNotNull();
        assertThat(issuedAt).isAfterOrEqualTo(before);
        assertThat(issuedAt).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("Access token expiry is approximately 24 hours from now")
    void generateAccessToken_expiryIsApproximately24HoursFromNow() {
        Instant before = Instant.now().plus(23, ChronoUnit.HOURS);
        Instant after = Instant.now().plus(25, ChronoUnit.HOURS);

        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER);
        Instant expiry = jwtService.extractExpiry(token).toInstant();

        assertThat(expiry).isAfter(before);
        assertThat(expiry).isBefore(after);
    }

    @ParameterizedTest(name = "extractRole round-trips {0}")
    @EnumSource(UserRole.class)
    @DisplayName("extractRole returns the correct role for every role value")
    void extractRole_roundTripsAllRoles(UserRole role) {
        String token = jwtService.generateAccessToken("alice", role);
        assertThat(jwtService.extractRole(token)).isEqualTo(role);
    }

    @Test
    @DisplayName("generateRefreshToken produces a UUID-format string")
    void generateRefreshToken_isUuidFormat() {
        Pattern uuid = Pattern.compile(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        String refresh = jwtService.generateRefreshToken();
        assertThat(refresh).matches(uuid);
    }

    @Test
    @DisplayName("Two calls to generateRefreshToken produce different tokens")
    void generateRefreshToken_producesUniqueValues() {
        assertThat(jwtService.generateRefreshToken())
                .isNotEqualTo(jwtService.generateRefreshToken());
    }

    @Test
    @DisplayName("hashToken produces the known SHA-256 hex digest for 'hello'")
    void hashToken_knownInput_producesKnownOutput() {
        // echo -n "hello" | sha256sum → 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(jwtService.hashToken("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("Two different tokens produce different hashes")
    void hashToken_differentInputs_produceDifferentHashes() {
        assertThat(jwtService.hashToken("token-a"))
                .isNotEqualTo(jwtService.hashToken("token-b"));
    }

    @Test
    @DisplayName("validateToken returns false for null input")
    void validateToken_nullInput_returnsFalse() {
        assertThat(jwtService.validateToken(null)).isFalse();
    }
}
