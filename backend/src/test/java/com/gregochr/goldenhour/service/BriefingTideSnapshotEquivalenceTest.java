package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Proves the build-scoped {@link TideExtremeSnapshot} changes how many queries a briefing build
 * makes and nothing else: the real {@link TideService}, {@link TideFactDeriver} and
 * {@link BriefingSlotBuilder} build every slot twice, once on the old per-slot-query path and once
 * on the snapshot path, over a repository fake that implements the inclusive {@code BETWEEN} of
 * both query methods.
 *
 * <p>Limits: {@code sun()} uses fixed UTC times, so the BST crossing and the {@code windowFor}
 * +/-1-day and UTC-midnight mutants are not observable here; {@code TideExtremeSnapshotTest}'s
 * exact {@code windowFor} tests pin them.
 */
@ExtendWith(MockitoExtension.class)
class BriefingTideSnapshotEquivalenceTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 10, 23);
    /** Crosses the 25 October clock change (BST to GMT). */
    private static final List<LocalDate> DATES = List.of(FIRST, FIRST.plusDays(1),
            FIRST.plusDays(2), FIRST.plusDays(3), FIRST.plusDays(4));
    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private static final long A_HIGH = 1L;
    private static final long B_LOW = 2L;
    private static final long C_MID = 3L;
    private static final long D_HIGH_MID = 4L;
    private static final long F_ALL = 5L;
    private static final long E_EDGE = 6L;
    private static final long G_INLAND = 7L;
    private static final long H_NO_ROWS = 8L;

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
    private TideFetchRecorder tideFetchRecorder;
    @Mock
    private SolarService solarService;
    @Mock
    private LocationService locationService;
    @Mock
    private LunarPhaseService lunarPhaseService;

    private TideService tideService;
    private BriefingSlotBuilder slotBuilder;
    private final Map<Long, List<TideExtremeEntity>> store = new HashMap<>();
    private List<LocationEntity> locations;

    @BeforeEach
    void setUp() {
        tideService = new TideService(restClient, repo, tideFetchRepository, tideFetchRecorder,
                worldTidesProperties, jobRunService);
        // One observation each: present, but too few for thresholds, so no percentile reads follow.
        lenient().when(repo.findHeightStatsByLocationIdAndTypeBefore(anyLong(), any(), any()))
                .thenAnswer(inv -> new Object[] {BigDecimal.valueOf(3), BigDecimal.valueOf(5),
                    BigDecimal.valueOf(1), 1L});
        slotBuilder = new BriefingSlotBuilder(solarService, locationService,
                new TideFactDeriver(tideService, lunarPhaseService, solarService),
                new BriefingVerdictEvaluator(), new WoodlandVerdictEvaluator(), repo,
                mock(EclipseSightAssembler.class));

        lenient().when(locationService.isCoastal(any()))
                .thenAnswer(inv -> !((LocationEntity) inv.getArgument(0)).getTideType().isEmpty());
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> sun(inv.getArgument(2), true));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> sun(inv.getArgument(2), false));
        lenient().when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    LocalDateTime t = sun(inv.getArgument(2), inv.getArgument(3));
                    return new SolarService.SolarWindow(t.minusMinutes(45), t.plusMinutes(45),
                            t.minusMinutes(45), t.plusMinutes(45));
                });

        // Both repository methods implement the inclusive BETWEEN, ascending, as the real ones do.
        lenient().when(repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(any(), any(), any()))
                .thenAnswer(inv -> between(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        lenient().when(repo.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), any(), any()))
                .thenAnswer(inv -> {
                    List<TideExtremeEntity> all = new ArrayList<>();
                    for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                        all.addAll(between((Long) id, inv.getArgument(1), inv.getArgument(2)));
                    }
                    all.sort(java.util.Comparator.comparing(TideExtremeEntity::getEventTime));
                    return all;
                });

        locations = List.of(
                location(A_HIGH, Set.of(TideType.HIGH)),
                location(B_LOW, Set.of(TideType.LOW)),
                location(C_MID, Set.of(TideType.MID)),
                location(D_HIGH_MID, Set.of(TideType.HIGH, TideType.MID)),
                location(F_ALL, Set.of(TideType.HIGH, TideType.LOW, TideType.MID)),
                location(E_EDGE, Set.of(TideType.HIGH, TideType.LOW)),
                location(G_INLAND, Set.of()),
                location(H_NO_ROWS, Set.of(TideType.HIGH)));

        store.put(A_HIGH, series(A_HIGH, LocalDateTime.of(2026, 10, 17, 3, 10), TideExtremeType.HIGH, 380));
        store.put(B_LOW, series(B_LOW, LocalDateTime.of(2026, 10, 17, 5, 0), TideExtremeType.LOW, 372));
        // A high 3 h 10 min before and a low 3 h 10 min after the 24 Oct sunset: the midpoint
        // lands on the light, so the MID quality path (the second fetch) runs.
        store.put(C_MID, series(C_MID, LocalDateTime.of(2026, 10, 24, 13, 20), TideExtremeType.HIGH, 380));
        store.put(D_HIGH_MID, series(D_HIGH_MID, LocalDateTime.of(2026, 10, 17, 1, 0), TideExtremeType.HIGH, 400));
        store.put(F_ALL, series(F_ALL, LocalDateTime.of(2026, 10, 17, 2, 30), TideExtremeType.LOW, 360));
        // The only rows sit exactly at the 25 Oct sunset minus and plus two days: an exclusive
        // bound at either end empties the read and drops the slot to NONE.
        TideExtremeEntity before = extreme(E_EDGE, sun(FIRST.plusDays(2), false).minusDays(2),
                TideExtremeType.LOW, 0.4);
        TideExtremeEntity after = extreme(E_EDGE, sun(FIRST.plusDays(2), false).plusDays(2),
                TideExtremeType.HIGH, 4.0);
        store.put(E_EDGE, List.of(before, after));
        store.put(H_NO_ROWS, List.of());
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
    private static List<TideExtremeEntity> series(long id, LocalDateTime anchor, TideExtremeType anchorType,
            int stepMinutes) {
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
                .sorted(java.util.Comparator.comparing(TideExtremeEntity::getEventTime))
                .toList();
    }

    private static OpenMeteoForecastResponse weather() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();
        List<Double> visibility = new ArrayList<>();
        List<Double> windSpeed = new ArrayList<>();
        List<Double> precip = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        LocalDateTime start = FIRST.minusDays(1).atStartOfDay();
        for (int i = 0; i < 24 * 8; i++) {
            times.add(start.plusHours(i).toString());
            cloudLow.add(20);
            visibility.add(15000.0);
            windSpeed.add(5.0);
            precip.add(0.0);
            humidity.add(70);
        }
        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setPrecipitation(precip);
        hourly.setRelativeHumidity2m(humidity);
        response.setHourly(hourly);
        return response;
    }

    private BriefingSlotBuilder.LocationWeather weatherFor(LocationEntity loc) {
        return new BriefingSlotBuilder.LocationWeather(loc, weather());
    }

    private BriefingSlot slotOf(List<BriefingSlot> slots, long locationId, LocalDate date,
            TargetType type) {
        return slots.stream()
                .filter(s -> s.locationId() == locationId
                        && s.solarEventTime().toLocalDate().equals(date)
                        && s.solarEventTime().equals(sun(date, type == TargetType.SUNRISE)))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("every slot is identical on the snapshot path and on the per-slot-query path")
    void snapshotPath_buildsEverySlotIdenticallyToTheQueryPath() {
        List<BriefingSlot> old = new ArrayList<>();
        for (LocationEntity loc : locations) {
            for (LocalDate date : DATES) {
                for (TargetType type : EVENTS) {
                    old.add(slotBuilder.buildSlot(weatherFor(loc), date, type));
                }
            }
        }

        TideExtremeSnapshot snapshot = slotBuilder.snapshotFor(locations, DATES);
        List<BriefingSlot> fresh = new ArrayList<>();
        for (LocationEntity loc : locations) {
            for (LocalDate date : DATES) {
                for (TargetType type : EVENTS) {
                    fresh.add(slotBuilder.buildSlot(weatherFor(loc), date, type, null, snapshot));
                }
            }
        }

        assertThat(old).hasSize(locations.size() * DATES.size() * EVENTS.size());
        assertThat(fresh).isEqualTo(old);

        // The fixtures are only worth anything if the paths they target actually run.
        assertThat(slotOf(fresh, E_EDGE, FIRST.plusDays(2), TargetType.SUNSET).tide().tideState())
                .as("the slot whose only rows sit on the +/- 2 day bounds still has a tide")
                .isNotNull();
        assertThat(slotOf(fresh, C_MID, FIRST.plusDays(1), TargetType.SUNSET).tide()
                .tideAlignmentQuality())
                .as("the MID quality path (the second fetch) ran")
                .isNotNull();
        assertThat(fresh.stream().filter(s -> s.tide().tideLevel() != null).count())
                .as("the tide-fit curve path ran").isGreaterThan(10);
        assertThat(slotOf(fresh, H_NO_ROWS, FIRST, TargetType.SUNSET).tide().tideState())
                .as("a coastal location with no rows reads as no tide").isNull();
        assertThat(slotOf(fresh, G_INLAND, FIRST, TargetType.SUNSET).tide().tideState()).isNull();
    }

    @Test
    @DisplayName("a location the snapshot did not cover reads through the per-location query, equal "
            + "to the old path")
    void uncoveredLocation_fallsBackAndMatchesTheOldPath() {
        List<LocationEntity> coveredOnly = locations.stream()
                .filter(l -> l.getId() != A_HIGH).toList();
        TideExtremeSnapshot snapshot = slotBuilder.snapshotFor(coveredOnly, DATES);
        LocationEntity uncovered = locations.stream()
                .filter(l -> l.getId() == A_HIGH).findFirst().orElseThrow();

        for (LocalDate date : DATES) {
            for (TargetType type : EVENTS) {
                BriefingSlot old = slotBuilder.buildSlot(weatherFor(uncovered), date, type);
                BriefingSlot fresh = slotBuilder.buildSlot(weatherFor(uncovered), date, type, null,
                        snapshot);
                assertThat(fresh).isEqualTo(old);
            }
        }
        assertThat(slotOf(List.of(slotBuilder.buildSlot(weatherFor(uncovered), FIRST,
                TargetType.SUNSET, null, snapshot)), A_HIGH, FIRST, TargetType.SUNSET)
                .tide().tideState()).as("the fallback found the rows, not an empty list").isNotNull();
    }

    @Test
    @DisplayName("a range outside the loaded window reads through the per-location query, equal to "
            + "the old path")
    void rangeOutsideWindow_fallsBackAndMatchesTheOldPath() {
        // A snapshot for one date only: every other date's +/- 2 day read is outside its window.
        TideExtremeSnapshot narrow = slotBuilder.snapshotFor(locations, List.of(FIRST));
        LocationEntity loc = locations.stream()
                .filter(l -> l.getId() == F_ALL).findFirst().orElseThrow();
        LocalDate far = FIRST.plusDays(4);

        BriefingSlot old = slotBuilder.buildSlot(weatherFor(loc), far, TargetType.SUNSET);
        BriefingSlot fresh = slotBuilder.buildSlot(weatherFor(loc), far, TargetType.SUNSET, null,
                narrow);

        assertThat(fresh).isEqualTo(old);
        assertThat(fresh.tide().tideState()).isNotNull();
    }

    @Test
    @DisplayName("a build over N coastal slots makes one bulk query and no per-location query, and "
            + "reads the size statistics at most once per location")
    void snapshotBuild_makesOneBulkQuery() {
        TideExtremeSnapshot snapshot = slotBuilder.snapshotFor(locations, DATES);
        for (LocationEntity loc : locations) {
            for (LocalDate date : DATES) {
                for (TargetType type : EVENTS) {
                    slotBuilder.buildSlot(weatherFor(loc), date, type, null, snapshot);
                }
            }
        }

        verify(repo, times(1)).findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), any(), any());
        verify(repo, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any());
        // Every coastal slot with a high water asks for the size statistics; the memo behind
        // getTideStats keeps that to one read per location.
        for (LocationEntity loc : locations) {
            verify(repo, atMost(1)).findHeightStatsByLocationIdAndTypeBefore(
                    eq(loc.getId()), eq(TideExtremeType.HIGH), any());
        }
        assertThat(snapshot.rowCount()).isPositive();
    }

    @Test
    @DisplayName("an empty roster makes no tide query at all")
    void emptyRoster_makesNoQuery() {
        TideExtremeSnapshot snapshot = slotBuilder.snapshotFor(List.of(), DATES);

        assertThat(snapshot.rowCount()).isZero();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("a roster with no coastal location makes no tide query at all")
    void inlandOnlyRoster_makesNoQuery() {
        TideExtremeSnapshot snapshot = slotBuilder.snapshotFor(
                locations.stream().filter(l -> l.getId() == G_INLAND).toList(), DATES);

        assertThat(snapshot.rowCount()).isZero();
        verifyNoInteractions(repo);
    }
}
