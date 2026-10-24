package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the retention window leaves every reader's history whole: with a real H2 table, rows
 * older than 180 days are pruned by the real {@link SlotAtmosphereCleanupJob}, and then the real
 * {@link SlotSignalReader} still returns the full trailing window (60 days by default, the
 * longest read-back) — oldest day included. If the
 * trailing window were ever widened past retention, the startup guard
 * ({@code SlotAtmosphereRetentionConfigurationTest}) refuses it; this test is the behavioural half.
 */
@DataJpaTest
class SlotAtmosphereRetentionReadersTest {

    /** The UK date the nightly job and the feed are "run" on. */
    private static final LocalDate TODAY = LocalDate.of(2027, 9, 10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-09-10T12:00:00Z"), ZoneOffset.UTC);
    private static final int WINDOW = new ComingUpScoringProperties().getRecurrent().getTrailingWindowDays();

    @Autowired
    private SlotAtmosphereRepository slotAtmosphereRepository;
    @Autowired
    private ForecastScoreRepository forecastScoreRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private EntityManager entityManager;

    private LocationEntity fell;
    private SlotSignalReader reader;
    private SlotAtmosphereCleanupJob job;

    @BeforeEach
    void setUp() {
        fell = locationRepository.save(LocationEntity.builder()
                .name("Old Fell").lat(54.4).lon(-3.0)
                .createdAt(LocalDateTime.of(2027, 1, 1, 12, 0)).build());
        reader = new SlotSignalReader(forecastScoreRepository, slotAtmosphereRepository);
        job = new SlotAtmosphereCleanupJob(slotAtmosphereRepository,
                Mockito.mock(DynamicSchedulerService.class), CLOCK, new ComingUpScoringProperties(),
                SlotAtmosphereCleanupJob.DEFAULT_RETENTION_DAYS);
    }

    private void strongInversionMorning(LocalDate date) {
        SlotAtmosphereEntity entity = new SlotAtmosphereEntity();
        entity.setLocation(fell);
        entity.setEvaluationDate(date);
        entity.setEventType(TargetType.SUNRISE);
        entity.setInversionScore(9.0);
        entity.setInversionScored(true);
        entity.setEvaluatedAt(Instant.parse("2027-09-09T03:00:00Z"));
        slotAtmosphereRepository.save(entity);
    }

    @Test
    @DisplayName("after a prune the reader still returns every row in the 60-day trailing window, "
            + "including the oldest day, and the rows older than 180 days are gone")
    void reader_trailingWindowSurvivesAPrune() {
        LocalDate oldestWindowDay = TODAY.minusDays(WINDOW);
        LocalDate yesterday = TODAY.minusDays(1);
        LocalDate lastKeptDay = TODAY.minusDays(180);
        LocalDate firstPrunedDay = TODAY.minusDays(181);
        strongInversionMorning(oldestWindowDay);
        strongInversionMorning(TODAY.minusDays(30));
        strongInversionMorning(yesterday);
        strongInversionMorning(lastKeptDay);
        strongInversionMorning(firstPrunedDay);
        strongInversionMorning(TODAY.minusDays(400));

        int deleted = job.prune();
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(2);
        List<SlotSignals> window = reader.read(oldestWindowDay, yesterday);
        assertThat(window).extracting(SlotSignals::date)
                .containsExactlyInAnyOrder(oldestWindowDay, TODAY.minusDays(30), yesterday);
        assertThat(reader.read(TODAY.minusDays(400), TODAY.minusDays(61))).extracting(SlotSignals::date)
                .containsExactly(lastKeptDay);
    }
}
