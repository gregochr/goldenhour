package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.client.GitHubActionsClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link GitHubActionsConfig} bean: it sends the configured token as the Bearer credential, and builds
 * (with a placeholder credential no request will use) when no token is set.
 */
class GitHubActionsConfigTest {

    private HttpServer server;
    private final CopyOnWriteArrayList<String> authorization = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            authorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CiSpendProperties properties(String token) {
        CiSpendProperties p = new CiSpendProperties();
        p.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        p.setGithubToken(token);
        return p;
    }

    @Test
    @DisplayName("the bean sends the configured token as a Bearer credential")
    void beanSendsTheToken() {
        GitHubActionsClient client = new GitHubActionsConfig().gitHubActionsClient(properties("real-token-1"));

        assertThat(client.listCommitPulls("o", "r", "sha")).isEqualTo("[]");
        assertThat(authorization).containsExactly("Bearer real-token-1");
    }

    @Test
    @DisplayName("with no token the bean still builds, with a placeholder credential")
    void beanBuildsWithoutAToken() {
        GitHubActionsClient client = new GitHubActionsConfig().gitHubActionsClient(properties(""));

        assertThat(client.listCommitPulls("o", "r", "sha")).isEqualTo("[]");
        assertThat(authorization).containsExactly("Bearer unset");
    }
}
