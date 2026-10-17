package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AskLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data repository for {@link AskLogEntity}.
 *
 * <p>Writes are one native {@code INSERT}, never {@code save} (see the entity). Reads are
 * aggregates — counts by outcome and counts of the {@code missing} phrase — and <b>none selects the
 * question column</b>, so no read path can return a raw question.
 */
public interface AskLogRepository extends JpaRepository<AskLogEntity, Long> {

    /** A count of rows with one outcome. */
    interface OutcomeCount {

        /**
         * The outcome name.
         *
         * @return the {@code AskLog.Outcome} name
         */
        String getOutcome();

        /**
         * How many rows carry it.
         *
         * @return the count
         */
        long getTotal();
    }

    /** A count of rows with one {@code missing} phrase. */
    interface MissingCount {

        /**
         * The phrase.
         *
         * @return what the answer said PhotoCast does not have
         */
        String getMissing();

        /**
         * How many rows carry it.
         *
         * @return the count
         */
        long getTotal();
    }

    /**
     * Inserts one row. Native, so a failed insert leaves nothing half-persisted in the request's
     * session (the {@code AskUsageRepository.insertRow} reasoning). The three nullable columns are
     * cast, so a null parameter has a type on every dialect (Postgres cannot always infer one for a
     * bare null bind).
     *
     * @param createdAt          when the request was answered
     * @param userId             the asker
     * @param scopeKey           {@code ALL} or a region id as text
     * @param view               {@code map}, {@code plan} or {@code coming-up}
     * @param outcome            the outcome name
     * @param normalisedQuestion the question, only for an engine-answered outcome; else null
     * @param missing            the missing phrase, only for a can't-answer; else null
     * @param durationMs         how long the request took
     * @return rows inserted (1)
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO ask_log (created_at, user_id, scope_key, view, outcome, "
            + "normalised_question, missing, duration_ms) VALUES (:createdAt, CAST(:userId AS BIGINT), "
            + ":scopeKey, :view, :outcome, CAST(:normalisedQuestion AS VARCHAR(200)), "
            + "CAST(:missing AS VARCHAR(60)), :durationMs)", nativeQuery = true)
    int insertRow(@Param("createdAt") Instant createdAt, @Param("userId") Long userId,
            @Param("scopeKey") String scopeKey, @Param("view") String view,
            @Param("outcome") String outcome, @Param("normalisedQuestion") String normalisedQuestion,
            @Param("missing") String missing, @Param("durationMs") long durationMs);

    /**
     * How many rows each outcome has had since a moment.
     *
     * @param since the earliest {@code created_at}, inclusive
     * @return one row per outcome that occurred
     */
    @Query("SELECT l.outcome AS outcome, COUNT(l) AS total FROM AskLogEntity l "
            + "WHERE l.createdAt >= :since GROUP BY l.outcome")
    List<OutcomeCount> countByOutcomeSince(@Param("since") Instant since);

    /**
     * The most common {@code missing} phrases since a moment, most common first (ties by phrase).
     *
     * @param since    the earliest {@code created_at}, inclusive
     * @param pageable the page: its size is how many phrases to return
     * @return the phrases with their counts
     */
    @Query("SELECT l.missing AS missing, COUNT(l) AS total FROM AskLogEntity l "
            + "WHERE l.createdAt >= :since AND l.missing IS NOT NULL GROUP BY l.missing "
            + "ORDER BY COUNT(l) DESC, l.missing ASC")
    List<MissingCount> topMissingSince(@Param("since") Instant since, Pageable pageable);

    /**
     * Deletes every row created before a moment, in one statement.
     *
     * @param cutoff the first {@code created_at} that is kept
     * @return rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM AskLogEntity l WHERE l.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
