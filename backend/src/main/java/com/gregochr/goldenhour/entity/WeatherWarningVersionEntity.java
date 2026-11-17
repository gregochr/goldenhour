package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One version of one Met Office weather warning (V224). Insert-only: every column is {@code updatable = false}, the
 * repository has no delete, and a cancellation is a later version, not an edit. Weather types and levels are free
 * strings by design; no {@code @Enumerated} column, so {@code LocalH2EnumWidener} is untouched.
 */
@Entity
@Table(name = "weather_warning_version")
@Getter
@Setter
@NoArgsConstructor
public class WeatherWarningVersionEntity {

    /** Status of a live warning version. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Status of a cancellation version. */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** The Met Office warning id. */
    @Column(name = "warning_id", nullable = false, length = 100, updatable = false)
    private String warningId;

    /** The warning's version, a decimal such as 3.0 (NUMERIC(10,2), compared numerically, never as text). */
    @Column(name = "warning_version", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal warningVersion;

    /**
     * {@link #STATUS_ACTIVE} (feed ISSUED) or {@link #STATUS_CANCELLED} (feed CANCELLED or EXPIRED: the warning has
     * ended), normalised from the feed's word.
     */
    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    /** The feed's own status word, kept as received. */
    @Column(name = "source_status", length = 50, updatable = false)
    private String sourceStatus;

    /** The level word, upper case (YELLOW, AMBER, RED ...). */
    @Column(name = "warning_level", length = 30, updatable = false)
    private String warningLevel;

    /** JSON array text of the weather types as received. */
    @Column(name = "weather_types", columnDefinition = "TEXT", updatable = false)
    private String weatherTypes;

    /** Likelihood as the raw feed integer (1 to 4), or null. */
    @Column(name = "likelihood", updatable = false)
    private Integer likelihood;

    /** Impact as the raw feed integer (1 to 4), or null. */
    @Column(name = "impact", updatable = false)
    private Integer impact;

    /** Start of validity. */
    @Column(name = "valid_from", updatable = false)
    private Instant validFrom;

    /** End of validity. */
    @Column(name = "valid_to", updatable = false)
    private Instant validTo;

    /** When the Met Office issued the warning. */
    @Column(name = "issued_at", updatable = false)
    private Instant issuedAt;

    /** When the Met Office last modified the warning. */
    @Column(name = "modified_at", updatable = false)
    private Instant modifiedAt;

    /** The Met Office headline. */
    @Column(name = "headline", columnDefinition = "TEXT", updatable = false)
    private String headline;

    /** JSON array text of the "what to expect" lines. */
    @Column(name = "what_to_expect", columnDefinition = "TEXT", updatable = false)
    private String whatToExpect;

    /** The Met Office's further details text. */
    @Column(name = "further_details", columnDefinition = "TEXT", updatable = false)
    private String furtherDetails;

    /** JSON array text of the affected region codes (the feed's {@code affectedAreas[].regionCode}). */
    @Column(name = "affected_areas", columnDefinition = "TEXT", updatable = false)
    private String affectedAreas;

    /** The feed's {@code warningUpdateDescription}, kept in full. */
    @Column(name = "update_description", columnDefinition = "TEXT", updatable = false)
    private String updateDescription;

    /** The feed's {@code whatShouldIDo} advice, kept in full. */
    @Column(name = "what_should_i_do", columnDefinition = "TEXT", updatable = false)
    private String whatShouldIDo;

    /** The feed's GeoJSON geometry as text. */
    @Column(name = "geometry", columnDefinition = "TEXT", updatable = false)
    private String geometry;

    /** The vertex-thinned geometry served to the map. */
    @Column(name = "simplified_geometry", columnDefinition = "TEXT", updatable = false)
    private String simplifiedGeometry;

    /** True for an admin-inserted test warning; never served in a shared payload. */
    @Column(name = "simulated", nullable = false, updatable = false)
    private boolean simulated;

    /** The poller's own clock when this row was stored. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
