package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.SlotAtmosphereCleanupJob;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V162 and the nightly retention prune against a real Postgres (Flyway schema,
 * {@code ddl-auto=validate}): the {@code slot_atmosphere_cleanup} scheduler row is seeded with its
 * 03:45 UTC cron, and the real {@link SlotAtmosphereCleanupJob} bean — constructed by Spring with
 * the real property default and the real Coming up trailing window, so its startup guard has passed
 * — deletes rows older than 180 days, keeps the 180-day-old boundary row, and uses the slot-date
 * predicate on the real table.
 *
 * <p><b>CI-only.</b> It extends {@link IntegrationTestBase}, which starts a Postgres Testcontainer;
 * there is no Docker on the development machine (see CLAUDE.md), so this cannot run locally and is
 * proven by CI's Backend job.
 */
class SlotAtmosphereCleanupIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SchedulerJobConfigRepository schedulerJobConfigRepository;
    @Autowired
    private SlotAtmosphereRepository slotAtmosphereRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private SlotAtmosphereCleanupJob job;
    @Autowired
    private Clock clock;

    @AfterEach
    void removeTestRows() {
        slotAtmosphereRepository.deleteAll();
    }

    @Test
    @DisplayName("V162 seeds the slot_atmosphere_cleanup job: CRON, 03:45 UTC daily, ACTIVE")
    void v162_seedsTheCleanupJobRow() {
        SchedulerJobConfigEntity row = schedulerJobConfigRepository
                .findByJobKey("slot_atmosphere_cleanup").orElseThrow();

        assertThat(row.getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(row.getCronExpression()).isEqualTo("0 45 3 * * *");
        assertThat(row.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        assertThat(row.getDisplayName()).isEqualTo("Slot Atmosphere Cleanup");
        assertThat(row.getDescription()).contains("180 days");
    }

    @Test
    @DisplayName("the job deletes slot rows dated more than 180 days before the UK date, keeps the "
            + "180-day-old boundary row and everything newer, against real Postgres")
    void prune_deletesOlderThan180DaysAndKeepsTheBoundary() {
        LocationEntity location = locationRepository.findAll().getFirst();
        LocalDate today = ForecastHorizon.today(clock);
        slotAtmosphereRepository.save(row(location, today.minusDays(400), TargetType.SUNRISE));
        slotAtmosphereRepository.save(row(location, today.minusDays(181), TargetType.SUNSET));
        slotAtmosphereRepository.save(row(location, today.minusDays(180), TargetType.SUNRISE));
        slotAtmosphereRepository.save(row(location, today.minusDays(60), TargetType.SUNSET));
        slotAtmosphereRepository.save(row(location, today.plusDays(2), TargetType.SUNRISE));

        int deleted = job.prune();

        assertThat(deleted).isEqualTo(2);
        List<LocalDate> kept = slotAtmosphereRepository.findAll().stream()
                .map(SlotAtmosphereEntity::getEvaluationDate).sorted().toList();
        assertThat(kept).containsExactly(
                today.minusDays(180), today.minusDays(60), today.plusDays(2));
    }

    private static SlotAtmosphereEntity row(LocationEntity location, LocalDate date, TargetType event) {
        SlotAtmosphereEntity entity = new SlotAtmosphereEntity();
        entity.setLocation(location);
        entity.setEvaluationDate(date);
        entity.setEventType(event);
        entity.setEvaluatedAt(Instant.now());
        return entity;
    }
}
