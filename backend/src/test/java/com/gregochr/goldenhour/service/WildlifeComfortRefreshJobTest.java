package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WildlifeComfortRefreshJob}, with the clock fixed at 2026-10-02 10:00 BST
 * (09:00 UTC) so the six dates are 2 to 7 October. Sunrise is stubbed at 06:41 and sunset at 17:28
 * UTC every day, so each place-date carries the twelve hours 06:00 to 17:00 inclusive.
 */
@ExtendWith(MockitoExtension.class)
class WildlifeComfortRefreshJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final List<LocalDate> DATES = TODAY.datesUntil(TODAY.plusDays(6)).toList();
    private static final int HOURS_PER_DAY = 12;
    private static final double[] BARNS = {54.65, -1.70};
    private static final double[] SALTHOLME = {54.60, -1.25};

    @Mock
    private LocationService locationService;

    @Mock
    private OpenMeteoClient openMeteoClient;

    @Mock
    private SolarService solarService;

    @Mock
    private WildlifeComfortWriter writer;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private WildlifeComfortRefreshJob job;
    private JobRunEntity jobRun;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
        job = new WildlifeComfortRefreshJob(locationService, openMeteoClient, solarService, writer,
                jobRunService, dynamicSchedulerService, clock);
        jobRun = JobRunEntity.builder().id(77L).build();
    }

    private void stubJobRun(boolean manual) {
        when(jobRunService.startRun(RunType.WEATHER, manual, EvaluationModel.WILDLIFE)).thenReturn(jobRun);
    }

    private void stubSolarTimes() {
        when(solarService.sunriseUtc(anyDouble(),
                anyDouble(), any(LocalDate.class)))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(6, 41));
        when(solarService.sunsetUtc(anyDouble(),
                anyDouble(), any(LocalDate.class)))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(17, 28));
    }

    private LocationEntity place(long id, String name, double lat, double lon, LocationType... types) {
        return LocationEntity.builder().id(id).name(name).lat(lat).lon(lon)
                .locationType(new HashSet<>(Set.of(types))).build();
    }

    /** Matches a coordinate list by value — {@code double[]} elements compare by identity in {@code List.equals}. */
    private static List<double[]> coordsOf(double[]... expected) {
        return argThat(actual -> actual != null && actual.size() == expected.length
                && IntStream.range(0, expected.length).allMatch(i -> Arrays.equals(actual.get(i), expected[i])));
    }

    private List<OpenMeteoForecastResponse> responses(OpenMeteoForecastResponse... each) {
        return new ArrayList<>(Arrays.asList(each));
    }

    @Test
    @DisplayName("registers a manual-aware target under the key V161 seeds, and it runs this job")
    void registerJob_registersUnderTheSeededKey() {
        job.registerJob();

        // The literal is asserted, not JOB_KEY: the key is the join to the scheduler_job_config row
        // V161 seeds, and a schedule whose key matches no target fires silently forever.
        ArgumentCaptor<Consumer<Boolean>> target = ArgumentCaptor.captor();
        verify(dynamicSchedulerService)
                .registerManualAwareJobTarget(eq("wildlife_comfort_refresh"), target.capture());

        stubJobRun(true);
        when(locationService.findAllEnabled()).thenReturn(List.of());
        target.getValue().accept(true);

        // The manual flag reaches the job run: the Scheduler screen's Run now is recorded as manual.
        verify(jobRunService).startRun(RunType.WEATHER, true, EvaluationModel.WILDLIFE);
    }

    @Test
    @DisplayName("one hide: a single batched request, six replaced dates of twelve hours each")
    void oneHide_writesSixDatesOfTwelveHours() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        ArgumentCaptor<List<double[]>> coords = ArgumentCaptor.captor();
        verify(openMeteoClient).fetchForecastBriefingBatch(coords.capture());
        assertThat(coords.getValue()).hasSize(1);
        assertThat(coords.getValue().getFirst()).containsExactly(54.65, -1.70);
        // One forecast request and nothing else — in particular no air-quality call.
        verifyNoMoreInteractions(openMeteoClient);

        ArgumentCaptor<List<ForecastEvaluationEntity>> rows = ArgumentCaptor.captor();
        for (LocalDate date : DATES) {
            verify(writer).replaceHourlyRows(eq(5L), eq(date), rows.capture());
        }
        verifyNoMoreInteractions(writer);
        assertThat(rows.getAllValues()).hasSize(6).allSatisfy(r -> assertThat(r).hasSize(HOURS_PER_DAY));

        List<ForecastEvaluationEntity> today = rows.getAllValues().getFirst();
        assertThat(today).extracting(ForecastEvaluationEntity::getSolarEventTime)
                .first().isEqualTo(LocalDateTime.of(2026, 10, 2, 6, 0));
        assertThat(today).extracting(ForecastEvaluationEntity::getSolarEventTime)
                .last().isEqualTo(LocalDateTime.of(2026, 10, 2, 17, 0));

        ForecastEvaluationEntity nine = today.get(3);
        assertThat(nine.getSolarEventTime()).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 0));
        assertThat(nine.getTargetType()).isEqualTo(TargetType.HOURLY);
        assertThat(nine.getEvaluationModel()).isEqualTo(EvaluationModel.WILDLIFE);
        assertThat(nine.getLocation()).isSameAs(hide);
        assertThat(nine.getLocationLat()).isEqualByComparingTo("54.65");
        assertThat(nine.getLocationLon()).isEqualByComparingTo("-1.7");
        assertThat(nine.getTargetDate()).isEqualTo(TODAY);
        assertThat(nine.getDaysAhead()).isEqualTo(0);
        assertThat(nine.getConfidence()).isEqualTo("HIGH");
        assertThat(nine.getForecastRunAt()).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 0));
        assertThat(nine.getTemperatureCelsius()).isEqualTo(14.0);
        assertThat(nine.getApparentTemperatureCelsius()).isEqualTo(12.0);
        assertThat(nine.getPrecipitationProbabilityPercent()).isEqualTo(9);
        assertThat(nine.getWindSpeed()).isEqualTo(new BigDecimal("9.13"));
        assertThat(nine.getWindDirection()).isEqualTo(90);
        assertThat(nine.getPrecipitation()).isEqualTo(new BigDecimal("1.13"));
        // A comfort row is not a scored row, and carries none of the colour columns.
        assertThat(nine.getRating()).isNull();
        assertThat(nine.getFierySkyPotential()).isNull();
        assertThat(nine.getGoldenHourPotential()).isNull();
        assertThat(nine.getAzimuthDeg()).isNull();
        assertThat(nine.getLowCloud()).isNull();
        assertThat(nine.getVisibility()).isNull();
        assertThat(nine.getAerosolOpticalDepth()).isNull();

        // Horizon-derived fields follow each date: T+2 is MEDIUM, T+5 is LOW.
        assertThat(rows.getAllValues().get(2).getFirst().getDaysAhead()).isEqualTo(2);
        assertThat(rows.getAllValues().get(2).getFirst().getConfidence()).isEqualTo("MEDIUM");
        assertThat(rows.getAllValues().get(5).getFirst().getDaysAhead()).isEqualTo(5);
        assertThat(rows.getAllValues().get(5).getFirst().getConfidence()).isEqualTo("LOW");
        // Each date's hours come from that date's own slice of the one response: T+1 07:00 is index 31.
        assertThat(rows.getAllValues().get(1).get(1).getTemperatureCelsius()).isEqualTo(36.0);
    }

    @Test
    @DisplayName("job_run counts location-dates, and locations_processed counts the hides considered")
    void jobRunCounts_areInOneUnit() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity a = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        LocationEntity b = place(6L, "Saltholme", 54.60, -1.25, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS, SALTHOLME)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6),
                        ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(anyLong(),
                any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        // Two hides, six dates: twelve location-dates written, none failed. Not 144 rows.
        verify(jobRunService).completeRun(jobRun, 12, 0, DATES);
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(2);
        verify(openMeteoClient, times(1)).fetchForecastBriefingBatch(
                coordsOf(BARNS, SALTHOLME));
    }

    @Test
    @DisplayName("only enabled WILDLIFE-only places are fetched; mixed and colour places are not")
    void onlyWildlifeOnlyPlacesAreSelected() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        LocationEntity wood = place(6L, "Wood Hide", 54.7, -1.8, LocationType.WILDLIFE, LocationType.WOODLAND);
        LocationEntity mixed = place(7L, "Dune Hide", 55.0, -1.5, LocationType.WILDLIFE, LocationType.SEASCAPE);
        LocationEntity colour = place(8L, "High Force", 54.6, -2.1, LocationType.WATERFALL);
        LocationEntity untyped = place(9L, "Untyped", 54.1, -2.0);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide, wood, mixed, colour, untyped));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        verify(writer, times(6)).replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList());
        verifyNoMoreInteractions(writer);
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("no qualifying place: no request is made and a completed run with zero counts is recorded")
    void noHides_makesNoRequestAndRecordsAnEmptyRun() {
        stubJobRun(false);
        when(locationService.findAllEnabled()).thenReturn(List.of(
                place(8L, "High Force", 54.6, -2.1, LocationType.WATERFALL)));

        job.run(false);

        verifyNoInteractions(openMeteoClient);
        verifyNoInteractions(writer);
        verify(jobRunService).completeRun(jobRun, 0, 0, DATES);
        assertThat(jobRun.getLocationsProcessed()).isEqualTo(0);
    }

    @Test
    @DisplayName("one hide missing from the batch response fails its dates and the other hide is still written")
    void oneHideMissingFromBatch_otherStillWritten() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity a = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        LocationEntity b = place(6L, "Saltholme", 54.60, -1.25, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        List<OpenMeteoForecastResponse> chunkFailedForA = new ArrayList<>();
        chunkFailedForA.add(null);
        chunkFailedForA.add(ComfortForecastFixtures.forecast(TODAY, 6));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS, SALTHOLME))).thenReturn(chunkFailedForA);
        when(writer.replaceHourlyRows(eq(6L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        verify(writer, never()).replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList());
        verify(writer, times(6)).replaceHourlyRows(eq(6L), any(LocalDate.class),
                anyList());
        verify(jobRunService).completeRun(jobRun, 6, 6, DATES);
    }

    @Test
    @DisplayName("a whole-batch failure writes and deletes nothing and fails every location-date")
    void batchFailure_keepsEveryPreviousRow() {
        stubJobRun(false);
        LocationEntity a = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        LocationEntity b = place(6L, "Saltholme", 54.60, -1.25, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(a, b));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS, SALTHOLME)))
                .thenThrow(new IllegalStateException("Open-Meteo unreachable"));

        job.run(false);

        verifyNoInteractions(writer);
        verify(jobRunService).completeRun(jobRun, 0, 12, DATES);
        verify(jobRunService).logApiCall(eq(77L), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("wildlife-comfort-forecast-batch(2)"), isNull(), anyLong(), isNull(), isNull(),
                eq(false), eq("Open-Meteo unreachable"));
    }

    @Test
    @DisplayName("a successful batch is logged as one OPEN_METEO_FORECAST call and no air-quality call")
    void successfulBatch_logsOneForecastCall() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        verify(jobRunService).logApiCall(eq(77L), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"),
                eq("wildlife-comfort-forecast-batch(1)"), isNull(), anyLong(), eq(200), isNull(),
                eq(true), isNull());
        verify(jobRunService, never()).logApiCall(eq(77L), eq(ServiceName.OPEN_METEO_AIR_QUALITY),
                eq("GET"), eq("wildlife-comfort-forecast-batch(1)"), isNull(), anyLong(), eq(200),
                isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("an empty extraction for a date leaves that date's previous rows and fails only that date")
    void emptyExtraction_isNotWritten() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        // The response covers only the first day; the other five dates have no hours at all.
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 1)));
        when(writer.replaceHourlyRows(eq(5L), eq(TODAY), anyList()))
                .thenReturn(HOURS_PER_DAY);

        job.run(false);

        verify(writer).replaceHourlyRows(eq(5L), eq(TODAY), anyList());
        verifyNoMoreInteractions(writer);
        verify(jobRunService).completeRun(jobRun, 1, 5, DATES);
    }

    @Test
    @DisplayName("a null-temperature hour is dropped from the rows handed to the writer, the rest kept")
    void nullTemperatureHour_isSkipped() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(TODAY, 6);
        forecast.getHourly().getTemperature2m().set(9, null);
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(forecast));
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY - 1);

        job.run(false);

        ArgumentCaptor<List<ForecastEvaluationEntity>> rows = ArgumentCaptor.captor();
        verify(writer).replaceHourlyRows(eq(5L), eq(TODAY), rows.capture());
        assertThat(rows.getValue()).hasSize(HOURS_PER_DAY - 1);
        assertThat(rows.getValue()).extracting(ForecastEvaluationEntity::getSolarEventTime)
                .doesNotContain(LocalDateTime.of(2026, 10, 2, 9, 0));
    }

    @Test
    @DisplayName("one place-date whose write throws is counted failed and the others carry on")
    void writerFailure_doesNotStopTheOthers() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(eq(5L), eq(TODAY.plusDays(1)), anyList()))
                .thenThrow(new IllegalStateException("constraint violated"));
        when(writer.replaceHourlyRows(eq(5L), eq(TODAY), anyList()))
                .thenReturn(HOURS_PER_DAY);
        for (int d = 2; d < 6; d++) {
            when(writer.replaceHourlyRows(eq(5L), eq(TODAY.plusDays(d)), anyList()))
                    .thenReturn(HOURS_PER_DAY);
        }

        job.run(false);

        verify(writer, times(6)).replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList());
        verify(jobRunService).completeRun(jobRun, 5, 1, DATES);
    }

    @Test
    @DisplayName("a writer that stores nothing counts the location-date as failed")
    void writerStoringNothing_countsAsFailed() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenReturn(responses(ComfortForecastFixtures.forecast(TODAY, 6)));
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(0);

        job.run(false);

        verify(jobRunService).completeRun(jobRun, 0, 6, DATES);
    }

    @Test
    @DisplayName("a second fire while a run is in progress is skipped, not queued")
    void overlapGuard_refusesASecondFire() {
        stubJobRun(false);
        stubSolarTimes();
        LocationEntity hide = place(5L, "Low Barns", 54.65, -1.70, LocationType.WILDLIFE);
        when(locationService.findAllEnabled()).thenReturn(List.of(hide));
        // The first run, mid-flight at its forecast fetch, is fired again — the schedule and Run now
        // landing together. The second must return without starting a job run or a request.
        when(openMeteoClient.fetchForecastBriefingBatch(coordsOf(BARNS)))
                .thenAnswer(inv -> {
                    job.run(true);
                    return responses(ComfortForecastFixtures.forecast(TODAY, 6));
                });
        when(writer.replaceHourlyRows(eq(5L), any(LocalDate.class),
                anyList())).thenReturn(HOURS_PER_DAY);

        job.run(false);

        verify(jobRunService, times(1)).startRun(RunType.WEATHER, false, EvaluationModel.WILDLIFE);
        verify(jobRunService, never()).startRun(RunType.WEATHER, true, EvaluationModel.WILDLIFE);
        verify(openMeteoClient, times(1)).fetchForecastBriefingBatch(
                coordsOf(BARNS));
        verify(jobRunService, times(1)).completeRun(jobRun, 6, 0, DATES);
    }

    @Test
    @DisplayName("the guard is released after a run that threw, so the next fire runs")
    void overlapGuard_isReleasedAfterAFailure() {
        when(jobRunService.startRun(RunType.WEATHER, false, EvaluationModel.WILDLIFE))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(jobRun);
        when(locationService.findAllEnabled()).thenReturn(List.of());

        try {
            job.run(false);
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("database down");
        }
        job.run(false);

        verify(jobRunService).completeRun(jobRun, 0, 0, DATES);
    }
}
