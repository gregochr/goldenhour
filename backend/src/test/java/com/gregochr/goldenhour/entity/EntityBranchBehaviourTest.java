package com.gregochr.goldenhour.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of the small entity accessors and composite-key value classes: tolerant enum reads,
 * null-safe defaults and key equality.
 */
class EntityBranchBehaviourTest {

    // ── SchedulerJobConfigEntity ─────────────────────────────────────────────

    @Test
    @DisplayName("schedule type reads null for an absent or unknown stored string and the enum for a known one")
    void schedulerJob_scheduleType_readsTolerantly() {
        SchedulerJobConfigEntity entity = new SchedulerJobConfigEntity();
        assertThat(entity.getScheduleType()).isNull();

        entity.setScheduleTypeRaw("FUTURE_TYPE");
        assertThat(entity.getScheduleType()).isNull();
        assertThat(entity.getScheduleTypeRaw()).isEqualTo("FUTURE_TYPE");

        entity.setScheduleTypeRaw("FIXED_DELAY");
        assertThat(entity.getScheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
    }

    @Test
    @DisplayName("setScheduleType stores the enum name, and null clears the raw string")
    void schedulerJob_setScheduleType() {
        SchedulerJobConfigEntity entity = new SchedulerJobConfigEntity();

        entity.setScheduleType(ScheduleType.CRON);
        assertThat(entity.getScheduleTypeRaw()).isEqualTo("CRON");

        entity.setScheduleType(null);
        assertThat(entity.getScheduleTypeRaw()).isNull();
    }

    @Test
    @DisplayName("solar event reads null for an absent or unknown stored string and the enum for a known one")
    void schedulerJob_solarEvent_readsTolerantly() {
        SchedulerJobConfigEntity entity = new SchedulerJobConfigEntity();
        assertThat(entity.getSolarEvent()).isNull();

        entity.setSolarEventRaw("MOONRISE");
        assertThat(entity.getSolarEvent()).isNull();

        entity.setSolarEventRaw("SUNRISE");
        assertThat(entity.getSolarEvent()).isEqualTo(SolarEvent.SUNRISE);
    }

    @Test
    @DisplayName("setSolarEvent stores the enum name, and null clears the raw string")
    void schedulerJob_setSolarEvent() {
        SchedulerJobConfigEntity entity = new SchedulerJobConfigEntity();

        entity.setSolarEvent(SolarEvent.SUNRISE);
        assertThat(entity.getSolarEventRaw()).isEqualTo("SUNRISE");

        entity.setSolarEvent(null);
        assertThat(entity.getSolarEventRaw()).isNull();
    }

    @Test
    @DisplayName("the builder accepts the enums, and null for either clears the raw string")
    void schedulerJob_builder_acceptsEnums() {
        SchedulerJobConfigEntity set = SchedulerJobConfigEntity.builder()
                .scheduleType(ScheduleType.CRON)
                .solarEvent(SolarEvent.SUNRISE)
                .build();
        assertThat(set.getScheduleTypeRaw()).isEqualTo("CRON");
        assertThat(set.getSolarEventRaw()).isEqualTo("SUNRISE");

        SchedulerJobConfigEntity cleared = SchedulerJobConfigEntity.builder()
                .scheduleType(null)
                .solarEvent(null)
                .build();
        assertThat(cleared.getScheduleTypeRaw()).isNull();
        assertThat(cleared.getSolarEventRaw()).isNull();
    }

    // ── AreaEntity ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an area with a null kind reads as NAMED and is not a sub-region")
    void area_nullKind_readsAsNamed() {
        AreaEntity area = new AreaEntity();
        area.setKind(null);

        assertThat(area.getKind()).isEqualTo(AreaKind.NAMED);
        assertThat(area.isSubRegion()).isFalse();
    }

    @Test
    @DisplayName("an area of kind SUB_REGION is a sub-region")
    void area_subRegionKind() {
        AreaEntity area = new AreaEntity();
        area.setKind(AreaKind.SUB_REGION);

        assertThat(area.getKind()).isEqualTo(AreaKind.SUB_REGION);
        assertThat(area.isSubRegion()).isTrue();
    }

    @Test
    @DisplayName("onCreate stamps the creation time once and never overwrites an existing one")
    void area_onCreate_stampsOnce() {
        AreaEntity fresh = new AreaEntity();
        LocalDateTime before = LocalDateTime.now();
        fresh.onCreate();
        assertThat(fresh.getCreatedAt()).isBetween(before, LocalDateTime.now());

        AreaEntity existing = new AreaEntity();
        LocalDateTime original = LocalDateTime.of(2026, 1, 2, 3, 4);
        existing.setCreatedAt(original);
        existing.onCreate();
        assertThat(existing.getCreatedAt()).isEqualTo(original);
    }

    // ── ForecastScoreEntity ──────────────────────────────────────────────────

    @Test
    @DisplayName("forecast type round-trips through the stored lookup id, with null both ways")
    void forecastScore_forecastType_roundTrips() {
        ForecastScoreEntity entity = new ForecastScoreEntity();
        assertThat(entity.getForecastType()).isNull();

        entity.setForecastType(ForecastType.MIST);
        assertThat(entity.getForecastTypeId()).isEqualTo(ForecastType.MIST.getId());
        assertThat(entity.getForecastType()).isEqualTo(ForecastType.MIST);

        entity.setForecastType(null);
        assertThat(entity.getForecastTypeId()).isNull();
        assertThat(entity.getForecastType()).isNull();
    }

    @Test
    @DisplayName("an unknown stored forecast type id is reported rather than silently mapped")
    void forecastScore_unknownTypeId_throws() {
        ForecastScoreEntity entity = new ForecastScoreEntity();
        entity.setForecastTypeId(999L);

        assertThatThrownBy(entity::getForecastType)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No ForecastType with id 999");
    }

    // ── composite keys ───────────────────────────────────────────────────────

    @Test
    @DisplayName("UserDriveTimeId equality is by user and location")
    void userDriveTimeId_equality() {
        UserDriveTimeId key = new UserDriveTimeId(1L, 2L);

        assertThat(key).isEqualTo(key);
        assertThat(key).isEqualTo(new UserDriveTimeId(1L, 2L));
        assertThat(key.hashCode()).isEqualTo(new UserDriveTimeId(1L, 2L).hashCode());
        assertThat(key).isNotEqualTo(new UserDriveTimeId(9L, 2L));
        assertThat(key).isNotEqualTo(new UserDriveTimeId(1L, 9L));
        assertThat(key).isNotEqualTo(null);
        assertThat(key).isNotEqualTo("1:2");
        assertThat(new UserDriveTimeId()).isEqualTo(new UserDriveTimeId(null, null));
    }

    @Test
    @DisplayName("LocationAliasId equality is by location and alias")
    void locationAliasId_equality() {
        LocationAliasId key = new LocationAliasId(1L, "bam");

        assertThat(key).isEqualTo(key);
        assertThat(key).isEqualTo(new LocationAliasId(1L, "bam"));
        assertThat(key.hashCode()).isEqualTo(new LocationAliasId(1L, "bam").hashCode());
        assertThat(key).isNotEqualTo(new LocationAliasId(2L, "bam"));
        assertThat(key).isNotEqualTo(new LocationAliasId(1L, "other"));
        assertThat(key).isNotEqualTo(null);
        assertThat(key).isNotEqualTo("1:bam");
    }
}
