package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.BriefingRegionSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for per-build region snapshots — the movement chip's only source.
 */
public interface BriefingRegionSnapshotRepository
        extends JpaRepository<BriefingRegionSnapshotEntity, Long> {

    /**
     * The stamp of the most recent build STRICTLY BEFORE the given one.
     *
     * <p>Strictly, so the build currently being served can never be compared against its own rows
     * — the writer runs at the end of {@code refreshBriefing} and the reader runs on every serve
     * of the response that write belongs to, so a {@code <=} here would make every chip read zero.
     *
     * @param current the live response's own {@code generatedAt}
     * @return the previous build's stamp, or empty when this is the first build on record
     */
    @Query("SELECT MAX(s.briefingGeneratedAt) FROM BriefingRegionSnapshotEntity s "
            + "WHERE s.briefingGeneratedAt < :current")
    Optional<LocalDateTime> findPreviousBuildStamp(@Param("current") LocalDateTime current);

    /**
     * Every row written by one build.
     *
     * <p>The whole build rather than a row per region/date/event: one serve needs roughly
     * regions × 5 days × 2 events of them, and the alternative is that many queries per request.
     *
     * @param briefingGeneratedAt the build stamp, from {@link #findPreviousBuildStamp}
     * @return that build's snapshot rows
     */
    List<BriefingRegionSnapshotEntity> findByBriefingGeneratedAt(LocalDateTime briefingGeneratedAt);

    /**
     * Deletes rows written before the given instant.
     *
     * <p>Keyed on {@code generatedAt} (the write instant), not on {@code briefingGeneratedAt} or
     * {@code evaluationDate}: this is a retention rule about how long the table keeps history, and
     * the other two are forecast quantities that a backfill or a clock change could move.
     *
     * @param cutoff the retention boundary
     * @return how many rows were removed
     */
    @Modifying
    @Query("DELETE FROM BriefingRegionSnapshotEntity s WHERE s.generatedAt < :cutoff")
    int deleteByGeneratedAtBefore(@Param("cutoff") Instant cutoff);
}
