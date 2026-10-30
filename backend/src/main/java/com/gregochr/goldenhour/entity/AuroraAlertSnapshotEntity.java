package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The single {@code aurora_alert_snapshot} row (V203): the standing real aurora alert, persisted so a
 * restart can restore it instead of re-buying its scoring. The id is always {@link #ONLY_ID} (the
 * table's CHECK). A simulation is never stored.
 *
 * <p>Enum-valued columns are plain strings written by name, so no enum widening is needed locally.
 * The {@code SMALLINT} id is a {@code Short}, as in {@code SubRegionPolicyEntity}.
 */
@Entity
@Table(name = "aurora_alert_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class AuroraAlertSnapshotEntity {

    /** The only legal id. */
    public static final short ONLY_ID = 1;

    /** Primary key; always {@link #ONLY_ID}. */
    @Id
    private Short id = ONLY_ID;

    /** UTC date of the dusk of the poller night that last raised, escalated or confirmed the alert. */
    @Column(name = "night_date", nullable = false)
    private LocalDate nightDate;

    /** The {@code AlertLevel} name. */
    @Column(name = "alert_level", nullable = false, length = 20)
    private String alertLevel;

    /** When the alert became active or last escalated. */
    @Column(name = "active_since", nullable = false)
    private Instant activeSince;

    /** The {@code TriggerType} name, or null. */
    @Column(name = "trigger_type", length = 30)
    private String triggerType;

    /** The Kp that drove the last NOTIFY, or null. */
    @Column(name = "trigger_kp")
    private Double triggerKp;

    /** Number of Bortle-eligible locations. */
    @Column(name = "dark_sky_count", nullable = false)
    private int darkSkyCount;

    /** Number of locations that passed cloud triage, or null. */
    @Column(name = "clear_count")
    private Integer clearCount;

    /** JSON array of the cached scores. */
    @Column(name = "scores", nullable = false, columnDefinition = "TEXT")
    private String scores;

    /** The level a night poll is holding the alert for, or null. */
    @Column(name = "held_level", length = 20)
    private String heldLevel;

    /** The trigger of the hold, or null. */
    @Column(name = "held_trigger", length = 30)
    private String heldTrigger;

    /** When the row was last written. */
    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;
}
