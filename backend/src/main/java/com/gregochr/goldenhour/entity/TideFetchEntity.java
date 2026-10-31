package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.model.TideDiff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Provenance of one WorldTides call: where it was asked about, what it answered with, and why it
 * was made (tide-source-integrity-plan P1a).
 *
 * <p>The {@code reason} is a plain string code, not an enum column: {@link #SEED}, {@link #RESEED},
 * {@link #TAIL}, {@link #POINT_CHANGE} for forward fetches and {@link #BACKFILL} for history. The
 * latest successful <em>forward</em> row's request point is what tells the next refresh whether a
 * location's tide point has moved since its stored data was fetched.
 */
@Entity
@Table(name = "tide_fetch")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TideFetchEntity {

    /** Reason code: no forward data stored, the whole forward window fetched. */
    public static final String SEED = "SEED";

    /** Reason code: forward window re-fetched because it is stale. */
    public static final String RESEED = "RESEED";

    /** Reason code: only the tail beyond stored coverage fetched. */
    public static final String TAIL = "TAIL";

    /** Reason code: forward window re-fetched because the location's tide point changed. */
    public static final String POINT_CHANGE = "POINT_CHANGE";

    /** Reason code: a historical backfill chunk. */
    public static final String BACKFILL = "BACKFILL";

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code locations}; rows cascade-delete with the location. */
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** UTC time the call was made. */
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    /** Start of the requested window (UTC), or null. */
    @Column(name = "window_from")
    private LocalDateTime windowFrom;

    /** End of the requested window (UTC), or null. */
    @Column(name = "window_to")
    private LocalDateTime windowTo;

    /** Why the call was made: one of the reason-code constants on this class. */
    @Column(name = "reason", nullable = false, length = 16)
    private String reason;

    /** True when the call returned usable data and it was merged. */
    @Column(name = "success", nullable = false)
    private boolean success;

    /** Latitude sent to WorldTides. */
    @Column(name = "request_lat")
    private Double requestLat;

    /** Longitude sent to WorldTides. */
    @Column(name = "request_lng")
    private Double requestLng;

    /** Latitude WorldTides reports answering for, or null. */
    @Column(name = "response_lat")
    private Double responseLat;

    /** Longitude WorldTides reports answering for, or null. */
    @Column(name = "response_lng")
    private Double responseLng;

    /** Tidal atlas used (for example {@code FES2022}), or null. */
    @Column(name = "atlas", length = 64)
    private String atlas;

    /** Station used, or null when the answer came from a model rather than a station. */
    @Column(name = "station", length = 64)
    private String station;

    /** Datum the heights are relative to, or null. */
    @Column(name = "response_datum", length = 64)
    private String responseDatum;

    /** Credits WorldTides billed, or null when unreported. */
    @Column(name = "call_count")
    private Integer callCount;

    /** Number of extremes stored from this call. */
    @Column(name = "extremes_stored", nullable = false)
    private int extremesStored;

    /** Stored/new extremes paired by the diff-on-write comparison, or null when none was made. */
    @Column(name = "overlap_pairs")
    private Integer overlapPairs;

    /** Largest timing difference over the paired extremes, in minutes, or null. */
    @Column(name = "max_lag_delta_min")
    private Double maxLagDeltaMin;

    /** Largest height difference over the paired extremes, in metres, or null. */
    @Column(name = "max_height_delta_m")
    private Double maxHeightDeltaM;

    /**
     * Whether this row's diff-on-write comparison showed a change: more than five minutes or
     * 0.05 m on any pair. A row with no comparison did not show one.
     *
     * @return true when the recorded deltas exceed either threshold
     */
    public boolean showedChange() {
        return TideDiff.exceeds(maxLagDeltaMin, maxHeightDeltaM);
    }
}
