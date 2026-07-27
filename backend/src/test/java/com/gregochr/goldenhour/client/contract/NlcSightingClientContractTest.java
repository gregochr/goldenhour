package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.NlcSightingClient;
import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcSightingReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link NlcSightingClient} — a scraper whose entire outbound contract
 * is the URL path, since the NLCNET season page takes no query parameters.
 *
 * <p><strong>Why this exists.</strong> {@code NlcSightingClientTest} covers {@code resolveUrl} as a
 * pure function and stubs the fetch through {@code RestClientMocks}, so nothing connects the two:
 * if {@code refresh()} passed {@code properties.getSightingsUrl()} raw — with the
 * {@code &#123;year&#125;} / {@code &#123;month&#125;} placeholders unsubstituted — or held a stale
 * variable, every existing test would look identical. These tests assert the URL that actually
 * reaches the wire.
 *
 * <p><strong>The month name is the fragile part.</strong> The live page lives at
 * {@code .../2026-july} — a full, lower-cased, <em>English</em> month name. {@code 2026-07},
 * {@code 2026-Jul} or a default-locale rendering all 404, and the scraper fails open, so a broken
 * URL looks exactly like a quiet NLC night. The expected month here is built from this test's own
 * name table rather than by re-calling production code, so the assertion is not self-referential.
 *
 * <p><strong>No injectable clock.</strong> {@code refresh()} reads {@code LocalDate.now(London)}
 * directly, so the season-page test asserts the <em>shape</em> of the resolved URL against the
 * current date rather than a pinned one. Making the clock injectable is a main-source change and is
 * reported rather than made here.
 */
class NlcSightingClientContractTest {

    /**
     * Month names as the NLCNET URL spells them. Deliberately a literal table: deriving the
     * expected name from {@code TextStyle.FULL} + {@code Locale.ENGLISH} would just re-run the
     * production code and assert nothing.
     */
    private static final String[] MONTH_NAMES = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};

    /** The UK zone the season page is resolved against. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private RestClient.Builder builder;
    private NlcProperties properties;

    @BeforeEach
    void setUp() {
        this.builder = RestClient.builder();
        this.properties = new NlcProperties();
    }

    /**
     * The end-to-end assertion the existing {@code resolveUrl} unit test cannot make: the resolved
     * season URL is the URL actually requested. The {@code doesNotContain} halves are the load-
     * bearing ones — they fail if the raw configured template ever reaches the wire.
     */
    @Test
    @DisplayName("The configured season template is substituted before the request, not sent raw")
    void seasonTemplateIsResolvedBeforeItReachesTheWire() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LocalDate today = LocalDate.now(LONDON);
        String expected = "https://ed-co.net/nlcnet/"
                + today.getYear() + "-" + MONTH_NAMES[today.getMonthValue() - 1];

        server.expect(requestTo(expected))
                .andExpect(requestTo(not(containsString("{year}"))))
                .andExpect(requestTo(not(containsString("{month}"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));

        // The production default is the placeholder template — assert against it, unmodified.
        new NlcSightingClient(builder.build(), properties).refresh();

        server.verify();
    }

    /**
     * A placeholder-free URL — e.g. the NLCNET gallery archive — must pass through byte-for-byte.
     * This is the branch that lets an operator pin a fixed page, and the one that would silently
     * mangle a URL if substitution ever stopped being conditional.
     */
    @Test
    @DisplayName("A placeholder-free URL is requested unchanged")
    void placeholderFreeUrlIsRequestedUnchanged() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        properties.setSightingsUrl("https://ed-co.net/nlcnet/");

        server.expect(requestTo("https://ed-co.net/nlcnet/"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));

        new NlcSightingClient(builder.build(), properties).refresh();

        server.verify();
    }

    @Test
    @DisplayName("A live-season table page parses into reports, location joined to country")
    void seasonTablePageParsesIntoReports() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        properties.setSightingsUrl("https://ed-co.net/nlcnet/");

        server.expect(requestTo("https://ed-co.net/nlcnet/"))
                .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));

        List<NlcSightingReport> reports = new NlcSightingClient(builder.build(), properties).refresh();

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).location()).isEqualTo("Durham, Scotland");
        assertThat(reports.get(0).observer()).isEqualTo("A Observer");
        server.verify();
    }

    /**
     * Fail-open is the documented behaviour: a 404 (which is what the season page returns
     * off-season) must not throw. Pinned here alongside the URL tests because it is the reason a
     * <em>wrong</em> URL is invisible in production — the banner simply stays dark.
     */
    @Test
    @DisplayName("A 404 from the season page fails open with an empty list rather than throwing")
    void notFoundFailsOpen() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        properties.setSightingsUrl("https://ed-co.net/nlcnet/");

        server.expect(requestTo("https://ed-co.net/nlcnet/"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.NOT_FOUND));

        List<NlcSightingReport> reports = new NlcSightingClient(builder.build(), properties).refresh();

        assertThat(reports).isEmpty();
        server.verify();
    }

    private static String fixture() {
        String path = "/contract/nlc/sightings-page.html";
        try (InputStream in = NlcSightingClientContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
