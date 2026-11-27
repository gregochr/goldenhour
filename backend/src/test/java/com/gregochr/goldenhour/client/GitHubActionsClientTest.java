package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.GitHubActionsConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link GitHubActionsClient} and {@link GitHubArtifactDownloader} against a real local HTTP server (a
 * mock-server at the request-factory level is needed to see redirects): the artifact-zip call is <em>not</em>
 * followed, the token goes only to the API host, the blob request carries no Authorization header, and the
 * download stream is capped.
 */
class GitHubActionsClientTest {

    private static final String TOKEN = "github_pat_TESTTOKENVALUE1234567890";

    private HttpServer server;
    private String base;
    private final CopyOnWriteArrayList<String> zipAuthorization = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> blobAuthorization = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> apiRequests = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HttpHeaders> apiHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger blobHits = new AtomicInteger();
    private final AtomicInteger finalHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/o/r/actions/artifacts/7/zip", ex -> {
            zipAuthorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getResponseHeaders().add("Location", base + "/blob?sv=1&sig=SIGNATURE");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/blob", ex -> {
            blobHits.incrementAndGet();
            blobAuthorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            reply(ex, 200, "zip-bytes");
        });
        server.createContext("/blob-big", ex -> reply(ex, 200, "x".repeat(50)));
        server.createContext("/blob-gone", ex -> reply(ex, 404, "gone"));
        server.createContext("/blob-redirect", ex -> {
            ex.getResponseHeaders().add("Location", base + "/final");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/final", ex -> {
            finalHits.incrementAndGet();
            reply(ex, 200, "final");
        });
        server.createContext("/repos/o/r", ex -> {
            apiRequests.add(ex.getRequestURI().toString());
            HttpHeaders h = new HttpHeaders();
            ex.getRequestHeaders().forEach(h::addAll);
            apiHeaders.add(h);
            reply(ex, 200, "{\"ok\":true}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private GitHubArtifactDownloader downloader() {
        return new GitHubArtifactDownloader(
                RestClient.builder().requestFactory(GitHubActionsConfig.noRedirectRequestFactory()).build(), false);
    }

    @Test
    @DisplayName("the artifact-zip call returns the 302 and its Location, is not followed, and sends the Bearer token")
    void zipCallIsNotFollowed() {
        GitHubActionsClient client = GitHubActionsConfig.create(base + "/", TOKEN);

        ResponseEntity<Void> response = client.getArtifactZip("o", "r", 7L);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).startsWith(base + "/blob?");
        assertThat(zipAuthorization).containsExactly("Bearer " + TOKEN);
        assertThat(blobHits.get()).isZero();
    }

    @Test
    @DisplayName("the downloader fetches the signed URL with no Authorization header and returns the bytes")
    void blobRequestCarriesNoAuthorization() throws Exception {
        GitHubActionsClient client = GitHubActionsConfig.create(base, TOKEN);
        String location = client.getArtifactZip("o", "r", 7L).getHeaders().getFirst(HttpHeaders.LOCATION);

        byte[] bytes = downloader().download(location, 1024);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("zip-bytes");
        assertThat(blobHits.get()).isEqualTo(1);
        assertThat(blobAuthorization).containsExactly("null");
    }

    @Test
    @DisplayName("the downloader reads at most the cap, whatever the server sends")
    void downloadIsCapped() {
        assertThatThrownBy(() -> downloader().download(base + "/blob-big", 10))
                .isInstanceOf(ArtifactDownloadException.class).hasMessageContaining("exceeds 10 bytes");
    }

    @Test
    @DisplayName("a blob answer other than 200 surfaces as a response exception, and a redirect is not followed")
    void nonOkAndRedirectAreNotFollowed() {
        assertThatThrownBy(() -> downloader().download(base + "/blob-gone", 1024))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> downloader().download(base + "/blob-redirect", 1024))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(302));
        assertThat(finalHits.get()).isZero();
    }

    @Test
    @DisplayName("the production downloader refuses non-https, hostless and unparseable locations")
    void refusesUnusableLocations() {
        GitHubArtifactDownloader production = new GitHubArtifactDownloader();

        assertThatThrownBy(() -> production.download(base + "/blob", 1024))
                .isInstanceOf(ArtifactDownloadException.class).hasMessageContaining("not https");
        assertThatThrownBy(() -> production.download("/relative/path", 1024))
                .isInstanceOf(ArtifactDownloadException.class).hasMessageContaining("no host");
        assertThatThrownBy(() -> production.download("https://exa mple.test/a b", 1024))
                .isInstanceOf(ArtifactDownloadException.class).hasMessageContaining("not a valid URI");
        assertThatThrownBy(() -> production.download(null, 1024))
                .isInstanceOf(ArtifactDownloadException.class).hasMessageContaining("no host");
    }

    @Test
    @DisplayName("the production downloader accepts an https location (and a refused connection surfaces as such)")
    void httpsLocationIsAcceptedThenTransportFails() {
        GitHubArtifactDownloader production = new GitHubArtifactDownloader();

        assertThatThrownBy(() -> production.download("https://127.0.0.1:1/blob?sig=X", 1024))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("every API call carries Authorization, Accept and X-GitHub-Api-Version, and encodes its parameters")
    void apiCallsCarryTheStandardHeaders() {
        GitHubActionsClient client = GitHubActionsConfig.create(base, TOKEN);

        assertThat(client.listWorkflowRuns("o", "r", "prompt-regression.yml", ">=2025-02-28", 100, 2))
                .isEqualTo("{\"ok\":true}");
        client.getRunAttempt("o", "r", 5L, 2);
        client.listAttemptJobs("o", "r", 5L, 2, 100);
        client.listRunArtifacts("o", "r", 5L, "prompt-regression-usage-sky-2", 100);
        client.listCommitPulls("o", "r", "abc123");

        assertThat(apiRequests).hasSize(5);
        assertThat(apiRequests.get(0)).startsWith("/repos/o/r/actions/workflows/prompt-regression.yml/runs?created=")
                .contains("per_page=100").contains("page=2");
        assertThat(apiRequests.get(1)).isEqualTo("/repos/o/r/actions/runs/5/attempts/2");
        assertThat(apiRequests.get(2)).isEqualTo("/repos/o/r/actions/runs/5/attempts/2/jobs?per_page=100");
        assertThat(apiRequests.get(3)).isEqualTo(
                "/repos/o/r/actions/runs/5/artifacts?name=prompt-regression-usage-sky-2&per_page=100");
        assertThat(apiRequests.get(4)).isEqualTo("/repos/o/r/commits/abc123/pulls");
        for (HttpHeaders h : apiHeaders) {
            assertThat(h.getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(h.getFirst("Accept")).isEqualTo("application/vnd.github+json");
            assertThat(h.getFirst("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
        }
    }
}
