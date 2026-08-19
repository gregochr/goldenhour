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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What one briefing build displayed for one region, on one date, for one solar event.
 *
 * <p>The sink behind the Plan tab's movement chip. {@code daily_briefing_cache} holds a single
 * upserted row, so the previous build's numbers are gone the moment the next one lands; this table
 * keeps them, pruned by age.
 *
 * <p><b>The mean is the same one the screen printed.</b> It is
 * {@code BriefingRegion.meanRating} — the mean over {@link com.gregochr.goldenhour.model.BriefingSlot}
 * {@code .votingSlots} — taken off the enriched hierarchy rather than recomputed here, so a chip
 * reading {@code ▲0.6} can always be reconciled against the star it sits beside. A per-location
 * delta store cannot do that: the canopy merges that separate a slot list from a voting mean are
 * invisible in one.
 */
@Entity
@Table(name = "briefing_region_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class BriefingRegionSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The region's display name, joined byte-identically to
     * {@code BriefingRegion.regionName} — nothing on either side trims or normalises it.
     */
    @Column(name = "region_name", nullable = false)
    private String regionName;

    /** The briefed date (a UK civil date, as everything in the briefing hierarchy is). */
    @Column(name = "evaluation_date", nullable = false)
    private LocalDate evaluationDate;

    /** {@code SUNRISE} or {@code SUNSET}, stored as the enum name. */
    @Column(name = "target_type", nullable = false, length = 10)
    private String targetType;

    /**
     * The voting mean this build displayed, or {@code null} when nothing that votes was scored.
     *
     * <p>⚠️ Null is a value, not a missing figure. It means "no sky rating existed here", which is
     * a different statement from a low mean and must not be read as zero: a delta with a null on
     * either side is no delta at all, and the strip renders nothing.
     */
    @Column(name = "mean_rating", precision = 3, scale = 1)
    private BigDecimal meanRating;

    /**
     * How many slots the mean was taken over — the RATED voting slots, not the voting roster.
     *
     * <p>Carried so a later reader can tell a mean over one location from a mean over twelve
     * without joining back to a payload that no longer exists. It is the same {@code count} the
     * mean's own statistics report, so the two describe one population by construction; see
     * {@code BriefingRegionSnapshotService.ratedVotingCount}.
     *
     * <p>It is also the only signal that would let a later reader tell weather from ROSTER change:
     * both sides of a delta are means over each build's own population, so adding, disabling or
     * retyping a location moves the number with no weather behind it. Nothing consumes this yet.
     */
    @Column(name = "voting_count", nullable = false)
    private int votingCount;

    /** The region's {@code displayVerdict} at build time, as the enum name (nullable). */
    @Column(name = "display_verdict", length = 20)
    private String displayVerdict;

    /** When this row was written — the pruner's age basis, and the only absolute instant here. */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /**
     * The build stamp: {@code DailyBriefingResponse.generatedAt} verbatim.
     *
     * <p>A {@code LocalDateTime} on purpose — see the migration's own note. It is compared against
     * the live response's own {@code generatedAt}, so storing it as anything that needs a zone
     * conversion would put a timezone rule between a value and its own copy.
     */
    @Column(name = "briefing_generated_at", nullable = false)
    private LocalDateTime briefingGeneratedAt;
}
