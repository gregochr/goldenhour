package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastPreview;
import com.gregochr.goldenhour.model.AuroraForecastRunRequest;
import com.gregochr.goldenhour.model.AuroraForecastRunResponse;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraForecastRunService}.
 *
 * <p>Verifies the preview generation, per-night forecast pipeline, and database persistence
 * logic without making real HTTP or Claude API calls.
 */
@ExtendWith(MockitoExtension.class)
class AuroraForecastRunServiceTest {

    @Mock private NoaaSwpcClient noaaClient;
    @Mock private WeatherTriageService weatherTriage;
    @Mock private ClaudeAuroraInterpreter claudeInterpreter;
    @Mock private LocationRepository locationRepository;
    @Mock private AuroraForecastResultRepository resultRepository;
    @Mock private SolarCalculator solarCalculator;
    @Mock private AuroraStateCache stateCache;
    @Mock private AuroraForecastResultWriter resultWriter;

    private AuroraForecastRunService service;
    private AuroraProperties properties;

    /**
     * 22:00 UTC on 10 February 2027 — GMT, so London and UTC agree, and well after that date's
     * dawn, so {@link AuroraForecastRunService#currentNightDate()} resolves to {@link #TODAY} and
     * every "tonight" fixture below keeps the meaning it had when this class read the wall clock.
     *
     * <p>A date no real clock will return, deliberately: the interesting mutation here is code that
     * ignores the injected clock and reads the system date, and a fixture resolving to the real
     * today would agree with it by coincidence.
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2027-02-10T22:00:00Z"), ZoneOffset.UTC);

    /** The night in progress at {@link #CLOCK} — dusk was two hours ago. */
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 10);

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        service = new AuroraForecastRunService(noaaClient, weatherTriage,
                claudeInterpreter, locationRepository, resultRepository, properties, solarCalculator,
                stateCache, resultWriter, CLOCK);
        // Exactly three lenient stubs, and only because the class mixes pure-function tests with
        // pipeline tests. isSimulated() is unused by the pure calculators (gScaleFromKp,
        // maxKpInWindow, buildDateLabel) and by runForecast_emptyRequest_returnsEmpty, and it is
        // re-stubbed to true by the two simulation tests. The civil dusk/dawn pair is consumed only
        // by the code paths that resolve a dark window — computeWindowForDate, getPreview* and every
        // runForecast* case — never by the calculators. Everything stubbed inside a test method is
        // strict; there is no class-level leniency here.
        lenient().when(stateCache.isSimulated()).thenReturn(false);

        ZoneId utc = ZoneId.of("UTC");
        // Answers per requested date rather than returning one fixed pair. That matters now:
        // currentNightDate() asks for *today's* dawn and compares the clock against it, so a stub
        // that returned tomorrow's dawn for every date would put the answer permanently before
        // dawn and select yesterday's night forever. The old flat stub was invisible while nothing
        // read a per-date answer, and it is exactly what let a date-confused selection look fine.
        lenient().when(solarCalculator.civilDusk(eq(AuroraForecastRunService.DURHAM_LAT),
                eq(AuroraForecastRunService.DURHAM_LON), any(LocalDate.class), eq(utc)))
                .thenAnswer(inv -> LocalDateTime.of(
                        inv.<LocalDate>getArgument(2), java.time.LocalTime.of(20, 0)));
        lenient().when(solarCalculator.civilDawn(eq(AuroraForecastRunService.DURHAM_LAT),
                eq(AuroraForecastRunService.DURHAM_LON), any(LocalDate.class), eq(utc)))
                .thenAnswer(inv -> LocalDateTime.of(
                        inv.<LocalDate>getArgument(2), java.time.LocalTime.of(4, 0)));
    }

    // -------------------------------------------------------------------------
    // currentNightDate — a night is not a day
    // -------------------------------------------------------------------------

    /**
     * Builds a service on a clock of its own, so a case can stand at a chosen point of the night.
     */
    private AuroraForecastRunService serviceAt(String instant) {
        return new AuroraForecastRunService(noaaClient, weatherTriage, claudeInterpreter,
                locationRepository, resultRepository, properties, solarCalculator, stateCache,
                resultWriter, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("between midnight and dawn the current night is the one that began yesterday")
    void currentNightDate_beforeDawn_isYesterday() {
        // 02:00 on the 11th. Dawn (04:00 − 35 min = 03:25) has not happened, so the window that
        // opened at 20:35 on the 10th is still running — and that is the night you are under.
        // Keyed to the calendar date this returned the 11th, whose window starts in 18½ hours.
        assertThat(serviceAt("2027-02-11T02:00:00Z").currentNightDate())
                .isEqualTo(LocalDate.of(2027, 2, 10));
    }

    @Test
    @DisplayName("after dawn the current night is tonight's, not the one that just ended")
    void currentNightDate_afterDawn_isToday() {
        // 03:26 — one minute past nautical dawn. The old night is over; the next one is tonight's.
        assertThat(serviceAt("2027-02-11T03:26:00Z").currentNightDate())
                .isEqualTo(LocalDate.of(2027, 2, 11));
    }

    @Test
    @DisplayName("at the dawn boundary itself the night has ended")
    void currentNightDate_atDawn_isToday() {
        // 03:25 exactly. isBefore is strict, so this is the first instant of the new day's count.
        assertThat(serviceAt("2027-02-11T03:25:00Z").currentNightDate())
                .isEqualTo(LocalDate.of(2027, 2, 11));
        assertThat(serviceAt("2027-02-11T03:24:00Z").currentNightDate())
                .isEqualTo(LocalDate.of(2027, 2, 10));
    }

    @Test
    @DisplayName("the selected night is the same window AuroraPollingJob would have picked")
    void currentNightDate_windowMatchesTheAuroraPollingJobRule() {
        // The point of the fix: one answer to "which night is it", not two. AuroraPollingJob has
        // always resolved the in-progress window correctly; this asserts the preview now agrees,
        // by the only property that matters — the window itself.
        AuroraForecastRunService preDawn = serviceAt("2027-02-11T02:00:00Z");
        TonightWindow window = preDawn.computeWindowForDate(preDawn.currentNightDate());

        assertThat(window.dusk().toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2027, 2, 10, 20, 35));
        assertThat(window.dawn().toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2027, 2, 11, 3, 25));
        // And the instant we are standing at is inside it, which is the whole claim.
        assertThat(Instant.parse("2027-02-11T02:00:00Z"))
                .isBetween(window.dusk().toInstant(), window.dawn().toInstant());
    }

    @Test
    @DisplayName("in the small hours the preview leads with the night in progress, labelled Tonight")
    void getPreview_beforeDawn_leadsWithTheNightInProgress() {
        AuroraForecastRunService preDawn = serviceAt("2027-02-11T02:00:00Z");
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = preDawn.getPreview();

        // "Tonight" used to name 11 Feb — a window that had not started — while the user stood
        // under the one that opened on the 10th.
        assertThat(preview.nights()).hasSize(3);
        assertThat(preview.nights().get(0).date()).isEqualTo(LocalDate.of(2027, 2, 10));
        assertThat(preview.nights().get(0).label()).startsWith("Tonight");
        assertThat(preview.nights().get(1).date()).isEqualTo(LocalDate.of(2027, 2, 11));
        assertThat(preview.nights().get(2).date()).isEqualTo(LocalDate.of(2027, 2, 12));
    }

    @Test
    @DisplayName("in the small hours real triage goes to the night in progress, not the next one")
    void runForecast_beforeDawn_realTriageFollowsTheNightInProgress() {
        // The other half of the fix, and the half with a cost attached. The triage switch keys off
        // the same selection, so before dawn the night in progress gets WeatherTriageService —
        // whose lookahead is the next few hours and so actually overlaps that window — while the
        // following night gets the optimistic 50%-cloud default. Keyed to the calendar date it was
        // the wrong way round: real triage was spent on a window ~18 h away, and its verdict
        // persisted, while the window overhead got defaults.
        AuroraForecastRunService preDawn = serviceAt("2027-02-11T02:00:00Z");
        ZoneId utc = ZoneId.of("UTC");
        LocalDate nightInProgress = LocalDate.of(2027, 2, 10);
        LocalDate nextNight = LocalDate.of(2027, 2, 11);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(nightInProgress.atTime(20, 35).atZone(utc),
                                nightInProgress.plusDays(1).atTime(3, 25).atZone(utc), 6.0),
                        new KpForecast(nextNight.atTime(20, 35).atZone(utc),
                                nextNight.plusDays(1).atTime(3, 25).atZone(utc), 6.0)),
                null, List.of(), List.of());
        when(noaaClient.fetchAll()).thenReturn(data);

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Kielder").lat(55.2).lon(-2.5).bortleClass(2).build();
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(loc), List.of(), Map.of(loc, 20)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 4, AlertLevel.MODERATE, 20,
                        "Clear", "✓ Cloud: 20%")));

        preDawn.runForecast(new AuroraForecastRunRequest(
                List.of(nightInProgress, nextNight)));

        // Counting the calls is not enough — triage runs exactly once either way, just for the
        // wrong night. (Written that way first; the mutation survived.) What distinguishes them is
        // which window got the measured cloud: the real stub returns 20%, buildFutureNightTriage
        // fabricates 50%, so pairing each interpret call's window with its cloud figure says
        // precisely which night was measured and which was assumed.
        verify(weatherTriage, times(1)).triage(any());

        ArgumentCaptor<Map<LocationEntity, Integer>> cloudCaptor =
                ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<TonightWindow> windowCaptor = ArgumentCaptor.forClass(TonightWindow.class);
        verify(claudeInterpreter, times(2)).interpret(any(), any(), cloudCaptor.capture(),
                any(), any(), windowCaptor.capture());

        for (int i = 0; i < 2; i++) {
            LocalDate duskDate = windowCaptor.getAllValues().get(i).dusk().toLocalDate();
            int cloud = cloudCaptor.getAllValues().get(i).get(loc);
            if (duskDate.equals(nightInProgress)) {
                assertThat(cloud).as("night in progress is measured").isEqualTo(20);
            } else {
                assertThat(duskDate).isEqualTo(nextNight);
                assertThat(cloud).as("the following night is assumed").isEqualTo(50);
            }
        }
    }

    // -------------------------------------------------------------------------
    // computeWindowForDate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("computeWindowForDate returns dusk + 35 min and dawn - 35 min")
    void computeWindowForDate_appliesNauticalBuffer() {
        LocalDate date = TODAY;
        TonightWindow window = service.computeWindowForDate(date);
        // dusk = 20:00 + 35 min = 20:35; dawn = 04:00 - 35 min = 03:25 (next day)
        assertThat(window.dusk().getHour()).isEqualTo(20);
        assertThat(window.dusk().getMinute()).isEqualTo(35);
        assertThat(window.dawn().getHour()).isEqualTo(3);
        assertThat(window.dawn().getMinute()).isEqualTo(25);
    }

    // -------------------------------------------------------------------------
    // gScaleFromKp
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gScaleFromKp returns null below Kp 5")
    void gScale_nullBelowG1() {
        assertThat(service.gScaleFromKp(4.9)).isNull();
        assertThat(service.gScaleFromKp(0.0)).isNull();
    }

    @Test
    @DisplayName("gScaleFromKp maps Kp 5–9 to G1–G5")
    void gScale_correctLabels() {
        assertThat(service.gScaleFromKp(5.0)).isEqualTo("G1");
        assertThat(service.gScaleFromKp(5.9)).isEqualTo("G1");
        assertThat(service.gScaleFromKp(6.0)).isEqualTo("G2");
        assertThat(service.gScaleFromKp(7.0)).isEqualTo("G3");
        assertThat(service.gScaleFromKp(8.0)).isEqualTo("G4");
        assertThat(service.gScaleFromKp(9.0)).isEqualTo("G5");
    }

    // -------------------------------------------------------------------------
    // buildDateLabel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildDateLabel returns Tonight, Tomorrow, or day name")
    void buildDateLabel_correctLabels() {
        LocalDate date = LocalDate.of(2026, 3, 21);
        assertThat(service.buildDateLabel(date, 0)).startsWith("Tonight — ");
        assertThat(service.buildDateLabel(date, 1)).startsWith("Tomorrow — ");
        assertThat(service.buildDateLabel(date, 2)).doesNotContain("Tonight").doesNotContain("Tomorrow");
    }

    // -------------------------------------------------------------------------
    // maxKpInWindow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("maxKpInWindow returns max Kp from overlapping forecast windows")
    void maxKpInWindow_returnsMax() {
        ZonedDateTime dusk = ZonedDateTime.parse("2026-03-21T20:35:00Z");
        ZonedDateTime dawn = ZonedDateTime.parse("2026-03-22T03:25:00Z");
        TonightWindow window = new TonightWindow(dusk, dawn);

        KpForecast inWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-21T21:00:00Z"),
                ZonedDateTime.parse("2026-03-22T00:00:00Z"), 6.0);
        KpForecast outOfWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-22T06:00:00Z"),
                ZonedDateTime.parse("2026-03-22T09:00:00Z"), 9.0);

        double max = service.maxKpInWindow(List.of(inWindow, outOfWindow), window);
        assertThat(max).isEqualTo(6.0);
    }

    @Test
    @DisplayName("maxKpInWindow returns 0.0 when no windows overlap")
    void maxKpInWindow_returnsZeroWhenNoOverlap() {
        ZonedDateTime dusk = ZonedDateTime.parse("2026-03-21T20:35:00Z");
        ZonedDateTime dawn = ZonedDateTime.parse("2026-03-22T03:25:00Z");
        TonightWindow window = new TonightWindow(dusk, dawn);

        KpForecast futureWindow = new KpForecast(
                ZonedDateTime.parse("2026-03-22T12:00:00Z"),
                ZonedDateTime.parse("2026-03-22T15:00:00Z"), 7.0);

        double max = service.maxKpInWindow(List.of(futureWindow), window);
        assertThat(max).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // getPreview
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getPreview returns 3 nights")
    void getPreview_returnsThreeNights() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        assertThat(preview.nights()).hasSize(3);
    }

    @Test
    @DisplayName("getPreview marks Kp >= 5 nights as recommended")
    void getPreview_recommendedWhenKpAtThreshold() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = TODAY;
        ZonedDateTime dusk = today.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = today.plusDays(1).atTime(3, 25).atZone(utc);

        KpForecast highKp = new KpForecast(dusk, dawn, 5.0);
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(highKp));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        // Tonight should be recommended (Kp >= 5)
        assertThat(preview.nights().get(0).recommended()).isTrue();
    }

    @Test
    @DisplayName("getPreview marks quiet nights as not recommended")
    void getPreview_notRecommendedWhenKpLow() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        // No forecast windows → all quiet → none recommended
        assertThat(preview.nights()).allMatch(n -> !n.recommended());
    }

    // -------------------------------------------------------------------------
    // runForecast
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("runForecast returns no_activity when Kp is near zero")
    void runForecast_noActivity_whenKpLow() {
        when(noaaClient.fetchAll()).thenReturn(quietSpaceWeather());


        LocalDate date = TODAY;
        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(date)));

        assertThat(response.nights()).hasSize(1);
        assertThat(response.nights().get(0).status()).isEqualTo("no_activity");
        assertThat(response.totalClaudeCalls()).isZero();
        verify(claudeInterpreter, never()).interpret(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("runForecast calls Claude once per viable night")
    void runForecast_oneClaudeCallPerNight() {
        LocalDate tonight = TODAY;
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0)),
                null, List.of(), List.of());

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Test Location").lat(55.0).lon(-1.5).bortleClass(3).build();

        when(noaaClient.fetchAll()).thenReturn(data);

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(loc), List.of(), Map.of(loc, 30)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 4, AlertLevel.MODERATE, 30,
                        "Excellent conditions", "✓ Geomagnetic: MODERATE\n✓ Cloud: 30%")));

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        assertThat(response.totalClaudeCalls()).isEqualTo(1);
        assertThat(response.nights().get(0).status()).isEqualTo("scored");
        assertThat(response.nights().get(0).locationsScored()).isEqualTo(1);
    }

    @Test
    @DisplayName("runForecast persists triaged locations as 1-star with triage_template source")
    void runForecast_persistsTriagedLocations() {
        LocalDate tonight = TODAY;
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 5.5)),
                null, List.of(), List.of());

        LocationEntity viableLoc = LocationEntity.builder()
                .id(1L).name("Clear Sky").lat(55.0).lon(-1.5).bortleClass(3).build();
        LocationEntity triageLoc = LocationEntity.builder()
                .id(2L).name("Overcast Bay").lat(54.0).lon(-2.0).bortleClass(2).build();

        when(noaaClient.fetchAll()).thenReturn(data);

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(viableLoc, triageLoc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(
                        List.of(viableLoc), List.of(triageLoc),
                        Map.of(viableLoc, 20, triageLoc, 95)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(viableLoc, 3, AlertLevel.MODERATE, 20,
                        "Good conditions", "✓ Cloud: 20%")));

        service.runForecast(new AuroraForecastRunRequest(List.of(tonight)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastResultEntity>> savedCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(resultWriter).replaceNightResults(eq(tonight), savedCaptor.capture());
        List<AuroraForecastResultEntity> saved = savedCaptor.getValue();
        assertThat(saved).hasSize(2);

        AuroraForecastResultEntity triaged = saved.stream()
                .filter(AuroraForecastResultEntity::isTriaged).findFirst().orElseThrow();
        assertThat(triaged.getStars()).isEqualTo(1);
        assertThat(triaged.getSource()).isEqualTo("triage_template");
        assertThat(triaged.getLocation().getName()).isEqualTo("Overcast Bay");

        AuroraForecastResultEntity claude = saved.stream()
                .filter(e -> !e.isTriaged()).findFirst().orElseThrow();
        assertThat(claude.getSource()).isEqualTo("claude");
        assertThat(claude.getStars()).isEqualTo(3);
    }

    @Test
    @DisplayName("runForecast clears stored results for a quiet night via the writer")
    void runForecast_quietNight_clearsStoredResults() {
        LocalDate tonight = TODAY;
        when(noaaClient.fetchAll()).thenReturn(quietSpaceWeather());

        service.runForecast(new AuroraForecastRunRequest(List.of(tonight)));

        verify(resultWriter).replaceNightResults(tonight, List.of());
    }

    @Test
    @DisplayName("runForecast skips Claude when all locations are triaged")
    void runForecast_skipsClaudeWhenAllTriaged() {
        LocalDate tonight = TODAY;
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0)),
                null, List.of(), List.of());

        LocationEntity overcastLoc = LocationEntity.builder()
                .id(1L).name("Overcast").lat(55.0).lon(-1.5).bortleClass(2).build();

        when(noaaClient.fetchAll()).thenReturn(data);

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(overcastLoc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(
                        List.of(), List.of(overcastLoc), Map.of(overcastLoc, 90)));

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        verify(claudeInterpreter, never()).interpret(any(), any(), any(), any(), any(), any());
        assertThat(response.nights().get(0).status()).isEqualTo("all_triaged");
        assertThat(response.totalClaudeCalls()).isZero();

        // The triage-template row must still replace the night's stored results
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastResultEntity>> writtenCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(resultWriter).replaceNightResults(eq(tonight), writtenCaptor.capture());
        assertThat(writtenCaptor.getValue()).hasSize(1);
        assertThat(writtenCaptor.getValue().get(0).isTriaged()).isTrue();
    }

    @Test
    @DisplayName("runForecast clears stored results when no Bortle-eligible locations exist")
    void runForecast_noEligibleLocations_clearsStoredResults() {
        LocalDate tonight = TODAY;
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime dusk = tonight.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tonight.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0)),
                null, List.of(), List.of());
        when(noaaClient.fetchAll()).thenReturn(data);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        assertThat(response.nights().get(0).status()).isEqualTo("no_eligible_locations");
        verify(resultWriter).replaceNightResults(tonight, List.of());
    }

    @Test
    @DisplayName("a Claude failure on night 2 leaves night 1 committed and night 2's stored rows untouched")
    void runForecast_laterNightFailure_preservesEarlierNightWrites() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate night1 = TODAY;
        LocalDate night2 = night1.plusDays(1);
        // One Kp entry per night, because the two nights now have distinct dark windows. They
        // shared one only while the solar stub returned a fixed pair for every date.
        ZonedDateTime dusk = night1.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = night1.plusDays(1).atTime(3, 25).atZone(utc);
        ZonedDateTime dusk2 = night2.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn2 = night2.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 6.0), new KpForecast(dusk2, dawn2, 6.0)),
                null, List.of(), List.of());
        when(noaaClient.fetchAll()).thenReturn(data);

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Test Location").lat(55.0).lon(-1.5).bortleClass(3).build();
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(loc), List.of(), Map.of(loc, 30)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 4, AlertLevel.MODERATE, 30,
                        "Good conditions", "✓ Cloud: 30%")))
                .thenThrow(new RuntimeException("Claude 529 overloaded"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runForecast(
                new AuroraForecastRunRequest(List.of(night1, night2))))
                .hasMessageContaining("529");

        // Night 1's results were written before the failure; night 2 was never touched,
        // so its previously stored rows survive the failed re-run
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuroraForecastResultEntity>> writtenCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(resultWriter).replaceNightResults(eq(night1), writtenCaptor.capture());
        assertThat(writtenCaptor.getValue()).hasSize(1);
        verify(resultWriter, never()).replaceNightResults(eq(night2), any());
    }

    @Test
    @DisplayName("runForecast returns empty response for empty request")
    void runForecast_emptyRequest_returnsEmpty() {
        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of()));
        assertThat(response.nights()).isEmpty();
        assertThat(response.totalClaudeCalls()).isZero();
    }

    @Test
    @DisplayName("runForecast uses future-night triage (no real triage) for T+1")
    void runForecast_futureDateSkipsWeatherTriage() {
        LocalDate tomorrow = TODAY.plusDays(1);
        ZoneId utc = ZoneId.of("UTC");
        // Tomorrow's dark window is tomorrow 20:35 → the day after at 03:25, and the Kp forecast
        // has to overlap *that*. It used to be built against today's window instead, which worked
        // only because the solar stub returned one fixed pair whatever date it was asked for.
        ZonedDateTime dusk = tomorrow.atTime(20, 35).atZone(utc);
        ZonedDateTime dawn = tomorrow.plusDays(1).atTime(3, 25).atZone(utc);

        SpaceWeatherData data = new SpaceWeatherData(
                List.of(),
                List.of(new KpForecast(dusk, dawn, 5.0)),
                null, List.of(), List.of());

        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Future Location").lat(55.0).lon(-1.5).bortleClass(3).build();

        when(noaaClient.fetchAll()).thenReturn(data);

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 3, AlertLevel.MODERATE, 50,
                        "Possible aurora", "✓ Geomagnetic: MODERATE")));

        service.runForecast(new AuroraForecastRunRequest(List.of(tomorrow)));

        // weatherTriage.triage() should NOT be called for a future date
        verify(weatherTriage, never()).triage(any());
        verify(claudeInterpreter, times(1)).interpret(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Simulation mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getPreview uses simulated Kp when stateCache.isSimulated() is true")
    void getPreview_simulated_usesSimulatedKp() {
        AuroraStateCache.SimulatedNoaaData simData =
                new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3");
        when(stateCache.isSimulated()).thenReturn(true);
        when(stateCache.getSimulatedData()).thenReturn(simData);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        assertThat(preview.simulated()).isTrue();
        // All nights should have Kp 7 from simulation
        assertThat(preview.nights()).allMatch(n -> n.maxKp() == 7.0);
        assertThat(preview.nights()).allMatch(n -> n.recommended());
    }

    @Test
    @DisplayName("getPreview returns simulated=false in normal mode")
    void getPreview_notSimulated_returnsSimulatedFalse() {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        AuroraForecastPreview preview = service.getPreview();

        assertThat(preview.simulated()).isFalse();
    }

    @Test
    @DisplayName("runForecast uses simulated SpaceWeatherData when simulation is active")
    void runForecast_simulated_usesSimulatedSpaceWeather() {
        AuroraStateCache.SimulatedNoaaData simData =
                new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3");
        when(stateCache.isSimulated()).thenReturn(true);
        when(stateCache.getSimulatedData()).thenReturn(simData);


        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Sim Location").lat(55.0).lon(-1.5).bortleClass(3).build();

        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(loc));

        LocalDate tonight = TODAY;
        when(weatherTriage.triage(any())).thenReturn(
                new WeatherTriageService.TriageResult(List.of(loc), List.of(), Map.of(loc, 30)));
        when(claudeInterpreter.interpret(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AuroraForecastScore(loc, 4, AlertLevel.STRONG, 30,
                        "Strong conditions", "✓ Geomagnetic: STRONG")));

        AuroraForecastRunResponse response = service.runForecast(
                new AuroraForecastRunRequest(List.of(tonight)));

        // Should have called Claude (Kp 7 = STRONG — above threshold)
        assertThat(response.totalClaudeCalls()).isEqualTo(1);
        assertThat(response.nights().get(0).status()).isEqualTo("scored");
        // Should NOT have called noaaClient.fetchAll() — uses simulated data instead
        verify(noaaClient, never()).fetchAll();
    }

    // -------------------------------------------------------------------------
    // Helper to build a quiet SpaceWeatherData (Kp near zero)
    // -------------------------------------------------------------------------

    private SpaceWeatherData quietSpaceWeather() {
        return new SpaceWeatherData(List.of(), List.of(), null, List.of(), List.of());
    }
}
