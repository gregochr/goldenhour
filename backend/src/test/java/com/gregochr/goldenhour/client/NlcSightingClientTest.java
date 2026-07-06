package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcSightingReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        RestClient rest = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(rest.get().uri(anyString()).retrieve().body(String.class)).thenReturn(html);
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
        RestClient rest = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(rest.get().uri(anyString()).retrieve().body(String.class))
                .thenThrow(new RuntimeException("network down"));
        NlcSightingClient c = new NlcSightingClient(rest, new NlcProperties());

        assertThat(c.refresh()).isEmpty();
        assertThat(c.getReports()).isEmpty();
    }
}
