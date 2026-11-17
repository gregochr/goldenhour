package com.gregochr.goldenhour.config;

import com.sun.net.httpserver.HttpServer;
import com.gregochr.goldenhour.client.MetOfficeWarningClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarningsProperties} and the {@link MetOfficeWarningClient} bean {@link AppConfig} builds from
 * it. The client is exercised against a loopback server; the live Met Office is never called.
 */
class WarningsPropertiesTest {

    @Test
    @DisplayName("defaults: off, no URL, no key, 30 minutes to stale")
    void defaults() {
        WarningsProperties properties = new WarningsProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isEmpty();
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getPollStaleMinutes()).isEqualTo(30);
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
    @DisplayName("the client sends the key as x-api-key and reads the body at the given absolute URI")
    void clientSendsKeyHeader() throws Exception {
        AtomicReference<String> seenKey = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            seenPath.set(exchange.getRequestURI().getPath());
            byte[] body = "<feed/>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey("test-key-123");
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            String body = client.getFeed(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed"));

            assertThat(body).isEqualTo("<feed/>");
            assertThat(seenKey.get()).isEqualTo("test-key-123");
            assertThat(seenPath.get()).isEqualTo("/feed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("with a blank key no x-api-key header is sent, and a detail read also works")
    void clientOmitsBlankKey() throws Exception {
        AtomicReference<String> seenKey = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/geo+json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey(null);
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            String body = client.getWarning(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/w/1"));

            assertThat(body).isEqualTo("{}");
            assertThat(seenKey.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a whitespace-only key is treated as no key")
    void clientOmitsWhitespaceKey() throws Exception {
        AtomicReference<String> seenKey = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            WarningsProperties properties = new WarningsProperties();
            properties.setApiKey("   ");
            MetOfficeWarningClient client = new AppConfig().metOfficeWarningClient(properties);

            client.getFeed(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed"));

            assertThat(seenKey.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
