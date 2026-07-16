package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * Result of a successful authentication or token refresh: the issued token pair plus the user
 * fields the auth API returns alongside it.
 *
 * @param accessToken            the signed JWT access token
 * @param refreshToken           the raw (unhashed) refresh token to hand to the client
 * @param accessTokenExpiresAt   expiry instant of the access token
 * @param refreshTokenExpiresAt  expiry instant of the refresh token
 * @param username               the authenticated user's username
 * @param role                   the user's role name
 * @param passwordChangeRequired whether the user must change their password before proceeding
 * @param marketingEmailOptIn    the user's marketing email preference
 * @param termsVersion           the terms version the user accepted, or {@code null} for
 *                               pre-terms accounts
 */
public record AuthTokens(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        String username,
        String role,
        boolean passwordChangeRequired,
        boolean marketingEmailOptIn,
        String termsVersion) {
}
