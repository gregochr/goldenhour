package com.gregochr.goldenhour.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link JwtAuthenticationFilter} — a token authenticates only against the account row it names
 * (login-registration plan §2 #13): the row must exist and be enabled, the token's {@code uid} must be the
 * row's id, and its {@code cv} must EQUAL the row's credential version; a token with no {@code cv} falls
 * back to the {@code iat} rule; and the authority comes from the ROW's role, never the token's claim.
 *
 * <p>The full-stack cases run real requests through the Spring Security chain against real H2 repositories
 * (the context gets its own in-memory database: every other context in the suite shares {@code testdb}, and
 * a second {@code create-drop} schema in the same database would drop the tables from under it).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:jwtfilter;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest {

    private static final String ADMIN_ONLY_ROUTE = "/api/users";
    private static final String READER_ROUTE = "/api/forecast";
    private static final List<String> STREAM_PATHS = List.of(
            "/api/forecast/run/123/progress", "/api/forecast/run/notifications", "/api/status/stream");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @MockitoBean
    private ForecastService forecastService;

    private long aliceId;

    @BeforeEach
    void aliceExists() {
        aliceId = users.save(AppUserEntity.builder().username("alice").password("hash")
                .role(UserRole.LITE_USER).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build()).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user");
        SecurityContextHolder.clearContext();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void setVersion(long version) {
        jdbc.update("UPDATE app_user SET credentials_version = ? WHERE id = ?", version, aliceId);
    }

    @Test
    @DisplayName("Request with valid JWT token is authenticated and reaches the endpoint")
    void request_validJwt_isAuthenticated() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Request with malformed JWT token returns 401")
    void request_malformedJwt_returns401() throws Exception {
        mockMvc.perform(get(READER_ROUTE).header("Authorization", "Bearer this.is.not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Request with no Authorization header returns 401")
    void request_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(get(READER_ROUTE)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token for an account that no longer exists is 401, though its signature and expiry are fine")
    void request_tokenForADeletedAccount_returns401() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token))).andExpect(status().isOk());

        jdbc.update("DELETE FROM app_user WHERE id = ?", aliceId);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token for a DISABLED account is 401")
    void request_tokenForADisabledAccount_returns401() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", aliceId);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token whose cv is behind the row's credential version is 401 (a password change signed "
            + "it out), and a token carrying the current cv is accepted")
    void request_staleCredentialVersion_returns401() throws Exception {
        String before = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);
        setVersion(1);
        String after = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 1L);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(before)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(after)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token whose cv is AHEAD of the row is 401 too: the claim must EQUAL the row's value")
    void request_cvAheadOfTheRow_returns401() throws Exception {
        String ahead = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 5L);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(ahead)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token bound to another account id is 401: a username freed by a deletion and taken again "
            + "does not inherit an old token")
    void request_uidForAnotherAccount_returns401() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId + 1000, 0L);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a legacy token (no uid, no cv) is accepted by name while the row has never changed")
    void request_legacyTokenOnANeverChangedRow_isAccepted() throws Exception {
        String legacy = jwtService.generateAccessToken("alice", UserRole.LITE_USER);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(legacy)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a legacy token issued BEFORE the credentials-changed stamp is 401; one issued at or after "
            + "the stamp's second is accepted — the second-truncated iat rule, for that window only")
    void request_legacyToken_followsTheIatRule() throws Exception {
        String legacy = jwtService.generateAccessToken("alice", UserRole.LITE_USER);
        Instant issuedAt = jwtService.extractIssuedAt(legacy);

        jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                Timestamp.from(issuedAt.plusSeconds(10)), aliceId);
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(legacy)))
                .andExpect(status().isUnauthorized());

        jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                Timestamp.from(issuedAt.minusSeconds(10)), aliceId);
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(legacy)))
                .andExpect(status().isOk());

        // The stamp's own second is truncated before the comparison: a stamp 0.5 s into the token's second
        // does not reject the token (the stated, accepted residual of the legacy window).
        jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                Timestamp.from(issuedAt.truncatedTo(ChronoUnit.SECONDS).plusMillis(500)), aliceId);
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(legacy)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the authority is the ROW's role: a demoted ADMIN's old token is 403 on an admin route and "
            + "200 on a reader route, while a promoted account's old token reaches the admin route")
    void request_authorityComesFromTheRowNotTheTokenClaim() throws Exception {
        long adminId = users.save(AppUserEntity.builder().username("boss").password("hash")
                .role(UserRole.ADMIN).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build()).getId();
        String adminToken = jwtService.generateAccessToken("boss", UserRole.ADMIN, adminId, 0L);
        mockMvc.perform(get(ADMIN_ONLY_ROUTE).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        jdbc.update("UPDATE app_user SET role = 'LITE_USER' WHERE id = ?", adminId);

        mockMvc.perform(get(ADMIN_ONLY_ROUTE).header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        // The other direction: a token that CLAIMS a lesser role does not shrink a row that is ADMIN.
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", aliceId);
        String claimsLite = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);
        mockMvc.perform(get(ADMIN_ONLY_ROUTE).header("Authorization", bearer(claimsLite)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an authenticated request stamps lastActiveAt with a scoped update and changes nothing else")
    void request_stampsLastActiveWithoutTouchingTheCredentials() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);

        mockMvc.perform(get(READER_ROUTE).header("Authorization", bearer(token))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT last_active_at FROM app_user WHERE id = ?", Object.class,
                aliceId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class, aliceId))
                .isEqualTo("hash");
        assertThat(jdbc.queryForObject("SELECT credentials_version FROM app_user WHERE id = ?", Long.class,
                aliceId)).isZero();
    }

    @Test
    @DisplayName("a valid token in ?token= on the status stream is 401: URLs never carry credentials")
    void statusStream_queryParamToken_returns401() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, aliceId, 0L);

        mockMvc.perform(get("/api/status/stream").param("token", token))
                .andExpect(status().isUnauthorized());
    }

    /** The filter on its own: what lands on the {@code SecurityContext}, and what is logged. */
    @Nested
    class OnItsOwn {

        private AppUserRepository repository;
        private JwtAuthenticationFilter filter;
        private ListAppender<ILoggingEvent> logs;
        private Logger filterLogger;

        @BeforeEach
        void filterOverAMockRepository() {
            repository = mock(AppUserRepository.class);
            filter = new JwtAuthenticationFilter(jwtService, repository);
            filterLogger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
            logs = new ListAppender<>();
            logs.start();
            filterLogger.addAppender(logs);
            SecurityContextHolder.clearContext();
        }

        @AfterEach
        void detach() {
            filterLogger.detachAppender(logs);
            SecurityContextHolder.clearContext();
        }

        private AppUserEntity row(long id, UserRole role, long version) {
            return AppUserEntity.builder().id(id).username("alice").password("hash").role(role).enabled(true)
                    .credentialsVersion(version).createdAt(LocalDateTime.now()).build();
        }

        private FilterChain run(String token) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/forecast");
            request.addHeader("Authorization", "Bearer " + token);
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            verify(chain).doFilter(any(), any());
            return chain;
        }

        @Test
        @DisplayName("an accepted token puts the ROW's role on the context and its cv on the details")
        void accepted_setsRowAuthorityAndTheTokensCv() throws Exception {
            when(repository.findByUsername("alice")).thenReturn(Optional.of(row(9L, UserRole.PRO_USER, 4L)));

            run(jwtService.generateAccessToken("alice", UserRole.ADMIN, 9L, 4L));

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getName()).isEqualTo("alice");
            assertThat(authentication.getAuthorities()).extracting(Object::toString)
                    .containsExactly("ROLE_PRO_USER");
            assertThat(authentication.getDetails()).isEqualTo(new JwtAuthenticationDetails(4L));
        }

        @Test
        @DisplayName("a legacy token's details carry a NULL cv, which change-password reads as the legacy mode")
        void legacyToken_hasNullCvOnTheDetails() throws Exception {
            when(repository.findByUsername("alice")).thenReturn(Optional.of(row(9L, UserRole.LITE_USER, 0L)));

            run(jwtService.generateAccessToken("alice", UserRole.LITE_USER));

            assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
                    .isEqualTo(new JwtAuthenticationDetails(null));
        }

        @Test
        @DisplayName("a refused token leaves the context EMPTY and logs one INFO line naming the reason, with "
                + "the untrusted username sanitised")
        void refused_leavesTheContextEmptyAndLogsOneSanitisedLine() throws Exception {
            when(repository.findByUsername("evil\r\nINFO forged line")).thenReturn(Optional.empty());

            run(jwtService.generateAccessToken("evil\r\nINFO forged line", UserRole.LITE_USER, 9L, 0L));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(logs.list).hasSize(1);
            ILoggingEvent event = logs.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("no such account")
                    .doesNotContain("\r").doesNotContain("\n");
            verify(repository, never()).touchLastActiveAt(any(), any());
        }

        @Test
        @DisplayName("lastActiveAt is stamped by the scoped update when null or an hour stale, and not otherwise")
        void lastActive_followsTheHourlyRule() throws Exception {
            AppUserEntity fresh = row(9L, UserRole.LITE_USER, 0L);
            fresh.setLastActiveAt(LocalDateTime.now().minusMinutes(5));
            when(repository.findByUsername("alice")).thenReturn(Optional.of(fresh));
            run(jwtService.generateAccessToken("alice", UserRole.LITE_USER, 9L, 0L));
            verify(repository, never()).touchLastActiveAt(any(), any());

            SecurityContextHolder.clearContext();
            fresh.setLastActiveAt(LocalDateTime.now().minusMinutes(61));
            run(jwtService.generateAccessToken("alice", UserRole.LITE_USER, 9L, 0L));
            verify(repository).touchLastActiveAt(any(Long.class), any(LocalDateTime.class));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a failing last-active stamp never blocks the request")
        void failingStamp_doesNotBlockTheRequest() throws Exception {
            when(repository.findByUsername("alice")).thenReturn(Optional.of(row(9L, UserRole.LITE_USER, 0L)));
            when(repository.touchLastActiveAt(any(), any())).thenThrow(new IllegalStateException("db down"));

            run(jwtService.generateAccessToken("alice", UserRole.LITE_USER, 9L, 0L));

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        }

        private void runOnPath(String path, boolean viaHeader, String token) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            if (viaHeader) {
                request.addHeader("Authorization", "Bearer " + token);
            } else {
                request.setParameter("token", token);
            }
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            verify(chain).doFilter(any(), any());
        }

        @Test
        @DisplayName("a valid ?token= on each former event-stream path does NOT authenticate")
        void streamPaths_queryParamToken_doesNotAuthenticate() throws Exception {
            String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, 9L, 0L);

            for (String path : STREAM_PATHS) {
                runOnPath(path, false, token);

                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as(path).isNull();
            }
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the Authorization header DOES authenticate on each event-stream path")
        void streamPaths_authorizationHeader_authenticates() throws Exception {
            when(repository.findByUsername("alice")).thenReturn(Optional.of(row(9L, UserRole.LITE_USER, 0L)));
            String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER, 9L, 0L);

            for (String path : STREAM_PATHS) {
                SecurityContextHolder.clearContext();
                runOnPath(path, true, token);

                assertThat(SecurityContextHolder.getContext().getAuthentication())
                        .as(path).isNotNull();
            }
        }

        @Test
        @DisplayName("an invalid token never touches the repository")
        void invalidToken_neverLoadsTheAccount() throws Exception {
            run("not-a-jwt");

            verifyNoInteractions(repository);
        }
    }
}
