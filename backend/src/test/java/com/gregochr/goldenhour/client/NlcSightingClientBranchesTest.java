package com.gregochr.goldenhour.client;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.model.NlcSightingReport;
import com.gregochr.goldenhour.service.SchedulerFireOutcome;
import com.gregochr.goldenhour.util.NlcTestSeason;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cache, retention and malformed-row behaviour of {@link NlcSightingClient} beyond the happy path: a stale cache is
 * refetched, a failed or missing page keeps the last good reports, and rows missing a cell are dropped one by one.
 */
class NlcSightingClientBranchesTest {

    private static final Clock FIXED_AUGUST = Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private static final String CAPTION_A =
            "<div class=\"caption\">Ken Kennedy from Dundee, Scotland on 2026, 08, 11 from 23:30 UT.</div>";
    private static final String CAPTION_B =
            "<div class=\"caption\">Alan Tough from Elgin, Scotland on 2026, 08, 10 from 23:10 UT.</div>";

    private static String html(String... captions) {
        return "<html><body>" + String.join("", captions) + "</body></html>";
    }

    private static NlcSightingClient client(RestClient rest) {
        return new NlcSightingClient(rest, new NlcProperties(), NlcTestSeason.inSeason(), FIXED_AUGUST);
    }

    private static NlcSightingClient parserOnly() {
        return client(mock(RestClient.class));
    }

    // ── cache and retention ──────────────────────────────────────────────────

    @Test
    @DisplayName("a zero-minute TTL makes every getReports() refetch rather than serve the cache")
    void getReports_staleCache_refetches() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetSequence(rest, String.class, html(CAPTION_A), html(CAPTION_A, CAPTION_B));
        NlcProperties props = new NlcProperties();
        props.setCacheTtlMinutes(0);
        NlcSightingClient c = new NlcSightingClient(rest, props, NlcTestSeason.inSeason(), FIXED_AUGUST);

        assertThat(c.getReports()).hasSize(1);
        assertThat(c.getReports()).hasSize(2);
    }

    @Test
    @DisplayName("a 404 after a good scrape keeps the last good reports, today and on the same-day retry")
    void refresh_notFoundAfterSuccess_retainsCache() {
        RestClient rest = mock(RestClient.class);
        HttpClientErrorException notFound = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null);
        RestClientMocks.stubGetSequence(rest, String.class, html(CAPTION_A));
        NlcSightingClient c = client(rest);
        assertThat(c.refresh()).hasSize(1);
        RestClientMocks.stubGetThrows(rest, String.class, notFound);

        assertThat(c.refresh()).hasSize(1);
        assertThat(c.refresh()).hasSize(1);
    }

    @Test
    @DisplayName("a generic failure after a good scrape keeps the last good reports")
    void refresh_genericFailureAfterSuccess_retainsCache() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetSequence(rest, String.class, html(CAPTION_A));
        NlcSightingClient c = client(rest);
        assertThat(c.refresh()).hasSize(1);
        RestClientMocks.stubGetThrows(rest, String.class, new RuntimeException("boom"));

        assertThat(c.refresh()).hasSize(1);
    }

    @Test
    @DisplayName("the scrape outcome counts one report in the singular and several in the plural")
    void refresh_outcomeSummary_singularAndPlural() {
        RestClient one = mock(RestClient.class);
        RestClientMocks.stubGet(one, String.class, html(CAPTION_A));
        RestClient two = mock(RestClient.class);
        RestClientMocks.stubGet(two, String.class, html(CAPTION_A, CAPTION_B));

        SchedulerFireOutcome.Report singular = SchedulerFireOutcome.capture(client(one)::refresh);
        SchedulerFireOutcome.Report plural = SchedulerFireOutcome.capture(client(two)::refresh);

        assertThat(singular.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(singular.summary()).isEqualTo("1 sighting report parsed");
        assertThat(plural.summary()).isEqualTo("2 sighting reports parsed");
    }

    // ── URL resolution ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveUrl substitutes a lone {month} or a lone {year}, and returns a null URL as null")
    void resolveUrl_partialPlaceholders() {
        NlcProperties props = new NlcProperties();
        NlcSightingClient c = new NlcSightingClient(mock(RestClient.class), props, NlcTestSeason.inSeason(),
                FIXED_AUGUST);
        LocalDate date = LocalDate.of(2026, 8, 12);

        props.setSightingsUrl("https://ed-co.net/nlcnet/{month}");
        assertThat(c.resolveUrl(date)).isEqualTo("https://ed-co.net/nlcnet/august");
        props.setSightingsUrl("https://ed-co.net/nlcnet/{year}");
        assertThat(c.resolveUrl(date)).isEqualTo("https://ed-co.net/nlcnet/2026");
        props.setSightingsUrl(null);
        assertThat(c.resolveUrl(date)).isNull();
    }

    @Test
    @DisplayName("a month-only or year-only URL is still a month page: out of season it is skipped")
    void refresh_partialPlaceholderUrl_isSeasonGated() {
        RestClient rest = mock(RestClient.class);
        NlcProperties props = new NlcProperties();
        NlcSightingClient c = new NlcSightingClient(rest, props, NlcTestSeason.withSeason(false), FIXED_AUGUST);

        props.setSightingsUrl("https://ed-co.net/nlcnet/{month}");
        SchedulerFireOutcome.Report monthOnly = SchedulerFireOutcome.capture(c::refresh);
        props.setSightingsUrl("https://ed-co.net/nlcnet/{year}");
        SchedulerFireOutcome.Report yearOnly = SchedulerFireOutcome.capture(c::refresh);

        assertThat(monthOnly.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        assertThat(yearOnly.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
    }

    @Test
    @DisplayName("a null configured URL is no month page: the season gate is skipped and the fetch fails open")
    void refresh_nullUrl_failsOpenWithoutSeasonGate() {
        NlcProperties props = new NlcProperties();
        props.setSightingsUrl(null);
        NlcSightingClient c = new NlcSightingClient(mock(RestClient.class), props, NlcTestSeason.withSeason(false),
                FIXED_AUGUST);

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(c::refresh);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null HTML yields no reports")
    void parse_null_isEmpty() {
        assertThat(parserOnly().parse(null)).isEmpty();
        assertThat(parserOnly().parse("   ")).isEmpty();
    }

    private static String cell(String title, String text) {
        return "<td title=\"" + title + "\">" + text + "</td>";
    }

    private static String row(String... cells) {
        return "<tr>" + String.join("", cells) + "</tr>";
    }

    private static String table(String... rows) {
        return "<html><body><table>" + String.join("", rows) + "</table></body></html>";
    }

    private static final String LOCATION = cell("Location", "Kielder");
    private static final String YEAR = cell("yy", "2026");
    private static final String MONTH = cell("mm", "07");
    private static final String DAY = cell("dd", "01");
    private static final String TIME = cell("UT-Start", "23:10");

    @Test
    @DisplayName("a row missing any one required cell, or with an empty Location, is dropped")
    void parse_rowMissingARequiredCell_isDropped() {
        String html = table(
                row(YEAR, MONTH, DAY, TIME),
                row(cell("Location", ""), YEAR, MONTH, DAY, TIME),
                row(LOCATION, MONTH, DAY, TIME),
                row(LOCATION, YEAR, DAY, TIME),
                row(LOCATION, YEAR, MONTH, TIME),
                row(LOCATION, YEAR, MONTH, DAY),
                row(cell("Observer", "Complete"), LOCATION, YEAR, MONTH, DAY, TIME));

        List<NlcSightingReport> reports = parserOnly().parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Complete");
    }

    @Test
    @DisplayName("a row without a Country keeps just the location, and one without an Observer has an empty observer")
    void parse_rowWithoutCountryOrObserver() {
        String noCountry = row(cell("Observer", "A"), LOCATION, YEAR, MONTH, DAY, TIME);
        String emptyCountry = row(cell("Observer", "B"), LOCATION, cell("Country", ""), YEAR, MONTH, DAY,
                cell("UT-Start", "23:20"));
        String noObserver = row(LOCATION, cell("Country", "England"), YEAR, MONTH, DAY, cell("UT-Start", "23:30"));

        List<NlcSightingReport> reports = parserOnly().parse(table(noCountry, emptyCountry, noObserver));

        assertThat(reports).extracting(NlcSightingReport::location)
                .containsExactly("Kielder, England", "Kielder", "Kielder");
        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("", "B", "A");
        assertThat(reports.get(0).reportedAt()).isEqualTo(Instant.parse("2026-07-01T23:30:00Z"));
    }

    @Test
    @DisplayName("a caption whose observer or date does not fit the pattern is skipped")
    void parse_captionNotMatching_isSkipped() {
        String html = html("<div class=\"caption\">No sighting words here</div>",
                "<div class=\"caption\">Obs from Elgin on 26, 06, 20 from 23:00 UT.</div>",
                CAPTION_A);

        List<NlcSightingReport> reports = parserOnly().parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Ken Kennedy");
    }
}
