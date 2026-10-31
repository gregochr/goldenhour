package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the collection-scoped {@link TideExtremeSnapshot} (finding F26) changes how many queries
 * the batch triage's tide step makes and nothing else: the real {@link TideService},
 * {@link TideFactDeriver} and {@link ForecastDataAugmentor} derive every candidate's tide facts
 * twice, once per candidate on the live-query path and once from one snapshot, over a repository
 * fake that implements the inclusive {@code BETWEEN} of both query methods.
 *
 * <p>The fixture mixes spring and king lunar days, a high-water above the spring threshold, a
 * location with no rows, an edge location whose only rows sit on the window bound, and an inland
 * location, so the equality is not trivially empty. Limits: {@code sun()} is a fixed UTC time, so
 * the BST crossing in {@code windowFor} is pinned by {@code TideExtremeSnapshotTest}, not here.
 */
@ExtendWith(MockitoExtension.class)
class TriageTideSnapshotEquivalenceTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 10, 23);
    /** Crosses the 25 October clock change (BST to GMT). */
    private static final List<LocalDate> DATES = List.of(FIRST, FIRST.plusDays(1),
            FIRST.plusDays(2), FIRST.plusDays(3), FIRST.plusDays(4));
    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private static final long A_HIGH = 1L;
    private static final long B_LOW = 2L;
    private static final long C_MID = 3L;
    private static final long D_HIGH_LOW = 4L;
    private static final long E_EDGE = 5L;
    private static final long F_NO_ROWS = 6L;
    private static final long G_INLAND = 7L;

    @Mock
    private RestClient restClient;
    @Mock
    private WorldTidesProperties worldTidesProperties;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private TideExtremeRepository repo;

    @Mock
    private TideFetchRepository tideFetchRepository;
    @Mock
    private SolarService solarService;
    @Mock
    private LunarPhaseService lunarPhaseService;

    private TideService tideService;
    private ForecastDataAugmentor augmentor;
    private final Map<Long, List<TideExtremeEntity>> store = new HashMap<>();
    private List<LocationEntity> locations;

    @BeforeEach
    void setUp() {
        tideService = new TideService(restClient, repo, tideFetchRepository, worldTidesProperties, jobRunService);
        augmentor = new ForecastDataAugmentor(null, solarService, tideService,
                new TideFactDeriver(tideService, lunarPhaseService, solarService),
                null, null, null,
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"));

        locations = List.of(
                location(A_HIGH, Set.of(TideType.HIGH)),
                location(B_LOW, Set.of(TideType.LOW)),
                location(C_MID, Set.of(TideType.MID)),
                location(D_HIGH_LOW, Set.of(TideType.HIGH, TideType.LOW)),
                location(E_EDGE, Set.of(TideType.HIGH, TideType.LOW)),
                location(F_NO_ROWS, Set.of(TideType.HIGH)),
                location(G_INLAND, Set.of()));

        store.put(A_HIGH, series(A_HIGH, LocalDateTime.of(2026, 10, 17, 3, 10), TideExtremeType.HIGH, 380));
        store.put(B_LOW, series(B_LOW, LocalDateTime.of(2026, 10, 17, 5, 0), TideExtremeType.LOW, 372));
        // A high 3 h 10 min before and a low 3 h 10 min after the 24 Oct sunset: the midpoint
        // lands on the light, so the MID quality path (the second fetch) runs.
        store.put(C_MID, series(C_MID, LocalDateTime.of(2026, 10, 24, 13, 20), TideExtremeType.HIGH, 380));
        store.put(D_HIGH_LOW, series(D_HIGH_LOW, LocalDateTime.of(2026, 10, 17, 1, 0), TideExtremeType.HIGH, 400));
        // The only rows sit exactly at the 25 Oct sunset minus and plus two days: an exclusive
        // bound at either end empties the read and drops the slot.
        store.put(E_EDGE, List.of(
                extreme(E_EDGE, sun(FIRST.plusDays(2), false).minusDays(2), TideExtremeType.LOW, 0.4),
                extreme(E_EDGE, sun(FIRST.plusDays(2), false).plusDays(2), TideExtremeType.HIGH, 4.0)));
        store.put(F_NO_ROWS, List.of());
    }

    private static boolean coastal(LocationEntity loc) {
        return !loc.getTideType().isEmpty();
    }

    private static List<Long> coastalIds(List<LocationEntity> locs) {
        return locs.stream().filter(TriageTideSnapshotEquivalenceTest::coastal)
                .map(LocationEntity::getId).toList();
    }

    /** The golden/blue window for each exact (coordinates, date, event) the derivation asks for. */
    private void stubSolar(List<LocationEntity> locs, List<LocalDate> dates, List<TargetType> events) {
        for (LocationEntity loc : locs.stream().filter(TriageTideSnapshotEquivalenceTest::coastal).toList()) {
            for (LocalDate date : dates) {
                for (TargetType type : events) {
                    boolean sunrise = type == TargetType.SUNRISE;
                    LocalDateTime t = sun(date, sunrise);
                    when(solarService.goldenBlueWindow(loc.getLat(), loc.getLon(), date, sunrise))
                            .thenReturn(new SolarService.SolarWindow(t.minusMinutes(45),
                                    t.plusMinutes(45), t.minusMinutes(45), t.plusMinutes(45)));
                }
            }
        }
    }

    /** Lunar days cycle KING, SPRING, REGULAR so every classification is exercised. */
    private void stubLunar(List<LocalDate> dates) {
        for (LocalDate date : dates) {
            LunarTideType type = switch (date.getDayOfMonth() % 3) {
                case 0 -> LunarTideType.KING_TIDE;
                case 1 -> LunarTideType.SPRING_TIDE;
                default -> LunarTideType.REGULAR_TIDE;
            };
            when(lunarPhaseService.classifyTide(date)).thenReturn(type);
            when(lunarPhaseService.getMoonPhase(date)).thenReturn("FULL_MOON");
            when(lunarPhaseService.isMoonAtPerigee(date)).thenReturn(true);
        }
    }

    /**
     * Historical stats for the given locations: an average high of 3.0 m puts the spring threshold
     * at 3.75 m, and a 3.1 to 5.0 m sample gives a P95 near 4.9 m, so the fixture's highs (3.5 to
     * 5.1 m) land in every band: none, above the spring threshold only, and above P95.
     */
    private void stubStats(List<Long> ids) {
        LocalDateTime cutoff = LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay();
        List<BigDecimal> heights = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            heights.add(BigDecimal.valueOf(3.1 + i * 0.1));
        }
        for (long id : ids) {
            when(repo.findHeightStatsByLocationIdAndTypeBefore(id, TideExtremeType.HIGH, cutoff))
                    .thenReturn(new Object[] {BigDecimal.valueOf(3.0), BigDecimal.valueOf(5.1),
                        BigDecimal.valueOf(2.0), 100L});
            when(repo.findHeightStatsByLocationIdAndTypeBefore(id, TideExtremeType.LOW, cutoff))
                    .thenReturn(new Object[] {BigDecimal.valueOf(0.6), BigDecimal.valueOf(1.0),
                        BigDecimal.valueOf(0.2), 100L});
            when(repo.findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(id, TideExtremeType.HIGH,
                    cutoff)).thenReturn(heights);
        }
    }

    /**
     * The per-location query, one stub per exact location id. The time bounds are matched by type
     * only and answered from them with the inclusive BETWEEN the real query implements, so the
     * answer depends on the arguments the code actually passes.
     */
    private void stubPerLocationQuery(List<Long> ids) {
        for (long id : ids) {
            when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(eq(id),
                    any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenAnswer(inv -> between(id, inv.getArgument(1), inv.getArgument(2)));
        }
    }

    /** The multi-location query for exactly these ids over exactly this window. */
    private void stubSnapshotQuery(Set<Long> ids, TideExtremeSnapshot.Window window) {
        when(repo.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(ids, window.from(), window.to()))
                .thenAnswer(inv -> {
                    List<TideExtremeEntity> all = new ArrayList<>();
                    for (long id : ids) {
                        all.addAll(between(id, window.from(), window.to()));
                    }
                    all.sort(Comparator.comparing(TideExtremeEntity::getEventTime));
                    return all;
                });
    }

    private static LocalDateTime sun(LocalDate date, boolean sunrise) {
        return date.atTime(sunrise ? 6 : 16, 30);
    }

    private static LocationEntity location(long id, Set<TideType> tides) {
        return LocationEntity.builder()
                .id(id).name("Loc " + id).lat(54.5 + id / 10.0).lon(-1.4)
                .locationType(Set.of(LocationType.SEASCAPE))
                .tideType(tides)
                .solarEventType(Set.of())
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    private static TideExtremeEntity extreme(long locationId, LocalDateTime utc,
            TideExtremeType type, double metres) {
        return TideExtremeEntity.builder().locationId(locationId).eventTime(utc)
                .heightMetres(BigDecimal.valueOf(metres)).type(type).build();
    }

    /** Alternating extremes every {@code stepMinutes}, both directions from an anchor. */
    private static List<TideExtremeEntity> series(long id, LocalDateTime anchor,
            TideExtremeType anchorType, int stepMinutes) {
        List<TideExtremeEntity> rows = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 10, 16, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 0, 0);
        int back = 0;
        while (anchor.minusMinutes((long) (back + 1) * stepMinutes).isAfter(start)) {
            back++;
        }
        for (int i = -back; anchor.plusMinutes((long) i * stepMinutes).isBefore(end); i++) {
            boolean sameAsAnchor = Math.floorMod(i, 2) == 0;
            TideExtremeType type = sameAsAnchor ? anchorType
                    : (anchorType == TideExtremeType.HIGH ? TideExtremeType.LOW : TideExtremeType.HIGH);
            double metres = type == TideExtremeType.HIGH ? 3.5 + (Math.floorMod(i, 5)) * 0.4
                    : 0.4 + (Math.floorMod(i, 3)) * 0.2;
            rows.add(extreme(id, anchor.plusMinutes((long) i * stepMinutes), type, metres));
        }
        return rows;
    }

    private List<TideExtremeEntity> between(Long locationId, LocalDateTime from, LocalDateTime to) {
        return store.getOrDefault(locationId, List.of()).stream()
                .filter(e -> !e.getEventTime().isBefore(from) && !e.getEventTime().isAfter(to))
                .sorted(Comparator.comparing(TideExtremeEntity::getEventTime))
                .toList();
    }

    /** Derives every location x date x event through the augmentor, keyed for comparison. */
    private Map<String, AtmosphericData> deriveAll(TideExtremeSnapshot snapshot) {
        Map<String, AtmosphericData> out = new LinkedHashMap<>();
        AtmosphericData base = TestAtmosphericData.defaults();
        for (LocationEntity loc : locations) {
            for (LocalDate date : DATES) {
                for (TargetType type : EVENTS) {
                    LocalDateTime eventTime = sun(date, type == TargetType.SUNRISE);
                    AtmosphericData result = snapshot == null
                            ? augmentor.augmentWithTideData(base, loc.getId(), eventTime,
                                    loc.getTideType(), loc.getLat(), loc.getLon(), type)
                            : augmentor.augmentWithTideData(base, loc.getId(), eventTime,
                                    loc.getTideType(), loc.getLat(), loc.getLon(), type, snapshot);
                    out.put(loc.getId() + "|" + date + "|" + type, result);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("every candidate's tide facts are identical from the snapshot and from the "
            + "per-candidate query, across spring, king, regular, edge, empty and inland")
    void snapshotPath_derivesEveryCandidateIdenticallyToTheQueryPath() {
        stubSolar(locations, DATES, EVENTS);
        stubLunar(DATES);
        stubStats(List.of(A_HIGH, B_LOW, C_MID, D_HIGH_LOW, E_EDGE));
        stubPerLocationQuery(coastalIds(locations));
        stubSnapshotQuery(Set.copyOf(coastalIds(locations)), TideExtremeSnapshot.windowFor(DATES));

        Map<String, AtmosphericData> live = deriveAll(null);
        TideExtremeSnapshot snapshot = augmentor.tideSnapshotFor(locations, DATES);
        Map<String, AtmosphericData> fromSnapshot = deriveAll(snapshot);

        assertThat(fromSnapshot).isEqualTo(live);

        // The fixture is not trivially empty: tide facts exist, lunar types vary, and the
        // inland location carries none.
        List<AtmosphericData> coastal = live.entrySet().stream()
                .filter(e -> !e.getKey().startsWith(G_INLAND + "|"))
                .map(Map.Entry::getValue).toList();
        assertThat(coastal).anyMatch(d -> d.tide() != null);
        assertThat(coastal).filteredOn(d -> d.tide() != null)
                .extracting(d -> d.tide().lunarTideType())
                .contains(LunarTideType.KING_TIDE, LunarTideType.SPRING_TIDE,
                        LunarTideType.REGULAR_TIDE);
        java.util.Set<Object> sizes = new java.util.HashSet<>();
        coastal.stream().filter(d -> d.tide() != null)
                .forEach(d -> sizes.add(String.valueOf(d.tide().statisticalSize())));
        assertThat(sizes).as("statistical size bands covered").hasSizeGreaterThanOrEqualTo(3);
        assertThat(live.entrySet().stream()
                .filter(e -> e.getKey().startsWith(G_INLAND + "|"))
                .map(Map.Entry::getValue)).allMatch(d -> d.tide() == null);
        assertThat(live.entrySet().stream()
                .filter(e -> e.getKey().startsWith(F_NO_ROWS + "|"))
                .map(Map.Entry::getValue)).as("no stored rows means no tide, on both paths")
                .allMatch(d -> d.tide() == null);
    }

    @Test
    @DisplayName("the snapshot path issues one multi-location read for the whole collection and "
            + "no per-candidate extremes query")
    void snapshotPath_loadsOnceAndNeverQueriesPerCandidate() {
        Set<Long> ids = Set.copyOf(coastalIds(locations));
        TideExtremeSnapshot.Window window = TideExtremeSnapshot.windowFor(DATES);
        stubSolar(locations, DATES, EVENTS);
        stubLunar(DATES);
        stubStats(List.of(A_HIGH, B_LOW, C_MID, D_HIGH_LOW, E_EDGE));
        stubSnapshotQuery(ids, window);

        TideExtremeSnapshot snapshot = augmentor.tideSnapshotFor(locations, DATES);
        deriveAll(snapshot);

        verify(repo, times(1)).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                ids, window.from(), window.to());
        // Every candidate's range lies inside the loaded window, so the live fallback must not run.
        // any() is only the matcher for the never() verification.
        verify(repo, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(any(), any(), any());
    }

    @Test
    @DisplayName("with no snapshot passed, each candidate reads its own extremes exactly as before")
    void noSnapshot_fallsBackToThePerCandidateQuery() {
        LocationEntity loc = locations.getFirst();
        LocalDateTime eventTime = sun(FIRST, false);
        stubSolar(List.of(loc), List.of(FIRST), List.of(TargetType.SUNSET));
        stubLunar(List.of(FIRST));
        stubStats(List.of(A_HIGH));
        when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                A_HIGH, eventTime.minusDays(2), eventTime.plusDays(2)))
                .thenReturn(between(A_HIGH, eventTime.minusDays(2), eventTime.plusDays(2)));

        augmentor.augmentWithTideData(TestAtmosphericData.defaults(), loc.getId(), eventTime,
                loc.getTideType(), loc.getLat(), loc.getLon(), TargetType.SUNSET);

        verify(repo, times(1)).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                A_HIGH, eventTime.minusDays(2), eventTime.plusDays(2));
        // any() is only the matcher for the never() verification.
        verify(repo, never()).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(any(), any(), any());
    }

    @Test
    @DisplayName("a candidate outside the loaded window falls back to the live query, never to a "
            + "silent empty")
    void candidateOutsideWindow_fallsBackToLiveQuery() {
        LocationEntity loc = locations.getFirst();
        LocalDateTime farEvent = sun(FIRST.plusDays(20), false);
        stubSnapshotQuery(Set.of(A_HIGH), TideExtremeSnapshot.windowFor(List.of(FIRST)));
        stubSolar(List.of(loc), List.of(FIRST.plusDays(20)), List.of(TargetType.SUNSET));
        when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                A_HIGH, farEvent.minusDays(2), farEvent.plusDays(2))).thenReturn(List.of());
        TideExtremeSnapshot snapshot = augmentor.tideSnapshotFor(List.of(loc), List.of(FIRST));

        augmentor.augmentWithTideData(TestAtmosphericData.defaults(), loc.getId(), farEvent,
                loc.getTideType(), loc.getLat(), loc.getLon(), TargetType.SUNSET, snapshot);

        verify(repo, times(1)).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                A_HIGH, farEvent.minusDays(2), farEvent.plusDays(2));
    }

    @Test
    @DisplayName("inland-only locations or no dates load nothing")
    void inlandOnlyOrNoDates_loadNothing() {
        augmentor.tideSnapshotFor(List.of(locations.getLast()), DATES);
        augmentor.tideSnapshotFor(locations, Collections.emptyList());

        // any() is only the matcher for the never() verification.
        verify(repo, never()).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(any(), any(), any());
    }

    @Test
    @DisplayName("TideService.snapshotFor accepts dates in any order and ignores null ids")
    void tideServiceSnapshotFor_sortsDatesAndSkipsNullIds() {
        List<LocalDate> shuffled = List.of(FIRST.plusDays(3), FIRST, FIRST.plusDays(1));
        List<Long> ids = new ArrayList<>();
        ids.add(A_HIGH);
        ids.add(null);
        TideExtremeSnapshot.Window window = TideExtremeSnapshot.windowFor(
                List.of(FIRST, FIRST.plusDays(1), FIRST.plusDays(3)));
        stubSnapshotQuery(Set.of(A_HIGH), window);

        tideService.snapshotFor(ids, shuffled);

        verify(repo, times(1)).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                Set.of(A_HIGH), window.from(), window.to());
    }
}

