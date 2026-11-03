package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideFetchEntity;
import com.gregochr.goldenhour.model.TideFetchResult;
import com.gregochr.goldenhour.model.TideSourceChange;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideFetchRepository;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins what {@link WorldTidesIngestionService} writes, when it reports the write to the
 * tide-stats eviction callback, and which window it fetches. The vendor call is stubbed; nothing
 * here touches WorldTides.
 *
 * <p>The callback is {@code TideService.evictTideStats}, which defers the actual cache drop until
 * the transaction completes ({@code TideServiceTest} pins that half). What this class pins is the
 * other half: the ingestion service calls it exactly once per write, with the location id, after
 * the delete and insert and never when nothing was written.
 */
@ExtendWith(MockitoExtension.class)
class WorldTidesIngestionServiceMergeTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2027-03-05T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime START_OF_DAY = LocalDate.of(2027, 3, 5).atStartOfDay();
    /** (90-day horizon + 7-day slack) of seconds after the start of day. */
    private static final LocalDateTime HORIZON = START_OF_DAY.plusSeconds(97L * 24 * 3600);
    private static final List<String> FORWARD_REASONS = List.of(TideFetchEntity.SEED,
            TideFetchEntity.RESEED, TideFetchEntity.TAIL, TideFetchEntity.POINT_CHANGE);
    private static final LocationEntity.TidePoint PIN =
            new LocationEntity.TidePoint(55.7702, -2.0054);

    @Mock private TideExtremeRepository tideExtremeRepository;
    @Mock private TideFetchRepository tideFetchRepository;
    @Mock private TideFetchRecorder tideFetchRecorder;
    @Mock private WorldTidesProperties worldTidesProperties;
    @Mock private JobRunService jobRunService;

    private final List<String> events = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger tideLogger;

    @BeforeEach
    void setUp() {
        tideLogger = (Logger) LoggerFactory.getLogger(TideService.class);
        logs = new ListAppender<>();
        logs.start();
        tideLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        tideLogger.detachAppender(logs);
    }

    private LocationEntity location() {
        return LocationEntity.builder().id(1L).name("Berwick-Upon-Tweed")
                .lat(PIN.lat()).lon(PIN.lng()).build();
    }

    private WorldTidesIngestionService service(RestClient client) {
        return new WorldTidesIngestionService(client, tideExtremeRepository, tideFetchRepository,
                tideFetchRecorder, worldTidesProperties, jobRunService,
                id -> events.add("evict:" + id), CLOCK);
    }

    private void recordRepositoryOrder() {
        doAnswer(inv -> {
            events.add("delete");
            return null;
        }).when(tideExtremeRepository)
                .deleteByLocationIdAndEventTimeBetween(1L, START_OF_DAY, HORIZON);
        recordSaveOrder();
    }

    private void recordSaveOrder() {
        doAnswer(inv -> {
            events.add("save");
            return inv.getArgument(0);
        }).when(tideExtremeRepository).saveAll(anyList());
    }

    private static WorldTidesResponse.Extreme extreme(String time, double height, String type) {
        WorldTidesResponse.Extreme e = new WorldTidesResponse.Extreme();
        e.setDt(Instant.parse(time).getEpochSecond());
        e.setHeight(height);
        e.setType(type);
        return e;
    }

    private static WorldTidesResponse response(WorldTidesResponse.Extreme... extremes) {
        WorldTidesResponse r = new WorldTidesResponse();
        r.setStatus(200);
        r.setExtremes(List.of(extremes));
        return r;
    }

    private static TideExtremeEntity stored(String time, double height, TideExtremeType type) {
        return TideExtremeEntity.builder().locationId(1L)
                .eventTime(LocalDateTime.ofInstant(Instant.parse(time), ZoneOffset.UTC))
                .heightMetres(BigDecimal.valueOf(height).setScale(3)).type(type)
                .fetchedAt(START_OF_DAY.minusDays(1)).build();
    }

    private RestClient clientReturning(WorldTidesResponse response) {
        RestClient client = mock(RestClient.class);
        RestClientMocks.stubGet(client, WorldTidesResponse.class, response);
        return client;
    }

    private TideFetchEntity recordedRow() {
        ArgumentCaptor<TideFetchEntity> captor = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder).record(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------------------------
    // A seed fetch: what is stored, in what order, and what is reported
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a seed fetch deletes the window, saves the mapped extremes, then reports the write once")
    void seed_deletesThenSavesThenEvictsOnce() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        recordRepositoryOrder();
        WorldTidesResponse response = response(
                extreme("2027-03-05T06:12:00Z", 4.12345, "High"),
                extreme("2027-03-05T12:20:00Z", 0.4455, "low"),
                extreme("2027-03-05T18:30:00Z", 3.9, null),
                extreme("2027-03-05T19:00:00Z", 3.9, "Other"));

        TideFetchResult result = service(clientReturning(response))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(events).containsExactly("delete", "save", "evict:1");
        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(1L, START_OF_DAY, HORIZON);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TideExtremeEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(tideExtremeRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        TideExtremeEntity high = saved.getValue().get(0);
        assertThat(high.getLocationId()).isEqualTo(1L);
        assertThat(high.getEventTime()).isEqualTo(LocalDateTime.of(2027, 3, 5, 6, 12));
        assertThat(high.getHeightMetres()).isEqualByComparingTo("4.123");
        assertThat(high.getHeightMetres().scale()).isEqualTo(3);
        assertThat(high.getType()).isEqualTo(TideExtremeType.HIGH);
        assertThat(high.getFetchedAt()).isEqualTo(LocalDateTime.of(2027, 3, 5, 9, 0));
        TideExtremeEntity low = saved.getValue().get(1);
        assertThat(low.getEventTime()).isEqualTo(LocalDateTime.of(2027, 3, 5, 12, 20));
        assertThat(low.getHeightMetres()).isEqualByComparingTo("0.446");
        assertThat(low.getType()).isEqualTo(TideExtremeType.LOW);
        assertThat(result).isEqualTo(TideFetchResult.stored(null));
    }

    @Test
    @DisplayName("an empty answer still deletes the window, so it still reports the write")
    void emptyAnswer_stillDeletesAndEvicts() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        recordRepositoryOrder();

        TideFetchResult result = service(clientReturning(response()))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(events).containsExactly("delete", "save", "evict:1");
        assertThat(result.status()).isEqualTo(TideFetchResult.Status.STORED);
        assertThat(recordedRow().getExtremesStored()).isZero();
    }

    @Test
    @DisplayName("the provenance row records reason, window, request and response point, source and count")
    void seed_recordsProvenance() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        WorldTidesResponse response = response(extreme("2027-03-05T06:12:00Z", 4.1, "High"),
                extreme("2027-03-05T12:20:00Z", 0.4, "Low"));
        response.setCallCount(3);
        response.setAtlas("TPXO");
        response.setStation("Tweed");
        response.setResponseLat(55.8);
        response.setResponseLon(-2.0);
        response.setResponseDatum("MSL");

        service(clientReturning(response)).fetchAndStoreTideExtremesWithOutcome(location(), null);

        TideFetchEntity row = recordedRow();
        assertThat(row.getLocationId()).isEqualTo(1L);
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.SEED);
        assertThat(row.isSuccess()).isTrue();
        assertThat(row.getFetchedAt()).isEqualTo(LocalDateTime.of(2027, 3, 5, 9, 0));
        assertThat(row.getWindowFrom()).isEqualTo(START_OF_DAY);
        assertThat(row.getWindowTo()).isEqualTo(HORIZON);
        assertThat(row.getRequestLat()).isEqualTo(55.7702);
        assertThat(row.getRequestLng()).isEqualTo(-2.0054);
        assertThat(row.getResponseLat()).isEqualTo(55.8);
        assertThat(row.getResponseLng()).isEqualTo(-2.0);
        assertThat(row.getAtlas()).isEqualTo("TPXO");
        assertThat(row.getStation()).isEqualTo("Tweed");
        assertThat(row.getResponseDatum()).isEqualTo("MSL");
        assertThat(row.getCallCount()).isEqualTo(3);
        assertThat(row.getExtremesStored()).isEqualTo(2);
        assertThat(row.getOverlapPairs()).isNull();
        assertThat(row.getMaxLagDeltaMin()).isNull();
    }

    @Test
    @DisplayName("with a job run the successful call is metered with the vendor's credits and a masked key")
    void seed_metersTheCallWithCreditsAndMaskedKey() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        WorldTidesResponse response = response(extreme("2027-03-05T06:12:00Z", 4.1, "High"));
        response.setCallCount(3);

        service(clientReturning(response)).fetchAndStoreTideExtremesWithOutcome(
                location(), JobRunEntity.builder().id(9L).build());

        long startEpoch = START_OF_DAY.toEpochSecond(ZoneOffset.UTC);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(jobRunService).logMeteredApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                url.capture(), anyLong(), eq(200), eq(true), isNull(), eq(3));
        assertThat(url.getValue()).isEqualTo("https://www.worldtides.info/api/v3?extremes&lat=55.7702"
                + "&lon=-2.0054&start=" + startEpoch + "&length=8380800&key=test***");
        verify(jobRunService, never()).logApiCall(anyLong(), any(), any(), any(), any(), anyLong(),
                any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("without a job run nothing is metered")
    void seed_noJobRun_noMetering() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");

        service(clientReturning(response())).fetchAndStoreTideExtremesWithOutcome(location(), null);

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("a short API key is masked entirely in the metered URL")
    void shortKeyIsFullyMasked() {
        when(worldTidesProperties.getApiKey()).thenReturn("abcd");

        service(clientReturning(response())).fetchAndStoreTideExtremesWithOutcome(
                location(), JobRunEntity.builder().id(9L).build());

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(jobRunService).logMeteredApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                url.capture(), anyLong(), eq(200), eq(true), isNull(), isNull());
        assertThat(url.getValue()).endsWith("&key=***").doesNotContain("abcd");
    }

    // -------------------------------------------------------------------------
    // Failures write nothing and report nothing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an answer with no extremes list deletes nothing, saves nothing, evicts nothing")
    void unusableAnswer_writesNothing() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        WorldTidesResponse noExtremes = new WorldTidesResponse();
        noExtremes.setStatus(200);

        TideFetchResult result = service(clientReturning(noExtremes))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.failed());
        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                anyLong(), any(), any());
        verify(tideExtremeRepository, never()).saveAll(anyList());
        assertThat(events).isEmpty();
        TideFetchEntity row = recordedRow();
        assertThat(row.isSuccess()).isFalse();
        assertThat(row.getExtremesStored()).isZero();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.SEED);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().equals(
                        "WorldTides returned no usable data for Berwick-Upon-Tweed (status=200)"));
    }

    @Test
    @DisplayName("a null answer is reported as status=null and records a failed fetch")
    void nullAnswer_isFailed() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");

        TideFetchResult result = service(clientReturning(null))
                .fetchAndStoreTideExtremesWithOutcome(location(),
                        JobRunEntity.builder().id(9L).build());

        assertThat(result).isEqualTo(TideFetchResult.failed());
        verify(jobRunService).logMeteredApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                any(), anyLong(), isNull(), eq(false), eq("WorldTides returned status=null"),
                isNull());
        assertThat(recordedRow().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a save that throws leaves no eviction and no success row, and the fetch is FAILED")
    void saveThrows_noEvictionNoSuccessRow() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.saveAll(anyList())).thenThrow(new IllegalStateException("db down"));

        TideFetchResult result = service(clientReturning(
                response(extreme("2027-03-05T06:12:00Z", 4.1, "High"))))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.failed());
        assertThat(events).isEmpty();
        TideFetchEntity row = recordedRow();
        assertThat(row.isSuccess()).isFalse();
        assertThat(row.getExtremesStored()).isZero();
        assertThat(row.getResponseLat()).isNull();
    }

    @Test
    @DisplayName("a missing API key touches no repository and reports nothing")
    void noKey_touchesNothing() {
        when(worldTidesProperties.getApiKey()).thenReturn(null);

        TideFetchResult result = service(mock(RestClient.class))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.notConfigured());
        verifyNoInteractions(tideExtremeRepository, tideFetchRepository, tideFetchRecorder);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("coverage already at the horizon makes no write and no eviction")
    void alreadyCovered_noWrite() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY)).thenReturn(HORIZON);
        when(tideExtremeRepository.findOldestFetchedAtFrom(1L, START_OF_DAY))
                .thenReturn(START_OF_DAY.minusDays(1));

        TideFetchResult result = service(mock(RestClient.class))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.unchanged());
        verify(tideExtremeRepository, never()).saveAll(anyList());
        verifyNoInteractions(tideFetchRecorder);
        assertThat(events).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Best-effort side channels never break the merge
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a recorder that throws does not fail the fetch")
    void recorderThrows_fetchStillStored() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        doThrow(new IllegalStateException("recorder down")).when(tideFetchRecorder).record(any());

        TideFetchResult result = service(clientReturning(response()))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result.status()).isEqualTo(TideFetchResult.Status.STORED);
        assertThat(events).containsExactly("evict:1");
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith(
                        "Could not record the SEED WorldTides fetch of Berwick-Upon-Tweed"));
    }

    @Test
    @DisplayName("an unreadable previous fetch only disables source-change detection")
    void previousFetchUnreadable_fetchStillStored() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        // First read is the window decision, second is the previous-fetch read after the call.
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(1L), eq(FORWARD_REASONS))).thenReturn(Optional.empty()).thenThrow(new IllegalStateException("x"));

        TideFetchResult result = service(clientReturning(response()))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.stored(null));
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().startsWith(
                "Could not read the previous WorldTides fetch of Berwick-Upon-Tweed"));
    }

    @Test
    @DisplayName("same-kind neighbours after the merge are logged as an integrity warning")
    void integrityWarning_forConsecutiveSameKind() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                1L, START_OF_DAY.minusDays(1), HORIZON)).thenReturn(List.of(
                        stored("2027-03-05T06:00:00Z", 4.0, TideExtremeType.HIGH),
                        stored("2027-03-05T18:00:00Z", 4.1, TideExtremeType.HIGH)));

        TideFetchResult result = service(clientReturning(response()))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result.status()).isEqualTo(TideFetchResult.Status.STORED);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("Tide integrity: consecutive HIGH extremes at "
                        + "2027-03-05T06:00 and 2027-03-05T18:00 for Berwick-Upon-Tweed"));
    }

    @Test
    @DisplayName("a failing integrity read-back is not reported as an API failure")
    void integrityReadBackThrows_callStillMeteredAsSuccess() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                1L, START_OF_DAY.minusDays(1), HORIZON)).thenThrow(new IllegalStateException("x"));

        TideFetchResult result = service(clientReturning(response()))
                .fetchAndStoreTideExtremesWithOutcome(location(),
                        JobRunEntity.builder().id(9L).build());

        assertThat(result.status()).isEqualTo(TideFetchResult.Status.STORED);
        verify(jobRunService).logMeteredApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                any(), anyLong(), eq(200), eq(true), isNull(), isNull());
        assertThat(recordedRow().isSuccess()).isTrue();
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().startsWith(
                "Tide integrity check failed for Berwick-Upon-Tweed"));
    }

    // -------------------------------------------------------------------------
    // A tail fetch: window, diff-on-write and source-change alerting
    // -------------------------------------------------------------------------

    private TideFetchEntity.TideFetchEntityBuilder previousFetch() {
        return TideFetchEntity.builder().locationId(1L).success(true)
                .reason(TideFetchEntity.TAIL).requestLat(PIN.lat()).requestLng(PIN.lng())
                .atlas("TPXO").station("Tweed").responseLat(55.8).responseLng(-2.0);
    }

    /** Stubs a tail fetch whose frontier is 20 days out, with one stored high and its replacement. */
    private TideFetchResult tailFetch(TideFetchEntity previous, String atlas, double newHeight) {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(1L), eq(FORWARD_REASONS))).thenReturn(Optional.of(previous));
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY))
                .thenReturn(START_OF_DAY.plusDays(20));
        when(tideExtremeRepository.findOldestFetchedAtFrom(1L, START_OF_DAY))
                .thenReturn(START_OF_DAY.minusDays(1));
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                1L, START_OF_DAY.plusDays(13), HORIZON)).thenReturn(List.of(
                        stored("2027-03-20T06:00:00Z", 4.0, TideExtremeType.HIGH)));
        WorldTidesResponse response = response(extreme("2027-03-20T06:20:00Z", newHeight, "High"));
        response.setAtlas(atlas);
        response.setStation("Tweed");
        response.setResponseLat(55.8);
        response.setResponseLon(-2.0);
        return service(clientReturning(response))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);
    }

    @Test
    @DisplayName("a tail fetch starts a week before the frontier, deletes only that span, and records the diff")
    void tail_windowDeleteAndDiff() {
        TideFetchResult result = tailFetch(previousFetch().build(), "TPXO", 4.2);

        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                1L, START_OF_DAY.plusDays(13), HORIZON);
        TideFetchEntity row = recordedRow();
        assertThat(row.getReason()).isEqualTo(TideFetchEntity.TAIL);
        assertThat(row.getWindowFrom()).isEqualTo(START_OF_DAY.plusDays(13));
        assertThat(row.getWindowTo()).isEqualTo(HORIZON);
        assertThat(row.getOverlapPairs()).isEqualTo(1);
        assertThat(row.getMaxLagDeltaMin()).isEqualTo(20.0);
        assertThat(row.getMaxHeightDeltaM()).isCloseTo(0.2, within(1e-9));
        assertThat(result.change()).isEqualTo(new TideSourceChange("Berwick-Upon-Tweed", null, List.of(
                "HIGH 2027-03-20T06:00 UTC -> 2027-03-20T06:20 UTC (+20 min), 4.000 m -> 4.200 m")));
    }

    @Test
    @DisplayName("a changed atlas is reported with both names, alongside the timing example")
    void tail_atlasChangeIsDescribed() {
        TideFetchResult result = tailFetch(previousFetch().build(), "FES", 4.2);

        assertThat(result.change().sourceChange()).isEqualTo("atlas TPXO -> FES");
        assertThat(result.change().timingExamples()).hasSize(1);
    }

    @Test
    @DisplayName("a persisting timing difference is not repeated while the previous fetch showed it")
    void tail_previousAlreadyShowedChange_suppressesTimingAlert() {
        TideFetchResult result = tailFetch(
                previousFetch().maxLagDeltaMin(12.0).maxHeightDeltaM(0.0).build(), "TPXO", 4.2);

        assertThat(result.change()).isNull();
    }

    @Test
    @DisplayName("a previous fetch that showed no change does not suppress a new timing alert")
    void tail_previousShowedNoChange_timingAlertFires() {
        TideFetchResult result = tailFetch(
                previousFetch().maxLagDeltaMin(1.0).maxHeightDeltaM(0.01).build(), "TPXO", 4.2);

        assertThat(result.change()).isNotNull();
        assertThat(result.change().sourceChange()).isNull();
    }

    @Test
    @DisplayName("a new station and a moved response point are both described, joined by a semicolon")
    void tail_stationAndResponsePointChangeDescribed() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(1L), eq(FORWARD_REASONS))).thenReturn(Optional.of(previousFetch().build()));
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY))
                .thenReturn(START_OF_DAY.plusDays(20));
        when(tideExtremeRepository.findOldestFetchedAtFrom(1L, START_OF_DAY))
                .thenReturn(START_OF_DAY.minusDays(1));
        WorldTidesResponse response = response();
        response.setAtlas("TPXO");
        response.setStation("Holy Island");
        response.setResponseLat(55.9);
        response.setResponseLon(-2.1);

        TideFetchResult result = service(clientReturning(response))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result.change().sourceChange()).isEqualTo("station Tweed -> Holy Island; "
                + "response point (55.8, -2.0) -> (55.9, -2.1)");
    }

    // -------------------------------------------------------------------------
    // resolveFetchWindow boundaries
    // -------------------------------------------------------------------------

    private WorldTidesIngestionService.FetchWindow window(LocationEntity.TidePoint point,
            LocalDateTime frontier, LocalDateTime oldestFetch) {
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY)).thenReturn(frontier);
        if (frontier != null) {
            when(tideExtremeRepository.findOldestFetchedAtFrom(1L, START_OF_DAY))
                    .thenReturn(oldestFetch);
        }
        return service(mock(RestClient.class)).resolveFetchWindow(1L, PIN, point, START_OF_DAY, HORIZON);
    }

    @Test
    @DisplayName("no forward data seeds the whole window")
    void window_noFrontier_seeds() {
        WorldTidesIngestionService.FetchWindow w = window(PIN, null, null);

        assertThat(w.code()).isEqualTo(TideFetchEntity.SEED);
        assertThat(w.from()).isEqualTo(START_OF_DAY);
        assertThat(w.to()).isEqualTo(HORIZON);
        assertThat(w.lengthSeconds()).isEqualTo(97L * 24 * 3600);
    }

    @Test
    @DisplayName("data fetched exactly 90 days ago is still fresh; one second older re-seeds")
    void window_reseedBoundary() {
        LocalDateTime frontier = START_OF_DAY.plusDays(30);
        WorldTidesIngestionService.FetchWindow fresh =
                window(PIN, frontier, START_OF_DAY.minusDays(90));

        assertThat(fresh.code()).isEqualTo(TideFetchEntity.TAIL);
        assertThat(fresh.from()).isEqualTo(START_OF_DAY.plusDays(23));
    }

    @Test
    @DisplayName("data fetched 90 days and a second ago re-seeds the whole window")
    void window_reseedJustPastBoundary() {
        WorldTidesIngestionService.FetchWindow w = window(PIN, START_OF_DAY.plusDays(30),
                START_OF_DAY.minusDays(90).minusSeconds(1));

        assertThat(w.code()).isEqualTo(TideFetchEntity.RESEED);
        assertThat(w.from()).isEqualTo(START_OF_DAY);
        assertThat(w.to()).isEqualTo(HORIZON);
    }

    @Test
    @DisplayName("unknown fetch age means re-seed, not skip")
    void window_unknownAgeReseeds() {
        WorldTidesIngestionService.FetchWindow w = window(PIN, START_OF_DAY.plusDays(30), null);

        assertThat(w.code()).isEqualTo(TideFetchEntity.RESEED);
    }

    @Test
    @DisplayName("a frontier at the horizon needs no fetch; one second short of it does")
    void window_horizonBoundary() {
        LocalDateTime recent = START_OF_DAY.minusDays(1);

        assertThat(window(PIN, HORIZON, recent)).isNull();
        WorldTidesIngestionService.FetchWindow w = window(PIN, HORIZON.minusSeconds(1), recent);

        assertThat(w.code()).isEqualTo(TideFetchEntity.TAIL);
        assertThat(w.from()).isEqualTo(HORIZON.minusSeconds(1).minusDays(7));
    }

    @Test
    @DisplayName("the tail never starts before today: a frontier a week out starts exactly at midnight")
    void window_tailClampedToStartOfDay() {
        LocalDateTime recent = START_OF_DAY.minusDays(1);

        assertThat(window(PIN, START_OF_DAY.plusDays(7), recent).from()).isEqualTo(START_OF_DAY);
        assertThat(window(PIN, START_OF_DAY.plusDays(3), recent).from()).isEqualTo(START_OF_DAY);
        assertThat(window(PIN, START_OF_DAY.plusDays(7).plusHours(1), recent).from())
                .isEqualTo(START_OF_DAY.plusHours(1));
    }

    @Test
    @DisplayName("a tide point moved by more than a micro-degree re-seeds, whatever else is true")
    void window_pointChangeBeyondTolerance() {
        WorldTidesIngestionService svc = service(mock(RestClient.class));

        WorldTidesIngestionService.FetchWindow lat = svc.resolveFetchWindow(1L, PIN,
                new LocationEntity.TidePoint(PIN.lat() + 2e-6, PIN.lng()), START_OF_DAY, HORIZON);
        WorldTidesIngestionService.FetchWindow lng = svc.resolveFetchWindow(1L, PIN,
                new LocationEntity.TidePoint(PIN.lat(), PIN.lng() - 2e-6), START_OF_DAY, HORIZON);

        assertThat(lat.code()).isEqualTo(TideFetchEntity.POINT_CHANGE);
        assertThat(lng.code()).isEqualTo(TideFetchEntity.POINT_CHANGE);
        assertThat(lat.from()).isEqualTo(START_OF_DAY);
        assertThat(lat.to()).isEqualTo(HORIZON);
        verify(tideExtremeRepository, never()).findLatestEventTimeFrom(anyLong(), any());
    }

    @Test
    @DisplayName("a point moved by less than the tolerance is the same point")
    void window_pointWithinTolerance() {
        WorldTidesIngestionService.FetchWindow w = window(
                new LocationEntity.TidePoint(PIN.lat() + 5e-7, PIN.lng() - 5e-7), null, null);

        assertThat(w.code()).isEqualTo(TideFetchEntity.SEED);
    }

    @Test
    @DisplayName("the last forward fetch's request point, not the pin, is what the current point is compared with")
    void window_comparesAgainstLastForwardRequest() {
        LocationEntity.TidePoint moved = new LocationEntity.TidePoint(55.9, -2.1);
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(1L), eq(FORWARD_REASONS))).thenReturn(Optional.of(
                        previousFetch().requestLat(55.9).requestLng(-2.1).build()));
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY)).thenReturn(null);

        WorldTidesIngestionService svc = service(mock(RestClient.class));

        assertThat(svc.resolveFetchWindow(1L, PIN, moved, START_OF_DAY, HORIZON).code())
                .isEqualTo(TideFetchEntity.SEED);
        assertThat(svc.resolveFetchWindow(1L, PIN, PIN, START_OF_DAY, HORIZON).code())
                .isEqualTo(TideFetchEntity.POINT_CHANGE);
    }

    @Test
    @DisplayName("a forward fetch row with no recorded request point is taken to have used the pin")
    void window_rowWithoutRequestPointFallsBackToPin() {
        when(tideFetchRepository.findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
                eq(1L), eq(FORWARD_REASONS))).thenReturn(Optional.of(
                        previousFetch().requestLat(null).requestLng(null).build()));
        when(tideExtremeRepository.findLatestEventTimeFrom(1L, START_OF_DAY)).thenReturn(null);

        assertThat(service(mock(RestClient.class))
                .resolveFetchWindow(1L, PIN, PIN, START_OF_DAY, HORIZON).code())
                .isEqualTo(TideFetchEntity.SEED);
    }

    /** True for the first instant of a 7-day backfill chunk: 2026-03-05 plus a whole number of weeks. */
    private static boolean isBackfillChunkStart(LocalDateTime t) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.of(2026, 3, 5, 0, 0), t);
        return t.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) && days >= 0 && days < 365
                && days % 7 == 0;
    }

    /** No stored data in any chunk of location 1's 12-month backfill; other ids or windows get null. */
    private void stubNoDataInAnyBackfillChunk() {
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(eq(1L),
                org.mockito.ArgumentMatchers.argThat(WorldTidesIngestionServiceMergeTest::isBackfillChunkStart),
                org.mockito.ArgumentMatchers.argThat(
                        to -> isBackfillChunkStart(to.minusDays(7)))))
                .thenReturn(false);
    }

    // -------------------------------------------------------------------------
    // Backfill
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backfill walks 12 months in 7-day chunks, skips chunks with data, and reports each write")
    void backfill_chunksSkipsAndEvicts() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        recordSaveOrder();
        stubNoDataInAnyBackfillChunk();
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                1L, LocalDateTime.of(2026, 3, 12, 0, 0), LocalDateTime.of(2026, 3, 19, 0, 0)))
                .thenReturn(true);
        WorldTidesResponse response = response(extreme("2026-06-01T06:00:00Z", 4.1, "High"));
        JobRunEntity jobRun = JobRunEntity.builder().id(9L).build();

        int fetched = service(clientReturning(response)).backfillTideExtremes(location(), jobRun);

        // 2026-03-05 + 7k for k = 0..52 is 53 chunks; one of them already has data.
        assertThat(fetched).isEqualTo(52);
        verify(tideExtremeRepository, times(52)).saveAll(anyList());
        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                anyLong(), any(), any());
        assertThat(events).hasSize(104);
        assertThat(events.subList(0, 4)).containsExactly("save", "evict:1", "save", "evict:1");
        verify(tideExtremeRepository).existsByLocationIdAndEventTimeBetween(
                1L, LocalDateTime.of(2026, 3, 5, 0, 0), LocalDateTime.of(2026, 3, 12, 0, 0));
        verify(tideExtremeRepository).existsByLocationIdAndEventTimeBetween(
                1L, LocalDateTime.of(2027, 3, 4, 0, 0), LocalDateTime.of(2027, 3, 11, 0, 0));
        verify(tideExtremeRepository, never()).existsByLocationIdAndEventTimeBetween(
                1L, LocalDateTime.of(2027, 3, 11, 0, 0), LocalDateTime.of(2027, 3, 18, 0, 0));

        ArgumentCaptor<TideFetchEntity> rows = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder, times(52)).record(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(r -> {
            assertThat(r.getReason()).isEqualTo(TideFetchEntity.BACKFILL);
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getExtremesStored()).isEqualTo(1);
        });
        assertThat(rows.getAllValues().get(0).getWindowFrom())
                .isEqualTo(LocalDateTime.of(2026, 3, 5, 0, 0));
        assertThat(rows.getAllValues().get(0).getWindowTo())
                .isEqualTo(LocalDateTime.of(2026, 3, 12, 0, 0));
        assertThat(rows.getAllValues().get(1).getWindowFrom())
                .isEqualTo(LocalDateTime.of(2026, 3, 19, 0, 0));

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(jobRunService, times(52)).logApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                urls.capture(), isNull(), anyLong(), eq(200), isNull(), eq(true), isNull());
        assertThat(urls.getAllValues().get(0)).isEqualTo(
                "https://www.worldtides.info/api/v3?extremes&date=2026-03-05&lat=55.7702"
                        + "&lon=-2.0054&days=7&key=test***");
    }

    @Test
    @DisplayName("a failed backfill chunk is recorded as failed, stores nothing and evicts nothing")
    void backfill_failedChunkWritesNothing() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        stubNoDataInAnyBackfillChunk();
        WorldTidesResponse bad = new WorldTidesResponse();
        bad.setStatus(500);

        int fetched = service(clientReturning(bad)).backfillTideExtremes(location(), null);

        assertThat(fetched).isZero();
        verify(tideExtremeRepository, never()).saveAll(anyList());
        assertThat(events).isEmpty();
        ArgumentCaptor<TideFetchEntity> rows = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder, times(53)).record(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getReason()).isEqualTo(TideFetchEntity.BACKFILL);
        });
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage()
                .equals("Backfill failed for Berwick-Upon-Tweed at 2026-03-05 — status=500"));
    }

    @Test
    @DisplayName("a backfill with no API key does nothing")
    void backfill_noKey() {
        when(worldTidesProperties.getApiKey()).thenReturn("");

        assertThat(service(mock(RestClient.class)).backfillTideExtremes(location(), null)).isZero();

        verifyNoInteractions(tideExtremeRepository, tideFetchRecorder);
    }

    @Test
    @DisplayName("a non-200 status is a failure even when an extremes list came with it")
    void nonOkStatusWithExtremes_writesNothing() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        WorldTidesResponse bad = response(extreme("2027-03-05T06:12:00Z", 4.1, "High"));
        bad.setStatus(500);

        TideFetchResult result = service(clientReturning(bad))
                .fetchAndStoreTideExtremesWithOutcome(location(), null);

        assertThat(result).isEqualTo(TideFetchResult.failed());
        verify(tideExtremeRepository, never()).saveAll(anyList());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("backfill stores only High and Low extremes, rounded to three places, stamped with now")
    void backfill_mapsExtremes() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        recordSaveOrder();
        WorldTidesResponse response = response(
                extreme("2026-06-01T06:00:00Z", 4.12345, "High"),
                extreme("2026-06-01T12:10:00Z", 0.4455, "LOW"),
                extreme("2026-06-01T15:00:00Z", 2.0, null),
                extreme("2026-06-01T16:00:00Z", 2.0, "Other"));

        service(clientReturning(response)).backfillTideExtremes(location(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TideExtremeEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(tideExtremeRepository, times(53)).saveAll(saved.capture());
        List<TideExtremeEntity> first = saved.getAllValues().get(0);
        assertThat(first).hasSize(2);
        assertThat(first.get(0).getLocationId()).isEqualTo(1L);
        assertThat(first.get(0).getEventTime()).isEqualTo(LocalDateTime.of(2026, 6, 1, 6, 0));
        assertThat(first.get(0).getHeightMetres()).isEqualByComparingTo("4.123");
        assertThat(first.get(0).getHeightMetres().scale()).isEqualTo(3);
        assertThat(first.get(0).getType()).isEqualTo(TideExtremeType.HIGH);
        assertThat(first.get(0).getFetchedAt()).isEqualTo(LocalDateTime.of(2027, 3, 5, 9, 0));
        assertThat(first.get(1).getHeightMetres()).isEqualByComparingTo("0.446");
        assertThat(first.get(1).getType()).isEqualTo(TideExtremeType.LOW);
    }

    @Test
    @DisplayName("a backfill chunk answered with a null body or no extremes list is recorded as failed")
    void backfill_nullOrEmptyBodyIsFailure() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        stubNoDataInAnyBackfillChunk();
        WorldTidesResponse noExtremes = new WorldTidesResponse();
        noExtremes.setStatus(200);
        JobRunEntity jobRun = JobRunEntity.builder().id(9L).build();

        assertThat(service(clientReturning(noExtremes)).backfillTideExtremes(location(), jobRun)).isZero();

        verify(jobRunService, times(53)).logApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                any(), isNull(), anyLong(), eq(200), isNull(), eq(false),
                eq("Non-200 status on backfill"));
        verify(tideExtremeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("a backfill chunk whose call throws is recorded as failed with no response fields")
    void backfill_exceptionRecordsFailedRow() {
        when(worldTidesProperties.getApiKey()).thenReturn("abcd");
        stubNoDataInAnyBackfillChunk();
        RestClient client = mock(RestClient.class);
        RestClientMocks.stubGetThrows(client, WorldTidesResponse.class, new IllegalStateException("down"));

        assertThat(service(client).backfillTideExtremes(location(),
                JobRunEntity.builder().id(9L).build())).isZero();

        ArgumentCaptor<TideFetchEntity> rows = ArgumentCaptor.forClass(TideFetchEntity.class);
        verify(tideFetchRecorder, times(53)).record(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getReason()).isEqualTo(TideFetchEntity.BACKFILL);
            assertThat(r.getResponseLat()).isNull();
            assertThat(r.getAtlas()).isNull();
            assertThat(r.getCallCount()).isNull();
        });
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(jobRunService, times(53)).logApiCall(eq(9L), eq(ServiceName.WORLD_TIDES), eq("GET"),
                urls.capture(), isNull(), anyLong(), isNull(), isNull(), eq(false), eq("down"));
        assertThat(urls.getAllValues()).allSatisfy(u -> assertThat(u).endsWith("&key=***"));
    }

    @Test
    @DisplayName("a null chunk body is recorded as a failed fetch with no status")
    void backfill_nullBody() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        stubNoDataInAnyBackfillChunk();

        assertThat(service(clientReturning(null)).backfillTideExtremes(location(), null)).isZero();

        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage()
                .equals("Backfill failed for Berwick-Upon-Tweed at 2026-03-05 — status=null"));
    }
}
