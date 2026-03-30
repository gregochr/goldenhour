package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Service for generating, validating, and parsing JWT access tokens and refresh tokens.
 *
 * <p>Access tokens are signed HMAC-SHA256 JWTs. Refresh tokens are random UUIDs
 * whose SHA-256 hash is stored in the database.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    /** Claim key for the user's role. */
    private static final String CLAIM_ROLE = "role";

    /** SHA-256 algorithm name. */
    private static final String SHA_256 = "SHA-256";

    private final JwtProperties jwtProperties;

    /**
     * Generates a signed JWT access token for the given user.
     *
     * @param username the subject of the token
     * @param role     the user's role, embedded as a claim
     * @return the compact serialised JWT string
     */
    public String generateAccessToken(String username, UserRole role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getAccessTokenExpiryHours(), ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey())
                .compact();
    }

    /**
     * Generates a random UUID string to be used as a refresh token.
     * The caller is responsible for hashing and persisting it.
     *
     * @return a random UUID string
     */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Computes a deterministic SHA-256 hex digest of the given token.
     * This is stored in the database instead of the raw token value.
     *
     * @param token the raw token string
     * @return the lowercase hex SHA-256 digest (64 characters)
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Validates a JWT access token by verifying its signature and checking it has not expired.
     *
     * @param token the compact JWT string
     * @return {@code true} if the token is valid and not expired
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Extracts the subject (username) from a JWT access token.
     *
     * @param token the compact JWT string
     * @return the {@code sub} claim value
     */
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the role from a JWT access token.
     *
     * @param token the compact JWT string
     * @return the {@link UserRole} embedded in the token
     */
    public UserRole extractRole(String token) {
        String roleName = parseClaims(token).get(CLAIM_ROLE, String.class);
        return UserRole.valueOf(roleName);
    }

    /**
     * Extracts the expiry time from a JWT access token.
     *
     * @param token the compact JWT string
     * @return the expiry {@link Date}
     */
    public Date extractExpiry(String token) {
        return parseClaims(token).getExpiration();
    }

    /**
     * Extracts the issued-at time from a JWT access token.
     *
     * @param token the compact JWT string
     * @return the {@link Instant} when the token was issued
     */
    public Instant extractIssuedAt(String token) {
        return parseClaims(token).getIssuedAt().toInstant();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
