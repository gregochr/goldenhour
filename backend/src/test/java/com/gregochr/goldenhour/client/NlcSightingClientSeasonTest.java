package com.gregochr.goldenhour.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.util.NlcTestSeason;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Season gate, 404 back-off and error-body truncation of {@link NlcSightingClient}. */
class NlcSightingClientSeasonTest {

    private static final Instant OCTOBER_DAY_1 = Instant.parse("2026-10-19T10:00:00Z");

    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(NlcSightingClient.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        clientLogger.detachAppender(appender);
    }

    private static Clock clockAt(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    private static HttpClientErrorException notFound(String body) {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("outside the NLC season no request is made and one INFO line is logged per day")
    void outOfSeason_doesNotFetch_logsOncePerDay() {
        RestClient rest = mock(RestClient.class);
        AtomicReference<Instant> now = new AtomicReference<>(OCTOBER_DAY_1);
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties(),
                NlcTestSeason.withSeason(false), clockAt(now));

        assertThat(c.refresh()).isEmpty();
        assertThat(c.refresh()).isEmpty();
        assertThat(c.getReports()).isEmpty();

        verify(rest, never()).get();
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO).hasSize(1);

        now.set(OCTOBER_DAY_1.plusSeconds(24 * 3600));
        c.refresh();
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO).hasSize(2);
    }

    @Test
    @DisplayName("a pinned (placeholder-free) archive URL is still fetched out of season")
    void outOfSeason_pinnedArchiveUrl_stillFetched() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGet(rest, String.class, "<html></html>");
        NlcProperties props = new NlcProperties();
        props.setSightingsUrl("https://ed-co.net/nlcnet/");
        NlcSightingClient c = new NlcSightingClient(rest, props, NlcTestSeason.withSeason(false),
                clockAt(new AtomicReference<>(OCTOBER_DAY_1)));

        c.refresh();

        verify(rest, times(1)).get();
    }

    @Test
    @DisplayName("a 404 stops retries of that URL for the rest of the day, then retries next day")
    void notFound_backsOffUntilTomorrow() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetThrows(rest, String.class, notFound("<html>gone</html>"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-12T10:00:00Z"));
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties(),
                NlcTestSeason.inSeason(), clockAt(now));

        c.refresh();
        now.set(now.get().plusSeconds(600));
        c.refresh();
        c.refresh();
        verify(rest, times(1)).get();

        now.set(Instant.parse("2026-08-13T10:00:00Z"));
        c.refresh();
        verify(rest, times(2)).get();
    }

    @Test
    @DisplayName("a 404 does not log the response body")
    void notFound_logsNoBody() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetThrows(rest, String.class, notFound("X".repeat(30_000)));
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties(),
                NlcTestSeason.inSeason(), clockAt(new AtomicReference<>(Instant.parse("2026-08-12T10:00:00Z"))));

        c.refresh();

        assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage().length()).isLessThan(400));
    }

    @Test
    @DisplayName("any other failure logs at most ~200 characters of the error message")
    void otherFailure_logIsTruncated() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetThrows(rest, String.class, new RuntimeException("Y".repeat(30_000)));
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties(),
                NlcTestSeason.inSeason(), clockAt(new AtomicReference<>(Instant.parse("2026-08-12T10:00:00Z"))));

        c.refresh();

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage().length()).isLessThan(400);
    }

    @Test
    @DisplayName("the production constructor reads the real UK date, never a rewound clock")
    void productionConstructor_usesRealDate() {
        com.gregochr.goldenhour.service.NlcClarityService season = NlcTestSeason.withSeason(false);
        NlcSightingClient c = new NlcSightingClient(mock(RestClient.class), new NlcProperties(), season);

        c.refresh();

        verify(season).isNlcSeason(java.time.LocalDate.now(java.time.ZoneId.of("Europe/London")));
    }

    @Test
    @DisplayName("truncate keeps short messages, cuts long ones and tolerates null")
    void truncate_behaviour() {
        assertThat(NlcSightingClient.truncate(null)).isNull();
        assertThat(NlcSightingClient.truncate("short")).isEqualTo("short");
        assertThat(NlcSightingClient.truncate("Z".repeat(500))).hasSize(200 + "... [truncated]".length());
    }
}
