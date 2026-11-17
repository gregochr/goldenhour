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
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
 * Unit tests for {@link WarningPollingJob}: recorded fixtures through a mocked client (the live feed is never called).
 */
@ExtendWith(MockitoExtension.class)
class WarningPollingJobTest {

    private static final URI FEED = URI.create("https://example.test/feed");
    private static final Instant NOW = Instant.parse("2031-03-10T06:00:00Z");

    private static final List<String[]> FIXTURES = List.of(
            new String[] {"W-YEL-WIND", "1"}, new String[] {"W-UPG", "1"}, new String[] {"W-UPG", "2"},
            new String[] {"W-CAN", "2"}, new String[] {"W-SNOW", "1"}, new String[] {"W-UNK", "1"},
            new String[] {"W-HOLE", "1"});

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
        properties.setBaseUrl(FEED.toString());
        job = new WarningPollingJob(client, new WarningFeedParser(), repository, properties, status, epoch,
                scheduler, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    private static URI detail(String id, String version) {
        return URI.create("https://example.test/nswws/warnings/" + id + "/" + version);
    }

    private void stubFeedAndAllDetails() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        for (String[] f : FIXTURES) {
            when(client.getWarning(detail(f[0], f[1]))).thenReturn(fixture(f[0] + "-" + f[1] + ".json"));
        }
    }

    private SchedulerFireOutcome.Report run() {
        return SchedulerFireOutcome.capture(job::poll);
    }

    private static HttpClientErrorException clientError(int code) {
        return HttpClientErrorException.create(HttpStatusCode.valueOf(code), "x", HttpHeaders.EMPTY, new byte[0],
                null);
    }

    private static HttpServerErrorException serverError(int code) {
        return HttpServerErrorException.create(HttpStatusCode.valueOf(code), "x", HttpHeaders.EMPTY, new byte[0],
                null);
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

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        verifyNoInteractions(client, repository);
        assertThat(status.lastAttempt()).isNull();
    }

    @Test
    @DisplayName("with no feed URL the poll fails as NOT_CONFIGURED without calling out")
    void notConfigured() {
        properties.setBaseUrl(" ");

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(status.lastAttempt().failureReason()).isEqualTo("NOT_CONFIGURED");
        verifyNoInteractions(client, repository);
    }

    @Test
    @DisplayName("a null feed URL is also NOT_CONFIGURED")
    void nullFeedUrl() {
        properties.setBaseUrl(null);

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a poll that starts while another is running is skipped, and the first still finishes")
    void overlapGuard() {
        when(client.getFeed(FEED)).thenAnswer(inv -> {
            SchedulerFireOutcome.Report inner = run();
            assertThat(inner.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
            assertThat(inner.summary()).contains("Already running");
            return "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>";
        });

        SchedulerFireOutcome.Report outer = run();

        assertThat(outer.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        // and the guard is released afterwards
        assertThat(run().status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
    }

    // ── Ingest ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the fixture feed stores all seven identified versions with the poller's clock, never simulated, "
            + "counts the malformed entry and the unknown type, and bumps the epoch once")
    void storesFixtureFeed() {
        stubFeedAndAllDetails();
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        ArgumentCaptor<WeatherWarningVersionEntity> saved = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository, times(7)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(r -> r.getWarningId() + "/" + r.getWarningVersion())
                .containsExactly("W-YEL-WIND/1", "W-UPG/1", "W-UPG/2", "W-CAN/2", "W-SNOW/1", "W-UNK/1",
                        "W-HOLE/1");
        assertThat(saved.getAllValues()).allSatisfy(r -> {
            assertThat(r.getReceivedAt()).isEqualTo(NOW);
            assertThat(r.isSimulated()).isFalse();
        });
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("7 new warning versions stored").contains("1 unreadable");
        WarningPollStatus.Attempt attempt = status.lastAttempt();
        assertThat(attempt.succeeded()).isTrue();
        assertThat(attempt.at()).isEqualTo(NOW);
        assertThat(attempt.feedEntries()).isEqualTo(8);
        assertThat(attempt.stored()).isEqualTo(7);
        assertThat(attempt.malformedSkipped()).isEqualTo(1);
        assertThat(status.lastSuccessAt()).isEqualTo(NOW);
        assertThat(status.unknownTypeCounts()).containsExactly(java.util.Map.entry("Volcanic Ash", 1));
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("re-polling the same feed is a no-op: nothing fetched, nothing stored, epoch unmoved")
    void idempotentRepoll() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        for (String[] f : FIXTURES) {
            when(repository.existsByWarningIdAndWarningVersion(f[0], Integer.parseInt(f[1]))).thenReturn(true);
        }
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        verify(client, never()).getWarning(detail("W-YEL-WIND", "1"));
        verify(repository, never()).save(argThat(w -> true));
        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().alreadyStored()).isEqualTo(7);
        assertThat(status.lastAttempt().stored()).isZero();
        assertThat(epoch.current()).isEqualTo(before);
    }

    @Test
    @DisplayName("only the unstored versions are fetched: with v1 held, v2 alone is fetched and stored")
    void onlyUnstoredFetched() {
        String feed = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<entry><link href=\"https://example.test/nswws/warnings/W-UPG/1\"/></entry>"
                + "<entry><link href=\"https://example.test/nswws/warnings/W-UPG/2\"/></entry></feed>";
        when(client.getFeed(FEED)).thenReturn(feed);
        when(repository.existsByWarningIdAndWarningVersion("W-UPG", 1)).thenReturn(true);
        when(client.getWarning(detail("W-UPG", "2"))).thenReturn(fixture("W-UPG-2.json"));

        run();

        verify(client, never()).getWarning(detail("W-UPG", "1"));
        ArgumentCaptor<WeatherWarningVersionEntity> saved = ArgumentCaptor.forClass(WeatherWarningVersionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getWarningVersion()).isEqualTo(2);
        assertThat(saved.getValue().getWarningLevel()).isEqualTo("Amber");
    }

    @Test
    @DisplayName("a unique-key collision on insert (a racing writer) counts as already stored, not a failure")
    void uniqueViolationIsAlreadyStored() {
        stubFeedAndAllDetails();
        org.mockito.Mockito.doAnswer(inv -> {
            WeatherWarningVersionEntity row = inv.getArgument(0);
            if ("W-SNOW".equals(row.getWarningId())) {
                throw new DataIntegrityViolationException("dup");
            }
            return row;
        }).when(repository).save(argThat(w -> true));

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isEqualTo(6);
        assertThat(status.lastAttempt().alreadyStored()).isEqualTo(1);
    }

    @Test
    @DisplayName("a detail that has expired (404 or 410) is skipped and counted; the poll still succeeds")
    void goneDetailSkipped() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        when(client.getWarning(detail("W-YEL-WIND", "1"))).thenThrow(clientError(404));
        when(client.getWarning(detail("W-UPG", "1"))).thenThrow(clientError(410));
        for (String[] f : FIXTURES.subList(2, FIXTURES.size())) {
            when(client.getWarning(detail(f[0], f[1]))).thenReturn(fixture(f[0] + "-" + f[1] + ".json"));
        }

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().goneSkipped()).isEqualTo(2);
        assertThat(status.lastAttempt().stored()).isEqualTo(5);
    }

    @Test
    @DisplayName("when nothing at all is stored the epoch does not move")
    void noEpochBumpWithoutChange() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        for (String[] f : FIXTURES) {
            when(client.getWarning(detail(f[0], f[1]))).thenThrow(clientError(404));
        }
        long before = epoch.current();

        run();

        assertThat(epoch.current()).isEqualTo(before);
        assertThat(status.lastAttempt().goneSkipped()).isEqualTo(7);
    }

    @Test
    @DisplayName("a malformed detail is skipped and counted, and the rest of the feed is still stored")
    void malformedDetailSkipped() {
        stubFeedAndAllDetails();
        when(client.getWarning(detail("W-UNK", "1"))).thenReturn("{not json");

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(status.lastAttempt().stored()).isEqualTo(6);
        assertThat(status.lastAttempt().malformedSkipped()).isEqualTo(2);
        assertThat(status.unknownTypeCounts()).isEmpty();
    }

    @Test
    @DisplayName("a detail that answers 500 fails the poll as HTTP_500 but the other entries are still stored")
    void detailServerErrorContinues() {
        stubFeedAndAllDetails();
        when(client.getWarning(detail("W-UPG", "1"))).thenThrow(serverError(500));
        long before = epoch.current();

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(status.lastAttempt().failureReason()).isEqualTo("HTTP_500");
        assertThat(status.lastAttempt().stored()).isEqualTo(6);
        assertThat(status.lastSuccessAt()).isNull();
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a detail fetch that cannot connect fails the poll as UNREACHABLE and the first reason stands")
    void detailUnreachableKeepsFirstReason() {
        stubFeedAndAllDetails();
        when(client.getWarning(detail("W-YEL-WIND", "1"))).thenThrow(new ResourceAccessException("down",
                new IOException("refused")));
        when(client.getWarning(detail("W-UPG", "1"))).thenThrow(serverError(502));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("UNREACHABLE");
    }

    @Test
    @DisplayName("a 429 on a detail stops the poll at once as RATE_LIMITED; later entries are not fetched")
    void detailRateLimitedStops() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        when(client.getWarning(detail("W-YEL-WIND", "1"))).thenThrow(clientError(429));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("RATE_LIMITED");
        verify(client, never()).getWarning(detail("W-UPG", "1"));
        verify(repository, never()).save(argThat(w -> true));
    }

    @Test
    @DisplayName("a 401 on a detail stops the poll as UNAUTHORISED")
    void detailUnauthorisedStops() {
        when(client.getFeed(FEED)).thenReturn(fixture("feed.atom.xml"));
        when(client.getWarning(detail("W-YEL-WIND", "1"))).thenThrow(clientError(401));

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("UNAUTHORISED");
        verify(client, never()).getWarning(detail("W-UPG", "1"));
    }

    // ── Feed failures leave stored warnings untouched ───────────────────────────

    @Test
    @DisplayName("401 and 403 on the feed are UNAUTHORISED; 429 is RATE_LIMITED; other statuses are HTTP_n")
    void feedHttpFailures() {
        List<String> reasons = new ArrayList<>();
        for (int code : new int[] {401, 403, 429, 503}) {
            doThrow(code == 503 ? serverError(code) : clientError(code)).when(client).getFeed(FEED);
            run();
            reasons.add(status.lastAttempt().failureReason());
        }

        assertThat(reasons).containsExactly("UNAUTHORISED", "UNAUTHORISED", "RATE_LIMITED", "HTTP_503");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an unreachable feed is UNREACHABLE, is reported FAILED, and never records a success")
    void feedUnreachable() {
        when(client.getFeed(FEED)).thenThrow(new ResourceAccessException("down", new IOException("refused")));

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
        when(client.getFeed(FEED)).thenReturn("<html>maintenance</html");

        run();

        assertThat(status.lastAttempt().failureReason()).isEqualTo("MALFORMED_FEED");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an empty feed is a successful poll that stores nothing and cancels nothing")
    void emptyFeedInfersNothing() {
        when(client.getFeed(FEED)).thenReturn("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>");

        SchedulerFireOutcome.Report report = run();

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).contains("0 new warning versions");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a single stored version is worded in the singular")
    void singularSummary() {
        String feed = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>"
                + "<link href=\"https://example.test/nswws/warnings/W-UPG/2\"/></entry></feed>";
        when(client.getFeed(FEED)).thenReturn(feed);
        when(client.getWarning(detail("W-UPG", "2"))).thenReturn(fixture("W-UPG-2.json"));

        assertThat(run().summary()).startsWith("1 new warning version stored");
    }
}
