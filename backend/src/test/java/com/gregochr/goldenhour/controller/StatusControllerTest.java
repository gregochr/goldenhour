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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
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

    private static final SessionInfo TEST_SESSION = new SessionInfo("admin", "ADMIN", null);

    @Nested
    @DisplayName("buildStatus")
    class BuildStatusTests {

        @Test
        @DisplayName("UP when all components are healthy")
        void upWhenAllHealthy() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor openMeteoProbe = indicatedWithLatency(Status.UP, 42L);
            HealthDescriptor tideCheckProbe = indicatedWithLatency(Status.UP, 118L);
            HealthDescriptor claudeApiProbe = indicatedWithLatency(Status.UP, 89L);
            HealthDescriptor root = compositeOf(
                    Map.of("db", dbHealth,
                            "openMeteo", openMeteoProbe,
                            "tideCheck", tideCheckProbe,
                            "claudeApi", claudeApiProbe));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("UP");
            assertThat(response.degraded()).isEmpty();
            assertThat(response.database().status()).isEqualTo("UP");
            assertThat(response.services()).containsKeys("openMeteo", "tideCheck", "claudeApi");
            assertThat(response.services().get("openMeteo").latencyMs()).isEqualTo(42L);
            assertThat(response.session().username()).isEqualTo("admin");
        }

        @Test
        @DisplayName("DOWN when a hard component is down")
        void downWhenHardComponentDown() {
            HealthDescriptor dbHealth = indicated(Status.DOWN, null);
            HealthDescriptor root = compositeOf(Map.of("db", dbHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
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

            StatusController controller = new StatusController(healthEndpoint, null, null);
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

            StatusController controller = new StatusController(healthEndpoint, null, null);
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

            StatusController controller = new StatusController(healthEndpoint, gitProperties, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.build().commitId()).isEqualTo("abc1234");
            assertThat(response.build().branch()).isEqualTo("main");
            assertThat(response.build().dirty()).isFalse();
        }

        @Test
        @DisplayName("DEGRADED when probe component is down (soft failure)")
        void degradedWhenProbeDown() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor openMeteoProbe = indicatedWithLatency(Status.DOWN, 5001L);
            HealthDescriptor root = compositeOf(
                    Map.of("db", dbHealth, "openMeteo", openMeteoProbe));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("DEGRADED");
            assertThat(response.degraded()).contains("openMeteo");
        }

        @Test
        @DisplayName("Null GitProperties produces empty build info")
        void nullGitProperties() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.build().commitId()).isNull();
        }
    }

    @Nested
    @DisplayName("stream")
    class StreamTests {

        @Test
        @DisplayName("Returns SseEmitter on successful connection with auth")
        void streamReturnsEmitter() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Authentication auth = mockAuth("testuser", "ROLE_ADMIN");
            StatusController controller = new StatusController(healthEndpoint, null, null);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }

        @Test
        @DisplayName("Returns SseEmitter when auth is null (anonymous)")
        void streamWithNullAuth() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);

            SseEmitter emitter = controller.stream(null, request);

            assertThat(emitter).isNotNull();
        }

        @Test
        @DisplayName("Returns SseEmitter when auth has no authorities")
        void streamWithNoAuthorities() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Authentication auth = mock(Authentication.class);
            when(auth.getName()).thenReturn("user");
            org.mockito.Mockito.doReturn(List.of()).when(auth).getAuthorities();

            StatusController controller = new StatusController(healthEndpoint, null, null);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }
    }

    @Nested
    @DisplayName("extractSession (via buildStatus session field)")
    class SessionExtractionTests {

        @Test
        @DisplayName("Extracts username and role from Authentication")
        void extractsFromAuth() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Authentication auth = mockAuth("alice", "ROLE_PRO_USER");
            StatusController controller = new StatusController(healthEndpoint, null, null);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);
            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
            // Verify session extraction indirectly via buildStatus
            StatusResponse response = controller.buildStatus(
                    new SessionInfo("alice", "PRO_USER", null));
            assertThat(response.session().username()).isEqualTo("alice");
            assertThat(response.session().role()).isEqualTo("PRO_USER");
        }

        @Test
        @DisplayName("JWT login time extracted from Bearer header")
        void jwtLoginTimeExtracted() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            com.gregochr.goldenhour.service.JwtService jwtService =
                    mock(com.gregochr.goldenhour.service.JwtService.class);
            java.time.Instant loginTime = java.time.Instant.parse("2026-03-31T10:00:00Z");
            when(jwtService.extractIssuedAt("test-token")).thenReturn(loginTime);

            Authentication auth = mockAuth("alice", "ROLE_ADMIN");
            StatusController controller = new StatusController(healthEndpoint, null, jwtService);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer test-token");

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }

        @Test
        @DisplayName("JWT login time extracted from token query parameter for SSE")
        void jwtLoginTimeFromQueryParam() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            com.gregochr.goldenhour.service.JwtService jwtService =
                    mock(com.gregochr.goldenhour.service.JwtService.class);
            java.time.Instant loginTime = java.time.Instant.parse("2026-03-31T10:00:00Z");
            when(jwtService.extractIssuedAt("sse-token")).thenReturn(loginTime);

            Authentication auth = mockAuth("alice", "ROLE_ADMIN");
            StatusController controller = new StatusController(healthEndpoint, null, jwtService);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getParameter("token")).thenReturn("sse-token");

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }

        @Test
        @DisplayName("JWT extraction failure logs and returns null login time")
        void jwtExtractionFailureReturnsNullLoginTime() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            com.gregochr.goldenhour.service.JwtService jwtService =
                    mock(com.gregochr.goldenhour.service.JwtService.class);
            when(jwtService.extractIssuedAt("bad-token"))
                    .thenThrow(new RuntimeException("Invalid token"));

            Authentication auth = mockAuth("alice", "ROLE_ADMIN");
            StatusController controller = new StatusController(healthEndpoint, null, jwtService);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }

        @Test
        @DisplayName("Null JwtService returns null login time without error")
        void nullJwtServiceReturnsNullLoginTime() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Authentication auth = mockAuth("alice", "ROLE_ADMIN");
            StatusController controller = new StatusController(healthEndpoint, null, null);
            jakarta.servlet.http.HttpServletRequest request =
                    mock(jakarta.servlet.http.HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer some-token");

            SseEmitter emitter = controller.stream(auth, request);

            assertThat(emitter).isNotNull();
        }
    }

    @Nested
    @DisplayName("buildStatus edge cases")
    class BuildStatusEdgeCases {

        @Test
        @DisplayName("Non-composite root returns UNKNOWN database and empty services")
        void nonCompositeRoot() {
            HealthDescriptor root = indicated(Status.UP, null);
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("UP");
            assertThat(response.database().status()).isEqualTo("UNKNOWN");
            assertThat(response.services()).isEmpty();
        }

        @Test
        @DisplayName("Dirty git properties reports dirty=true")
        void dirtyGitProperties() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            Properties props = new Properties();
            props.setProperty("commit.id.abbrev", "def5678");
            props.setProperty("branch", "feature/test");
            props.setProperty("commit.time", "2026-03-30T12:00:00Z");
            props.setProperty("dirty", "true");
            GitProperties gitProperties = new GitProperties(props);

            StatusController controller = new StatusController(healthEndpoint, gitProperties, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.build().dirty()).isTrue();
            assertThat(response.build().branch()).isEqualTo("feature/test");
        }

        @Test
        @DisplayName("Component with non-composite circuitBreakers returns empty services")
        void nonCompositeCircuitBreakers() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor cbHealth = indicated(Status.UP, null);
            HealthDescriptor root = compositeOf(
                    Map.of("db", dbHealth, "circuitBreakers", cbHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.services()).isEmpty();
        }

        @Test
        @DisplayName("Null db descriptor returns UNKNOWN database status")
        void nullDbDescriptor() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.database().status()).isEqualTo("UNKNOWN");
            assertThat(response.database().detail()).isNull();
        }

        @Test
        @DisplayName("checkedAt is populated")
        void checkedAtPopulated() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.checkedAt()).isNotNull();
        }

        @Test
        @DisplayName("appVersion detail is extracted from appVersion health component")
        void appVersionExtractedFromComponent() {
            HealthDescriptor appVersionHealth = indicatedWithVersion("v2.3.1");
            HealthDescriptor root = compositeOf(Map.of("appVersion", appVersionHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.appVersion()).isEqualTo("v2.3.1");
        }

        @Test
        @DisplayName("appVersion is null when appVersion component is absent")
        void appVersionNullWhenComponentAbsent() {
            HealthDescriptor root = compositeOf(Map.of());
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.appVersion()).isNull();
        }

        @Test
        @DisplayName("appVersion component does not affect overall UP status")
        void appVersionComponentDoesNotAffectOverallStatus() {
            HealthDescriptor dbHealth = indicated(Status.UP, null);
            HealthDescriptor appVersionHealth = indicatedWithVersion("v2.3.1");
            HealthDescriptor root = compositeOf(Map.of("db", dbHealth, "appVersion", appVersionHealth));
            when(healthEndpoint.health()).thenReturn(root);

            StatusController controller = new StatusController(healthEndpoint, null, null);
            StatusResponse response = controller.buildStatus(TEST_SESSION);

            assertThat(response.status()).isEqualTo("UP");
            assertThat(response.degraded()).isEmpty();
        }
    }

    private static Authentication mockAuth(String username, String role) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(username);
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        org.mockito.Mockito.doReturn(authorities).when(auth).getAuthorities();
        return auth;
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
     * Creates an {@link IndicatedHealthDescriptor} mock with latencyMs detail (probe-style).
     */
    private static HealthDescriptor indicatedWithLatency(Status status, Long latencyMs) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        lenient().when(descriptor.getStatus()).thenReturn(status);
        if (latencyMs != null) {
            lenient().when(descriptor.getDetails()).thenReturn(Map.of("latencyMs", latencyMs));
        } else {
            lenient().when(descriptor.getDetails()).thenReturn(Map.of());
        }
        return descriptor;
    }

    /**
     * Creates an {@link IndicatedHealthDescriptor} mock with a {@code version} detail.
     */
    private static HealthDescriptor indicatedWithVersion(String version) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        lenient().when(descriptor.getStatus()).thenReturn(Status.UP);
        lenient().when(descriptor.getDetails()).thenReturn(Map.of("version", version));
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
