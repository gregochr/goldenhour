package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.StatusResponse;
import com.gregochr.goldenhour.model.StatusResponse.BuildInfo;
import com.gregochr.goldenhour.model.StatusResponse.ComponentStatus;
import com.gregochr.goldenhour.model.StatusResponse.SessionInfo;
import com.gregochr.goldenhour.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE endpoint that pushes enriched application status to connected clients.
 *
 * <p>Each connected client receives a {@link StatusResponse} event immediately on connection
 * and every 30 seconds thereafter. Replaces the previous polling-based
 * {@code /actuator/health} approach with a push model.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final Logger LOG = LoggerFactory.getLogger(StatusController.class);

    /** Interval between status pushes to each connected client. */
    private static final long PUSH_INTERVAL_SECONDS = 30;

    /** SSE emitter timeout — 0 means no server-side timeout (browser reconnects on drop). */
    private static final long EMITTER_TIMEOUT_MS = 0L;

    /** Components whose failure is a soft warning (DEGRADED), not a hard failure (DOWN). */
    private static final Set<String> SOFT_COMPONENTS = Set.of("mail", "openMeteo", "tideCheck", "claudeApi");

    /**
     * Components to ignore when determining overall status.
     * Rate limiters report UNKNOWN when no calls have been made yet, which is normal.
     */
    private static final Set<String> IGNORED_COMPONENTS = Set.of("rateLimiters");

    /** Health indicator component names that represent external service probes. */
    private static final Set<String> PROBE_COMPONENTS = Set.of("openMeteo", "tideCheck", "claudeApi");

    private final HealthEndpoint healthEndpoint;
    private final GitProperties gitProperties;
    private final JwtService jwtService;
    private final ScheduledExecutorService scheduler;
    private final List<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    /**
     * Constructs a {@code StatusController}.
     *
     * @param healthEndpoint Spring Boot health endpoint bean
     * @param gitProperties  git metadata (null in dev if git.properties not generated)
     * @param jwtService     JWT service for parsing issued-at claims
     */
    public StatusController(HealthEndpoint healthEndpoint,
            GitProperties gitProperties,
            JwtService jwtService) {
        this.healthEndpoint = healthEndpoint;
        this.gitProperties = gitProperties;
        this.jwtService = jwtService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "status-sse-push");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * SSE stream that pushes the full {@link StatusResponse} on connection and every 30 seconds.
     *
     * @param auth    the authenticated principal (captured at connection time for session info)
     * @param request the HTTP request (used to extract JWT issued-at for login time)
     * @return the SSE emitter
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth, HttpServletRequest request) {
        SessionInfo session = extractSession(auth, request);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        // Send initial status immediately
        try {
            emitter.send(SseEmitter.event().name("status").data(buildStatus(session)));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // Schedule periodic pushes
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> pushSafely(emitter, session),
                PUSH_INTERVAL_SECONDS, PUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        EmitterEntry entry = new EmitterEntry(emitter, task);
        emitters.add(entry);

        emitter.onCompletion(() -> cleanup(entry));
        emitter.onTimeout(() -> cleanup(entry));
        emitter.onError(e -> cleanup(entry));

        LOG.info("SSE status stream opened for {} ({} active)", session.username(), emitters.size());
        return emitter;
    }

    private void pushSafely(SseEmitter emitter, SessionInfo session) {
        try {
            emitter.send(SseEmitter.event().name("status").data(buildStatus(session)));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void cleanup(EmitterEntry entry) {
        entry.task().cancel(false);
        emitters.remove(entry);
        LOG.debug("SSE status stream closed ({} remaining)", emitters.size());
    }

    /**
     * Assembles the full {@link StatusResponse} from health indicators, git metadata,
     * and session info.
     */
    StatusResponse buildStatus(SessionInfo session) {
        HealthDescriptor root = healthEndpoint.health();
        Map<String, HealthDescriptor> components = Map.of();
        if (root instanceof CompositeHealthDescriptor composite) {
            components = composite.getComponents();
        }

        // Database status
        ComponentStatus database = toComponentStatus(components.get("db"));

        // Circuit breaker sub-components (anthropic, open-meteo)
        Map<String, ComponentStatus> services = extractServices(components);

        // Determine overall status and degraded list
        List<String> degraded = new ArrayList<>();
        boolean hardDown = false;
        for (Map.Entry<String, HealthDescriptor> e : components.entrySet()) {
            String name = e.getKey();
            if (IGNORED_COMPONENTS.contains(name)) {
                continue;
            }
            String status = e.getValue().getStatus().getCode();
            if (!"UP".equals(status) && !"UNKNOWN".equals(status)) {
                if (SOFT_COMPONENTS.contains(name)) {
                    degraded.add(name);
                } else {
                    hardDown = true;
                }
            }
        }

        String overall = hardDown ? "DOWN" : !degraded.isEmpty() ? "DEGRADED" : "UP";

        return new StatusResponse(overall, List.copyOf(degraded), database,
                services, buildInfo(), session, Instant.now());
    }

    private Map<String, ComponentStatus> extractServices(Map<String, HealthDescriptor> components) {
        Map<String, ComponentStatus> services = new LinkedHashMap<>();
        for (String name : PROBE_COMPONENTS) {
            HealthDescriptor descriptor = components.get(name);
            if (descriptor != null) {
                services.put(name, toComponentStatus(descriptor));
            }
        }
        return services;
    }

    private ComponentStatus toComponentStatus(HealthDescriptor descriptor) {
        if (descriptor == null) {
            return new ComponentStatus("UNKNOWN", null, null);
        }
        String detail = null;
        Long latencyMs = null;
        if (descriptor instanceof IndicatedHealthDescriptor indicated) {
            Map<String, Object> details = indicated.getDetails();
            if (details != null) {
                if (details.containsKey("state")) {
                    detail = String.valueOf(details.get("state"));
                }
                if (details.containsKey("latencyMs")) {
                    latencyMs = ((Number) details.get("latencyMs")).longValue();
                }
            }
        }
        return new ComponentStatus(descriptor.getStatus().getCode(), detail, latencyMs);
    }

    private BuildInfo buildInfo() {
        if (gitProperties == null) {
            return new BuildInfo(null, null, null, false);
        }
        return new BuildInfo(
                gitProperties.getShortCommitId(),
                gitProperties.getBranch(),
                gitProperties.get("commit.time"),
                "true".equals(gitProperties.get("dirty")));
    }

    private SessionInfo extractSession(Authentication auth, HttpServletRequest request) {
        String username = auth != null ? auth.getName() : "anonymous";
        String role = auth != null
                ? auth.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replace("ROLE_", ""))
                .orElse("UNKNOWN")
                : "UNKNOWN";
        Instant loginTime = extractLoginTime(request);
        return new SessionInfo(username, role, loginTime);
    }

    /**
     * Extracts the issued-at time from the JWT token in the request.
     * Falls back to the {@code token} query parameter (used by SSE connections).
     */
    private Instant extractLoginTime(HttpServletRequest request) {
        try {
            String token = null;
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
            if (token == null) {
                token = request.getParameter("token");
            }
            if (token != null && jwtService != null) {
                return jwtService.extractIssuedAt(token);
            }
        } catch (Exception ex) {
            LOG.debug("Could not extract login time from JWT", ex);
        }
        return null;
    }

    private record EmitterEntry(SseEmitter emitter, ScheduledFuture<?> task) {
    }
}
