package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.NOW;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AskSnapshotBuilder} on the awkward inputs the main builder tests leave out: a day with no date, two
 * summaries that claim the same window, a roster or night row with a missing part, a tide fact with no state, a slot
 * with no id, and a briefing that carries no hot topics.
 */
@ExtendWith(MockitoExtension.class)
class AskSnapshotBuilderEdgeCasesTest {

    private static final Instant GENERATION = Instant.parse("2026-10-05T14:00:00Z");
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
        builder = new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness,
                AskFixtures.clockAt(NOW), astroRepository, auroraRepository, astroService, nightSelector,
                stateCache);
    }

    private void serve(DailyBriefingResponse briefing) {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);
        when(freshness.now()).thenReturn(NOW);
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, new ArrayList<>()));
    }

    private AskSnapshotBuilder withRoster(LocationRepository roster) {
        return new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness,
                AskFixtures.clockAt(NOW), astroRepository, auroraRepository, astroService, nightSelector,
                stateCache, () -> AskAreas.Names.NONE, roster);
    }

    private static LocationEntity place(Long id, String name, String regionName, boolean enabled) {
        return LocationEntity.builder().id(id).name(name).enabled(enabled)
                .region(RegionEntity.builder().name(regionName).build()).build();
    }

    private static BriefingEventSummary blankedWithFacts(TargetType type, LocalDateTime eventTime,
            LocationTideFact... facts) {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(eventTime, DisplayVerdict.WORTH_IT, 4, null), facts);
        return AskFixtures.summary(type, window, AskFixtures.blankedRegion("Coast", CoverageReason.UNKNOWN));
    }

    // -- the solar windows ------------------------------------------------------------------

    @Test
    @DisplayName("a day with no date contributes no window and no later window")
    void dayWithoutADateIsSkipped() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingDay undated = AskFixtures.day(null, AskFixtures.summary(TargetType.SUNSET,
                AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null), region));
        LocalDate later = TODAY.plusDays(3);
        BriefingDay dated = AskFixtures.day(later, blankedWithFacts(TargetType.SUNSET, later.atTime(18, 0),
                AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water")));
        serve(AskFixtures.briefing(List.of(undated, dated), List.of(),
                List.of(new PlanRenderedEvent(null, TargetType.SUNSET))));
        LocationRepository roster = mock(LocationRepository.class);
        when(roster.findEnabledFetchingRegion()).thenReturn(List.of(place(3L, "Bamburgh", "Coast", true)));

        AskSnapshot snapshot = withRoster(roster).build().orElseThrow();

        assertThat(snapshot.windows()).isEmpty();
        assertThat(snapshot.laterWindows()).extracting(AskSnapshot.LaterWindow::id)
                .containsExactly("2026-10-08_sunset");
    }

    @Test
    @DisplayName("two summaries for the same window give one window and one later window: the first wins")
    void duplicateWindowsKeepTheFirst() {
        BriefingRegion first = AskFixtures.region("First coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingRegion second = AskFixtures.region("Second coast", true, AskFixtures.slot(2L, "B", 4));
        BriefingWindow window = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null);
        LocalDate later = TODAY.plusDays(3);
        LocationTideFact fact = AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water");
        LocationTideFact other = AskFixtures.fact(4L, "Seahouses", "LOW", false, "low water");
        BriefingDay today = AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, window, first),
                AskFixtures.summary(TargetType.SUNSET, window, second));
        BriefingDay laterDay = AskFixtures.day(later,
                blankedWithFacts(TargetType.SUNSET, later.atTime(18, 0), fact),
                blankedWithFacts(TargetType.SUNSET, later.atTime(18, 0), other));
        serve(AskFixtures.briefing(List.of(today, laterDay), List.of(),
                List.of(new PlanRenderedEvent(TODAY, TargetType.SUNSET))));
        LocationRepository roster = mock(LocationRepository.class);
        when(roster.findEnabledFetchingRegion()).thenReturn(List.of(place(3L, "Bamburgh", "Coast", true),
                place(4L, "Seahouses", "Coast", true)));

        AskSnapshot snapshot = withRoster(roster).build().orElseThrow();

        assertThat(snapshot.windows()).hasSize(1);
        assertThat(snapshot.windows().getFirst().regions()).extracting(AskSnapshot.Region::name)
                .containsExactly("First coast");
        assertThat(snapshot.laterWindows()).hasSize(1);
        assertThat(snapshot.laterWindows().getFirst().tides()).extracting(AskSnapshot.LaterTide::name)
                .containsExactly("Bamburgh");
    }

    @Test
    @DisplayName("a later window with no tide facts at all gives no later window")
    void windowWithNullTideFacts() {
        LocalDate later = TODAY.plusDays(3);
        BriefingEventSummary noFacts = AskFixtures.summary(TargetType.SUNSET,
                AskFixtures.window(later.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.blankedRegion("Coast", CoverageReason.UNKNOWN));
        assertThat(noFacts.window().tideFacts()).isNull();
        serve(AskFixtures.briefing(List.of(AskFixtures.day(later, noFacts)), List.of(), List.of()));
        LocationRepository roster = mock(LocationRepository.class);
        when(roster.findEnabledFetchingRegion()).thenReturn(List.of(place(3L, "Bamburgh", "Coast", true)));

        assertThat(withRoster(roster).build().orElseThrow().laterWindows()).isEmpty();
    }

    @Test
    @DisplayName("a roster entry with no id, no region name, no region or that is disabled is not a place for a "
            + "later tide; a tide fact with no state is a later tide that is not coastal")
    void rosterGapsAndStatelessTide() {
        LocalDate later = TODAY.plusDays(3);
        serve(AskFixtures.briefing(List.of(AskFixtures.day(later, blankedWithFacts(TargetType.SUNSET,
                later.atTime(18, 0),
                AskFixtures.fact(3L, "Bamburgh", null, false, null),
                AskFixtures.fact(4L, "No region name", "HIGH", true, "x"),
                AskFixtures.fact(5L, "Disabled", "HIGH", true, "x")))), List.of(), List.of()));
        LocationEntity noRegion = LocationEntity.builder().id(6L).name("No region").enabled(true).build();
        LocationRepository roster = mock(LocationRepository.class);
        when(roster.findEnabledFetchingRegion()).thenReturn(Arrays.asList(null,
                place(null, "No id", "Coast", true), noRegion, place(4L, "No region name", null, true),
                place(5L, "Disabled", "Coast", false), place(3L, "Bamburgh", "Coast", true)));

        AskSnapshot snapshot = withRoster(roster).build().orElseThrow();

        assertThat(snapshot.laterWindows()).hasSize(1);
        assertThat(snapshot.laterWindows().getFirst().tides()).containsExactly(
                new AskSnapshot.LaterTide(3L, "Bamburgh", "Coast", null, false, false, null, null));
    }

    @Test
    @DisplayName("a slot joins its tide fact by location id, else by name for a legacy fact with no id, and a slot "
            + "with neither match is inland")
    void slotJoinsItsTideFact() {
        LocationTideFact byId = AskFixtures.fact(1L, "Bamburgh", "HIGH", true, "high water");
        LocationTideFact legacy = AskFixtures.fact(null, "Seahouses", "LOW", false, "low water");
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Bamburgh", 4),
                AskFixtures.slot(null, "Seahouses", 4), AskFixtures.slot(3L, null, 4),
                AskFixtures.slot(4L, "Alnmouth", 4));
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null), byId, legacy);
        serve(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, window,
                region))), List.of()));

        List<AskSnapshot.Slot> slots = builder.build().orElseThrow().windows().getFirst().regions().getFirst()
                .slots();

        assertThat(slotNamed(slots, "Bamburgh").tideState()).isEqualTo("HIGH");
        assertThat(slotNamed(slots, "Bamburgh").tideAligned()).isTrue();
        assertThat(slotNamed(slots, "Seahouses").tideState()).isEqualTo("LOW");
        assertThat(slotNamed(slots, "Seahouses").tideAligned()).isFalse();
        assertThat(slotNamed(slots, "Alnmouth").tideState()).isNull();
        assertThat(slots.stream().filter(s -> s.name() == null).findFirst().orElseThrow().tideState()).isNull();
    }

    private static AskSnapshot.Slot slotNamed(List<AskSnapshot.Slot> slots, String name) {
        return slots.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("tide facts are keyed by id and by name; a missing window or a window with no facts gives none")
    void tideFactsByLocationKeys() {
        LocationTideFact both = AskFixtures.fact(1L, "Bamburgh", "HIGH", true, "high water");
        LocationTideFact legacy = AskFixtures.fact(null, "Seahouses", "LOW", false, "low water");
        LocationTideFact unnamed = AskFixtures.fact(7L, null, "MID", false, "mid");
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null), both, legacy, unnamed);

        Map<String, LocationTideFact> facts = AskSnapshotBuilder.tideFactsByLocation(window);

        assertThat(facts).containsOnlyKeys("id:1", "name:Bamburgh", "name:Seahouses", "id:7");
        assertThat(AskSnapshotBuilder.tideFactsByLocation(null)).isEmpty();
        assertThat(AskSnapshotBuilder.tideFactsByLocation(
                AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null))).isEmpty();
    }

    @Test
    @DisplayName("the first tide fact for a key wins")
    void firstFactWins() {
        LocationTideFact first = AskFixtures.fact(1L, "Bamburgh", "HIGH", true, "high water");
        LocationTideFact second = AskFixtures.fact(1L, "Bamburgh", "LOW", false, "low water");
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null), first, second);

        Map<String, LocationTideFact> facts = AskSnapshotBuilder.tideFactsByLocation(window);

        assertThat(facts.get("id:1")).isSameAs(first);
        assertThat(facts.get("name:Bamburgh")).isSameAs(first);
    }

    // -- the nights -------------------------------------------------------------------------

    private static LocationEntity night(long id, String name, RegionEntity region, boolean enabled) {
        return LocationEntity.builder().id(id).name(name).lat(54.9).lon(-1.6).bortleClass(3).enabled(enabled)
                .skyBrightnessSqm(21.5).locationType(Set.of(LocationType.LANDSCAPE)).region(region).build();
    }

    @Test
    @DisplayName("a night row with no location, or whose place has a region with no name, is not a spot")
    void nightRowsWithMissingPartsAreDropped() {
        serve(AskFixtures.briefing(List.of(), List.of()));
        when(nightSelector.currentNightDate()).thenReturn(TODAY);
        LocationEntity good = night(1L, "Good", RegionEntity.builder().name("Coast").build(), true);
        LocationEntity unnamedRegion = night(2L, "Unnamed region", RegionEntity.builder().build(), true);
        AstroConditionsEntity goodRow = AstroConditionsEntity.builder().location(good).forecastDate(TODAY)
                .runTimestamp(GENERATION).stars(4).build();
        AstroConditionsEntity noLocation = AstroConditionsEntity.builder().forecastDate(TODAY)
                .runTimestamp(GENERATION).stars(5).build();
        AstroConditionsEntity unnamedRow = AstroConditionsEntity.builder().location(unnamedRegion)
                .forecastDate(TODAY).runTimestamp(GENERATION).stars(5).build();
        when(astroRepository.findByForecastDateFetchingLocation(TODAY))
                .thenReturn(List.of(goodRow, noLocation, unnamedRow));
        when(astroService.resolveNightWindow(goodRow)).thenReturn(new AstroConditionsService.NightWindow(DUSK, DAWN));
        AuroraForecastResultEntity goodAurora = AuroraForecastResultEntity.builder().location(good)
                .forecastDate(TODAY).runTimestamp(GENERATION).stars(4).build();
        AuroraForecastResultEntity auroraNoLocation = AuroraForecastResultEntity.builder().forecastDate(TODAY)
                .runTimestamp(GENERATION).stars(5).build();
        AuroraForecastResultEntity auroraUnnamed = AuroraForecastResultEntity.builder().location(unnamedRegion)
                .forecastDate(TODAY).runTimestamp(GENERATION).stars(5).build();
        when(auroraRepository.findByForecastDateAndSimulatedFalseFetchingLocation(TODAY))
                .thenReturn(List.of(goodAurora, auroraNoLocation, auroraUnnamed));

        AskSnapshot.Nights nights = builder.build().orElseThrow().nights();

        assertThat(nights.night("2026-10-05_astro").orElseThrow().spots())
                .extracting(AskSnapshot.NightSpot::name).containsExactly("Good");
        assertThat(nights.night("2026-10-05_aurora").orElseThrow().spots())
                .extracting(AskSnapshot.NightSpot::name).containsExactly("Good");
    }
}
