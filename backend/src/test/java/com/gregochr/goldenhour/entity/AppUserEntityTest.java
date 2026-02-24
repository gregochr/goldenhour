package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppUserEntity}.
 *
 * <p>Covers the {@link org.springframework.security.core.userdetails.UserDetails} contract
 * and authority derivation logic.
 */
class AppUserEntityTest {

    @Test
    @DisplayName("getAuthorities() returns ROLE_ADMIN for an ADMIN user")
    void getAuthorities_adminRole_returnsRoleAdmin() {
        AppUserEntity user = buildUser(UserRole.ADMIN);

        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();

        assertThat(authorities).hasSize(1);
        assertThat(authorities.iterator().next().getAuthority()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("getAuthorities() returns ROLE_PRO_USER for a PRO_USER")
    void getAuthorities_proUserRole_returnsRoleProUser() {
        AppUserEntity user = buildUser(UserRole.PRO_USER);

        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PRO_USER");
    }

    @Test
    @DisplayName("getAuthorities() returns ROLE_LITE_USER for a LITE_USER")
    void getAuthorities_liteUserRole_returnsRoleLiteUser() {
        AppUserEntity user = buildUser(UserRole.LITE_USER);

        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_LITE_USER");
    }

    @Test
    @DisplayName("isAccountNonExpired() always returns true")
    void isAccountNonExpired_alwaysTrue() {
        assertThat(buildUser(UserRole.PRO_USER).isAccountNonExpired()).isTrue();
    }

    @Test
    @DisplayName("isAccountNonLocked() always returns true")
    void isAccountNonLocked_alwaysTrue() {
        assertThat(buildUser(UserRole.PRO_USER).isAccountNonLocked()).isTrue();
    }

    @Test
    @DisplayName("isCredentialsNonExpired() always returns true")
    void isCredentialsNonExpired_alwaysTrue() {
        assertThat(buildUser(UserRole.PRO_USER).isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("isEnabled() reflects the enabled field")
    void isEnabled_reflectsField() {
        assertThat(buildUser(UserRole.PRO_USER).isEnabled()).isTrue();
        assertThat(AppUserEntity.builder()
                .username("disabled")
                .password("hash")
                .role(UserRole.LITE_USER)
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .build()
                .isEnabled()).isFalse();
    }

    private AppUserEntity buildUser(UserRole role) {
        return AppUserEntity.builder()
                .id(1L)
                .username("testuser")
                .password("$2a$10$hashedpassword")
                .role(role)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .passwordChangeRequired(false)
                .build();
    }
}
