package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests that {@link WorldTidesIngestionService} asks WorldTides about the effective tide point,
 * and records where every call's data came from (tide-source-integrity-plan P1a). Real HTTP
 * request building runs against {@link MockRestServiceServer}, so the query parameters actually
 * sent are asserted, not just the arguments handed to a mock.
 */
@ExtendWith(MockitoExtension.class)
class WorldTidesTidePointTest {

    private static final double PIN_LAT = 54.5;
    private static final double PIN_LON = -0.8;
    private static final double TIDE_LAT = 54.4;
    private static final double TIDE_LON = -0.7;

    private static final String FULL_RESPONSE = """
            {"status":200,"callCount":2,"atlas":"FES2022","station":null,
             "requestLat":54.4,"requestLon":-0.7,"responseLat":54.41,"responseLon":-0.69,
             "responseDatum":"LAT","unknownField":1,
             "extremes":[{"dt":1790000000,"height":1.5,"type":"High"},
                         {"dt":1790022000,"height":0.2,"type":"Low"}]}""";

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

    private static LocationEntity pinOnly() {
        return LocationEntity.builder().id(1L).name("Plain Bay").lat(PIN_LAT).lon(PIN_LON).build();
    }

    private static LocationEntity withTidePoint() {
        return LocationEntity.builder().id(2L).name("Borrowing Bay").lat(PIN_LAT).lon(PIN_LON)
                .tideLat(TIDE_LAT).tideLng(TIDE_LON).build();
    }

    private List<TideFetchEntity> savedRows() {
        ArgumentCaptor<TideFetchEntity> captor = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder, atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("forward fetch sends the tide point, not the pin")
    void forwardFetchSendsTheTidePoint() {
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andExpect(queryParam("lon", String.valueOf(TIDE_LON)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        server.verify();
    }

    @Test
    @DisplayName("forward fetch sends the pin when no tide point is set")
    void forwardFetchSendsThePinWithoutATidePoint() {
        server.expect(queryParam("lat", String.valueOf(PIN_LAT)))
                .andExpect(queryParam("lon", String.valueOf(PIN_LON)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(pinOnly());

        server.verify();
        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.getReason()).isEqualTo(TideFetchEntity.SEED));
    }

    @Test
    @DisplayName("backfill sends the tide point, not the pin, and records BACKFILL rows")
    void backfillSendsTheTidePoint() {
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(eq(2L), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        server.expect(ExpectedCount.manyTimes(), queryParam("lat", String.valueOf(TIDE_LAT)))
                .andExpect(queryParam("lon", String.valueOf(TIDE_LON)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        int chunks = service.backfillTideExtremes(withTidePoint(), null);

        assertThat(chunks).isPositive();
        List<TideFetchEntity> rows = savedRows();
        assertThat(rows).hasSize(chunks).allSatisfy(row -> {
            assertThat(row.getReason()).isEqualTo(TideFetchEntity.BACKFILL);
            assertThat(row.isSuccess()).isTrue();
            assertThat(row.getRequestLat()).isEqualTo(TIDE_LAT);
            assertThat(row.getRequestLng()).isEqualTo(TIDE_LON);
        });
    }

    @Test
    @DisplayName("a successful forward fetch writes one row carrying the response's provenance")
    void successRowCarriesTheResponseFields() {
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getLocationId()).isEqualTo(2L);
            assertThat(row.getReason()).isEqualTo(TideFetchEntity.POINT_CHANGE);
            assertThat(row.isSuccess()).isTrue();
            assertThat(row.getRequestLat()).isEqualTo(TIDE_LAT);
            assertThat(row.getRequestLng()).isEqualTo(TIDE_LON);
            assertThat(row.getResponseLat()).isEqualTo(54.41);
            assertThat(row.getResponseLng()).isEqualTo(-0.69);
            assertThat(row.getAtlas()).isEqualTo("FES2022");
            assertThat(row.getStation()).isNull();
            assertThat(row.getResponseDatum()).isEqualTo("LAT");
            assertThat(row.getCallCount()).isEqualTo(2);
            assertThat(row.getExtremesStored()).isEqualTo(2);
            assertThat(row.getWindowFrom()).isNotNull();
            assertThat(row.getWindowTo()).isAfter(row.getWindowFrom());
            assertThat(row.getFetchedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a latest forward row at the current tide point makes the next fetch a TAIL, not a re-seed")
    void unchangedPointRecordsATail() {
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(2L), eq(List.of("SEED", "RESEED", "TAIL", "POINT_CHANGE"))))
                .thenReturn(Optional.of(TideFetchEntity.builder().locationId(2L)
                .reason(TideFetchEntity.POINT_CHANGE).success(true)
                .requestLat(TIDE_LAT).requestLng(TIDE_LON).build()));
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(2L), any(LocalDateTime.class)))
                .thenReturn(LocalDateTime.now(ZoneOffset.UTC).plusDays(90));
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(2L), any(LocalDateTime.class)))
                .thenReturn(LocalDateTime.now(ZoneOffset.UTC).minusDays(7));
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.getReason()).isEqualTo(TideFetchEntity.TAIL));
    }

    @Test
    @DisplayName("an HTTP failure writes a success=false row and stores no extremes")
    void httpFailureWritesAFailureRow() {
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT))).andRespond(withServerError());

        service.fetchAndStoreTideExtremes(withTidePoint());

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.isSuccess()).isFalse();
            assertThat(row.getExtremesStored()).isZero();
            assertThat(row.getRequestLat()).isEqualTo(TIDE_LAT);
            assertThat(row.getAtlas()).isNull();
        });
        verify(tideExtremeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("a non-200 body status writes a success=false row")
    void nonOkStatusWritesAFailureRow() {
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andRespond(withSuccess("{\"status\":400,\"error\":\"bad\"}", MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.isSuccess()).isFalse());
    }

    @Test
    @DisplayName("when the merge throws, no success row is written, only a failure row")
    void mergeFailureLeavesNoSuccessRow() {
        doThrow(new IllegalStateException("db down")).when(tideExtremeRepository).saveAll(anyList());
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.isSuccess()).isFalse());
    }

    @Test
    @DisplayName("a recorder that throws is swallowed: the extremes are still stored, exactly as fetched")
    void provenanceFailureNeverFailsTheFetch() {
        doThrow(new IllegalStateException("tide_fetch down")).when(tideFetchRecorder)
                .record(any(TideFetchEntity.class));
        server.expect(queryParam("lat", String.valueOf(TIDE_LAT)))
                .andRespond(withSuccess(FULL_RESPONSE, MediaType.APPLICATION_JSON));

        service.fetchAndStoreTideExtremes(withTidePoint());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TideExtremeEntity>> stored = ArgumentCaptor.forClass(List.class);
        verify(tideExtremeRepository).saveAll(stored.capture());
        assertThat(stored.getValue()).extracting(TideExtremeEntity::getLocationId).containsOnly(2L);
        assertThat(stored.getValue()).extracting(TideExtremeEntity::getEventTime).containsExactly(
                LocalDateTime.ofEpochSecond(1790000000L, 0, ZoneOffset.UTC),
                LocalDateTime.ofEpochSecond(1790022000L, 0, ZoneOffset.UTC));
        assertThat(stored.getValue()).extracting(TideExtremeEntity::getType)
                .containsExactly(TideExtremeType.HIGH, TideExtremeType.LOW);
        assertThat(stored.getValue()).extracting(TideExtremeEntity::getHeightMetres)
                .containsExactly(new BigDecimal("1.500"), new BigDecimal("0.200"));
    }
}
