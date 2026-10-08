package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.VerdictSampleGate;
import com.gregochr.goldenhour.service.ask.AskLocalFixtureSeeder.Fixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AskLocalFixtureSeeder} against real repositories on H2: what it writes, that a second run
 * writes nothing more, that it never touches a row it did not create, and how the verdict sample gate
 * is met.
 */
@DataJpaTest
@SuppressWarnings("unchecked")
class AskLocalFixtureSeederTest {

    /** 12:00 UTC on Monday 5 October 2026: today's sunrise has gone, today's sunset has not. */
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final long HALF_CYCLE = 22_350L;

    @Autowired
    private RegionRepository regions;
    @Autowired
    private LocationRepository locations;
    @Autowired
    private TideExtremeRepository tides;
    @Autowired
    private DataSource dataSource;

    private final BriefingEvaluationService evaluation = mock(BriefingEvaluationService.class);
    private final BriefingService briefing = mock(BriefingService.class);
    private final AskSnapshotBuilder snapshots = mock(AskSnapshotBuilder.class);
    private Clock clock;
    private AskLocalFixtureSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = seederAt(NOW);
    }

    private AskLocalFixtureSeeder seederAt(Instant now) {
        clock = Clock.fixed(now, ZoneOffset.UTC);
        StandardEnvironment local = new StandardEnvironment();
        local.setActiveProfiles("local");
        return new AskLocalFixtureSeeder(regions, locations, tides, evaluation, briefing, snapshots,
                new SolarEventFreshness(new SolarService(), clock), dataSource, local, clock);
    }

    // -- regions and locations --------------------------------------------------------------

    @Test
    @DisplayName("it brings its own regions and locations: three regions, twenty-two locations, all named as "
            + "fixtures, with the right types and tide wants")
    void createsRegionsAndLocations() {
        seeder.seed();

        assertThat(regions.findAll()).extracting(RegionEntity::getName)
                .containsExactlyInAnyOrderElementsOf(Fixture.REGION_NAMES);
        List<LocationEntity> all = locations.findAll();
        assertThat(all).hasSize(Fixture.SPOTS.size()).hasSize(22);
        assertThat(all).allSatisfy(l -> {
            assertThat(l.getName()).endsWith(AskLocalFixtureSeeder.LOCATION_SUFFIX);
            assertThat(l.getRegion().getName()).startsWith(AskLocalFixtureSeeder.REGION_PREFIX);
            assertThat(l.isEnabled()).isTrue();
        });
        LocationEntity wood = locations.findByName("Hollin Wood (fixture)").orElseThrow();
        assertThat(wood.isWoodlandOnly()).isTrue();
        LocationEntity craster = locations.findByName("Craster Rocks (fixture)").orElseThrow();
        assertThat(craster.getTideType()).containsExactly(TideType.LOW);
        assertThat(craster.getLocationType()).containsExactly(LocationType.SEASCAPE);
        assertThat(all.stream().filter(l -> !l.getTideType().isEmpty())).hasSize(3);
    }

    @Test
    @DisplayName("run again, nothing doubles: same regions, same locations, same tide rows, and every "
            + "rating write is the same set again")
    void idempotent() {
        seeder.seed();
        long regionCount = regions.count();
        long locationCount = locations.count();
        long tideCount = tides.count();

        seeder.seed();

        assertThat(regions.count()).isEqualTo(regionCount);
        assertThat(locations.count()).isEqualTo(locationCount);
        assertThat(tides.count()).isEqualTo(tideCount);
    }

    @Test
    @DisplayName("it never deletes and never edits what it did not create: a real region, a real location "
            + "with its own tide rows and a pre-existing fixture-named location are all left exactly as they were")
    void leavesOtherRowsAlone() {
        RegionEntity real = regions.save(RegionEntity.builder().name("Real Region").enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        LocationEntity realPlace = locations.save(LocationEntity.builder().name("Real Place").lat(55.0).lon(-1.5)
                .region(real).createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .tideType(new HashSet<>(Set.of(TideType.HIGH)))
                .locationType(new HashSet<>(Set.of(LocationType.SEASCAPE))).build());
        tides.save(TideExtremeEntity.builder().locationId(realPlace.getId())
                .eventTime(LocalDateTime.of(2026, 10, 6, 6, 0)).heightMetres(new java.math.BigDecimal("4.2"))
                .type(TideExtremeType.HIGH).fetchedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build());
        RegionEntity coast = regions.save(RegionEntity.builder().name(Fixture.COAST).enabled(false)
                .createdAt(LocalDateTime.of(2026, 2, 2, 0, 0)).build());
        LocationEntity earlier = locations.save(LocationEntity.builder().name("Bamburgh Beach (fixture)")
                .lat(1.0).lon(1.0).region(coast).createdAt(LocalDateTime.of(2026, 2, 2, 0, 0))
                .tideType(new HashSet<>()).locationType(new HashSet<>(Set.of(LocationType.LANDSCAPE))).build());

        seeder.seed();

        assertThat(regions.findByName("Real Region")).isPresent();
        assertThat(locations.findByName("Real Place")).isPresent();
        assertThat(tides.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(realPlace.getId(),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0))).hasSize(1);
        // The pre-existing fixture-named rows are reused as they were, not rewritten.
        assertThat(regions.findByName(Fixture.COAST).orElseThrow().isEnabled()).isFalse();
        LocationEntity kept = locations.findByName("Bamburgh Beach (fixture)").orElseThrow();
        assertThat(kept.getId()).isEqualTo(earlier.getId());
        assertThat(kept.getLat()).isEqualTo(1.0);
        assertThat(locations.count()).isEqualTo(Fixture.SPOTS.size() + 1L);
        // Only the fixture's own locations were ever offered to the rating service.
        ArgumentCaptor<List<BriefingEvaluationResult>> written = ArgumentCaptor.forClass(List.class);
        verify(evaluation, atLeastOnce()).mergeFromBatch(anyString(), written.capture());
        written.getAllValues().forEach(batch -> assertThat(batch)
                .allSatisfy(r -> assertThat(r.locationName()).endsWith(AskLocalFixtureSeeder.LOCATION_SUFFIX)));
    }

    // -- ratings ----------------------------------------------------------------------------

    @Test
    @DisplayName("it rates the next four upcoming windows through the pipeline's own service: sky locations "
            + "merged into each region's entry, the wood only on sunrises, nothing for an unrated region")
    void ratesTheUpcomingWindows() {
        seeder.seed();

        ArgumentCaptor<String> skyKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<BriefingEvaluationResult>> skyBatches = ArgumentCaptor.forClass(List.class);
        // Three regions rate sky locations in each of four windows.
        verify(evaluation, times(12)).mergeFromBatch(skyKeys.capture(), skyBatches.capture());
        ArgumentCaptor<String> woodKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<BriefingEvaluationResult>> woodBatches = ArgumentCaptor.forClass(List.class);
        verify(evaluation, times(2)).mergeWoodlandFromBatch(woodKeys.capture(), woodBatches.capture());
        verify(evaluation, never()).writeFromBatch(anyString(), anyList());

        // After 12:00 on 5 Oct: that day's sunset, then 6 Oct sunrise and sunset, then 7 Oct sunrise.
        assertThat(skyKeys.getAllValues()).contains(
                "Fixture Northumberland Coast|2026-10-05|SUNSET", "Fixture Northumberland Coast|2026-10-06|SUNRISE",
                "Fixture Northumberland Coast|2026-10-06|SUNSET", "Fixture Northumberland Coast|2026-10-07|SUNRISE",
                "Fixture Lake District|2026-10-05|SUNSET", "Fixture Yorkshire Dales|2026-10-07|SUNRISE");
        assertThat(skyKeys.getAllValues()).doesNotContain("Fixture Lake District|2026-10-05|SUNRISE");
        assertThat(woodKeys.getAllValues()).containsExactly(
                "Fixture Northumberland Coast|2026-10-06|SUNRISE", "Fixture Northumberland Coast|2026-10-07|SUNRISE");
        assertThat(woodBatches.getAllValues()).allSatisfy(batch -> assertThat(batch).singleElement().satisfies(r -> {
            assertThat(r.locationName()).isEqualTo("Hollin Wood (fixture)");
            assertThat(r.rating()).isEqualTo(5);
        }));
        // Every entry is a believable rating on the 1-5 scale, with the honest "not a forecast" wording.
        skyBatches.getAllValues().forEach(batch -> assertThat(batch).allSatisfy(r -> {
            assertThat(r.rating()).isBetween(1, 5);
            assertThat(r.skyRating()).isEqualTo(r.rating());
            assertThat(r.summary()).contains("Not a forecast");
            assertThat(r.triageReason()).isNull();
        }));
    }

    @Test
    @DisplayName("the verdict sample gate: both eligible regions rate every sky location (7 of 7, 6 of 6), "
            + "the third has a single 4-star slot among eight and is refused")
    void sampleGateIsMetWhereIntended() {
        for (int window = 0; window < AskLocalFixtureSeeder.WINDOWS; window++) {
            for (String region : List.of(Fixture.COAST, Fixture.LAKES)) {
                int w = window;
                List<Fixture.Spot> sky = Fixture.SPOTS.stream()
                        .filter(s -> s.region().equals(region) && !s.canopy()).toList();
                int rated = (int) sky.stream().filter(s -> !s.ratings().isEmpty() && s.ratings().get(w) != null)
                        .count();
                assertThat(VerdictSampleGate.isSufficient(rated, rated, sky.size()))
                        .as(region + " window " + window).isTrue();
            }
        }
        List<Fixture.Spot> dales = Fixture.SPOTS.stream().filter(s -> s.region().equals(Fixture.DALES)).toList();
        long rated = dales.stream().filter(s -> !s.ratings().isEmpty()).count();
        assertThat(dales).hasSize(8);
        assertThat(rated).isEqualTo(1);
        assertThat(dales.stream().filter(s -> !s.ratings().isEmpty()).findFirst().orElseThrow().ratings())
                .containsOnly(4);
        assertThat(VerdictSampleGate.isSufficient((int) rated, (int) rated, dales.size())).isFalse();
    }

    @Test
    @DisplayName("every fixture rating table has one entry per window, all on the 1-5 scale")
    void ratingTablesAreWellFormed() {
        assertThat(Fixture.SPOTS).allSatisfy(spot -> {
            if (!spot.ratings().isEmpty()) {
                assertThat(spot.ratings()).hasSize(AskLocalFixtureSeeder.WINDOWS)
                        .allSatisfy(r -> assertThat(r).isBetween(1, 5));
            }
        });
        assertThat(Fixture.SPOTS.stream().map(Fixture.Spot::name)).doesNotHaveDuplicates()
                .allSatisfy(n -> assertThat(n).endsWith(AskLocalFixtureSeeder.LOCATION_SUFFIX));
        assertThat(Fixture.SPOTS.stream().filter(Fixture.Spot::canopy)).singleElement()
                .satisfies(w -> assertThat(w.ratings()).containsOnly(5));
    }

    // -- tide -------------------------------------------------------------------------------

    @Test
    @DisplayName("synthetic tide: one coastal spot with high water at the light that wants it, one with high "
            + "water at the light that wants low, one with low water at the light that wants it")
    void tideIsAnchoredToTheFirstLight() {
        seeder.seed();

        LocalDateTime light = firstLight(locations.findByName("Bamburgh Beach (fixture)").orElseThrow());
        assertThat(extremeAt("Bamburgh Beach (fixture)", light)).isEqualTo(TideExtremeType.HIGH);
        assertThat(extremeAt("Dunstanburgh Shore (fixture)",
                firstLight(locations.findByName("Dunstanburgh Shore (fixture)").orElseThrow())))
                .isEqualTo(TideExtremeType.HIGH);
        assertThat(extremeAt("Craster Rocks (fixture)",
                firstLight(locations.findByName("Craster Rocks (fixture)").orElseThrow())))
                .isEqualTo(TideExtremeType.LOW);
        // An inland fixture location has none.
        LocationEntity inland = locations.findByName("Catbells (fixture)").orElseThrow();
        assertThat(tides.existsByLocationId(inland.getId())).isFalse();
    }

    @Test
    @DisplayName("high and low alternate: 0 and 2 half cycles give the same type back, 1 and 3 the opposite, "
            + "whichever way the count runs")
    void typeAfterHalfCycles_followsParity() {
        for (TideExtremeType start : TideExtremeType.values()) {
            TideExtremeType other = start == TideExtremeType.HIGH ? TideExtremeType.LOW : TideExtremeType.HIGH;
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, 0)).isEqualTo(start);
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, 1)).isEqualTo(other);
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, 2)).isEqualTo(start);
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, 3)).isEqualTo(other);
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, 6)).isEqualTo(start);
            assertThat(AskLocalFixtureSeeder.typeAfterHalfCycles(start, -1)).isEqualTo(other);
        }
    }

    @Test
    @DisplayName("the seeded series is unchanged by the helper: it starts six half cycles before the light "
            + "with the light's own type, and its heights are the fixed high and low")
    void seededSeriesStartIsPinned() {
        seeder.seed();

        for (String name : List.of("Bamburgh Beach (fixture)", "Dunstanburgh Shore (fixture)")) {
            TideExtremeEntity first = all(locations.findByName(name).orElseThrow()).getFirst();
            assertThat(first.getType()).isEqualTo(TideExtremeType.HIGH);
            assertThat(first.getHeightMetres()).isEqualByComparingTo("5.000");
        }
        LocationEntity craster = locations.findByName("Craster Rocks (fixture)").orElseThrow();
        TideExtremeEntity first = all(craster).getFirst();
        assertThat(first.getType()).isEqualTo(TideExtremeType.LOW);
        assertThat(first.getHeightMetres()).isEqualByComparingTo("0.600");
        assertThat(first.getEventTime()).isEqualTo(firstLight(craster).minusSeconds(6 * HALF_CYCLE));
        assertThat(all(craster)).hasSize(26);
    }

    @Test
    @DisplayName("the series alternates high and low every half cycle, and reaches four days past the first light")
    void tideSeriesIsAProperCurve() {
        seeder.seed();

        LocationEntity bamburgh = locations.findByName("Bamburgh Beach (fixture)").orElseThrow();
        List<TideExtremeEntity> series = all(bamburgh);
        for (int i = 1; i < series.size(); i++) {
            assertThat(Duration.between(series.get(i - 1).getEventTime(), series.get(i).getEventTime())
                    .getSeconds()).isEqualTo(HALF_CYCLE);
            assertThat(series.get(i).getType()).isNotEqualTo(series.get(i - 1).getType());
        }
        assertThat(series.getLast().getEventTime()).isAfter(firstLight(bamburgh).plusDays(4));
        assertThat(series.getFirst().getEventTime()).isBefore(firstLight(bamburgh).minusDays(1));
        assertThat(series).extracting(TideExtremeEntity::getHeightMetres).allSatisfy(h ->
                assertThat(h.doubleValue()).isIn(5.0, 0.6));
    }

    @Test
    @DisplayName("a later run continues the stored series' phase when it no longer covers the new windows: no "
            + "overlap, no gap, nothing already stored is touched")
    void laterRunExtendsTheSeries() {
        seeder.seed();
        LocationEntity bamburgh = locations.findByName("Bamburgh Beach (fixture)").orElseThrow();
        List<TideExtremeEntity> before = all(bamburgh);

        seederAt(NOW.plus(Duration.ofDays(6))).seed();

        List<TideExtremeEntity> after = all(bamburgh);
        assertThat(after.size()).isGreaterThan(before.size());
        assertThat(after.subList(0, before.size())).extracting(TideExtremeEntity::getId)
                .containsExactlyElementsOf(before.stream().map(TideExtremeEntity::getId).toList());
        for (int i = 1; i < after.size(); i++) {
            assertThat(Duration.between(after.get(i - 1).getEventTime(), after.get(i).getEventTime())
                    .getSeconds()).isEqualTo(HALF_CYCLE);
            assertThat(after.get(i).getType()).isNotEqualTo(after.get(i - 1).getType());
        }
        assertThat(after.getLast().getEventTime()).isAfter(LocalDateTime.of(2026, 10, 15, 0, 0));
    }

    // -- briefing and summary ---------------------------------------------------------------

    @Test
    @DisplayName("a missing briefing is built once; a briefing that already holds today with every fixture "
            + "location is left alone")
    void buildsOnlyWhenNeeded() {
        when(briefing.getCachedDays()).thenReturn(null);
        when(briefing.refreshBriefingIfIdle()).thenReturn(true);

        seeder.seed();

        verify(briefing, times(1)).refreshBriefingIfIdle();

        // A briefing that holds today and every fixture location.
        List<BriefingSlot> slots = Fixture.SPOTS.stream()
                .map(s -> AskFixtures.slot(1L, s.name(), null)).toList();
        BriefingRegion everyone = AskFixtures.region("Fixture Northumberland Coast", true,
                slots.toArray(BriefingSlot[]::new));
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(everyone), List.of());
        when(briefing.getCachedDays()).thenReturn(List.of(new BriefingDay(LocalDate.of(2026, 10, 5),
                List.of(summary))));

        seeder.seed();

        verify(briefing, times(1)).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("a cached briefing from another day, or one missing a fixture location, is rebuilt")
    void staleOrIncompleteBriefingIsRebuilt() {
        when(briefing.refreshBriefingIfIdle()).thenReturn(true);
        List<BriefingSlot> all = Fixture.SPOTS.stream().map(s -> AskFixtures.slot(1L, s.name(), null)).toList();
        BriefingRegion full = AskFixtures.region("R", true, all.toArray(BriefingSlot[]::new));
        BriefingRegion missingOne = AskFixtures.region("R", true,
                all.subList(1, all.size()).toArray(BriefingSlot[]::new));

        when(briefing.getCachedDays()).thenReturn(List.of(new BriefingDay(LocalDate.of(2026, 10, 4),
                List.of(new BriefingEventSummary(TargetType.SUNSET, List.of(full), List.of())))));
        seeder.seed();
        when(briefing.getCachedDays()).thenReturn(List.of(new BriefingDay(LocalDate.of(2026, 10, 5),
                List.of(new BriefingEventSummary(TargetType.SUNSET, List.of(missingOne), List.of())))));
        seeder.seed();
        when(briefing.getCachedDays()).thenReturn(List.of());
        seeder.seed();

        verify(briefing, times(3)).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("a build already running does not stop the seeding: it is reported and the run carries on")
    void buildAlreadyRunning() {
        when(briefing.refreshBriefingIfIdle()).thenReturn(false);
        when(snapshots.build()).thenReturn(Optional.empty());

        String summary = seeder.seed();

        assertThat(summary).isEqualTo("Ask snapshot: no briefing has been built, so no windows");
        verify(evaluation, atLeastOnce()).mergeFromBatch(anyString(), anyList());
    }

    @Test
    @DisplayName("summarise names each window and what each region gives Ask: eligible counts, a refused "
            + "region's held-back 4-star slots, and an unrated region")
    void summariseNamesWhatAskCanTake() {
        BriefingRegion eligible = AskFixtures.region("Sound", true, AskFixtures.slot(1L, "A", 5),
                AskFixtures.slot(2L, "B", 2), AskFixtures.wood(3L, "Wood", 5));
        BriefingRegion refused = AskFixtures.region("Thin", false, AskFixtures.slot(4L, "C", 4),
                AskFixtures.slot(5L, "D", 5), AskFixtures.wood(6L, "Wood2", 5), AskFixtures.slot(7L, "E", 2));
        BriefingRegion unrated = AskFixtures.region("Blank", false, AskFixtures.slot(8L, "F", null));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(AskFixtures.TODAY, null, eligible, refused, unrated)), List.of()));

        String summary = AskLocalFixtureSeeder.summarise(Optional.of(snapshot));

        assertThat(summary).isEqualTo("Ask snapshot: 1 windows | 2026-10-05_sunset: Sound 1 eligible, "
                + "Thin refused by the sample gate (2 rated 3★+ held back), Blank unrated");
    }

    @Test
    @DisplayName("with no windows at all the seeder says so and does not rate or build anything")
    void noUpcomingWindows() {
        SolarEventFreshness noSun = mock(SolarEventFreshness.class);
        when(noSun.now()).thenReturn(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        AskLocalFixtureSeeder blind = new AskLocalFixtureSeeder(regions, locations, tides, evaluation, briefing,
                snapshots, noSun, dataSource, new StandardEnvironment(), clock);

        assertThat(blind.seed()).isEqualTo("no upcoming windows");

        verify(evaluation, never()).mergeFromBatch(anyString(), anyList());
        verify(briefing, never()).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("a tide that cannot be timed (no light) adds no extremes and costs nothing")
    void unTimedLightAddsNoTide() {
        SolarEventFreshness flaky = mock(SolarEventFreshness.class);
        when(flaky.now()).thenReturn(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        // Timed once (the reference location, for the windows), then never again.
        LocalDateTime sunset = LocalDateTime.of(2026, 10, 5, 17, 0);
        when(flaky.eventTime(any(), any(), any())).thenReturn(sunset, (LocalDateTime) null);
        AskLocalFixtureSeeder odd = new AskLocalFixtureSeeder(regions, locations, tides, evaluation, briefing,
                snapshots, flaky, dataSource, new StandardEnvironment(), clock);

        odd.seed();

        assertThat(tides.count()).isZero();
    }

    // -- helpers ----------------------------------------------------------------------------

    private LocalDateTime firstLight(LocationEntity location) {
        SolarEventFreshness solar = new SolarEventFreshness(new SolarService(), clock);
        // The first window after 12:00 UTC on 5 Oct is that day's sunset.
        return solar.eventTime(location, LocalDate.of(2026, 10, 5), TargetType.SUNSET);
    }

    private TideExtremeType extremeAt(String locationName, LocalDateTime time) {
        LocationEntity location = locations.findByName(locationName).orElseThrow();
        Map<LocalDateTime, TideExtremeType> byTime = all(location).stream().collect(
                Collectors.toMap(TideExtremeEntity::getEventTime, TideExtremeEntity::getType));
        return byTime.get(time);
    }

    private List<TideExtremeEntity> all(LocationEntity location) {
        return tides.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(location.getId(),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0));
    }
}
