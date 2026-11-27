package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SnowStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Reads and writes {@code snow_state} (V231). Written only by {@code SnowStateWriter}. */
public interface SnowStateRepository extends JpaRepository<SnowStateEntity, Long> {

    /**
     * Every row whose date falls in the range, both ends inclusive.
     *
     * @param from first date
     * @param to   last date
     * @return the rows, in no particular order
     */
    List<SnowStateEntity> findByEvaluationDateBetween(LocalDate from, LocalDate to);

    /**
     * Deletes every row dated before the cutoff.
     *
     * @param cutoff the first date kept
     * @return rows deleted
     */
    @Modifying
    @Query("delete from SnowStateEntity s where s.evaluationDate < :cutoff")
    int deleteByEvaluationDateBefore(@Param("cutoff") LocalDate cutoff);
}
