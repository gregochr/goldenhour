package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration properties for JWT token generation.
 *
 * <p>Bound from the {@code jwt} prefix in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** Base64-encoded HMAC-SHA256 signing secret (minimum 256 bits). */
    private String secret;

    /** Lifetime of issued access tokens in hours. */
    private int accessTokenExpiryHours = 24;

    /** Lifetime of issued refresh tokens in days. */
    private int refreshTokenExpiryDays = 30;
}
