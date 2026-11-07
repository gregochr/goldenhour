package com.gregochr.goldenhour.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fetches the International Meteor Organization's yearly Meteor Shower Calendar PDF
 * ({@code cal{year}.pdf}).
 *
 * <p>Built from an injected {@link RestClient.Builder}, which in the application carries the shared connect and read
 * timeouts (10 s / 30 s, {@code AppConfig#restClientBuilder}). The body is bounded: a {@code Content-Length} above
 * {@link #MAX_BYTES} is refused, the read stops one byte past the cap, the content type must be compatible with
 * {@code application/pdf} and the body must start with {@code %PDF-}. A 404 is the one status that is a result
 * rather than an error (the caller decides whether it means "not published yet"); every other non-200 status, and
 * every refused body, throws {@link ImoCalendarException}.
 */
@Component
public class ImoCalendarClient {

    /** The most bytes of PDF accepted. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private static final int OK = 200;

    private static final int NOT_FOUND = 404;

    private final RestClient restClient;
    private final String baseUrl;

    /**
     * Constructs the client.
     *
     * @param builder the injected builder (the application's carries the shared connect and read timeouts)
     * @param baseUrl the folder the {@code cal{year}.pdf} files live in ({@code photocast.meteor.imo.base-url})
     */
    public ImoCalendarClient(RestClient.Builder builder,
            @Value("${photocast.meteor.imo.base-url:https://www.imo.net/files/meteor-shower/}") String baseUrl) {
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
    }

    /**
     * What a fetch returned.
     *
     * @param url        the URL fetched
     * @param httpStatus the HTTP status (200, or 404 when there is no body)
     * @param pdf        the PDF bytes, or null for a 404
     */
    public record Fetched(String url, int httpStatus, byte[] pdf) {

        /**
         * Whether the calendar was found.
         *
         * @return true for a 200 with a body
         */
        public boolean found() {
            return pdf != null;
        }
    }

    /**
     * The URL of a year's calendar.
     *
     * @param year the calendar year
     * @return {@code <base-url>/cal<year>.pdf}
     * @throws IllegalStateException if the configured base URL is not an https URL
     */
    public String urlFor(int year) {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        URI uri = UriComponentsBuilder.fromUriString(base).path("cal" + year + ".pdf").build().toUri();
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("photocast.meteor.imo.base-url must be https: " + base);
        }
        return uri.toString();
    }

    /**
     * Fetches a year's calendar PDF.
     *
     * @param year the calendar year
     * @return the PDF, or a {@link Fetched} with status 404 and no body when it is not published
     * @throws ImoCalendarException on a transport error, any other status, an oversize body, a wrong content
     *                              type or a body that is not a PDF
     */
    public Fetched fetch(int year) {
        String url = urlFor(year);
        try {
            return restClient.get()
                    .uri(URI.create(url))
                    .accept(MediaType.APPLICATION_PDF)
                    .exchange((request, response) -> read(url, response));
        } catch (ImoCalendarException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ImoCalendarException(url + " could not be fetched: " + e.getMessage(), null, e);
        }
    }

    private static Fetched read(String url, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == NOT_FOUND) {
            return new Fetched(url, NOT_FOUND, null);
        }
        if (status.value() != OK) {
            throw new ImoCalendarException(url + " answered HTTP " + status.value(), status.value(), null);
        }
        MediaType type = response.getHeaders().getContentType();
        if (type == null || !type.isCompatibleWith(MediaType.APPLICATION_PDF)) {
            throw new ImoCalendarException(url + " is not a PDF (content type " + type + ")", OK, null);
        }
        long declared = response.getHeaders().getContentLength();
        if (declared > MAX_BYTES) {
            throw new ImoCalendarException(url + " is larger than " + MAX_BYTES + " bytes", OK, null);
        }
        byte[] body;
        try (InputStream in = response.getBody()) {
            body = in.readNBytes(MAX_BYTES + 1);
        }
        if (body.length > MAX_BYTES) {
            throw new ImoCalendarException(url + " is larger than " + MAX_BYTES + " bytes", OK, null);
        }
        if (body.length < PDF_MAGIC.length || !Arrays.equals(Arrays.copyOf(body, PDF_MAGIC.length), PDF_MAGIC)) {
            throw new ImoCalendarException(url + " does not start with %PDF-", OK, null);
        }
        return new Fetched(url, OK, body);
    }
}
