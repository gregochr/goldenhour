package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.NOW;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AskSnapshotBuilder}'s night read (ask-night plan §2.1): which night is tonight, which rows become
 * spots (the latest generation, enabled, Bortle-enriched, regioned, never a wood), the aurora state and the
 * fail-soft read.
 */
@ExtendWith(MockitoExtension.class)
class AskSnapshotBuilderNightsTest {

    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final Instant AFTERNOON = Instant.parse("2026-10-05T14:00:00Z");
    private static final Instant YESTERDAY_AFTERNOON = Instant.parse("2026-10-04T14:00:00Z");
    private static final LocalDateTime DUSK = LocalDateTime.of(2026, 10, 5, 18, 30);
    private static final LocalDateTime DAWN = LocalDateTime.of(2026, 10, 6, 5, 0);

    @Mock
    private BriefingService briefingService;

    @Mock
    private TravelDayService travelDayService;

    @Mock
    private AlmanacService almanacService;

    @Mock
    private SolarEventFreshness freshness;

    @Mock
    private AstroConditionsRepository astroRepository;

    @Mock
    private AuroraForecastResultRepository auroraRepository;

    @Mock
    private AstroConditionsService astroService;

    @Mock
    private AuroraForecastRunService nightSelector;

    private final AuroraStateCache stateCache = new AuroraStateCache();

    private AskSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(AskFixtures.briefing(List.of(), List.of()));
        when(freshness.now()).thenReturn(NOW);
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, List.of(), new ArrayList<>()));
        builder = new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness,
                AskFixtures.clockAt(NOW), astroRepository, auroraRepository, astroService, nightSelector,
                stateCache);
    }

    private static LocationEntity place(long id, String name, String region) {
        return LocationEntity.builder().id(id).name(name).lat(54.9).lon(-1.6).bortleClass(3)
                .skyBrightnessSqm(21.5).locationType(Set.of(LocationType.LANDSCAPE))
                .region(RegionEntity.builder().name(region).build()).build();
    }

    private static AstroConditionsEntity astro(LocationEntity location, LocalDate date, Instant run, int stars) {
        return AstroConditionsEntity.builder().location(location).forecastDate(date).runTimestamp(run).stars(stars)
                .summary("Summary").cloudExplanation("Clear skies").visibilityExplanation("Visible")
                .moonExplanation("Dark").moonPhase("NEW_MOON").moonIlluminationPct(1.0).build();
    }

    private static AuroraForecastResultEntity aurora(LocationEntity location, LocalDate date, int stars,
            boolean triaged) {
        return AuroraForecastResultEntity.builder().location(location).forecastDate(date).runTimestamp(AFTERNOON)
                .stars(stars).summary("Aurora").triaged(triaged).triageReason(triaged ? "Overcast" : null)
                .alertLevel("MODERATE").maxKp(6.0).build();
    }

    private void nightIs(LocalDate tonight) {
        when(nightSelector.currentNightDate()).thenReturn(tonight);
    }

    private void windowIs() {
        when(astroService.resolveNightWindow(org.mockito.ArgumentMatchers.any(AstroConditionsEntity.class)))
                .thenReturn(new AstroConditionsService.NightWindow(DUSK, DAWN));
    }

    private AskSnapshot.Nights nights() {
        return builder.build().orElseThrow().nights();
    }

    // -- tonight ----------------------------------------------------------------------------

    @Test
    @DisplayName("tonight is the poller's night, not the civil date: before dawn it is yesterday's, and "
            + "tomorrow night is today's")
    void tonightIsTheRunningNight() {
        LocalDate yesterday = TODAY.minusDays(1);
        nightIs(yesterday);

        AskSnapshot.Nights nights = nights();

        assertThat(nights.tonight()).isEqualTo(yesterday);
        verify(astroRepository).findByForecastDateFetchingLocation(yesterday);
        verify(astroRepository).findByForecastDateFetchingLocation(TODAY);
        verify(astroRepository, never()).findByForecastDateFetchingLocation(TOMORROW);
        verify(auroraRepository).findByForecastDateAndSimulatedFalseFetchingLocation(yesterday);
        verify(auroraRepository).findByForecastDateAndSimulatedFalseFetchingLocation(TODAY);
    }

    @Test
    @DisplayName("no rows at all is a snapshot with a tonight and no nights")
    void noRows() {
        nightIs(TODAY);

        AskSnapshot.Nights nights = nights();

        assertThat(nights.tonight()).isEqualTo(TODAY);
        assertThat(nights.nights()).isEmpty();
    }

    // -- the astro night --------------------------------------------------------------------

    @Test
    @DisplayName("an astro night is the latest generation only: a place a failed run left from yesterday's "
            + "afternoon is not ranked beside today's")
    void astroNight_latestGenerationOnly() {
        nightIs(TODAY);
        windowIs();
        LocationEntity whitby = place(1L, "Whitby", "Coast");
        LocationEntity staithes = place(2L, "Staithes", "Coast");
        when(astroRepository.findByForecastDateFetchingLocation(TODAY)).thenReturn(List.of(
                astro(whitby, TODAY, AFTERNOON, 5), astro(staithes, TODAY, YESTERDAY_AFTERNOON, 5)));

        AskSnapshot.Night night = nights().night("2026-10-05_astro").orElseThrow();

        assertThat(night.spots()).extracting(AskSnapshot.NightSpot::name).containsExactly("Whitby");
        assertThat(night.generation()).isEqualTo(AFTERNOON);
        assertThat(night.start()).isEqualTo(DUSK);
        assertThat(night.end()).isEqualTo(DAWN);
        assertThat(night.kind()).isEqualTo(AskWindowKind.ASTRO);
    }

    @Test
    @DisplayName("a spot carries the served facts: the figure, the explanations, the Bortle class, the sky "
            + "brightness, the region and the coordinates")
    void astroSpot_carriesServedFacts() {
        nightIs(TODAY);
        windowIs();
        when(astroRepository.findByForecastDateFetchingLocation(TODAY))
                .thenReturn(List.of(astro(place(1L, "Whitby", "Coast"), TODAY, AFTERNOON, 4)));

        AskSnapshot.NightSpot spot = nights().night("2026-10-05_astro").orElseThrow().spots().getFirst();

        assertThat(spot.locationId()).isEqualTo(1L);
        assertThat(spot.regionName()).isEqualTo("Coast");
        assertThat(spot.astroStars()).isEqualTo(4);
        assertThat(spot.cloudExplanation()).isEqualTo("Clear skies");
        assertThat(spot.bortleClass()).isEqualTo(3);
        assertThat(spot.skyBrightnessSqm()).isEqualTo(21.5);
        assertThat(spot.lat()).isEqualTo(54.9);
        assertThat(spot.canopy()).isFalse();
        assertThat(spot.auroraStars()).isNull();
    }

    @Test
    @DisplayName("a disabled place, a place with no Bortle class, a place with no region and a wood are not spots")
    void ineligibleLocationsAreDropped() {
        nightIs(TODAY);
        windowIs();
        LocationEntity good = place(1L, "Good", "Coast");
        LocationEntity disabled = LocationEntity.builder().id(2L).name("Disabled").bortleClass(3).enabled(false)
                .region(RegionEntity.builder().name("Coast").build()).build();
        LocationEntity noBortle = LocationEntity.builder().id(3L).name("NoBortle")
                .region(RegionEntity.builder().name("Coast").build()).build();
        LocationEntity noRegion = LocationEntity.builder().id(4L).name("NoRegion").bortleClass(3).build();
        LocationEntity wood = LocationEntity.builder().id(5L).name("Wood").bortleClass(3)
                .locationType(Set.of(LocationType.WOODLAND)).region(RegionEntity.builder().name("Coast").build())
                .build();
        when(astroRepository.findByForecastDateFetchingLocation(TODAY)).thenReturn(List.of(
                astro(good, TODAY, AFTERNOON, 5), astro(disabled, TODAY, AFTERNOON, 5),
                astro(noBortle, TODAY, AFTERNOON, 5), astro(noRegion, TODAY, AFTERNOON, 5),
                astro(wood, TODAY, AFTERNOON, 5)));
        when(auroraRepository.findByForecastDateAndSimulatedFalseFetchingLocation(TODAY)).thenReturn(List.of(
                aurora(good, TODAY, 4, false), aurora(disabled, TODAY, 4, false), aurora(noBortle, TODAY, 4, false),
                aurora(noRegion, TODAY, 4, false), aurora(wood, TODAY, 4, false)));

        AskSnapshot.Nights nights = nights();

        assertThat(nights.night("2026-10-05_astro").orElseThrow().spots())
                .extracting(AskSnapshot.NightSpot::name).containsExactly("Good");
        assertThat(nights.night("2026-10-05_aurora").orElseThrow().spots())
                .extracting(AskSnapshot.NightSpot::name).containsExactly("Good");
    }

    @Test
    @DisplayName("a night whose every row is ineligible is not a night")
    void allIneligible_noNight() {
        nightIs(TODAY);
        LocationEntity noRegion = LocationEntity.builder().id(4L).name("NoRegion").bortleClass(3).build();
        when(astroRepository.findByForecastDateFetchingLocation(TODAY))
                .thenReturn(List.of(astro(noRegion, TODAY, AFTERNOON, 5)));

        assertThat(nights().nights()).isEmpty();
    }

    // -- the aurora night -------------------------------------------------------------------

    @Test
    @DisplayName("an aurora night carries the figures, the astro figures beside them and the date's astro window")
    void auroraNight_joinsAstroAndTakesItsWindow() {
        nightIs(TODAY);
        windowIs();
        LocationEntity whitby = place(1L, "Whitby", "Coast");
        LocationEntity kielder = place(2L, "Kielder", "Dales");
        when(astroRepository.findByForecastDateFetchingLocation(TODAY))
                .thenReturn(List.of(astro(whitby, TODAY, AFTERNOON, 4)));
        when(auroraRepository.findByForecastDateAndSimulatedFalseFetchingLocation(TODAY))
                .thenReturn(List.of(aurora(whitby, TODAY, 4, false), aurora(kielder, TODAY, 3, true)));

        AskSnapshot.Night night = nights().night("2026-10-05_aurora").orElseThrow();

        assertThat(night.start()).isEqualTo(DUSK);
        assertThat(night.end()).isEqualTo(DAWN);
        AskSnapshot.NightSpot withAstro = night.spots().getFirst();
        assertThat(withAstro.auroraStars()).isEqualTo(4);
        assertThat(withAstro.astroStars()).isEqualTo(4);
        assertThat(withAstro.alertLevel()).isEqualTo("MODERATE");
        assertThat(withAstro.maxKp()).isEqualTo(6.0);
        AskSnapshot.NightSpot auroraOnly = night.spots().get(1);
        assertThat(auroraOnly.astroStars()).isNull();
        assertThat(auroraOnly.triaged()).isTrue();
        assertThat(auroraOnly.triageReason()).isEqualTo("Overcast");
    }

    @Test
    @DisplayName("an aurora night with no astro night has no window")
    void auroraNight_noAstro_noWindow() {
        nightIs(TODAY);
        when(auroraRepository.findByForecastDateAndSimulatedFalseFetchingLocation(TODAY)).thenReturn(List.of());
        when(auroraRepository.findByForecastDateAndSimulatedFalseFetchingLocation(TOMORROW))
                .thenReturn(List.of(aurora(place(1L, "Whitby", "Coast"), TOMORROW, 4, false)));

        AskSnapshot.Night night = nights().night("2026-10-06_aurora").orElseThrow();

        assertThat(night.start()).isNull();
        assertThat(night.end()).isNull();
    }

    // -- the aurora state -------------------------------------------------------------------

    @Test
    @DisplayName("no active alert reads QUIET, an active one its level and the Kp that drove it")
    void auroraState() {
        nightIs(TODAY);
        assertThat(nights().aurora()).isEqualTo(new AskSnapshot.AuroraState("QUIET", null));

        stateCache.evaluate(AlertLevel.MODERATE);
        stateCache.updateTrigger(TriggerType.REALTIME, 6.0);

        assertThat(nights().aurora()).isEqualTo(new AskSnapshot.AuroraState("MODERATE", 6.0));
    }

    @Test
    @DisplayName("while an admin simulation is active the state is nothing at all")
    void auroraState_simulated() {
        nightIs(TODAY);
        stateCache.activateSimulation(AlertLevel.STRONG,
                new AuroraStateCache.SimulatedNoaaData(8.0, 80.0, -10.0, "G4"));

        assertThat(nights().aurora()).isNull();
    }

    @Test
    @DisplayName("a simulation that starts between the check and the alert reads leaves no aurora state: the "
            + "check is made again once the level and Kp are in hand")
    void auroraState_simulationStartingMidRead() {
        nightIs(TODAY);
        AuroraStateCache flipping = org.mockito.Mockito.mock(AuroraStateCache.class);
        when(flipping.getSimulatedData()).thenReturn(null, new AuroraStateCache.SimulatedNoaaData(8.0, 80.0, -10.0,
                "G4"));
        when(flipping.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(flipping.getLastTriggerKp()).thenReturn(8.0);
        AskSnapshotBuilder racing = new AskSnapshotBuilder(briefingService, travelDayService, almanacService,
                freshness, AskFixtures.clockAt(NOW), astroRepository, auroraRepository, astroService, nightSelector,
                flipping);

        AskSnapshot snapshot = racing.build().orElseThrow();

        assertThat(snapshot.nights().aurora()).isNull();
    }

    // -- fail-soft --------------------------------------------------------------------------

    @Test
    @DisplayName("a failed night read leaves a snapshot with no nights, not no snapshot")
    void failedNightRead_isFailSoft() {
        nightIs(TODAY);
        when(astroRepository.findByForecastDateFetchingLocation(TODAY)).thenThrow(new IllegalStateException("db"));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.nights()).isEqualTo(AskSnapshot.Nights.NONE);
        assertThat(snapshot.windows()).isEmpty();
    }
}
