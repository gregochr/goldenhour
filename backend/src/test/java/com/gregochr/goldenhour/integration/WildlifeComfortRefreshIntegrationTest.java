package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.ComfortForecastFixtures;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.OpenMeteoClient;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.WildlifeComfortRefreshJob;
import com.gregochr.goldenhour.service.WildlifeComfortWriter;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end against a real Postgres (Flyway schema, {@code ddl-auto=validate}): the wildlife
 * comfort job runs against a stubbed Open-Meteo client, writes through the real writer and
 * repository, and {@code GET /api/forecast} then serves the hide's HOURLY rows with their comfort
 * fields through the full security filter chain.
 *
 * <p><b>CI-only.</b> It extends {@link HttpIntegrationTestBase}, which starts a Postgres
 * Testcontainer, and there is no Docker on the development machine (see CLAUDE.md), so it cannot
 * run locally. It is also what proves V161 against a real database: the context only starts if
 * Flyway applies every migration, and the seed row is read back below.
 *
 * <p>The job under test is built by hand, with the real collaborators from the context and a
 * Mockito {@link OpenMeteoClient}: Open-Meteo is the one outbound edge, and an in-context
 * {@code @MockitoBean} would change the context cache key (see {@link HttpIntegrationTestBase}).
 * Its clock is fixed at the real "now" so the dates it writes line up with the window the
 * controller serves from its own clock.
 */
class WildlifeComfortRefreshIntegrationTest extends HttpIntegrationTestBase {

    private static final String HIDE_NAME = "WC Integration Hide";
    private static final String USERNAME = "wc-integration-lite";

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private LocationService locationService;

    @Autowired
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @Autowired
    private SchedulerJobConfigRepository schedulerJobConfigRepository;

    @Autowired
    private SolarService solarService;

    @Autowired
    private WildlifeComfortWriter writer;

    @Autowired
    private JobRunService jobRunService;

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtService jwtService;

    private LocationEntity hide;
    private Clock clock;
    private LocalDate today;
    private WildlifeComfortRefreshJob job;

    @BeforeEach
    void setUp() {
        hide = locationRepository.save(LocationEntity.builder()
                .name(HIDE_NAME).lat(54.65).lon(-1.70)
                .locationType(new HashSet<>(Set.of(LocationType.WILDLIFE)))
                .createdAt(LocalDateTime.now()).build());
        appUserRepository.save(AppUserEntity.builder()
                .username(USERNAME).password("never-authenticates").role(UserRole.LITE_USER)
                .enabled(true).createdAt(LocalDateTime.now()).build());

        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        today = ForecastHorizon.today(clock);
        OpenMeteoClient client = mock(OpenMeteoClient.class);
        OpenMeteoForecastResponse forecast = ComfortForecastFixtures.forecast(today, 6);
        // One response per requested coordinate, so any other WILDLIFE-only place the migrations
        // may have seeded is served too and the job's counts stay computable.
        when(client.fetchForecastBriefingBatch(anyList()))
                .thenAnswer(inv -> Collections.nCopies(((List<?>) inv.getArgument(0)).size(), forecast));
        job = new WildlifeComfortRefreshJob(locationService, client, solarService, writer,
                jobRunService, dynamicSchedulerService, clock);
    }

    @AfterEach
    void tearDown() {
        forecastEvaluationRepository.deleteAll(forecastEvaluationRepository.findAll().stream()
                .filter(e -> e.getLocation().getId().equals(hide.getId())).toList());
        locationRepository.delete(hide);
        appUserRepository.findByUsername(USERNAME).ifPresent(appUserRepository::delete);
    }

    @Test
    @DisplayName("V161 seeds the wildlife_comfort_refresh row ACTIVE at 05:30 and 17:30 UTC")
    void migrationSeedsTheSchedulerRow() {
        SchedulerJobConfigEntity row = schedulerJobConfigRepository.findByJobKey("wildlife_comfort_refresh")
                .orElseThrow();

        assertThat(row.getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(row.getCronExpression()).isEqualTo("0 30 5,17 * * *");
        assertThat(row.getStatus()).isEqualTo(SchedulerJobStatus.ACTIVE);
        assertThat(row.getDisplayName()).isEqualTo("Wildlife Comfort Forecast");
    }

    @Test
    @DisplayName("after a run, GET /api/forecast serves the hide's HOURLY rows with their comfort fields")
    void runThenServe_returnsTheHourlyComfortRows() throws Exception {
        job.run(false);

        List<Map<String, Object>> served = hourlyRowsServed();

        int expected = 0;
        for (int d = 0; d <= 5; d++) {
            LocalDate date = today.plusDays(d);
            LocalDateTime sunrise = solarService.sunriseUtc(hide.getLat(), hide.getLon(), date);
            LocalDateTime sunset = solarService.sunsetUtc(hide.getLat(), hide.getLon(), date);
            expected += (int) ChronoUnit.HOURS.between(
                    sunrise.truncatedTo(ChronoUnit.HOURS), sunset.truncatedTo(ChronoUnit.HOURS)) + 1;
        }
        assertThat(served).hasSize(expected);

        // The 09:00 UTC hour of today is inside daylight in every UK season. Fixture index 9.
        Map<String, Object> nine = served.stream()
                .filter(r -> today.toString().equals(r.get("targetDate"))
                        && String.valueOf(r.get("solarEventTime")).startsWith(today + "T09:00"))
                .findFirst().orElseThrow();
        assertThat(((Number) nine.get("temperatureCelsius")).doubleValue()).isEqualTo(14.0);
        assertThat(((Number) nine.get("apparentTemperatureCelsius")).doubleValue()).isEqualTo(12.0);
        assertThat(((Number) nine.get("precipitationProbabilityPercent")).intValue()).isEqualTo(9);
        assertThat(((Number) nine.get("windSpeed")).doubleValue()).isEqualTo(9.13);
        assertThat(((Number) nine.get("windDirection")).intValue()).isEqualTo(90);
        assertThat(((Number) nine.get("precipitation")).doubleValue()).isEqualTo(1.13);
        assertThat(nine.get("targetType")).isEqualTo("HOURLY");
        assertThat(nine.get("rating")).isNull();

        JobRunEntity run = jobRunService.getRecentRuns(RunType.WEATHER, 1).getFirst();
        assertThat(run.getFailed()).isZero();
        assertThat(run.getSucceeded() % 6).as("location-dates written: six per wildlife-only place").isZero();
        assertThat(run.getSucceeded()).isGreaterThanOrEqualTo(6);
        assertThat(run.getLocationsProcessed() * 6).isEqualTo(run.getSucceeded());
    }

    @Test
    @DisplayName("a second run replaces the first run's rows: the served set and the table stay the same size")
    void secondRun_replacesRatherThanAppends() throws Exception {
        job.run(false);
        int servedAfterFirst = hourlyRowsServed().size();
        long rowsAfterFirst = forecastEvaluationRepository.count();

        job.run(false);

        assertThat(hourlyRowsServed()).hasSize(servedAfterFirst);
        assertThat(forecastEvaluationRepository.count()).isEqualTo(rowsAfterFirst);
    }

    private List<Map<String, Object>> hourlyRowsServed() throws Exception {
        String body = mockMvc.perform(get("/api/forecast")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.generateAccessToken(USERNAME, UserRole.LITE_USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.locationName == '" + HIDE_NAME + "' && @.targetType == 'HOURLY')]");
    }
}
