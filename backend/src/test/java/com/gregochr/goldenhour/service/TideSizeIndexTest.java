package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideSizeIndex} — the "is this day's water spring- or king-sized" question,
 * answered from stored heights rather than from the moon.
 *
 * <p>Heights are chosen to sit specific distances either side of the thresholds rather than being
 * plausible-looking numbers: the comparison is strict, the two thresholds are independent, and the
 * whole point of the class is which side of a line a reading falls on.
 */
@ExtendWith(MockitoExtension.class)
class TideSizeIndexTest {

    /** Mid-August, and the local day the extremes below are placed in. */
    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    private static final long LOCATION_ID = 7L;
    private static final long SECOND_LOCATION_ID = 9L;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private TideService tideService;

    private TideSizeIndex index;

    @BeforeEach
    void setUp() {
        index = new TideSizeIndex(tideExtremeRepository, tideService);
    }

    @Test
    @DisplayName("a high water above the spring threshold makes the day spring-sized")
    void aboveSpringThresholdIsSpring() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.20"));

        TideSizeIndex.Sizes sizes = index.measure(List.of(location(LOCATION_ID)), DAY, DAY);

        assertThat(sizes.usable()).isTrue();
        assertThat(sizes.springOn(DAY)).isTrue();
        // 4.20 clears the 4.00 spring threshold but not the 4.60 P95: spring, not king.
        assertThat(sizes.kingOn(DAY)).isFalse();
    }

    @Test
    @DisplayName("a high water exactly on the threshold does not qualify — the comparison is "
            + "strictly greater, matching TideFactDeriver")
    void exactlyOnTheThresholdDoesNotQualify() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.00"));

        // If this ever loosens to >=, the two surfaces classify the same water differently on the
        // one reading where they are most likely to be compared.
        assertThat(index.measure(List.of(location(LOCATION_ID)), DAY, DAY).springOn(DAY)).isFalse();
    }

    @Test
    @DisplayName("a high water above P95 makes the day king-sized even when it is not also above "
            + "the spring threshold")
    void kingIsNotAssumedToImplySpring() {
        // Contrived, and deliberately so: P95 and 125%-of-mean come from one sample but neither is
        // defined in terms of the other, so nothing stops a location's P95 sitting below its spring
        // threshold. A caller that tested only spring would miss the day entirely.
        stats(LOCATION_ID, "5.00", "4.20");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.40"));

        TideSizeIndex.Sizes sizes = index.measure(List.of(location(LOCATION_ID)), DAY, DAY);

        assertThat(sizes.kingOn(DAY)).isTrue();
        assertThat(sizes.springOn(DAY)).isFalse();
    }

    @Test
    @DisplayName("the day takes its biggest high water, not the last one read")
    void theDayIsMeasuredByItsBiggestHighWater() {
        stats(LOCATION_ID, "4.00", "9.00");
        extremes(
                highWater(LOCATION_ID, DAY.atTime(5, 40), "4.30"),
                highWater(LOCATION_ID, DAY.atTime(18, 5), "3.10"));

        // The second high water of the day is the smaller one and comes last in event order, so a
        // put() rather than a max-merge would overwrite the qualifying reading with it.
        assertThat(index.measure(List.of(location(LOCATION_ID)), DAY, DAY).springOn(DAY)).isTrue();
    }

    @Test
    @DisplayName("any location on the roster can carry the day, matching the Plan tab's rule")
    void anyLocationQualifiesTheDay() {
        stats(LOCATION_ID, "4.00", "4.60");
        stats(SECOND_LOCATION_ID, "6.00", "7.00");
        extremes(
                highWater(LOCATION_ID, DAY.atTime(6, 12), "3.10"),
                highWater(SECOND_LOCATION_ID, DAY.atTime(6, 30), "6.40"));

        // KingTideHotTopicStrategy.findKingTide returns the first qualifying slot anywhere in the
        // briefing; a per-location or majority rule here would disagree with it by construction.
        assertThat(index.measure(
                List.of(location(LOCATION_ID), location(SECOND_LOCATION_ID)), DAY, DAY)
                .springOn(DAY)).isTrue();
    }

    @Test
    @DisplayName("a high water just after local midnight belongs to its London day, not its UTC one")
    void extremesAreBucketedByTheLondonDay() {
        stats(LOCATION_ID, "4.00", "4.60");
        // 23:20 UTC on the 13th is 00:20 BST on the 14th. Bucketing by the UTC date would credit
        // this reading to the previous day, which is how a run's dates and its chart come to
        // describe different nights.
        extremes(highWater(LOCATION_ID, DAY.minusDays(1).atTime(23, 20), "4.30"));

        TideSizeIndex.Sizes sizes = index.measure(
                List.of(location(LOCATION_ID)), DAY.minusDays(1), DAY);

        assertThat(sizes.springOn(DAY)).isTrue();
        assertThat(sizes.springOn(DAY.minusDays(1))).isFalse();
    }

    @Test
    @DisplayName("a roster with no thresholds is UNMEASURED, never an empty answer")
    void noThresholdsIsUnmeasured() {
        // TideService withholds both thresholds until a spring-neap cycle has been observed. The
        // difference between "nothing qualified" and "nothing could be measured" is the whole
        // fallback: reported as the former, a cold database renders as ninety days with no tides.
        when(tideService.getTideStats(LOCATION_ID)).thenReturn(Optional.empty());

        assertThat(index.measure(List.of(location(LOCATION_ID)), DAY, DAY))
                .isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
        verify(tideExtremeRepository, times(0))
                .findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                        anyCollection(), any(), any(), any());
    }

    @Test
    @DisplayName("thresholds without stored extremes are UNMEASURED too")
    void noExtremesIsUnmeasured() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes();

        assertThat(index.measure(List.of(location(LOCATION_ID)), DAY, DAY))
                .isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
    }

    @Test
    @DisplayName("thresholds at one location and heights at another measure nothing — the two have "
            + "to meet at the same place")
    void thresholdsAndHeightsMustMeetAtOneLocation() {
        // Between them the roster has both halves of a comparison and can still make none. Gating on
        // "some location has thresholds" and "some rows came back" separately passes this and then
        // reports an empty answer as a finding: no spring tides, measured.
        stats(LOCATION_ID, "4.00", "4.60");
        stats(SECOND_LOCATION_ID, null, null);
        extremes(highWater(SECOND_LOCATION_ID, DAY.atTime(6, 12), "9.90"));

        assertThat(index.measure(
                List.of(location(LOCATION_ID), location(SECOND_LOCATION_ID)), DAY, DAY))
                .isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
    }

    @Test
    @DisplayName("an empty roster is UNMEASURED and touches nothing")
    void emptyRosterIsUnmeasured() {
        assertThat(index.measure(List.of(), DAY, DAY)).isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
        assertThat(index.measure(null, DAY, DAY)).isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
    }

    @Test
    @DisplayName("a backwards window is UNMEASURED rather than a query with reversed bounds")
    void backwardsWindowIsUnmeasured() {
        assertThat(index.measure(List.of(location(LOCATION_ID)), DAY, DAY.minusDays(1)))
                .isEqualTo(TideSizeIndex.Sizes.UNMEASURED);
    }

    @Test
    @DisplayName("thresholds are fetched once per day, not once per measurement")
    void thresholdsAreCachedForTheDay() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.20"));

        index.measure(List.of(location(LOCATION_ID)), DAY, DAY);
        index.measure(List.of(location(LOCATION_ID)), DAY, DAY);

        // getTideStats is four queries and pulls the location's whole stored height history. Over a
        // sixty-location roster, paying that per request is the difference between a cheap feed and
        // a slow one — the feed above caches only one requested length, so a client alternating
        // ?days= values calls straight through.
        verify(tideService, times(1)).getTideStats(LOCATION_ID);
    }

    @Test
    @DisplayName("a location added since the cache was built forces a rebuild rather than being "
            + "silently treated as having no history")
    void aNewLocationRebuildsTheCache() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.20"));
        index.measure(List.of(location(LOCATION_ID)), DAY, DAY);

        stats(SECOND_LOCATION_ID, "6.00", "7.00");
        index.measure(List.of(location(LOCATION_ID), location(SECOND_LOCATION_ID)), DAY, DAY);

        // A missing map entry is indistinguishable from one with no thresholds, so a location added
        // through the Admin UI would otherwise be invisible to the feed until midnight.
        verify(tideService).getTideStats(SECOND_LOCATION_ID);
    }

    @Test
    @DisplayName("evict() drops the cached thresholds")
    void evictForcesARefetch() {
        stats(LOCATION_ID, "4.00", "4.60");
        extremes(highWater(LOCATION_ID, DAY.atTime(6, 12), "4.20"));

        index.measure(List.of(location(LOCATION_ID)), DAY, DAY);
        index.evict();
        index.measure(List.of(location(LOCATION_ID)), DAY, DAY);

        verify(tideService, times(2)).getTideStats(LOCATION_ID);
    }

    /**
     * Stubs a location's stats. Either threshold may be null, which is the shape
     * {@code TideService} returns before a whole spring–neap cycle has been observed.
     */
    private void stats(long locationId, String springThreshold, String p95) {
        when(tideService.getTideStats(locationId)).thenReturn(Optional.of(new TideStats(
                null, null, null, null, 900L, null, null, null,
                p95 == null ? null : new BigDecimal(p95), 0L, null,
                springThreshold == null ? null : new BigDecimal(springThreshold), null, 0L)));
    }

    private void extremes(TideExtremeEntity... rows) {
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(rows));
    }

    private static TideExtremeEntity highWater(long locationId, LocalDateTime utc, String metres) {
        TideExtremeEntity extreme = new TideExtremeEntity();
        extreme.setLocationId(locationId);
        extreme.setEventTime(utc);
        extreme.setHeightMetres(new BigDecimal(metres));
        extreme.setType(TideExtremeType.HIGH);
        return extreme;
    }

    private static LocationEntity location(long id) {
        return LocationEntity.builder().id(id).name("Location " + id).build();
    }
}
