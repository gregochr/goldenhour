package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.model.comingup.ComingUpCondition;
import com.gregochr.goldenhour.model.comingup.ComingUpConditionOccurrence;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpConditionsBuilder;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import com.gregochr.goldenhour.service.comingup.TideRunPeakHistory;
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
 * {@link SlotSignalReader} and the real {@link ComingUpConditionsBuilder} still return the full
 * trailing window (60 days by default, the longest read-back) — oldest day included. If the
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

    @Test
    @DisplayName("after a prune the Coming up valley-inversions condition still lists every strong "
            + "morning of its trailing window, oldest day included")
    void comingUpInversionHistory_isWholeAfterAPrune() {
        LocalDate oldestWindowDay = TODAY.minusDays(WINDOW);
        LocalDate yesterday = TODAY.minusDays(1);
        strongInversionMorning(oldestWindowDay);
        strongInversionMorning(TODAY.minusDays(30));
        strongInversionMorning(yesterday);
        strongInversionMorning(TODAY.minusDays(181));
        strongInversionMorning(TODAY.minusDays(300));

        job.prune();
        entityManager.flush();
        entityManager.clear();

        LocationRepository locations = Mockito.mock(LocationRepository.class);
        Mockito.when(locations.findCoastalLocations()).thenReturn(List.of());
        ComingUpConditionsBuilder builder = new ComingUpConditionsBuilder(locations,
                Mockito.mock(TideRunBuilder.class), Mockito.mock(TideRunPeakHistory.class),
                Mockito.mock(TideService.class), Mockito.mock(ForecastEvaluationRepository.class),
                reader, new ComingUpScoringProperties());

        ComingUpCondition inversion = builder.build(TODAY, List.of(), List.of()).get(2);

        assertThat(inversion.occurrences()).extracting(ComingUpConditionOccurrence::date)
                .containsExactlyInAnyOrder(oldestWindowDay, TODAY.minusDays(30), yesterday);
        assertThat(inversion.rateLabel()).startsWith("3 strong mornings since ");
    }
}
