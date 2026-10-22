package com.gregochr.goldenhour.config;

/**
 * What {@link JwtAuthenticationFilter} puts on the {@code Authentication}'s details: the credential
 * version the caller's access token was issued at.
 *
 * <p>A type of the filter's own, read only by {@code instanceof} — never a cast of Spring's
 * {@code WebAuthenticationDetails} — so that an endpoint that needs the caller's session version
 * ({@code POST /api/auth/change-password}) cannot mistake any other kind of authentication for one
 * (login-registration plan §2 #14 rule 5).
 *
 * @param credentialsVersion the token's {@code cv} claim, or {@code null} for a token issued before the
 *                           claim existed (the legacy window)
 */
public record JwtAuthenticationDetails(Long credentialsVersion) {
}
