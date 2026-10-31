package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.model.TideSourceChange;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Diff-on-write detection and the source-change decision of {@link WorldTidesIngestionService}
 * (tide-source-integrity-plan P2). Real request building runs against {@link MockRestServiceServer};
 * the repositories are mocks stubbed with the exact windows the service must ask for.
 */
@ExtendWith(MockitoExtension.class)
class WorldTidesDiffOnWriteTest {

    private static final long LOCATION_ID = 5L;
    private static final double LAT = 54.5;
    private static final double LON = -0.8;
    private static final List<String> FORWARD_REASONS = List.of(
            TideFetchEntity.SEED, TideFetchEntity.RESEED, TideFetchEntity.TAIL, TideFetchEntity.POINT_CHANGE);

    private static final LocalDateTime START_OF_DAY = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
    private static final LocalDateTime HORIZON = START_OF_DAY.plusDays(97);
    private static final LocalDateTime FRONTIER = START_OF_DAY.plusDays(90);
    private static final LocalDateTime TAIL_FROM = FRONTIER.minusDays(7);
    private static final LocalDateTime HIGH_AT = FRONTIER.minusDays(3);
    private static final LocalDateTime LOW_AT = HIGH_AT.plusHours(6).plusMinutes(12);

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private TideFetchRepository tideFetchRepository;

    @Mock
    private TideFetchRecorder tideFetchRecorder;

    @Mock
    private WorldTidesProperties worldTidesProperties;

    @Mock
    private JobRunService jobRunService;

    private MockRestServiceServer server;
    private WorldTidesIngestionService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        service = new WorldTidesIngestionService(builder.build(), tideExtremeRepository, tideFetchRepository,
                tideFetchRecorder, worldTidesProperties, jobRunService);
    }

    private static LocationEntity location() {
        return LocationEntity.builder().id(LOCATION_ID).name("Staithes").lat(LAT).lon(LON).build();
    }

    private static TideExtremeEntity stored(TideExtremeType type, LocalDateTime at, String height) {
        return TideExtremeEntity.builder().locationId(LOCATION_ID).eventTime(at)
                .heightMetres(new BigDecimal(height)).type(type).build();
    }

    private static long epoch(LocalDateTime time) {
        return time.toEpochSecond(ZoneOffset.UTC);
    }

    private static String response(String atlas, String station, double responseLat, long highDt,
            double highHeight, long lowDt, double lowHeight) {
        return """
                {"status":200,"callCount":3,"atlas":%s,"station":%s,
                 "requestLat":54.5,"requestLon":-0.8,"responseLat":%s,"responseLon":-0.79,
                 "responseDatum":"LAT",
                 "extremes":[{"dt":%d,"height":%s,"type":"High"},{"dt":%d,"height":%s,"type":"Low"}]}"""
                .formatted(quoted(atlas), quoted(station), responseLat, highDt, highHeight, lowDt, lowHeight);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** A fetch that will be resolved as a TAIL from {@link #TAIL_FROM}, with the stored week below it. */
    private void givenTailWithStoredWeek() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(FRONTIER);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(START_OF_DAY.minusDays(7));
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(TAIL_FROM), eq(HORIZON)))
                .thenReturn(List.of(stored(TideExtremeType.HIGH, HIGH_AT, "5.600"),
                        stored(TideExtremeType.LOW, LOW_AT, "1.200")));
    }

    private static TideFetchEntity previousRow(Integer pairs, Double lagMin, Double heightM, String atlas,
            String station, double responseLat) {
        return TideFetchEntity.builder().locationId(LOCATION_ID).reason(TideFetchEntity.TAIL).success(true)
                .requestLat(LAT).requestLng(LON).responseLat(responseLat).responseLng(-0.79)
                .atlas(atlas).station(station)
                .overlapPairs(pairs).maxLagDeltaMin(lagMin).maxHeightDeltaM(heightM).build();
    }

    private void givenPreviousForwardRow(TideFetchEntity row) {
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(LOCATION_ID), eq(FORWARD_REASONS))).thenReturn(Optional.of(row));
    }

    private void respondWith(String body) {
        server.expect(queryParam("lat", String.valueOf(LAT)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private TideFetchEntity recordedRow() {
        ArgumentCaptor<TideFetchEntity> captor = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a tail shifted by 46 minutes is stored, recorded with its deltas, and reported with examples")
    void shiftedTimesAreReported() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2022", null, 54.51));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusMinutes(46)), 5.61,
                epoch(LOW_AT.plusMinutes(44)), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNotNull();
        assertThat(change.locationName()).isEqualTo("Staithes");
        assertThat(change.sourceChange()).isNull();
        assertThat(change.timingExamples()).hasSize(2);
        assertThat(change.timingExamples().get(0)).contains("HIGH").contains("+46 min")
                .contains("5.600 m -> 5.610 m");
        TideFetchEntity row = recordedRow();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.TAIL);
        assertThat(row.getOverlapPairs()).isEqualTo(2);
        assertThat(row.getMaxLagDeltaMin()).isEqualTo(46.0);
        assertThat(row.getMaxHeightDeltaM()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("the diff reads the stored week before the delete, and the new values are still stored")
    void diffHappensBeforeTheDelete() {
        givenTailWithStoredWeek();
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusMinutes(46)), 5.6,
                epoch(LOW_AT), 1.2));

        service.fetchAndStoreTideExtremes(location());

        InOrder order = inOrder(tideExtremeRepository);
        order.verify(tideExtremeRepository).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(TAIL_FROM), eq(HORIZON));
        order.verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                eq(LOCATION_ID), eq(TAIL_FROM), eq(HORIZON));
        ArgumentCaptor<List<TideExtremeEntity>> saved = ArgumentCaptor.forClass(List.class);
        order.verify(tideExtremeRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(TideExtremeEntity::getEventTime)
                .containsExactly(HIGH_AT.plusMinutes(46), LOW_AT);
    }

    @Test
    @DisplayName("an identical re-fetch records zero deltas and reports nothing")
    void cleanFetchIsQuiet() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2022", null, 54.51));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNull();
        TideFetchEntity row = recordedRow();
        assertThat(row.getOverlapPairs()).isEqualTo(2);
        assertThat(row.getMaxLagDeltaMin()).isZero();
        assertThat(row.getMaxHeightDeltaM()).isZero();
    }

    @Test
    @DisplayName("exactly 5 minutes and exactly 0.05 m is not a change")
    void thresholdsAreExclusive() {
        givenTailWithStoredWeek();
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusMinutes(5)), 5.65,
                epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();
        assertThat(recordedRow().getMaxLagDeltaMin()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("a height-only change above 0.05 m is reported")
    void heightOnlyChangeIsReported() {
        givenTailWithStoredWeek();
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT), 5.66, epoch(LOW_AT), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNotNull();
        assertThat(change.timingExamples()).hasSize(1);
    }

    @Test
    @DisplayName("a persisting difference is not alerted again while the previous fetch also showed it")
    void persistingDifferenceIsNotRepeated() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 46.0, 0.01, "FES2022", null, 54.51));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusMinutes(46)), 5.6,
                epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();
        assertThat(recordedRow().getMaxLagDeltaMin()).isEqualTo(46.0);
    }

    @Test
    @DisplayName("a difference is alerted again once a fetch in between showed none")
    void differenceAlertsAgainAfterACleanFetch() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2022", null, 54.51));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusMinutes(46)), 5.6,
                epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNotNull();
    }

    @Test
    @DisplayName("a SEED fetch makes no comparison and records no deltas")
    void seedHasNoDiff() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION_ID), eq(START_OF_DAY))).thenReturn(null);
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();

        verify(tideExtremeRepository, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(START_OF_DAY), eq(HORIZON));
        TideFetchEntity row = recordedRow();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.SEED);
        assertThat(row.getOverlapPairs()).isNull();
        assertThat(row.getMaxLagDeltaMin()).isNull();
        assertThat(row.getMaxHeightDeltaM()).isNull();
    }

    @Test
    @DisplayName("a POINT_CHANGE re-seed makes no comparison: a difference is expected")
    void pointChangeHasNoDiff() {
        TideFetchEntity movedFrom = previousRow(null, null, null, "FES2022", null, 54.51);
        movedFrom.setRequestLat(54.0);
        givenPreviousForwardRow(movedFrom);
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusHours(1)), 5.9, epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();

        verify(tideExtremeRepository, never()).findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(START_OF_DAY), eq(HORIZON));
        TideFetchEntity row = recordedRow();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.POINT_CHANGE);
        assertThat(row.getOverlapPairs()).isNull();
    }

    @Test
    @DisplayName("a RESEED compares across its whole window")
    void reseedDiffsTheWholeWindow() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(FRONTIER);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(START_OF_DAY.minusDays(120));
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(START_OF_DAY), eq(HORIZON)))
                .thenReturn(List.of(stored(TideExtremeType.HIGH, HIGH_AT, "5.600")));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.minusMinutes(50)), 5.6,
                epoch(LOW_AT), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNotNull();
        TideFetchEntity row = recordedRow();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.RESEED);
        assertThat(row.getOverlapPairs()).isEqualTo(1);
        assertThat(row.getMaxLagDeltaMin()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("a pair more than three hours apart is not paired")
    void farApartExtremesAreNotPaired() {
        givenTailWithStoredWeek();
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT.plusHours(4)), 5.6,
                epoch(LOW_AT.plusHours(4)), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();
        assertThat(recordedRow().getOverlapPairs()).isZero();
    }

    @Test
    @DisplayName("an atlas change against the previous forward fetch at the same point is reported")
    void atlasChangeIsReported() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2022", null, 54.51));
        respondWith(response("FES2024", null, 54.51, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNotNull();
        assertThat(change.sourceChange()).isEqualTo("atlas FES2022 -> FES2024");
        assertThat(change.timingExamples()).isEmpty();
    }

    @Test
    @DisplayName("a station change and a response-point change are both reported")
    void stationAndResponsePointChangesAreReported() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2022", "Whitby", 54.51));
        respondWith(response("FES2022", "Runswick", 54.6, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        TideSourceChange change = service.fetchAndStoreTideExtremes(location());

        assertThat(change).isNotNull();
        assertThat(change.sourceChange()).contains("station Whitby -> Runswick")
                .contains("response point (54.51, -0.79) -> (54.6, -0.79)");
    }

    @Test
    @DisplayName("an unchanged atlas, station and response point is not reported")
    void unchangedSourceIsQuiet() {
        givenTailWithStoredWeek();
        givenPreviousForwardRow(previousRow(2, 0.0, 0.0, "FES2024", "Whitby", 54.6));
        respondWith(response("FES2024", "Whitby", 54.6, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();
    }

    @Test
    @DisplayName("a previous row that recorded no source is not compared against")
    void unrecordedPreviousSourceIsNotCompared() {
        givenTailWithStoredWeek();
        TideFetchEntity noSource = previousRow(null, null, null, null, null, 0.0);
        noSource.setResponseLat(null);
        noSource.setResponseLng(null);
        givenPreviousForwardRow(noSource);
        respondWith(response("FES2024", "Whitby", 54.6, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();
    }

    @Test
    @DisplayName("a failing stored-extremes read does not fail the fetch: it merges, records no deltas")
    void diffFailureNeverFailsTheFetch() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(FRONTIER);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION_ID), eq(START_OF_DAY)))
                .thenReturn(START_OF_DAY.minusDays(7));
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                eq(LOCATION_ID), eq(TAIL_FROM), eq(HORIZON))).thenThrow(new IllegalStateException("db"));
        respondWith(response("FES2022", null, 54.51, epoch(HIGH_AT), 5.6, epoch(LOW_AT), 1.2));

        assertThat(service.fetchAndStoreTideExtremes(location())).isNull();

        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                eq(LOCATION_ID), eq(TAIL_FROM), eq(HORIZON));
        TideFetchEntity row = recordedRow();
        assertThat(row.isSuccess()).isTrue();
        assertThat(row.getOverlapPairs()).isNull();
    }
}
