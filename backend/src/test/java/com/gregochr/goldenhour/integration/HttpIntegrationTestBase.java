package com.gregochr.goldenhour.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for full-stack HTTP acceptance tests:
 * MockMvc → real Spring Security filter chain → real controller → real service →
 * real repository → real Postgres (Flyway schema, {@code ddl-auto=validate}).
 *
 * <p>This is the T4 tier of {@code docs/engineering/integration-test-strategy.md}. It
 * exists because the two tiers either side of it each mock exactly the collaborator
 * that carries the risk:
 *
 * <ul>
 *   <li>{@link IntegrationTestBase} (T3) gives real Postgres but no HTTP. Its subclasses
 *       call services and repositories directly, so nothing downstream of
 *       {@code DispatcherServlet} — the security filter chain, Jackson serialisation,
 *       bean validation, {@code GlobalExceptionHandler} — ever executes. A DTO field
 *       that never reaches the wire, or a {@code @PreAuthorize} that never fires, is
 *       invisible there.</li>
 *   <li>{@code AbstractControllerTest} (the 36 controller classes) gives real HTTP and a
 *       real filter chain, but declares 56 {@code @MockitoBean}s covering every service
 *       and repository the app has. It proves a controller passes the right argument to
 *       a mock; it cannot prove the mock's real counterpart does anything with it, and
 *       it never touches a database.</li>
 * </ul>
 *
 * <p>This base is the join: real HTTP <em>and</em> real data, in one request.
 *
 * <p><strong>The security filters run.</strong> {@code @AutoConfigureMockMvc} is
 * deliberately used without {@code addFilters = false} — there is no
 * {@code addFilters = false} anywhere in this repository and there must not be one
 * here, because role gating and the 401/403 perimeter are among the behaviours this
 * tier exists to test. Authenticate the way a browser does: mint a token with
 * {@code JwtService} and send an {@code Authorization: Bearer} header, so
 * {@code JwtAuthenticationFilter} genuinely executes.
 *
 * <p><strong>Do not declare {@code @MockitoBean}, {@code @TestPropertySource} or
 * {@code @ActiveProfiles} in a subclass.</strong> Each of those mutates the Spring
 * context cache key, so the subclass declaring it pays a fresh application-context boot
 * — and so does every class that does <em>not</em> declare it. Just as importantly, an
 * internal {@code @MockitoBean} would re-introduce the exact blind spot this tier was
 * created to close. If a test needs a stub, stub the <em>outbound</em> edge: the
 * inherited {@code WIRE_MOCK} extension already fronts Anthropic.
 *
 * <p>Adding {@code @AutoConfigureMockMvc} on top of {@link IntegrationTestBase}'s
 * annotations produces a second, distinct context cache key — subclasses of this base
 * share one context with each other, and {@link IntegrationTestBase}'s direct subclasses
 * share a different one.
 *
 * <p>Context cache keys stopped mattering for correctness once the parent gained
 * {@code @DirtiesContext(AFTER_CLASS)}: no context now outlives the class that created
 * it, so none can outlive the container restart that happens between classes. Before
 * that, a second class sharing a key inherited a context pointing at the previous
 * container's port and every query failed — see {@link IntegrationTestBase}'s Javadoc,
 * and do not remove that annotation. Keys still matter for <em>cost</em>: each distinct
 * key is one more Spring context boot per surefire fork.
 */
@AutoConfigureMockMvc
public abstract class HttpIntegrationTestBase extends IntegrationTestBase {

    /** Entry point for every request in this tier — wired through the full filter chain. */
    @Autowired
    protected MockMvc mockMvc;
}
