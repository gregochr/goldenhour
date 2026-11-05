package com.gregochr.goldenhour.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.gregochr.goldenhour.model.StatusResponse;
import com.gregochr.goldenhour.model.StatusResponse.SessionInfo;
import com.gregochr.goldenhour.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The lifecycle and the odd-shaped-health paths of {@link StatusController}: an emitter that closes, times out or
 * errors stops being broadcast to, an unwritable first event is reported, and component descriptors with no
 * details, no state or no version are read without failing.
 */
@ExtendWith(MockitoExtension.class)
class StatusControllerBranchesTest {

    private static final SessionInfo SESSION = new SessionInfo("admin", "ADMIN", null);

    @Mock
    private HealthEndpoint healthEndpoint;

    private static Authentication auth(String username) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(username);
        org.mockito.Mockito.doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(auth).getAuthorities();
        return auth;
    }

    private void stubHealth(CompositeHealthDescriptor root) {
        when(healthEndpoint.health()).thenReturn(root);
    }

    private static CompositeHealthDescriptor compositeOf(Map<String, HealthDescriptor> components) {
        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        lenient().when(composite.getStatus()).thenReturn(Status.UP);
        when(composite.getComponents()).thenReturn(components);
        return composite;
    }

    /** A composite used as a component: it is a descriptor with no {@code details} of its own. */
    private static CompositeHealthDescriptor nestedComposite() {
        CompositeHealthDescriptor nested = mock(CompositeHealthDescriptor.class);
        lenient().when(nested.getStatus()).thenReturn(Status.UP);
        return nested;
    }

    private static IndicatedHealthDescriptor indicated(Status status, Map<String, Object> details) {
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        lenient().when(descriptor.getStatus()).thenReturn(status);
        lenient().when(descriptor.getDetails()).thenReturn(details);
        return descriptor;
    }

    // ── emitter lifecycle ────────────────────────────────────────────────────

    /** Opens one stream, fires the chosen lifecycle callback, and reports whether a broadcast still reaches it. */
    private boolean broadcastReachesEmitterAfter(Consumer<SseEmitter> fireCallback) throws IOException {
        stubHealth(compositeOf(Map.of()));
        StatusController controller = new StatusController(healthEndpoint, null, null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            controller.stream(auth("alice"), request);
            SseEmitter emitter = mocked.constructed().get(0);
            clearInvocations(healthEndpoint);

            fireCallback.accept(emitter);
            controller.broadcastStatus();

            return !org.mockito.Mockito.mockingDetails(healthEndpoint).getInvocations().isEmpty();
        }
    }

    private static void runCaptured(SseEmitter emitter, String callback) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        if ("completion".equals(callback)) {
            verify(emitter).onCompletion(captor.capture());
        } else {
            verify(emitter).onTimeout(captor.capture());
        }
        assertThat(captor.getValue()).isNotNull();
        captor.getValue().run();
    }

    @Test
    @DisplayName("a stream that completes is dropped: the next broadcast evaluates no health and pushes nothing")
    void completedEmitter_isNoLongerBroadcastTo() throws IOException {
        assertThat(broadcastReachesEmitterAfter(e -> runCaptured(e, "completion"))).isFalse();
    }

    @Test
    @DisplayName("a stream that times out is dropped: the next broadcast evaluates no health and pushes nothing")
    void timedOutEmitter_isNoLongerBroadcastTo() throws IOException {
        assertThat(broadcastReachesEmitterAfter(e -> runCaptured(e, "timeout"))).isFalse();
    }

    @Test
    @DisplayName("a stream that errors is dropped: the next broadcast evaluates no health and pushes nothing")
    void erroredEmitter_isNoLongerBroadcastTo() throws IOException {
        assertThat(broadcastReachesEmitterAfter(e -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
            verify(e).onError(captor.capture());
            assertThat(captor.getValue()).isNotNull();
            captor.getValue().accept(new IOException("connection reset"));
        })).isFalse();
    }

    @Test
    @DisplayName("without any callback firing, the same broadcast does reach the open stream (control)")
    void openEmitter_isBroadcastTo() throws IOException {
        assertThat(broadcastReachesEmitterAfter(e -> { })).isTrue();
    }

    @Test
    @DisplayName("a broadcast with nobody connected evaluates no health at all")
    void broadcastWithNoEmitters_doesNothing() {
        StatusController controller = new StatusController(healthEndpoint, null, null);

        controller.broadcastStatus();

        verifyNoInteractions(healthEndpoint);
    }

    @Test
    @DisplayName("an initial event that cannot be written ends the stream with the error and registers no emitter")
    void unwritableFirstEvent_completesWithError() throws IOException {
        stubHealth(compositeOf(Map.of()));
        StatusController controller = new StatusController(healthEndpoint, null, null);
        IOException closed = new IOException("closed before first event");
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class,
                withSettings().defaultAnswer(invocation -> {
                    if ("send".equals(invocation.getMethod().getName())) {
                        throw closed;
                    }
                    return null;
                }))) {
            SseEmitter returned = controller.stream(auth("alice"), mock(HttpServletRequest.class));

            SseEmitter emitter = mocked.constructed().get(0);
            assertThat(returned).isSameAs(emitter);
            verify(emitter).completeWithError(closed);
            verify(emitter, never()).onCompletion(org.mockito.ArgumentMatchers.<Runnable>any());
            clearInvocations(healthEndpoint);
            controller.broadcastStatus();
            verify(healthEndpoint, never()).health();
        }
    }

    @Test
    @DisplayName("a second connection reuses the broadcast schedule rather than starting another")
    void secondConnection_reusesSchedule() {
        stubHealth(compositeOf(Map.of()));
        StatusController controller = new StatusController(healthEndpoint, null, null);
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            controller.stream(auth("alice"), mock(HttpServletRequest.class));
            controller.stream(auth("bob"), mock(HttpServletRequest.class));
            clearInvocations(healthEndpoint);

            controller.broadcastStatus();

            verify(healthEndpoint, times(1)).health();
            assertThat(mocked.constructed()).hasSize(2);
        }
    }

    // ── odd-shaped health ────────────────────────────────────────────────────

    @Test
    @DisplayName("an UNKNOWN hard component does not take the service down or degrade it")
    void unknownHardComponent_isNotDown() {
        stubHealth(compositeOf(Map.of("db", indicated(Status.UNKNOWN, Map.of()))));

        StatusResponse response = new StatusController(healthEndpoint, null, null).buildStatus(SESSION);

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.database().status()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("a component's state detail is carried as its detail text")
    void stateDetail_isCarried() {
        stubHealth(compositeOf(Map.of(
                "db", indicated(Status.UP, Map.of("state", "CLOSED")))));

        StatusResponse response = new StatusController(healthEndpoint, null, null).buildStatus(SESSION);

        assertThat(response.database().detail()).isEqualTo("CLOSED");
        assertThat(response.database().latencyMs()).isNull();
    }

    @Test
    @DisplayName("a component with no details, or a nested group instead of an indicated one, reads as bare status")
    void componentWithoutDetails_readsAsBareStatus() {
        HealthDescriptor plain = nestedComposite();
        stubHealth(compositeOf(Map.of(
                "db", indicated(Status.UP, null),
                "openMeteo", plain)));

        StatusResponse response = new StatusController(healthEndpoint, null, null).buildStatus(SESSION);

        assertThat(response.database().status()).isEqualTo("UP");
        assertThat(response.database().detail()).isNull();
        assertThat(response.database().latencyMs()).isNull();
        assertThat(response.services().get("openMeteo").status()).isEqualTo("UP");
        assertThat(response.services().get("openMeteo").detail()).isNull();
    }

    @Test
    @DisplayName("the app version is null when its descriptor has no details, no version key, or is not indicated")
    void appVersion_nullForOddDescriptors() {
        HealthDescriptor plain = nestedComposite();

        stubHealth(compositeOf(Map.of("appVersion", indicated(Status.UP, null))));
        assertThat(new StatusController(healthEndpoint, null, null).buildStatus(SESSION).appVersion()).isNull();

        stubHealth(compositeOf(Map.of(
                "appVersion", indicated(Status.UP, Map.of("build", "x")))));
        assertThat(new StatusController(healthEndpoint, null, null).buildStatus(SESSION).appVersion()).isNull();

        stubHealth(compositeOf(Map.of("appVersion", plain)));
        assertThat(new StatusController(healthEndpoint, null, null).buildStatus(SESSION).appVersion()).isNull();
    }

    @Test
    @DisplayName("a non-Bearer Authorization header yields no login time and never consults the JWT service")
    void nonBearerAuthorization_hasNoLoginTime() {
        stubHealth(compositeOf(Map.of()));
        JwtService jwtService = mock(JwtService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Basic YWxpY2U6cGFzcw==");
        StatusController controller = new StatusController(healthEndpoint, null, jwtService);

        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            controller.stream(auth("alice"), request);

            assertThat(mocked.constructed()).hasSize(1);
        }
        verifyNoInteractions(jwtService);
    }
}
