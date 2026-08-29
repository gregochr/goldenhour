package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TopicDailyLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * Repository for {@link TopicDailyLogEntity} (V151) — the append-only per-night topic
 * presence/intensity log.
 */
@Repository
public interface TopicDailyLogRepository extends JpaRepository<TopicDailyLogEntity, Long> {

    /**
     * Whether a row already exists for this exact key.
     *
     * <p>{@link com.gregochr.goldenhour.service.TopicDailyLogJob} checks this before inserting so a
     * job re-trigger (a restart mid-run, an admin re-fire) cannot double-write the same night — the
     * table's unique constraint would reject the duplicate anyway, but skipping it up front avoids
     * a failed-and-caught insert on every re-run.
     *
     * @param topicType the bare topic identifier, e.g. {@code "DUST"}
     * @param logDate   the date the row reports on
     * @param regionId  the region primary key
     * @return {@code true} if a row for this key is already stored
     */
    boolean existsByTopicTypeAndLogDateAndRegionId(String topicType, LocalDate logDate, Long regionId);
}
