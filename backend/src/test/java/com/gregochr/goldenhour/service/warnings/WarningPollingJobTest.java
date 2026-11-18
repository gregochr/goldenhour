package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.client.MetOfficeWarningClient;
import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.SchedulerFireOutcome;
import com.gregochr.goldenhour.service.ServedBriefingEpoch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.warnings.WarningFeedParserTest.REAL_ISSUED_ID;
import static com.gregochr.goldenhour.service.warnings.WarningFeedParserTest.REAL_UPDATE_ID;
import static com.gregochr.goldenhour.service.warnings.WarningFeedParserTest.UUID_A;
import static com.gregochr.goldenhour.service.warnings.WarningFeedParserTest.UUID_B;
import static com.gregochr.goldenhour.service.warnings.WarningFeedParserTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarningPollingJob}: the real NSWWS v1.1 samples and fixtures derived from them, through a
 * mocked client (the live feed is never called).
 */
@ExtendWith(MockitoExtension.class)
class WarningPollingJobTest {

    private static final Instant NOW = Instant.parse("2031-03-10T06:00:00Z");
    private static final String SECOND_UPDATE_ID = "44444444-4444-4444-8444-444444444444";

    @Mock
    private MetOfficeWarningClient client;
    @Mock
    private WeatherWarningVersionRepository repository;
    @Mock
    private DynamicSchedulerService scheduler;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final WarningsProperties properties = new WarningsProperties();
    private final WarningPollStatus status = new WarningPollStatus();
    private final ServedBriefingEpoch epoch = new ServedBriefingEpoch();
    private WarningPollingJob job;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        job = new WarningPollingJob(client, new WarningFeedParser(), repository, properties, status, epoch,
                scheduler, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    private SchedulerFireOutcome.Report run() {
        return SchedulerFireOutcome.capture(job::poll);
    }

    /** A recent success with real rows stored: neither a cold start nor a gap, so no snapshot is wanted. */
    private void warmState() {
        status.record(new WarningPollStatus.Attempt(NOW.minus(Duration.ofMinutes(5)), true, null, 0, 0, 0, 0, 0));
        when(repository.existsBySimulatedFalse()).thenReturn(true);
    }

    private static HttpClientErrorException clientError(int code) {
        return HttpClientErrorException.create(HttpStatusCode.valueOf(code), "x", HttpHeaders.EMPTY, new byte[0],
                null);
    }

    private static HttpServerErrorException serverError(int code) {
        return HttpServerErrorException.create(HttpStatusCode.valueOf(code), "x", HttpHeaders.EMPTY, new byte[0],
                null);
    }

    private static String twoEntryFeed() {
        return fixture("feed-three-entries.xml");
    }

    private List<WeatherWarningVersionEntity> saved(int times) {
        ArgumentCaptor<WeatherWarningVersionEntity> captor = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── Registration and gating ─────────────────────────────────────────────────

    @Test
    @DisplayName("registers itself as the weather_warning_poll target, and that target polls")
    void registersTarget() {
        properties.setEnabled(false);
        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(org.mockito.ArgumentMatchers.eq("weather_warning_poll"),
                target.capture());
        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(target.getValue());
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
    }

    @Test
    @DisplayName("with the flag off the poll does nothing: no feed read, no storage")
    void flagOff() {
        properties.setEnabled(false);
        properties.setApiKey("test-key");

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        verifyNoInteractions(client, repository);
        assertThat(status.lastAttempt()).isNull();
    }

    @Test
    @DisplayName("with no API key the poll is skipped as NOT_CONFIGURED: no outbound call, no storage, no warning log")
    void notConfigured() {
        properties.setApiKey(" ");
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WarningPollingJob.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        SchedulerFireOutcome.Report report;
        try {
            report = run();
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        assertThat(report.summary()).contains("no API key");
        assertThat(status.lastAttempt().failureReason()).isEqualTo("NOT_CONFIGURED");
        assertThat(status.lastAttempt().succeeded()).isFalse();
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        verifyNoInteractions(client, repository);
    }

    @Test
    @DisplayName("registering logs one line for each of the three states and never fails")
    void registerLogsState() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WarningPollingJob.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            properties.setEnabled(false);
            job.registerJob();
            properties.setEnabled(true);
            properties.setApiKey("");
            job.registerJob();
            properties.setApiKey("test-key");
            job.registerJob();
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .hasSize(3)
                .satisfiesExactly(
                        m -> assertThat(m).contains("switched off"),
                        m -> assertThat(m).contains("no API key").contains("dormant"),
                        m -> assertThat(m).contains("polling is live"));
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
    }

    @Test
    @DisplayName("a null API key is also NOT_CONFIGURED")
    void nullKey() {
        properties.setApiKey(null);

        run();

        verifyNoInteractions(client, repository);
        assertThat(status.lastAttempt().failureReason()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a poll that starts while another is running is skipped, and the first still finishes")
    void overlapGuard() {
        when(client.getFeed()).thenAnswer(inv -> {
            SchedulerFireOutcome.Report inner = run();
            assertThat(inner.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
            assertThat(inner.summary()).contains("Already running");
            return "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>";
        });

        SchedulerFireOutcome.Report outer = run();

        assertThat(outer.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(run().status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
    }

    // ── Ingest ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cold start loads the issued snapshot and the update, stores every version with the poller's "
            + "clock, never simulated, and bumps the epoch once")
    void coldStartLoadsSnapshotAndUpdates() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenReturn(fixture("issued-snapshot.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-issued-v2.json"));
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        assertThat(saved(3)).extracting(r -> r.getWarningId() + "/" + r.getWarningVersion().toPlainString())
                .containsExactly(UUID_A + "/1.00", UUID_B + "/2.00", UUID_A + "/2.00");
        assertThat(saved(3)).allSatisfy(r -> {
            assertThat(r.getReceivedAt()).isEqualTo(NOW);
            assertThat(r.isSimulated()).isFalse();
        });
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("3 new warning versions stored");
        assertThat(status.lastAttempt().feedEntries()).isEqualTo(1);
        assertThat(status.lastAttempt().stored()).isEqualTo(3);
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a repeat poll does nothing: the entry was processed, no snapshot, nothing fetched or stored")
    void repeatPollIsANoOp() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenReturn(fixture("real/issued.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/updated-1.json"));
        run();
        when(repository.existsBySimulatedFalse()).thenReturn(true);
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        verify(client, times(1)).getUpdated(REAL_UPDATE_ID);
        verify(client, times(1)).getIssued(REAL_ISSUED_ID);
        verify(repository, times(1)).save(argThat(w -> true));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isZero();
        assertThat(epoch.current()).isEqualTo(before);
    }

    @Test
    @DisplayName("an entry that left the feed is forgotten, so a re-appearance is fetched again")
    void seenIdsArePrunedToTheFeed() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"))
                .thenReturn("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>")
                .thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        run();
        run();
        run();

        verify(client, times(2)).getUpdated(REAL_UPDATE_ID);
    }

    @Test
    @DisplayName("after a gap longer than the feed's 24 hour memory the snapshot is loaded again")
    void gapLoadsSnapshot() {
        status.record(new WarningPollStatus.Attempt(NOW.minus(Duration.ofHours(25)), true, null, 0, 0, 0, 0, 0));
        when(repository.existsBySimulatedFalse()).thenReturn(true);
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenReturn(fixture("real/issued.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        run();

        verify(client).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("a recent success with rows stored does not load the snapshot")
    void noSnapshotWhenWarm() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        run();

        verify(client, never()).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("a cold start whose feed names no snapshot skips it and still reads the updates")
    void noSnapshotLinkSkipsSnapshot() {
        when(client.getFeed()).thenReturn("<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>"
                + "<id>urn:uuid:" + REAL_UPDATE_ID + "</id></entry></feed>");
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        verify(client, never()).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("a version already stored is counted, not stored again; the check is numeric (3.0 equals 3.00)")
    void alreadyStored() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-cancelled.json"));
        when(repository.existsByWarningIdAndWarningVersion(UUID_A, new BigDecimal("3.00"))).thenReturn(true);
        long before = epoch.current();

        run();

        verify(repository, never()).save(argThat(w -> true));
        assertThat(status.lastAttempt().alreadyStored()).isEqualTo(1);
        assertThat(epoch.current()).isEqualTo(before);
    }

    @Test
    @DisplayName("a multi-warning document stores each feature, skips and counts the unreadable ones, and counts "
            + "unknown weather types")
    void multiFeatureDocument() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-multi.json"));

        SchedulerFireOutcome.Report report = run();

        assertThat(saved(3)).extracting(r -> r.getWarningVersion().toPlainString())
                .containsExactly("9.00", "10.00", "1.00");
        assertThat(report.summary()).contains("3 new warning versions stored").contains("3 unreadable");
        assertThat(status.lastAttempt().malformedSkipped()).isEqualTo(3);
        assertThat(status.unknownTypeCounts()).containsExactly(Map.entry("MYSTERY_WEATHER", 1));
    }

    @Test
    @DisplayName("a unique-key collision on insert (a racing writer) counts as already stored, not a failure")
    void uniqueViolationIsAlreadyStored() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-multi.json"));
        org.mockito.Mockito.doAnswer(inv -> {
            WeatherWarningVersionEntity row = inv.getArgument(0);
            if (row.getWarningVersion().compareTo(BigDecimal.TEN) == 0) {
                throw new DataIntegrityViolationException("dup");
            }
            return row;
        }).when(repository).save(argThat(w -> true));

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isEqualTo(2);
        assertThat(status.lastAttempt().alreadyStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("an update that has expired (404 or 410) is skipped, counted and not fetched again")
    void goneUpdateSkipped() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenThrow(clientError(404));
        when(client.getUpdated(SECOND_UPDATE_ID)).thenThrow(clientError(410));

        SchedulerFireOutcome.Report report = run();
        run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().goneSkipped()).isZero();
        verify(client, times(1)).getUpdated(REAL_UPDATE_ID);
        verify(client, times(1)).getUpdated(SECOND_UPDATE_ID);
        verify(repository, never()).save(argThat(w -> true));
    }

    @Test
    @DisplayName("gone updates are counted in the attempt and leave the epoch unmoved")
    void goneCounted() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenThrow(clientError(404));
        when(client.getUpdated(SECOND_UPDATE_ID)).thenThrow(clientError(410));
        long before = epoch.current();

        run();

        assertThat(status.lastAttempt().goneSkipped()).isEqualTo(2);
        assertThat(status.lastAttempt().malformedSkipped()).isEqualTo(1);
        assertThat(epoch.current()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unreadable document is skipped, counted and marked processed; the rest still stores")
    void malformedDocumentSkipped() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn("{not json");
        when(client.getUpdated(SECOND_UPDATE_ID)).thenReturn(fixture("updated-issued-v2.json"));

        SchedulerFireOutcome.Report report = run();
        run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isZero();
        verify(client, times(1)).getUpdated(REAL_UPDATE_ID);
        saved(1);
    }

    @Test
    @DisplayName("an update that answers 500 fails the poll as HTTP_500, other entries still store, and the failed "
            + "entry is retried next poll")
    void updateServerErrorRetried() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenThrow(serverError(500)).thenReturn(fixture("real/issued.json"));
        when(client.getUpdated(SECOND_UPDATE_ID)).thenReturn(fixture("updated-issued-v2.json"));
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(status.lastAttempt().failureReason()).isEqualTo("HTTP_500");
        assertThat(status.lastAttempt().stored()).isEqualTo(1);
        assertThat(epoch.current()).isEqualTo(before + 1);

        assertThat(run().status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        verify(client, times(2)).getUpdated(REAL_UPDATE_ID);
        verify(client, times(1)).getUpdated(SECOND_UPDATE_ID);
    }

    @Test
    @DisplayName("an unreachable update fails the poll as UNREACHABLE and the first reason stands")
    void updateUnreachableKeepsFirstReason() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenThrow(new ResourceAccessException("down",
                new IOException("refused")));
        when(client.getUpdated(SECOND_UPDATE_ID)).thenThrow(serverError(502));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("UNREACHABLE");
    }

    @Test
    @DisplayName("a 429 on an update stops the poll at once as RATE_LIMITED; later entries are not fetched")
    void updateRateLimitedStops() {
        warmState();
        when(client.getFeed()).thenReturn(twoEntryFeed());
        when(client.getUpdated(REAL_UPDATE_ID)).thenThrow(clientError(429));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("RATE_LIMITED");
        verify(client, never()).getUpdated(SECOND_UPDATE_ID);
        verify(repository, never()).save(argThat(w -> true));
    }

    @Test
    @DisplayName("a 401 on the snapshot stops the poll as UNAUTHORISED before any update is fetched")
    void snapshotUnauthorisedStops() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenThrow(clientError(401));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("UNAUTHORISED");
        verify(client, never()).getUpdated(REAL_UPDATE_ID);
    }

    @Test
    @DisplayName("a 500 on the snapshot fails the poll but the updates are still read")
    void snapshotServerErrorStillReadsUpdates() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenThrow(serverError(500));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-issued-v2.json"));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("HTTP_500");
        assertThat(status.lastAttempt().stored()).isEqualTo(1);
    }

    // ── Feed failures leave stored warnings untouched ───────────────────────────

    @Test
    @DisplayName("401 and 403 on the feed are UNAUTHORISED; 429 is RATE_LIMITED; other statuses are HTTP_n")
    void feedHttpFailures() {
        List<String> reasons = new ArrayList<>();
        for (int code : new int[] {401, 403, 429, 503}) {
            doThrow(code == 503 ? serverError(code) : clientError(code)).when(client).getFeed();
            run();
            reasons.add(status.lastAttempt().failureReason());
        }

        assertThat(reasons).containsExactly("UNAUTHORISED", "UNAUTHORISED", "RATE_LIMITED", "HTTP_503");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an unreachable feed is UNREACHABLE, is reported FAILED, and never records a success")
    void feedUnreachable() {
        when(client.getFeed()).thenThrow(new ResourceAccessException("down", new IOException("refused")));

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).contains("UNREACHABLE");
        assertThat(status.lastAttempt().succeeded()).isFalse();
        assertThat(status.lastSuccessAt()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a feed that is not Atom is MALFORMED_FEED and stores nothing")
    void feedMalformed() {
        when(client.getFeed()).thenReturn("<html>maintenance</html");

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("MALFORMED_FEED");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a 200 that is well-formed XML but not Atom is MALFORMED_FEED and does not advance lastSuccessAt")
    void nonAtomOkIsFailure() {
        when(client.getFeed()).thenReturn("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>")
                .thenReturn("<error>Service unavailable</error>");
        run();
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(status.lastAttempt().failureReason()).isEqualTo("MALFORMED_FEED");
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an empty feed is a successful poll that stores nothing and cancels nothing")
    void emptyFeedInfersNothing() {
        when(client.getFeed()).thenReturn("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>");

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("0 new warning versions");
        verify(repository, never()).save(argThat(w -> true));
    }

    @Test
    @DisplayName("a single stored version is worded in the singular")
    void singularSummary() {
        warmState();
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("updated-cancelled.json"));

        assertThat(run().summary()).startsWith("1 new warning version stored");
    }

    // ── A wanted snapshot stays pending until one is actually ingested ──────────

    @Test
    @DisplayName("a snapshot that answered 404 is retried next poll, even though the first poll stored real rows "
            + "and the poll counted as a success; once ingested it is not asked for again")
    void snapshotNotFoundIsRetried() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenThrow(clientError(404))
                .thenReturn(fixture("issued-snapshot.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/updated-1.json"));

        assertThat(run().status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isEqualTo(1);
        when(repository.existsBySimulatedFalse()).thenReturn(true);
        run();
        assertThat(status.lastAttempt().stored()).isEqualTo(2);
        run();

        verify(client, times(2)).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("an unreadable snapshot body leaves the snapshot pending, and it is retried")
    void unreadableSnapshotIsRetried() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenReturn("{not json").thenReturn(fixture("real/issued.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        run();
        when(repository.existsBySimulatedFalse()).thenReturn(true);
        run();
        run();

        verify(client, times(2)).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("a feed without a snapshot link keeps the snapshot pending until a feed names one")
    void pendingSurvivesAFeedWithoutALink() {
        String noLink = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>urn:uuid:" + REAL_UPDATE_ID
                + "</id></entry></feed>";
        when(client.getFeed()).thenReturn(noLink).thenReturn(fixture("real/feed.xml"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));
        when(client.getIssued(REAL_ISSUED_ID)).thenReturn(fixture("real/issued.json"));

        run();
        verify(client, never()).getIssued(REAL_ISSUED_ID);
        run();

        verify(client).getIssued(REAL_ISSUED_ID);
    }

    @Test
    @DisplayName("a failed snapshot fetch also leaves it pending")
    void failedSnapshotStaysPending() {
        when(client.getFeed()).thenReturn(fixture("real/feed.xml"));
        when(client.getIssued(REAL_ISSUED_ID)).thenThrow(serverError(500)).thenReturn(fixture("real/issued.json"));
        when(client.getUpdated(REAL_UPDATE_ID)).thenReturn(fixture("real/issued.json"));

        run();
        run();

        verify(client, times(2)).getIssued(REAL_ISSUED_ID);
    }
}
