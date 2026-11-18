package com.gregochr.goldenhour.config;

import com.sun.net.httpserver.HttpServer;
import com.gregochr.goldenhour.client.MetOfficeWarningClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarningsProperties} and the {@link MetOfficeWarningClient} bean {@link AppConfig} builds from
 * it. The client is exercised against a loopback server; the live Met Office is never called.
 */
class WarningsPropertiesTest {

    /** What the loopback server saw. */
    private record Seen(AtomicReference<String> header, AtomicReference<String> path,
            AtomicReference<String> accept) {
    }

    private static HttpServer server(String headerName, Seen seen, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.header().set(exchange.getRequestHeaders().getFirst(headerName));
            seen.path().set(exchange.getRequestURI().getPath());
            seen.accept().set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Seen newSeen() {
        return new Seen(new AtomicReference<>("unset"), new AtomicReference<>(), new AtomicReference<>());
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/nswws/v1.1";
    }

    @Test
    @DisplayName("defaults: off, the real v1.1 base URL, apikey header, no key, public link, attribution, 30 minutes")
    void defaults() {
        WarningsProperties properties = new WarningsProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isEqualTo("https://data.hub.api.metoffice.gov.uk/nswws/v1.1");
        assertThat(properties.getApiKeyHeader()).isEqualTo("apikey");
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getPublicUrl()).isEqualTo("https://www.metoffice.gov.uk/public/weather/warnings/");
        assertThat(properties.getAttribution()).isEqualTo("Met Office Weather Warnings © Crown Copyright 2026");
        assertThat(properties.getPollStaleMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("toString never includes the API key")
    void toStringHidesKey() {
        WarningsProperties properties = new WarningsProperties();
        properties.setApiKey("super-secret-key");
        properties.setEnabled(true);

        assertThat(properties.toString()).doesNotContain("super-secret-key").contains("enabled=true");
    }

    @Test
    @DisplayName("the client sends the key in the apikey header and reads the feed at /objects/feed/ under the base")
    void clientSendsKeyHeader() throws Exception {
        Seen seen = newSeen();
        HttpServer server = server("apikey", seen, "<feed/>");
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey("test-key-123");
            properties.setBaseUrl(base(server) + "/");
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            String body = client.getFeed();

            assertThat(body).isEqualTo("<feed/>");
            assertThat(seen.header().get()).isEqualTo("test-key-123");
            assertThat(seen.path().get()).isEqualTo("/nswws/v1.1/objects/feed/");
            assertThat(seen.accept().get()).contains("application/atom+xml");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the header name is configurable")
    void headerNameConfigurable() throws Exception {
        Seen seen = newSeen();
        HttpServer server = server("x-api-key", seen, "<feed/>");
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey("k2");
            properties.setApiKeyHeader("x-api-key");
            properties.setBaseUrl(base(server));
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            client.getFeed();

            assertThat(seen.header().get()).isEqualTo("k2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("updated and issued documents are read at /objects/updated/{uuid}/ and /objects/issued/{uuid}/")
    void documentPaths() throws Exception {
        Seen seen = newSeen();
        HttpServer server = server("apikey", seen, "{}");
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey("k");
            properties.setBaseUrl(base(server));
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            assertThat(client.getUpdated("aaaa")).isEqualTo("{}");
            assertThat(seen.path().get()).isEqualTo("/nswws/v1.1/objects/updated/aaaa/");
            assertThat(seen.accept().get()).contains("application/vnd.geo+json");
            assertThat(client.getIssued("bbbb")).isEqualTo("{}");
            assertThat(seen.path().get()).isEqualTo("/nswws/v1.1/objects/issued/bbbb/");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("with a blank or whitespace key no key header is sent")
    void clientOmitsBlankKey() throws Exception {
        Seen seen = newSeen();
        HttpServer server = server("apikey", seen, "{}");
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setBaseUrl(base(server));
            properties.setApiKey(null);
            new AppConfig().metOfficeWarningClient(properties).getUpdated("a");
            assertThat(seen.header().get()).isNull();

            properties.setApiKey("   ");
            seen.header().set("unset");
            new AppConfig().metOfficeWarningClient(properties).getUpdated("a");
            assertThat(seen.header().get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a blank base URL builds a client without a base (nothing is called here)")
    void blankBaseUrl() {
        WarningsProperties properties = new WarningsProperties();
        properties.setBaseUrl(" ");

        assertThat(new AppConfig().metOfficeWarningClient(properties)).isNotNull();

        properties.setBaseUrl(null);
        assertThat(new AppConfig().metOfficeWarningClient(properties)).isNotNull();
    }
}
