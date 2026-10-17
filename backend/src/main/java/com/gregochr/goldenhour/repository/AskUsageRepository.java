package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AskUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Data repository for {@link AskUsageEntity}.
 *
 * <p>Every change to a counter is one conditional {@code UPDATE} the database applies atomically; the
 * entity is never loaded, modified and saved. Reads are projections of the two counters, never the
 * entity, so a read cannot hand back a stale managed copy after an update in the same request
 * (open-session-in-view is on).
 */
public interface AskUsageRepository extends JpaRepository<AskUsageEntity, Long> {

    /** The two counters of one day's row, read without loading the entity. */
    interface Counts {

        /**
         * Typed questions charged.
         *
         * @return the {@code used} counter
         */
        int getUsed();

        /**
         * Engine runs.
         *
         * @return the {@code engine_calls} counter
         */
        int getEngineCalls();
    }

    /**
     * Reads a user's counters for a day.
     *
     * @param userId    the user
     * @param usageDate the UK civil date
     * @return the counters, or empty when the user has not asked that day
     */
    Optional<Counts> findCountsByUserIdAndUsageDate(Long userId, LocalDate usageDate);

    /**
     * Whether a user already has a row for a day.
     *
     * @param userId    the user
     * @param usageDate the UK civil date
     * @return true when the row exists
     */
    boolean existsByUserIdAndUsageDate(Long userId, LocalDate usageDate);

    /**
     * Inserts a day's row with both counters at zero. A plain {@code INSERT} (no {@code ON CONFLICT},
     * which the local H2 database lacks): a second insert of the same {@code (user, date)} violates
     * {@code uq_ask_usage_user_date}, which the caller reads as "the row exists now". A native
     * statement rather than {@code save}, so a lost race leaves no half-persisted entity in the
     * request's session.
     *
     * @param userId    the user
     * @param usageDate the UK civil date
     * @return rows inserted (1)
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO ask_usage (user_id, usage_date, used, engine_calls) "
            + "VALUES (:userId, :usageDate, 0, 0)", nativeQuery = true)
    int insertRow(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate);

    /**
     * Reserves one question: adds one to both counters, but only while the user is under both limits.
     * One statement, so the check and the increment cannot be separated by another request.
     *
     * @param userId    the user
     * @param usageDate the UK civil date
     * @param limit     the day's allowance of charged questions
     * @param ceiling   the day's ceiling on engine runs
     * @return 1 when reserved; 0 when either limit was already reached or there is no row
     */
    @Modifying
    @Transactional
    @Query("UPDATE AskUsageEntity u SET u.used = u.used + 1, u.engineCalls = u.engineCalls + 1 "
            + "WHERE u.userId = :userId AND u.usageDate = :usageDate "
            + "AND u.used < :limit AND u.engineCalls < :ceiling")
    int reserve(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate,
            @Param("limit") int limit, @Param("ceiling") int ceiling);

    /**
     * Gives back one charged question on the date it was reserved. Never below zero, and never
     * touches {@code engine_calls}: an engine run that was made stays counted.
     *
     * @param userId    the user
     * @param usageDate the date the question was reserved on
     * @return 1 when refunded; 0 when the counter was already zero or there is no row
     */
    @Modifying
    @Transactional
    @Query("UPDATE AskUsageEntity u SET u.used = u.used - 1 "
            + "WHERE u.userId = :userId AND u.usageDate = :usageDate AND u.used > 0")
    int refund(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate);
}
