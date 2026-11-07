package com.gregochr.goldenhour.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ImoCalendarClient} against a mock server: the 200 path, the 404 result, the refused statuses, and each
 * refusal of a body (size, content type, magic bytes). No test touches the network.
 */
class ImoCalendarClientTest {

    private static final String BASE = "https://www.imo.net/files/meteor-shower/";
    private static final String URL_2026 = BASE + "cal2026.pdf";
    private static final byte[] PDF = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);

    private MockRestServiceServer server;
    private ImoCalendarClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ImoCalendarClient(builder, BASE);
    }

    @Test
    @DisplayName("a 200 application/pdf starting %PDF- returns the bytes, asking for a PDF")
    void ok() {
        server.expect(requestTo(URL_2026))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_PDF_VALUE))
                .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));

        ImoCalendarClient.Fetched fetched = client.fetch(2026);

        assertThat(fetched.found()).isTrue();
        assertThat(fetched.httpStatus()).isEqualTo(200);
        assertThat(fetched.url()).isEqualTo(URL_2026);
        assertThat(fetched.pdf()).isEqualTo(PDF);
        server.verify();
    }

    @Test
    @DisplayName("a 404 is a result with no body, not an error")
    void notFound() {
        server.expect(requestTo(BASE + "cal2028.pdf")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        ImoCalendarClient.Fetched fetched = client.fetch(2028);

        assertThat(fetched.found()).isFalse();
        assertThat(fetched.httpStatus()).isEqualTo(404);
        assertThat(fetched.pdf()).isNull();
    }

    @Test
    @DisplayName("a 403 and a 500 throw, carrying the status")
    void refusedStatuses() {
        server.expect(requestTo(URL_2026)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(URL_2026)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .extracting(e -> ((ImoCalendarException) e).getHttpStatus()).isEqualTo(403);
        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .extracting(e -> ((ImoCalendarException) e).getHttpStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("a body that is not application/pdf, or has no content type, is refused")
    void wrongContentType() {
        server.expect(requestTo(URL_2026)).andRespond(withSuccess(PDF, MediaType.TEXT_HTML));
        server.expect(requestTo(URL_2026)).andRespond(withSuccess(PDF, null));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("not a PDF");
        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("not a PDF");
    }

    @Test
    @DisplayName("a Content-Length over 10 MB is refused before the body is read")
    void declaredOversize() {
        server.expect(requestTo(URL_2026)).andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(ImoCalendarClient.MAX_BYTES + 1L)));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("a body that runs past 10 MB with no Content-Length is refused after the capped read")
    void actualOversize() {
        byte[] big = new byte[ImoCalendarClient.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        server.expect(requestTo(URL_2026)).andRespond(withSuccess(big, MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("exactly 10 MB is accepted")
    void exactlyTheCap() {
        byte[] edge = new byte[ImoCalendarClient.MAX_BYTES];
        System.arraycopy(PDF, 0, edge, 0, PDF.length);
        server.expect(requestTo(URL_2026)).andRespond(withSuccess(edge, MediaType.APPLICATION_PDF));

        assertThat(client.fetch(2026).pdf()).hasSize(ImoCalendarClient.MAX_BYTES);
    }

    @Test
    @DisplayName("a body that does not start with %PDF-, or is shorter than the marker, is refused")
    void notAPdf() {
        server.expect(requestTo(URL_2026)).andRespond(withSuccess("<html>nope</html>".getBytes(
                StandardCharsets.US_ASCII), MediaType.APPLICATION_PDF));
        server.expect(requestTo(URL_2026)).andRespond(withSuccess("%PD".getBytes(StandardCharsets.US_ASCII),
                MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("%PDF-");
        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("%PDF-");
    }

    @Test
    @DisplayName("a transport error becomes an ImoCalendarException with no status")
    void transportError() {
        server.expect(requestTo(URL_2026)).andRespond(withException(new IOException("reset")));

        assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(ImoCalendarException.class)
                .hasMessageContaining("could not be fetched")
                .extracting(e -> ((ImoCalendarException) e).getHttpStatus()).isNull();
    }

    @Test
    @DisplayName("the base URL gets its missing trailing slash, and a non-https base URL is refused")
    void urlRules() {
        ImoCalendarClient noSlash = new ImoCalendarClient(RestClient.builder(),
                "https://www.imo.net/files/meteor-shower");
        ImoCalendarClient plain = new ImoCalendarClient(RestClient.builder(), "http://www.imo.net/x/");

        assertThat(noSlash.urlFor(2027)).isEqualTo(BASE + "cal2027.pdf");
        assertThatThrownBy(() -> plain.urlFor(2027)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("the Spring constructor builds from an injected builder")
    void springConstructor() {
        ImoCalendarClient built = new ImoCalendarClient(RestClient.builder(), BASE);

        assertThat(built.urlFor(2026)).isEqualTo(URL_2026);
    }
}
