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

import java.time.Instant;
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

    /** Email address for notifications and identification. */
    @Column(length = 255)
    private String email;

    /** Whether the user must change their password before accessing the app. */
    @Column(nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean passwordChangeRequired;

    /** Whether the user has opted in to marketing emails (feature updates, photography tips). */
    @Builder.Default
    @Column(name = "marketing_email_opt_in", nullable = false)
    private boolean marketingEmailOptIn = true;

    /** Timestamp of the user's most recent activity (updated at most once per hour). */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /** UK postcode for the user's home location (e.g. "DH1 3LE"). */
    @Column(name = "home_postcode", length = 10)
    private String homePostcode;

    /** Latitude of the user's home location, resolved from postcode. */
    @Column(name = "home_latitude")
    private Double homeLatitude;

    /** Longitude of the user's home location, resolved from postcode. */
    @Column(name = "home_longitude")
    private Double homeLongitude;

    /**
     * The caller's "close to home" radius in miles, or {@code null} when never chosen.
     *
     * <p>Null rather than a defaulted 22 so the column distinguishes "left alone" from
     * "deliberately set to 22" — {@code CloseToHomeService} applies the default when reading.
     */
    @Column(name = "local_radius_miles")
    private Integer localRadiusMiles;

    /** When per-user drive times were last calculated from the home location. */
    @Column(name = "drive_times_calculated_at")
    private Instant driveTimesCalculatedAt;

    /**
     * Which ramp paints the heat field, markers and map legend for this caller — {@code "temp"}
     * or {@code "verdict"}, or {@code null} when never chosen.
     *
     * <p>Null rather than a defaulted {@code "verdict"}, matching {@link #localRadiusMiles}'s
     * reasoning: it is what lets a later stage change the DEFAULT for callers who never chose
     * without overriding anyone who explicitly picked one.
     */
    @Column(name = "map_colour_scale", length = 10)
    private String mapColourScale;

    /** When the user accepted the Terms &amp; Conditions. */
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    /** The version of the Terms &amp; Conditions the user accepted (e.g. "April 2026"). */
    @Column(name = "terms_version", length = 20)
    private String termsVersion;

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

    /**
     * Returns whether this account may log in.
     *
     * <p>Written out by hand — rather than left to {@code @Getter} like every other accessor —
     * because {@link UserDetails#isEnabled()} is a <em>default</em> method returning {@code true}.
     * If Lombok ever stopped generating this accessor under that exact name (the field renamed,
     * or widened from {@code boolean} to {@code Boolean}, which yields {@code getEnabled()}), the
     * class would silently inherit the default and authenticate every disabled account. The
     * {@code @Override} makes that a compile error instead. The two abstract accessors
     * ({@code getUsername}, {@code getPassword}) need no such guard — dropping either fails to
     * compile on its own.
     *
     * @return {@code true} if the account is enabled
     */
    @Override
    public boolean isEnabled() {
        return enabled;
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
