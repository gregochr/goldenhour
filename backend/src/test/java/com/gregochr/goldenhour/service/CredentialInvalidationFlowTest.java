package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credential-invalidation sequences end to end (login-registration plan §3 R3c, §7): login, change of
 * password, an admin's disable, reset, email edit and demotion, the deletion of an account, the replay of a
 * revoked refresh token — through the real controllers, services, repositories and Spring Security chain,
 * on H2. Only the mail service is replaced.
 *
 * <p>What this cannot show is behaviour under Postgres's row locks, or the migration itself — that is
 * {@code CredentialsChangedAtMigrationTest}, which runs only in CI, and {@code TokenIssuerTest}'s two-thread
 * cases. The context gets its own in-memory database: every other context in the suite shares
 * {@code testdb}, and a second {@code create-drop} schema in the same database would drop the tables from
 * under it.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:credentialinvalidation;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CredentialInvalidationFlowTest {

    private static final String OLD_PASSWORD = "Old-P@ssw0rd!";
    private static final String NEW_PASSWORD = "New-P@ssw0rd!";
    private static final String ADMIN_PASSWORD = "Admin-P@ssw0rd!";

    /** An authenticated GET that is 200 for every role and touches nothing but the account. */
    private static final String ANY_READER_ROUTE = "/api/user/settings/ask";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private EmailVerificationTokenRepository verificationTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthAttemptLimiter attemptLimiter;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserEmailService userEmailService;

    private long readerId;
    private long adminId;

    @BeforeEach
    void createAccounts() {
        attemptLimiter.clear();
        reset(userEmailService);
        readerId = users.save(AppUserEntity.builder().username("reader").email("Reader@Example.com")
                .password(passwordEncoder.encode(OLD_PASSWORD)).role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
        adminId = users.save(AppUserEntity.builder().username("boss").email("boss@example.com")
                .password(passwordEncoder.encode(ADMIN_PASSWORD)).role(UserRole.ADMIN).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
        // Every address an account holds has its reservation (V179 backfilled them; the create paths and
        // the admin edit maintain them), which is what refuses a case variant.
        jdbc.update("INSERT INTO app_user_email_claim (email_lower, claimed_at) VALUES "
                + "('reader@example.com', CURRENT_TIMESTAMP), ('boss@example.com', CURRENT_TIMESTAMP)");
    }

    @AfterEach
    void clean() {
        resetTokens.deleteAll();
        verificationTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        jdbc.update("DELETE FROM app_user_email_claim");
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private JsonNode postJson(String path, String json, int expectedStatus) throws Exception {
        return send(post(path).contentType(MediaType.APPLICATION_JSON).content(json), expectedStatus);
    }

    private JsonNode login(String username, String password) throws Exception {
        return postJson("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", 200);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String accessToken) {
        return request.header("Authorization", "Bearer " + accessToken);
    }

    private void reachesTheApp(String accessToken, int expectedStatus) throws Exception {
        mockMvc.perform(authed(get(ANY_READER_ROUTE), accessToken)).andExpect(status().is(expectedStatus));
    }

    private void refresh(String refreshToken, int expectedStatus) throws Exception {
        postJson("/api/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", expectedStatus);
    }

    private long credentialsVersion(long id) {
        return jdbc.queryForObject("SELECT credentials_version FROM app_user WHERE id = ?", Long.class, id);
    }

    @Test
    @DisplayName("an access token carries the account id and the credential version it was issued at")
    void loginIssuesABoundToken() throws Exception {
        JsonNode session = login("reader", OLD_PASSWORD);

        String access = session.get("accessToken").asText();
        assertThat(jwtService.extractUserId(access)).isEqualTo(readerId);
        assertThat(jwtService.extractCredentialsVersion(access)).isZero();
        reachesTheApp(access, 200);
    }

    @Test
    @DisplayName("change-password signs the OTHER device out at its next request and refresh, hands THIS device "
            + "fresh tokens that work, and does not let the other device's refresh sign this one out")
    void changePassword_endsEveryOtherSessionAndKeepsThisOne() throws Exception {
        JsonNode laptop = login("reader", OLD_PASSWORD);
        JsonNode phone = login("reader", OLD_PASSWORD);

        JsonNode changed = send(authed(post("/api/auth/change-password"), laptop.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"),
                200);

        assertThat(changed.get("message").asText()).isEqualTo("Password updated");
        assertThat(credentialsVersion(readerId)).isEqualTo(1L);
        String freshAccess = changed.get("accessToken").asText();
        String freshRefresh = changed.get("refreshToken").asText();
        assertThat(jwtService.extractCredentialsVersion(freshAccess)).isEqualTo(1L);

        // The phone's next request and next refresh are refused...
        reachesTheApp(phone.get("accessToken").asText(), 401);
        refresh(phone.get("refreshToken").asText(), 401);
        // ...and the laptop's old access token is dead too, though it made the change.
        reachesTheApp(laptop.get("accessToken").asText(), 401);
        // The fresh tokens work, INCLUDING after the phone's dead refresh token was presented: that is a
        // credential change's aftermath, not theft, so it must not revoke the laptop's family.
        reachesTheApp(freshAccess, 200);
        refresh(freshRefresh, 200);
        // The old password is dead, the new one signs in.
        postJson("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + OLD_PASSWORD + "\"}", 401);
        login("reader", NEW_PASSWORD);
    }

    @Test
    @DisplayName("a second change-password from the superseded session is a 401 'Your session has changed' and "
            + "changes nothing")
    void changePassword_fromASupersededSession_isRefused() throws Exception {
        JsonNode first = login("reader", OLD_PASSWORD);
        String staleAccess = first.get("accessToken").asText();
        send(authed(post("/api/auth/change-password"), staleAccess).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"), 200);

        // The token's cv (0) is behind the row (1): the filter refuses it before the controller runs.
        send(authed(post("/api/auth/change-password"), staleAccess).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"Third-P@ssw0rd!\"}"), 401);

        assertThat(credentialsVersion(readerId)).isEqualTo(1L);
        login("reader", NEW_PASSWORD);
    }

    @Test
    @DisplayName("replaying a revoked refresh token is a 401 AND the whole family is revoked afterwards — the "
            + "revocation commits before the refusal is thrown")
    void replayedRefreshToken_revokesTheFamilyAfterThe401() throws Exception {
        JsonNode session = login("reader", OLD_PASSWORD);
        String original = session.get("refreshToken").asText();
        String rotated = postJson("/api/auth/refresh", "{\"refreshToken\":\"" + original + "\"}", 200)
                .get("refreshToken").asText();

        // The thief (or a confused client) replays the rotated-away token.
        refresh(original, 401);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE revoked = FALSE AND user_id = ?",
                Integer.class, readerId)).as("the family is revoked and STAYS revoked").isZero();
        refresh(rotated, 401);
    }

    @Test
    @DisplayName("an admin's disable ends the session at the next request, revokes its refresh token and, "
            + "after a re-enable, revives NONE of it — only a new sign-in works")
    void disable_thenReEnable_revivesNothing() throws Exception {
        JsonNode session = login("reader", OLD_PASSWORD);
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();

        send(authed(put("/api/users/" + readerId + "/enabled"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"), 200);

        assertThat(jdbc.queryForObject("SELECT password FROM app_user WHERE id = ?", String.class, readerId))
                .as("a disable leaves the hash alone").isNotBlank();
        reachesTheApp(session.get("accessToken").asText(), 401);
        postJson("/api/auth/login", "{\"username\":\"reader\",\"password\":\"" + OLD_PASSWORD + "\"}", 401);

        send(authed(put("/api/users/" + readerId + "/enabled"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"), 200);

        reachesTheApp(session.get("accessToken").asText(), 401);
        refresh(session.get("refreshToken").asText(), 401);
        JsonNode again = login("reader", OLD_PASSWORD);
        reachesTheApp(again.get("accessToken").asText(), 200);
    }

    @Test
    @DisplayName("an admin's temporary password ends every session of the account and sets the first-login gate")
    void adminTemporaryPassword_endsEverySession() throws Exception {
        JsonNode session = login("reader", OLD_PASSWORD);
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();

        JsonNode reset = send(authed(put("/api/users/" + readerId + "/reset-password"), admin), 200);

        String temporary = reset.get("temporaryPassword").asText();
        verify(userEmailService).sendPasswordResetEmail(eq("Reader@Example.com"), eq("reader"), eq(temporary));
        reachesTheApp(session.get("accessToken").asText(), 401);
        refresh(session.get("refreshToken").asText(), 401);
        assertThat(login("reader", temporary).get("passwordChangeRequired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a demotion lands at the next request: the ADMIN's old token is 403 on an admin route and 200 "
            + "on a reader route, with no sign-out")
    void demotion_takesEffectAtTheNextRequest() throws Exception {
        long second = users.save(AppUserEntity.builder().username("boss2").password(passwordEncoder.encode(
                ADMIN_PASSWORD)).role(UserRole.ADMIN).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
        String secondToken = login("boss2", ADMIN_PASSWORD).get("accessToken").asText();
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();
        mockMvc.perform(authed(get("/api/users"), secondToken)).andExpect(status().isOk());

        send(authed(put("/api/users/" + second + "/role"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"LITE_USER\"}"), 200);

        mockMvc.perform(authed(get("/api/users"), secondToken)).andExpect(status().isForbidden());
        reachesTheApp(secondToken, 200);
    }

    @Test
    @DisplayName("an access token for an account deleted after it was issued is a 401")
    void deletedAccount_tokenIsDead() throws Exception {
        String access = login("reader", OLD_PASSWORD).get("accessToken").asText();
        reachesTheApp(access, 200);

        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", readerId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", readerId);

        reachesTheApp(access, 401);
    }

    @Test
    @DisplayName("a username freed by a deletion and created again does not inherit the old account's token")
    void recreatedUsername_doesNotInheritTheOldToken() throws Exception {
        String oldAccess = login("reader", OLD_PASSWORD).get("accessToken").asText();
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", readerId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", readerId);
        users.save(AppUserEntity.builder().username("reader").password(passwordEncoder.encode(OLD_PASSWORD))
                .role(UserRole.ADMIN).enabled(true).createdAt(LocalDateTime.now()).build());

        reachesTheApp(oldAccess, 401);
    }

    @Test
    @DisplayName("an admin's email edit voids a reset link mailed to the OLD address: the link no longer redeems")
    void emailEdit_voidsAnOutstandingResetLink() throws Exception {
        postJson("/api/auth/forgot-password", "{\"email\":\"Reader@Example.com\"}", 200);
        ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
        verify(userEmailService).sendPasswordResetLinkEmail(eq("Reader@Example.com"), eq("reader"),
                rawToken.capture());
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();

        send(authed(put("/api/users/" + readerId + "/email"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"moved@example.com\"}"), 200);

        JsonNode refused = postJson("/api/auth/reset-password",
                "{\"token\":\"" + rawToken.getValue() + "\",\"password\":\"" + NEW_PASSWORD + "\"}", 400);
        assertThat(refused.get("error").asText()).isEqualTo("This reset link is invalid");
        // A fresh request goes to the NEW address.
        reset(userEmailService);
        postJson("/api/auth/forgot-password", "{\"email\":\"moved@example.com\"}", 200);
        verify(userEmailService).sendPasswordResetLinkEmail(eq("moved@example.com"), eq("reader"), anyString());
    }

    @Test
    @DisplayName("an admin's email edit to a case variant of another account's address is a 409, and releases "
            + "nothing it should not")
    void emailEdit_toACaseVariant_returns409() throws Exception {
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();
        send(authed(put("/api/users/" + readerId + "/email"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"BOSS@example.com\"}"), 409);

        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, readerId))
                .isEqualTo("Reader@Example.com");
    }

    @Test
    @DisplayName("activation through set-password issues a token bound to the new row, redeems once, and a "
            + "replay of the consumed link is refused")
    void setPassword_activatesOnce() throws Exception {
        long pendingId = users.save(AppUserEntity.builder().username("newbie").email("newbie@example.com")
                .password("").role(UserRole.LITE_USER).enabled(false).createdAt(LocalDateTime.now())
                .build()).getId();
        verificationTokens.save(EmailVerificationTokenEntity.builder().tokenHash(jwtService.hashToken("link-1"))
                .userId(pendingId).expiresAt(LocalDateTime.now().plusHours(12)).verified(true)
                .createdAt(LocalDateTime.now()).build());
        String body = "{\"token\":\"link-1\",\"password\":\"" + NEW_PASSWORD + "\"}";

        JsonNode activated = postJson("/api/auth/set-password", body, 200);

        String access = activated.get("accessToken").asText();
        assertThat(jwtService.extractUserId(access)).isEqualTo(pendingId);
        assertThat(jwtService.extractCredentialsVersion(access)).isEqualTo(1L);
        reachesTheApp(access, 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_token WHERE user_id = ?",
                Integer.class, pendingId)).isZero();
        JsonNode replay = postJson("/api/auth/set-password", body, 400);
        assertThat(replay.get("error").asText()).isEqualTo("Invalid or expired verification token");
        login("newbie", NEW_PASSWORD);
    }

    @Test
    @DisplayName("verify-email rotates the mailed link: it returns a setupToken, a second verify of the mailed "
            + "link is 400 'already been used', set-password with the mailed token is refused and with the "
            + "setupToken activates")
    void verifyEmail_rotatesTheLink_andOnlyTheSetupTokenActivates() throws Exception {
        long pendingId = users.save(AppUserEntity.builder().username("newbie").email("newbie@example.com")
                .password("").role(UserRole.LITE_USER).enabled(false).createdAt(LocalDateTime.now())
                .build()).getId();
        verificationTokens.save(EmailVerificationTokenEntity.builder().tokenHash(jwtService.hashToken("mailed"))
                .userId(pendingId).expiresAt(LocalDateTime.now().plusHours(12)).verified(false)
                .createdAt(LocalDateTime.now()).build());

        JsonNode verified = postJson("/api/auth/verify-email", "{\"token\":\"mailed\"}", 200);
        String setupToken = verified.get("setupToken").asText();
        assertThat(setupToken).isNotBlank().isNotEqualTo("mailed");

        JsonNode second = postJson("/api/auth/verify-email", "{\"token\":\"mailed\"}", 400);
        assertThat(second.get("error").asText()).contains("already been used");
        postJson("/api/auth/set-password", "{\"token\":\"mailed\",\"password\":\"" + NEW_PASSWORD + "\"}", 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_token WHERE user_id = ? "
                + "AND expires_at <= ?", Integer.class, pendingId, LocalDateTime.now().plusMinutes(31)))
                .as("the rotated row expires within 30 minutes").isEqualTo(1);

        postJson("/api/auth/set-password",
                "{\"token\":\"" + setupToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}", 200);
        login("newbie", NEW_PASSWORD);
    }

    @Test
    @DisplayName("logout kills the access token of EVERY device at its next request, leaves the other device's "
            + "refresh token alive for one silent refresh, and a replay of the logged-out refresh token still "
            + "reads as theft (credentials_changed_at is not stamped)")
    void logout_endsEveryAccessToken_butNotEveryRefreshToken() throws Exception {
        JsonNode laptop = login("reader", OLD_PASSWORD);
        JsonNode phone = login("reader", OLD_PASSWORD);
        String laptopRefresh = laptop.get("refreshToken").asText();

        postJson("/api/auth/logout", "{\"refreshToken\":\"" + laptopRefresh + "\"}", 200);

        assertThat(credentialsVersion(readerId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT credentials_changed_at FROM app_user WHERE id = ?",
                java.sql.Timestamp.class, readerId)).as("logout is not a credential change").isNull();
        reachesTheApp(laptop.get("accessToken").asText(), 401);
        reachesTheApp(phone.get("accessToken").asText(), 401);
        // The phone takes one silent refresh and carries on with a token at the new version.
        JsonNode phoneAgain = postJson("/api/auth/refresh",
                "{\"refreshToken\":\"" + phone.get("refreshToken").asText() + "\"}", 200);
        reachesTheApp(phoneAgain.get("accessToken").asText(), 200);

        // The logged-out refresh token, replayed, is REVOKED_SEEN: refused AND the family is revoked.
        refresh(laptopRefresh, 401);
        refresh(phoneAgain.get("refreshToken").asText(), 401);
    }

    @Test
    @DisplayName("an anonymous logout with an unknown refresh token is a 200 that touches no account")
    void logout_anonymousUnknownToken_touchesNothing() throws Exception {
        postJson("/api/auth/logout", "{\"refreshToken\":\"never-issued\"}", 200);
        postJson("/api/auth/logout", "{}", 200);

        assertThat(credentialsVersion(readerId)).isZero();
        assertThat(credentialsVersion(adminId)).isZero();
    }

    @Test
    @DisplayName("an admin create refuses a non-ASCII address with the sentence register uses")
    void adminCreate_refusesANonAsciiAddress() throws Exception {
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();

        JsonNode refused = send(authed(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bobby\",\"password\":\"Pass-w0rd!\",\"role\":\"LITE_USER\","
                        + "\"email\":\"bob@gmaıl.com\"}"), 400);

        assertThat(refused.get("error").asText())
                .isEqualTo("Please use an email address without accented or non-Latin characters");
    }

    @Test
    @DisplayName("an anonymous change-password is a 401 and writes nothing, even for an account that exists")
    void anonymousChangePassword_isRefused() throws Exception {
        postJson("/api/auth/change-password", "{\"newPassword\":\"" + NEW_PASSWORD + "\"}", 401);

        assertThat(credentialsVersion(readerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isZero();
    }

    @Test
    @DisplayName("deleting a user through the admin route ends its token too")
    void adminDelete_endsTheToken() throws Exception {
        String access = login("reader", OLD_PASSWORD).get("accessToken").asText();
        String admin = login("boss", ADMIN_PASSWORD).get("accessToken").asText();

        send(authed(delete("/api/users/" + readerId), admin), 200);

        reachesTheApp(access, 401);
        verify(userEmailService).sendAccountDeletedEmail("Reader@Example.com", "reader");
    }
}
