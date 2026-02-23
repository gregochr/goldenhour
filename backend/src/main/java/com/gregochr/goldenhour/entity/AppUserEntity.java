package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * JPA entity representing an application user.
 *
 * <p>Implements {@link UserDetails} so Spring Security can load and authenticate users
 * directly from the database. Passwords are stored as BCrypt hashes.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUserEntity implements UserDetails {

    /** Serial version UID for {@link java.io.Serializable} compliance (via {@link UserDetails}). */
    private static final long serialVersionUID = 1L;

    /** Primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique login name. */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt-hashed password. */
    @Column(nullable = false)
    private String password;

    /** Application role — ADMIN, PRO_USER, or LITE_USER. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    /** Whether this account may log in. */
    @Column(nullable = false)
    private boolean enabled;

    /** When the account was created. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Whether the user must change their password before accessing the app. */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean passwordChangeRequired;

    /**
     * Returns a single {@link GrantedAuthority} derived from {@link #role}.
     * Spring Security requires the {@code ROLE_} prefix for {@code hasRole()} expressions.
     *
     * @return authorities list containing exactly one entry
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
