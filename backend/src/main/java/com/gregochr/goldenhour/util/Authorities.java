package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The one place a {@link UserRole} is tested against an {@link Authentication}'s granted
 * authorities outside a {@code @PreAuthorize} expression.
 *
 * <p>{@code JwtAuthenticationFilter} and {@code AppUserEntity} grant every role as
 * {@code "ROLE_" + role.name()} — the prefix Spring Security's {@code hasRole()} expects — so a
 * hand-written check has to spell that convention out, and two sites had each spelled it out on
 * their own ({@code ForecastController}'s LITE test and {@code RewindFilter}'s ADMIN test). This
 * keeps the prefix and the test in one place, beside the other utilities every layer may read.
 */
public final class Authorities {

    /** The prefix Spring Security's {@code hasRole()} adds, and the two granting sites write. */
    public static final String ROLE_PREFIX = "ROLE_";

    private Authorities() {
    }

    /**
     * Whether the authentication carries the given role. A null or unauthenticated
     * {@link Authentication} carries nothing.
     *
     * @param auth the current authentication, or null when there is none
     * @param role the role to test for
     * @return true when {@code auth} is authenticated and holds {@code ROLE_<role>}
     */
    public static boolean hasRole(Authentication auth, UserRole role) {
        if (auth == null || !auth.isAuthenticated() || role == null) {
            return false;
        }
        String wanted = ROLE_PREFIX + role.name();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(wanted::equals);
    }
}
