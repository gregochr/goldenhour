package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Authorities#hasRole}: the {@code ROLE_} convention, tested in its one home. */
class AuthoritiesTest {

    private static Authentication authenticated(String... authorities) {
        return new UsernamePasswordAuthenticationToken("chris", "n/a",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    @DisplayName("holds the role exactly as the JWT filter grants it: ROLE_ + the enum name")
    void grantedRoleMatches() {
        Authentication admin = authenticated("ROLE_ADMIN");
        assertThat(Authorities.hasRole(admin, UserRole.ADMIN)).isTrue();
        assertThat(Authorities.hasRole(admin, UserRole.LITE_USER)).isFalse();
        assertThat(Authorities.hasRole(admin, UserRole.PRO_USER)).isFalse();
        assertThat(Authorities.ROLE_PREFIX + UserRole.LITE_USER.name()).isEqualTo("ROLE_LITE_USER");
    }

    @Test
    @DisplayName("a bare role name without the prefix is not a match — the convention is the contract")
    void bareNameIsNotARole() {
        assertThat(Authorities.hasRole(authenticated("ADMIN"), UserRole.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("null, anonymous and unauthenticated carry nothing")
    void nothingForNoOne() {
        assertThat(Authorities.hasRole(null, UserRole.ADMIN)).isFalse();
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(Authorities.hasRole(anonymous, UserRole.ADMIN)).isFalse();
        UsernamePasswordAuthenticationToken unauthenticated =
                new UsernamePasswordAuthenticationToken("chris", "n/a");
        assertThat(unauthenticated.isAuthenticated()).isFalse();
        assertThat(Authorities.hasRole(unauthenticated, UserRole.ADMIN)).isFalse();
        assertThat(Authorities.hasRole(authenticated("ROLE_ADMIN"), null)).isFalse();
    }
}
