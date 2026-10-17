package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one end-to-end reset sequence (login-registration plan §3 R3a): forgot-password, the emailed link's
 * token, reset-password, the response signing the reader in, and the earlier session refused — through the
 * real controller, services, repositories and Spring Security chain, on H2.
 *
 * <p>Only the mail service is replaced (its captured arguments ARE the link), and the context gets its own
 * in-memory database name: every other context in the suite shares {@code testdb}, and a second
 * {@code create-drop} schema in the same database would drop the tables from under it.
 *
 * <p>What this cannot show is behaviour under Postgres's row locks, or the migration itself — that is
 * {@code PasswordResetTokenMigrationTest}, which runs only in CI.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:passwordresetflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PasswordResetFlowTest {

    private static final String OLD_PASSWORD = "Old-P@ssw0rd!";
    private static final String NEW_PASSWORD = "New-P@ssw0rd!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthAttemptLimiter attemptLimiter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserEmailService userEmailService;

    private long userId;

    @BeforeEach
    void createReader() {
        attemptLimiter.clear();
        reset(userEmailService);
        userId = users.save(AppUserEntity.builder().username("reader").email("Reader@Example.com")
                .password(passwordEncoder.encode(OLD_PASSWORD)).role(UserRole.PRO_USER).enabled(true)
                .passwordChangeRequired(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    @AfterEach
    void clean() {
        resetTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private JsonNode call(String path, String json, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loginRefreshToken() throws Exception {
        return call("/api/auth/login",
                "{\"username\":\"reader\",\"password\":\"" + OLD_PASSWORD + "\"}", 200).get("refreshToken").asText();
    }

    /** Requests a reset for the given typed address and returns the raw token the mail would carry. */
    private String requestLink(String typedAddress) throws Exception {
        call("/api/auth/forgot-password", "{\"email\":\"" + typedAddress + "\"}", 200);
        ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
        verify(userEmailService).sendPasswordResetLinkEmail(eq("Reader@Example.com"), eq("reader"),
                rawToken.capture());
        return rawToken.getValue();
    }

    @Test
    @DisplayName("forgot -> the emailed token -> reset -> the response signs in -> the earlier refresh token "
            + "is refused, the old password is dead and the new one signs in")
    void fullSequence() throws Exception {
        String earlierRefresh = loginRefreshToken();

        // The typed address differs in case; the mail still goes to the STORED one (asserted in requestLink).
        String rawToken = requestLink("  reader@example.COM ");

        JsonNode reset = call("/api/auth/reset-password",
                "{\"token\":\"" + rawToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}", 200);
        assertThat(reset.get("username").asText()).isEqualTo("reader");
        assertThat(reset.get("role").asText()).isEqualTo("PRO_USER");
        assertThat(reset.get("passwordChangeRequired").asBoolean()).isFalse();
        String freshRefresh = reset.get("refreshToken").asText();
        assertThat(reset.get("accessToken").asText()).isNotBlank();

        // The reset device's session works...
        call("/api/auth/refresh", "{\"refreshToken\":\"" + freshRefresh + "\"}", 200);
        // ...and the session that existed before the reset does not.
        call("/api/auth/refresh", "{\"refreshToken\":\"" + earlierRefresh + "\"}", 401);

        call("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + OLD_PASSWORD + "\"}", 401);
        call("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + NEW_PASSWORD + "\"}", 200);
        // Sign-in by the email address works with the new password too.
        call("/api/auth/login", "{\"username\":\"READER@example.com\",\"password\":\"" + NEW_PASSWORD + "\"}",
                200);
    }

    @Test
    @DisplayName("a link works once: the second redemption is a 400 'already been used' and the password stays")
    void linkWorksOnce() throws Exception {
        String rawToken = requestLink("Reader@Example.com");
        String body = "{\"token\":\"" + rawToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}";
        call("/api/auth/reset-password", body, 200);

        JsonNode second = call("/api/auth/reset-password",
                "{\"token\":\"" + rawToken + "\",\"password\":\"Third-P@ssw0rd!\"}", 400);

        assertThat(second.get("error").asText()).isEqualTo("This reset link has already been used");
        call("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + NEW_PASSWORD + "\"}", 200);
    }

    @Test
    @DisplayName("an expired link is a 400 'has expired' and changes nothing")
    void expiredLink() throws Exception {
        String rawToken = requestLink("Reader@Example.com");
        jdbc.update("UPDATE password_reset_token SET expires_at = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)));

        JsonNode refused = call("/api/auth/reset-password",
                "{\"token\":\"" + rawToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}", 400);

        assertThat(refused.get("error").asText()).isEqualTo("This reset link has expired");
        call("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + OLD_PASSWORD + "\"}", 200);
    }

    @Test
    @DisplayName("an unknown token is a 400 'invalid'")
    void unknownLink() throws Exception {
        JsonNode refused = call("/api/auth/reset-password",
                "{\"token\":\"not-a-real-token\",\"password\":\"" + NEW_PASSWORD + "\"}", 400);

        assertThat(refused.get("error").asText()).isEqualTo("This reset link is invalid");
    }

    @Test
    @DisplayName("a link requested before the account was disabled cannot reset a banned account's password")
    void disabledAccountLink() throws Exception {
        String rawToken = requestLink("Reader@Example.com");
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

        JsonNode refused = call("/api/auth/reset-password",
                "{\"token\":\"" + rawToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}", 400);

        assertThat(refused.get("error").asText()).isEqualTo("This reset link is invalid");
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_token", Object.class)).isNull();
        assertThat(passwordEncoder.matches(OLD_PASSWORD,
                jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class, userId))).isTrue();
    }

    @Test
    @DisplayName("a second request deletes the first link (now unknown, 'invalid'); the second link works")
    void aNewRequestVoidsTheEarlierLink() throws Exception {
        String first = requestLink("Reader@Example.com");
        reset(userEmailService);
        String second = requestLink("Reader@Example.com");

        call("/api/auth/reset-password", "{\"token\":\"" + first + "\",\"password\":\"" + NEW_PASSWORD + "\"}",
                400);
        call("/api/auth/reset-password", "{\"token\":\"" + second + "\",\"password\":\"" + NEW_PASSWORD + "\"}",
                200);
    }

    @Test
    @DisplayName("forgot-password answers the identical 200 for an unknown address and sends nothing; "
            + "the fourth request for a known one sends nothing more")
    void forgotPasswordNeverSaysWhetherTheAccountExists() throws Exception {
        JsonNode unknown = call("/api/auth/forgot-password", "{\"email\":\"ghost@example.com\"}", 200);
        verify(userEmailService, never()).sendPasswordResetLinkEmail(anyString(), anyString(), anyString());

        for (int i = 0; i < 4; i++) {
            JsonNode known = call("/api/auth/forgot-password", "{\"email\":\"Reader@Example.com\"}", 200);
            assertThat(known).isEqualTo(unknown);
        }

        verify(userEmailService, times(3))
                .sendPasswordResetLinkEmail(eq("Reader@Example.com"), eq("reader"), anyString());
    }
}
