package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WildlifeComfortRefreshJob} with the real writer and a real H2 {@code forecast_evaluation}
 * table: what a run leaves in the database, and — as important — what it leaves alone when a fetch
 * or an extraction gives it nothing to replace the old rows with. Everything outside the database
 * (Open-Meteo, sunrise and sunset, the job-run bookkeeping) is stubbed.
 */
@DataJpaTest
@Import(WildlifeComfortWriter.class)
class WildlifeComfortRefreshJobPersistenceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final LocalDateTime EARLIER_RUN = LocalDateTime.of(2026, 10, 2, 5, 30);
    private static final double STALE_TEMPERATURE = 99.0;
    private static final int SIX_DATES_OF_TWELVE_HOURS = 72;

    @Autowired
    private WildlifeComfortWriter writer;

    @Autowired
    private ForecastEvaluationRepository repository;

    @Autowired
    private LocationRepository locationRepository;

    private final LocationService locationService = mock(LocationService.class);
    private final OpenMeteoClient openMeteoClient = mock(OpenMeteoClient.class);
    private final SolarService solarService = mock(SolarService.class);
    private final JobRunService jobRunService = mock(JobRunService.class);
    private final DynamicSchedulerService scheduler = mock(DynamicSchedulerService.class);

    private WildlifeComfortRefreshJob job;
    private LocationEntity barns;
    private LocationEntity saltholme;

    @BeforeEach
    void setUp() {
        barns = locationRepository.save(hide("Low Barns", 54.65, -1.70));
        saltholme = locationRepository.save(hide("Saltholme", 54.60, -1.25));
        when(locationService.findAllEnabled()).thenReturn(List.of(barns, saltholme));
        when(jobRunService.startRun(RunType.WEATHER, false, EvaluationModel.WILDLIFE))
                .thenReturn(JobRunEntity.builder().id(1L).build());
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(6, 41));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any(LocalDate.class)))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(17, 28));
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
        job = new WildlifeComfortRefreshJob(locationService, openMeteoClient, solarService, writer,
                jobRunService, scheduler, clock);
    }

    private LocationEntity hide(String name, double lat, double lon) {
        return LocationEntity.builder().name(name).lat(lat).lon(lon)
                .locationType(new HashSet<>(Set.of(LocationType.WILDLIFE)))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
    }

    private ForecastEvaluationEntity staleRow(LocationEntity location, LocalDate date, TargetType type, int hour) {
        return ForecastEvaluationEntity.builder()
                .locationLat(BigDecimal.valueOf(location.getLat()))
                .locationLon(BigDecimal.valueOf(location.getLon()))
                .location(location)
                .targetDate(date)
                .targetType(type)
                .solarEventTime(date.atTime(hour, 0))
                .forecastRunAt(EARLIER_RUN)
                .daysAhead(0)
                .evaluationModel(EvaluationModel.WILDLIFE)
                .temperatureCelsius(STALE_TEMPERATURE)
                .build();
    }

    private void respondWith(OpenMeteoForecastResponse... perHide) {
        when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                .thenReturn(new ArrayList<>(Arrays.asList(perHide)));
    }

    private List<ForecastEvaluationEntity> hourlyRows(LocationEntity location) {
        return repository.findAll().stream()
                .filter(e -> e.getLocation().getId().equals(location.getId()))
                .filter(e -> e.getTargetType() == TargetType.HOURLY)
                .toList();
    }

    @Test
    @DisplayName("a run replaces the stale HOURLY rows and never touches a SUNRISE or SUNSET row")
    void run_replacesStaleHourlyRows_andSparesSolarRows() {
        repository.save(staleRow(barns, TODAY, TargetType.HOURLY, 9));
        repository.save(staleRow(barns, TODAY, TargetType.HOURLY, 10));
        repository.save(staleRow(barns, TODAY, TargetType.SUNRISE, 6));
        repository.save(staleRow(barns, TODAY, TargetType.SUNSET, 17));
        respondWith(ComfortForecastFixtures.forecast(TODAY, 6), ComfortForecastFixtures.forecast(TODAY, 6));

        job.run(false);

        List<ForecastEvaluationEntity> hourly = hourlyRows(barns);
        assertThat(hourly).hasSize(SIX_DATES_OF_TWELVE_HOURS);
        assertThat(hourly).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .doesNotContain(STALE_TEMPERATURE);
        assertThat(hourly).filteredOn(e -> e.getTargetDate().equals(TODAY)
                        && e.getSolarEventTime().equals(TODAY.atTime(9, 0)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getTemperatureCelsius()).isEqualTo(14.0);
                    assertThat(e.getForecastRunAt()).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 0));
                });
        assertThat(repository.findAll())
                .filteredOn(e -> e.getTargetType() != TargetType.HOURLY)
                .extracting(e -> e.getTargetType() + "|" + e.getTemperatureCelsius())
                .containsExactlyInAnyOrder("SUNRISE|99.0", "SUNSET|99.0");
        assertThat(hourlyRows(saltholme)).hasSize(SIX_DATES_OF_TWELVE_HOURS);
    }

    @Test
    @DisplayName("running twice leaves one row per hour, not two: storage stays flat")
    void run_twice_keepsStorageFlat() {
        respondWith(ComfortForecastFixtures.forecast(TODAY, 6), ComfortForecastFixtures.forecast(TODAY, 6));

        job.run(false);
        job.run(false);

        assertThat(repository.count()).isEqualTo(2L * SIX_DATES_OF_TWELVE_HOURS);
        List<ForecastEvaluationEntity> served = repository.findLatestRunPerSlotByLocationIds(
                List.of(barns.getId(), saltholme.getId()), TODAY, TODAY.plusDays(5));
        assertThat(served).hasSize(2 * SIX_DATES_OF_TWELVE_HOURS);
    }

    @Test
    @DisplayName("a failed fetch leaves every previous HOURLY row exactly as it was")
    void failedFetch_keepsOldRows() {
        repository.save(staleRow(barns, TODAY, TargetType.HOURLY, 9));
        repository.save(staleRow(saltholme, TODAY, TargetType.HOURLY, 9));
        when(openMeteoClient.fetchForecastBriefingBatch(anyList()))
                .thenThrow(new IllegalStateException("Open-Meteo unreachable"));

        job.run(false);

        assertThat(repository.findAll())
                .extracting(e -> e.getLocationName() + "|" + e.getTemperatureCelsius()
                        + "|" + e.getForecastRunAt())
                .containsExactlyInAnyOrder("Low Barns|99.0|2026-10-02T05:30", "Saltholme|99.0|2026-10-02T05:30");
    }

    @Test
    @DisplayName("a hide missing from the batch keeps its old rows while the other hide is replaced")
    void oneHideMissing_keepsItsOldRows_andTheOtherIsWritten() {
        repository.save(staleRow(barns, TODAY, TargetType.HOURLY, 9));
        repository.save(staleRow(saltholme, TODAY, TargetType.HOURLY, 9));
        respondWith(null, ComfortForecastFixtures.forecast(TODAY, 6));

        job.run(false);

        assertThat(hourlyRows(barns)).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .containsExactly(STALE_TEMPERATURE);
        assertThat(hourlyRows(saltholme)).hasSize(SIX_DATES_OF_TWELVE_HOURS)
                .extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .doesNotContain(STALE_TEMPERATURE);
    }

    @Test
    @DisplayName("an empty extraction for a date keeps that date's old rows; the dates with data are replaced")
    void emptyExtraction_keepsThatDatesOldRows() {
        LocalDate lastDate = TODAY.plusDays(5);
        repository.save(staleRow(barns, lastDate, TargetType.HOURLY, 9));
        repository.save(staleRow(barns, TODAY, TargetType.HOURLY, 9));
        // Five days of data: the response runs out before the sixth date, so it has no hours at all.
        respondWith(ComfortForecastFixtures.forecast(TODAY, 5), ComfortForecastFixtures.forecast(TODAY, 5));

        job.run(false);

        assertThat(hourlyRows(barns)).filteredOn(e -> e.getTargetDate().equals(lastDate))
                .extracting(ForecastEvaluationEntity::getTemperatureCelsius).containsExactly(STALE_TEMPERATURE);
        assertThat(hourlyRows(barns)).filteredOn(e -> e.getTargetDate().equals(TODAY))
                .hasSize(12).extracting(ForecastEvaluationEntity::getTemperatureCelsius)
                .doesNotContain(STALE_TEMPERATURE);
    }
}
