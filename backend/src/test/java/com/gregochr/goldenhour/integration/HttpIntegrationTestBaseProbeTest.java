package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROVISIONAL PROBE — not a feature test.
 *
 * <p>This class exists for one reason: to make the cost of {@link HttpIntegrationTestBase}
 * measurable. {@code docs/engineering/integration-test-strategy.md} budgets the T4 tier at
 * "+45s (one new context cache key)" and says outright that this is the number it trusts
 * least, because the base class introduces a <em>second</em> Spring context cache key into a
 * surefire run configured {@code forkCount=1C, reuseForks=true}. Phase 5 is scoped off that
 * number. Landing one real test on the base makes the marginal cost observable — run this
 * class alone, run {@code IntegrationTestBaseSmokeTest} alone, then run both, and the
 * difference is the second context boot.
 *
 * <p><strong>Delete or absorb this class when the real T4 suite lands in Phase 5</strong>
 * ({@code FreemiumWirePayloadIT}, {@code RefreshTokenRotationIT},
 * {@code RegistrationFunnelIT}, {@code TurnstileOutageIT}, {@code WritePathColumnFillIT}).
 * Keeping it alongside them would just be a slower duplicate of assertions they make better.
 *
 * <p>That said, it is not a no-op: a probe that would pass against a broken database teaches
 * nothing about the cost of the tier, because it would not be exercising the tier. So every
 * assertion here is anchored to state that only a real Postgres can supply — the Flyway-seeded
 * {@code regions} rows (V31), the {@code scheduler_job_config} rows (V68), and a row this test
 * writes itself — and the requests are authenticated with real JWTs over the real filter chain.
 */
class HttpIntegrationTestBaseProbeTest extends HttpIntegrationTestBase {

    /**
     * A region name present after all migrations, used to prove the response is not an empty list.
     *
     * <p>Seeded by V31 as "Northumberland" and renamed by V137, which merged Tyne and Wear into it
     * — so this constant tracks the migrated name, not the seeded one. It is deliberately still a
     * literal rather than "whatever the first row happens to be": the point of the assertion is
     * that a row this test did not write survives the full migration chain.
     */
    private static final String SEEDED_REGION = "Northumberland & Tyneside";

    /** Username of the probe's LITE_USER row. Distinct so it cannot collide with real fixtures. */
    private static final String LITE_USERNAME = "http-probe-lite";

    /** Username of the probe's ADMIN row. */
    private static final String ADMIN_USERNAME = "http-probe-admin";

    /**
     * Placeholder for the non-null {@code password} column. Never used to authenticate —
     * these tests mint tokens directly via {@link JwtService}, so no password is ever verified.
     */
    private static final String UNUSED_PASSWORD_PLACEHOLDER = "probe-row-never-authenticates";

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private SchedulerJobConfigRepository schedulerJobConfigRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void createProbeUsers() {
        deleteProbeUsers();
        appUserRepository.save(probeUser(LITE_USERNAME, UserRole.LITE_USER));
        appUserRepository.save(probeUser(ADMIN_USERNAME, UserRole.ADMIN));
    }

    @AfterEach
    void deleteProbeUsers() {
        appUserRepository.findByUsername(LITE_USERNAME).ifPresent(appUserRepository::delete);
        appUserRepository.findByUsername(ADMIN_USERNAME).ifPresent(appUserRepository::delete);
    }

    @Test
    @DisplayName("No token: the filter chain rejects with 401 before any controller runs")
    void unauthenticatedRequestIsRejectedByTheSecurityFilterChain() throws Exception {
        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("LITE_USER token: GET /api/regions returns exactly the rows in Postgres")
    void authenticatedGetReturnsTheRowsThatAreActuallyInPostgres() throws Exception {
        List<String> namesInDatabase = regionRepository.findAllByOrderByNameAsc().stream()
                .map(RegionEntity::getName)
                .toList();
        assertThat(namesInDatabase)
                .as("V31 seeds five regions; if this is empty the assertions below would pass "
                        + "vacuously and the probe would prove nothing about the database")
                .contains(SEEDED_REGION);

        ResultActions response = mockMvc.perform(get("/api/regions")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(LITE_USERNAME, UserRole.LITE_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(namesInDatabase.size()));

        for (int i = 0; i < namesInDatabase.size(); i++) {
            response.andExpect(jsonPath("$[" + i + "].name").value(namesInDatabase.get(i)));
        }
    }

    @Test
    @DisplayName("LITE_USER token: an ADMIN-only endpoint is refused with 403")
    void methodSecurityRefusesALiteUserOnAnAdminOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/scheduler/jobs")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(LITE_USERNAME, UserRole.LITE_USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN token: the same endpoint serves one entry per scheduler_job_config row")
    void adminIsServedTheSchedulerRowsFromPostgres() throws Exception {
        long rowsInDatabase = schedulerJobConfigRepository.count();
        assertThat(rowsInDatabase)
                .as("V68 seeds the scheduler_job_config baseline — without rows, a broken "
                        + "query and a correct one would both return an empty array")
                .isGreaterThanOrEqualTo(5L);

        mockMvc.perform(get("/api/admin/scheduler/jobs")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADMIN_USERNAME, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value((int) rowsInDatabase));
    }

    @Test
    @DisplayName("JwtAuthenticationFilter really ran: it stamped lastActiveAt in Postgres")
    void theJwtFilterWroteBackToTheDatabaseDuringTheRequest() throws Exception {
        assertThat(appUserRepository.findByUsername(LITE_USERNAME).orElseThrow().getLastActiveAt())
                .as("the probe row is created with a null lastActiveAt")
                .isNull();

        mockMvc.perform(get("/api/regions")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(LITE_USERNAME, UserRole.LITE_USER)))
                .andExpect(status().isOk());

        assertThat(appUserRepository.findByUsername(LITE_USERNAME).orElseThrow().getLastActiveAt())
                .as("JwtAuthenticationFilter.updateLastActiveIfStale writes this. A null here "
                        + "means the request authenticated without the real filter executing, "
                        + "which would invalidate every security assertion in this tier")
                .isNotNull();
    }

    /**
     * Mints a genuine access token so {@code JwtAuthenticationFilter} performs the same
     * parse-validate-authorise work it does in production.
     */
    private String bearerFor(String username, UserRole role) {
        return "Bearer " + jwtService.generateAccessToken(username, role);
    }

    /** Builds an enabled probe user with a deliberately null {@code lastActiveAt}. */
    private AppUserEntity probeUser(String username, UserRole role) {
        return AppUserEntity.builder()
                .username(username)
                .password(UNUSED_PASSWORD_PLACEHOLDER)
                .role(role)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
