package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcSightingReport;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link NlcSightingClient} parsing.
 *
 * <p>Exercises the {@code parse()} routine directly against the verified NLCNET {@code div.caption}
 * format ("Observer from Location on YYYY, MM, DD from HH:MM UT …"), so the fragile scrape logic is
 * covered without a network dependency. The HTTP fetch itself fails open and is not unit-tested here.
 */
class NlcSightingClientTest {

    private final NlcSightingClient client =
            new NlcSightingClient(mock(org.springframework.web.client.RestClient.class), new NlcProperties());

    private static String page(String... captions) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (String caption : captions) {
            sb.append("<div class=\"caption\">").append(caption).append("</div>");
        }
        return sb.append("</body></html>").toString();
    }

    @Test
    @DisplayName("parses observer, location and evening-time instant from a well-formed caption")
    void parse_wellFormedCaption_extractsFields() {
        String html = page("Alan C Tough from Elgin, Scotland on 2026, 06, 20 from 23:01 - 02:03 UT. "
                + "NLC forms: II,III,IV, ↑>40 °, brightness 2.");

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).hasSize(1);
        NlcSightingReport report = reports.get(0);
        assertThat(report.observer()).isEqualTo("Alan C Tough");
        assertThat(report.location()).isEqualTo("Elgin, Scotland");
        // A 23:01 start on the reported evening date maps to that evening.
        assertThat(report.reportedAt()).isEqualTo(Instant.parse("2026-06-20T23:01:00Z"));
    }

    @Test
    @DisplayName("a small-hours time belongs to the following morning (date advances one day)")
    void parse_smallHoursTime_advancesToNextDay() {
        String html = page("Eric Pollock from Burnmouth, Scotland on 2026, 07, 11 from 01:15 UT.");

        NlcSightingReport report = client.parse(html).get(0);

        assertThat(report.reportedAt()).isEqualTo(Instant.parse("2026-07-12T01:15:00Z"));
    }

    @Test
    @DisplayName("tolerates a colon-less time and a night-spanning date range")
    void parse_colonlessTimeAndDateRange() {
        String html = page("Mark Zalcik from Edmonton, Canada on 2026, 06, 20-21 from 0540 - 0730 UT.");

        NlcSightingReport report = client.parse(html).get(0);

        assertThat(report.location()).isEqualTo("Edmonton, Canada");
        assertThat(report.reportedAt()).isEqualTo(Instant.parse("2026-06-21T05:40:00Z"));
    }

    @Test
    @DisplayName("captions that do not match the format are skipped, not thrown on")
    void parse_malformedCaption_skipped() {
        String html = page(
                "Just a photo caption with no structured sighting data",
                "Ken Kennedy from Dundee, Scotland on 2026, 06, 20 from 23:30 UT.");

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).observer()).isEqualTo("Ken Kennedy");
    }

    @Test
    @DisplayName("reports are returned newest-first")
    void parse_ordersNewestFirst() {
        String html = page(
                "Older Observer from Elgin on 2026, 06, 10 from 23:00 UT.",
                "Newer Observer from Kielder on 2026, 06, 20 from 23:00 UT.");

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer)
                .containsExactly("Newer Observer", "Older Observer");
    }

    @Test
    @DisplayName("blank or structureless HTML yields no reports")
    void parse_emptyHtml_empty() {
        assertThat(client.parse("")).isEmpty();
        assertThat(client.parse("<html><body><p>nothing here</p></body></html>")).isEmpty();
    }

    // ── HTTP fetch + cache paths (deep-stubbed RestClient) ──────────────────────

    private static final String ONE_CAPTION_PAGE =
            "<div class=\"caption\">Alan C Tough from Elgin, Scotland on 2026, 06, 20 from 23:01 UT.</div>";

    private static NlcSightingClient clientReturning(String html) {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGet(rest, String.class, html);
        return new NlcSightingClient(rest, new NlcProperties());
    }

    @Test
    @DisplayName("refresh fetches and parses the page, then getReports serves the cache")
    void refresh_thenGetReports_usesCache() {
        NlcSightingClient c = clientReturning("<html><body>" + ONE_CAPTION_PAGE + "</body></html>");

        assertThat(c.refresh()).hasSize(1);
        // Within the TTL the cached list is returned without another parse/fetch.
        assertThat(c.getReports()).hasSize(1);
    }

    @Test
    @DisplayName("getReports triggers a fetch when the cache is empty")
    void getReports_emptyCache_fetches() {
        NlcSightingClient c = clientReturning("<html><body>" + ONE_CAPTION_PAGE + "</body></html>");

        assertThat(c.getReports()).hasSize(1);
    }

    @Test
    @DisplayName("a fetch failure fails open — refresh returns an empty list, never throws")
    void refresh_httpError_failsOpen() {
        RestClient rest = mock(RestClient.class);
        RestClientMocks.stubGetThrows(rest, String.class, new RuntimeException("network down"));
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties());

        assertThat(c.refresh()).isEmpty();
        assertThat(c.getReports()).isEmpty();
    }

    // ── Live NLCNET season-table layout (real captured snapshot) ────────────────

    /** A trimmed but faithful snapshot of the live 2026-07 real-time sightings table. */
    private static String liveTableSnapshot() {
        try (InputStream in = NlcSightingClientTest.class
                .getResourceAsStream("/nlc/nlcnet-live-table-2026-07.html")) {
            assertThat(in).as("live-table fixture present").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("parses the live season table: joins Location+Country, decodes entities, orders by time")
    void parse_liveTable_extractsRows() {
        List<NlcSightingReport> reports = client.parse(liveTableSnapshot());

        // Header row (<th>) is skipped; the four data rows parse.
        assertThat(reports).hasSize(4);

        // Newest first: the 02–03 night 00:30 UT entry (→ morning of the 3rd) leads.
        NlcSightingReport newest = reports.get(0);
        assertThat(newest.observer()).isEqualTo("Tomasz Adam");
        assertThat(newest.location()).isEqualTo("Kraków, Poland"); // &oacute; decoded by Jsoup
        assertThat(newest.reportedAt()).isEqualTo(Instant.parse("2026-07-03T00:30:00Z"));

        // A UK row joins the Location and Country columns; a 00:17 start on the 01–02 night
        // resolves to the morning of the 2nd.
        assertThat(reports).anySatisfy(r -> {
            assertThat(r.observer()).isEqualTo("Ken Kennedy");
            assertThat(r.location()).isEqualTo("Broughty Ferry, Dundee, Scotland, UK");
            assertThat(r.reportedAt()).isEqualTo(Instant.parse("2026-07-02T00:17:00Z"));
        });
    }

    @Test
    @DisplayName("the live table and gallery captions parse together in one page")
    void parse_tableAndCaptions_combined() {
        String mixed = liveTableSnapshot()
                + page("Late Observer from Kielder on 2026, 07, 04 from 23:40 UT.");

        List<NlcSightingReport> reports = client.parse(mixed);

        // 4 table rows + 1 caption; the caption's 23:40 on the 4th is the newest overall.
        assertThat(reports).hasSize(5);
        assertThat(reports.get(0).observer()).isEqualTo("Late Observer");
    }

    // ── Malformed live-table rows degrade gracefully ────────────────────────────
    //
    // NLCNET is a third-party page that can change or serve junk at any time. A single bad row must
    // cost that row only — never the whole scrape — so every case below asserts that the good row
    // still parses. Any exception escaping parse() fails these tests outright.

    private static String cell(String title, String text) {
        return "<td title=\"" + title + "\">" + text + "</td>";
    }

    /** Builds a live-table row; the {@code Location} column is fixed so callers vary only the rest. */
    private static String tableRow(String observer, String yy, String mm, String dd, String utStart) {
        return "<tr>"
                + cell("Observer", observer)
                + cell("Location", "Kielder")
                + cell("Country", "England")
                + cell("yy", yy)
                + cell("mm", mm)
                + cell("dd", dd)
                + cell("UT-Start", utStart)
                + "</tr>";
    }

    private static String table(String... rows) {
        return "<html><body><table>" + String.join("", rows) + "</table></body></html>";
    }

    /** A well-formed row: 00:17 on the 01–02 night resolves to the morning of the 2nd. */
    private static final String GOOD_ROW = tableRow("Good Observer", "2026", "07", "01-02", "00:17");

    @Test
    @DisplayName("a year cell whose digit run overflows int skips that row, not the whole scrape")
    void parse_yearCellOverflowsInt_rowSkipped() {
        String html = table(tableRow("Bad Year", "99999999999", "07", "01-02", "00:17"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("a month cell whose digit run overflows int skips that row")
    void parse_monthCellOverflowsInt_rowSkipped() {
        String html = table(tableRow("Bad Month", "2026", "12345678901", "01-02", "00:17"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("a time cell of an overflowing digit run skips that row")
    void parse_timeCellOverflowingDigitRun_rowSkipped() {
        // The time regex reads "99999999999" as 99:99, which is not a real clock time.
        String html = table(tableRow("Bad Time", "2026", "07", "01-02", "99999999999"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("a garbled, digit-free time cell skips that row")
    void parse_garbledTimeCell_rowSkipped() {
        String html = table(tableRow("No Time", "2026", "07", "01-02", "not recorded"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("a non-numeric day cell skips that row")
    void parse_nonNumericDayCell_rowSkipped() {
        String html = table(tableRow("No Day", "2026", "07", "n/a", "00:17"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("an out-of-range date (month 13, day 40) skips that row")
    void parse_outOfRangeDateCells_rowSkipped() {
        String html = table(tableRow("Bad Date", "2026", "13", "40", "00:17"), GOOD_ROW);

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Good Observer");
    }

    @Test
    @DisplayName("a table of nothing but malformed rows yields an empty list, never an exception")
    void parse_allRowsMalformed_returnsEmpty() {
        String html = table(
                tableRow("Bad Year", "99999999999", "07", "01-02", "00:17"),
                tableRow("Bad Time", "2026", "07", "01-02", "99999999999"),
                tableRow("No Day", "2026", "07", "n/a", "00:17"));

        assertThat(client.parse(html)).isEmpty();
    }

    @Test
    @DisplayName("a caption carrying an impossible date/time is skipped, not thrown on")
    void parse_captionWithOutOfRangeDateTime_skipped() {
        String html = page(
                "Bad Caption from Elgin on 2026, 13, 40 from 25:99 UT.",
                "Ken Kennedy from Dundee, Scotland on 2026, 06, 20 from 23:30 UT.");

        List<NlcSightingReport> reports = client.parse(html);

        assertThat(reports).extracting(NlcSightingReport::observer).containsExactly("Ken Kennedy");
    }

    // ── Season-tracking URL resolution ──────────────────────────────────────────

    @Test
    @DisplayName("resolveUrl substitutes {year}/{month} from the date (auto-tracks the season)")
    void resolveUrl_substitutesYearAndMonth() {
        NlcProperties props = new NlcProperties();
        props.setSightingsUrl("https://ed-co.net/nlcnet/{year}-{month}");
        NlcSightingClient c = new NlcSightingClient(mock(RestClient.class), props);

        assertThat(c.resolveUrl(LocalDate.of(2026, 7, 6)))
                .isEqualTo("https://ed-co.net/nlcnet/2026-july");
        assertThat(c.resolveUrl(LocalDate.of(2026, 5, 25)))
                .isEqualTo("https://ed-co.net/nlcnet/2026-may");
    }

    @Test
    @DisplayName("resolveUrl leaves a placeholder-free URL (archive fallback) unchanged")
    void resolveUrl_noPlaceholders_unchanged() {
        NlcProperties props = new NlcProperties();
        props.setSightingsUrl("https://ed-co.net/nlcnet/");
        NlcSightingClient c = new NlcSightingClient(mock(RestClient.class), props);

        assertThat(c.resolveUrl(LocalDate.of(2026, 7, 6)))
                .isEqualTo("https://ed-co.net/nlcnet/");
    }
}
