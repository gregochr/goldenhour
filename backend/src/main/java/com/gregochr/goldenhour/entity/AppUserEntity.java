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
 *
 * <p><strong>The eight settings columns are {@code updatable = false}, deliberately.</strong> The
 * home postcode and coordinates, the local radius, the drive-time stamp, the map colour scale, the
 * map tide mode and the Coming-up last-seen instant are written only by the column-scoped updates on
 * {@link com.gregochr.goldenhour.repository.AppUserRepository}, never by {@code save()} on this
 * entity. It carries neither {@code @Version} nor {@code @DynamicUpdate}, so a whole-entity save
 * writes every updatable column from whatever copy it holds, and several callers hold a copy across
 * a gap: the JWT filter's hourly last-active write, login's (a BCrypt check wide), a password
 * change, the marketing opt-in and the admin paths. Any of them could write back a home, a colour or
 * a stamp read before a concurrent settings save committed, and silently undo it. Taking the
 * settings columns out of every entity UPDATE means no such save can reach them.
 * {@code @DynamicUpdate} would not have been enough: a detached copy merged back counts its stale
 * values as changes, and writes them.
 *
 * <p>⚠️ The consequence to know: setting one of these fields on an existing row and calling
 * {@code save()} writes nothing to that column. Inserts are unaffected, and so is reading.
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

    /**
     * UK postcode for the user's home location (e.g. "DH1 3LE").
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateHome}.
     */
    @Column(name = "home_postcode", length = 10, updatable = false)
    private String homePostcode;

    /**
     * Latitude of the user's home location, resolved from postcode.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateHome}.
     */
    @Column(name = "home_latitude", updatable = false)
    private Double homeLatitude;

    /**
     * Longitude of the user's home location, resolved from postcode.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateHome}.
     */
    @Column(name = "home_longitude", updatable = false)
    private Double homeLongitude;

    /**
     * The caller's "close to home" radius in miles, or {@code null} when never chosen.
     *
     * <p>Null rather than a defaulted 22 so the column distinguishes "left alone" from
     * "deliberately set to 22" — {@code CloseToHomeService} applies the default when reading.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateHome}.
     */
    @Column(name = "local_radius_miles", updatable = false)
    private Integer localRadiusMiles;

    /**
     * When per-user drive times were last calculated from the home location.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Set only by
     * {@code AppUserRepository.stampDriveTimesIfHomeIs}, which stamps only while the home is still
     * the one the drive times were measured from, and cleared by
     * {@code AppUserRepository.clearDriveTimesCalculatedAt} when the home moves.
     */
    @Column(name = "drive_times_calculated_at", updatable = false)
    private Instant driveTimesCalculatedAt;

    /**
     * Which ramp paints the heat field, markers and map legend for this caller — {@code "temp"}
     * or {@code "verdict"}, or {@code null} when never chosen.
     *
     * <p>Null rather than a defaulted {@code "verdict"}, matching {@link #localRadiusMiles}'s
     * reasoning: it is what lets a later stage change the DEFAULT for callers who never chose
     * without overriding anyone who explicitly picked one.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateMapColourScaleByUsername}.
     */
    @Column(name = "map_colour_scale", length = 10, updatable = false)
    private String mapColourScale;

    /**
     * Whether the Map tab's phone tide cues show — {@code "auto"}, {@code "always"} or
     * {@code "off"}, or {@code null} when never chosen (the client reads null as {@code "auto"}).
     *
     * <p>Null rather than a defaulted {@code "auto"}, matching {@link #mapColourScale}'s reasoning:
     * it lets a later stage change what "never chosen" resolves to without overriding anyone who
     * explicitly picked a mode.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateMapTideModeByUsername}.
     */
    @Column(name = "map_tide_mode", length = 10, updatable = false)
    private String mapTideMode;

    /**
     * When this user last opened the "Coming up" tab, or {@code null} when they never have.
     *
     * <p>The tab badge's only stored state (V152, plan D3): arrivals are computable from the
     * shared almanac payload, so "what is new <em>to you</em>" needs only this one per-user
     * instant. Null renders as no badge and no NEW flags — a brand-new account opens quiet — and
     * the first tab open converts it to now via the client's quiet bootstrap write. Stored as the
     * instant; the Europe/London civil date the client compares against is derived at serve time
     * ({@code ForecastHorizon.civilDate}) so the timezone rule lives in one place.
     *
     * <p>Not updatable through the entity — see the class Javadoc. Written by
     * {@code AppUserRepository.updateComingUpLastSeenAtByUsername}.
     */
    @Column(name = "coming_up_last_seen_at", updatable = false)
    private Instant comingUpLastSeenAt;

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
