package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserRole;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-side read of the {@code role} claim of a JWT access token, decoded straight from the token's
 * payload segment. This was {@code JwtService#extractRole} until nothing in {@code src/main} called
 * it; decoding the payload directly also keeps the assertion independent of the service under test.
 */
final class JwtTestClaims {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ROLE_CLAIM = "role";

    private JwtTestClaims() {
    }

    /**
     * Reads the role an access token carries.
     *
     * @param token a signed JWT access token
     * @return the role named in its {@code role} claim
     */
    static UserRole roleOf(String token) {
        String payload = token.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UserRole.valueOf(MAPPER.readTree(json).get(ROLE_CLAIM).asString());
    }
}
