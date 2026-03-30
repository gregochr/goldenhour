package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.StatusResponse;
import com.gregochr.goldenhour.model.StatusResponse.SessionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.GitProperties;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatusController#buildStatus(SessionInfo)}.
 */
@ExtendWith(MockitoExtension.class)
class StatusControllerTest {

    @Mock private HealthEndpoint healthEndpoint;

    private static final SessionInfo TEST_SESSION = new SessionInfo("admin", "ADMIN");

    @Nested
    @DisplayName("buildStatus")
    class BuildStatusTests {

        @Test
        @DisplayName("UP when all components are healthy")
        void upWhenAllHealthy() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor anthropicSvc = indicated(Status.UP, "CLOSED");
            HealthDescriptor openMeteoSvc = indicated(Status.UP, null);
            HealthDescriptor cbHealth = compositeOf(
                    Map.of("anthropic", anthropicSvc, "open-meteo", openMeteoSvc));
            HealthDescriptor root = compositeOf(
                    Map.of("db", dbHealth, "circuitBreakers", cbHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("UP");
            assertThat(response.degraded()).isEmpty();
            assertThat(response.database().status()).isEqualTo("UP");
            assertThat(response.services()).containsKeys("anthropic", "open-meteo");
            assertThat(response.services().get("anthropic").detail()).isEqualTo("CLOSED");
            assertThat(response.session().username()).isEqualTo("admin");
        }

        @Test
        @DisplayName("DOWN when a hard component is down")
        void downWhenHardComponentDown() {
            HealthDescriptor dbHealth = indicated(Status.DOWN, null);
            HealthDescriptor root = compositeOf(Map.of("db", dbHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("DOWN");
            assertThat(response.database().status()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("DEGRADED when only soft component is down")
        void degradedWhenSoftDown() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor mailHealth = indicated(Status.DOWN, null);
            HealthDescriptor root = compositeOf(Map.of("db", dbHealth, "mail", mailHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("DEGRADED");
            assertThat(response.degraded()).containsExactly("mail");
        }

        @Test
        @DisplayName("rateLimiters UNKNOWN status is ignored")
        void rateLimitersUnknownIgnored() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor rlHealth = indicated(Status.UNKNOWN, null);
            HealthDescriptor root = compositeOf(
                    Map.of("db", dbHealth, "rateLimiters", rlHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("UP");
        }

        @Test
        @DisplayName("Build info populated from GitProperties")
        void buildInfoFromGitProperties() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Properties props = new Properties();
            props.setProperty("commit.id.abbrev", "abc1234");
            props.setProperty("branch", "main");
            props.setProperty("commit.time", "2026-03-29T10:00:00Z");
            props.setProperty("dirty", "false");
            GitProperties gitProperties = new GitProperties(props);

            StatusController controller = new StatusController(healthEndpoint, gitProperties);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.build().commitId()).isEqualTo("abc1234");
            assertThat(response.build().branch()).isEqualTo("main");
            assertThat(response.build().dirty()).isFalse();
        }

        @Test
        @DisplayName("Null GitProperties produces empty build info")
        void nullGitProperties() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.build().commitId()).isNull();
        }
    }

    // ── Helpers ──

    /**
     * Creates an {@link IndicatedHealthDescriptor} mock with the given status and optional
     * state detail.
     */
    private static HealthDescriptor indicated(Status status, String stateDetail) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        lenient().when(descriptor.getStatus()).thenReturn(status);
        if (stateDetail != null) {
            lenient().when(descriptor.getDetails()).thenReturn(Map.of("state", stateDetail));
        } else {
            lenient().when(descriptor.getDetails()).thenReturn(Map.of());
        }
        return descriptor;
    }

    /**
     * Creates a {@link CompositeHealthDescriptor} mock with the given components.
     */
    private static CompositeHealthDescriptor compositeOf(
            Map<String, HealthDescriptor> components) {
        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        lenient().when(composite.getStatus()).thenReturn(Status.UP);
        when(composite.getComponents()).thenReturn(components);
        return composite;
    }
}
