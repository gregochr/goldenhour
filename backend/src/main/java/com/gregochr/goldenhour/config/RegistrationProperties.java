package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.registration} section of {@code application.yml}.
 *
 * <p>Controls the early-access registration cap, default role for new signups,
 * and whether self-registration is enabled at all.
 */
@Component
@ConfigurationProperties(prefix = "photocast.registration")
@Getter
@Setter
public class RegistrationProperties {

    /** Maximum number of non-admin users allowed to register. Defaults to 3. */
    private int maxUsers = 3;

    /** Role assigned to new self-registered users. Defaults to {@code PRO_USER}. */
    private String defaultRole = "PRO_USER";

    /** Whether self-registration is open. Defaults to {@code true}. */
    private boolean enabled = true;
}
