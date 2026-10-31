package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.service.CredentialChangeOutcome;
import com.gregochr.goldenhour.service.CredentialChangeWriter;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.RefreshFamilyRevoker;
import com.gregochr.goldenhour.service.TokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V180 ({@code app_user.credentials_changed_at} and {@code credentials_version}) against a real
 * Postgres, and the statements that use them on the production dialect (login-registration plan §2 #13):
 * the columns' types, nullability and default, the default taking effect for a row inserted without them,
 * the credential change's single {@code UPDATE} with its {@code CASE} expressions and compare-and-set
 * predicates, the native {@code SELECT … FOR UPDATE} projection's column types, and the issuer's
 * issue-and-rotate over it.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration and these statements are proven before merge only by CI's Backend job. The
 * same logic runs locally on H2 in {@code CredentialChangeWriterTest}, {@code TokenIssuerTest} and
 * {@code AppUserRepositoryEmailTest}.
 */
class CredentialsChangedAtMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CredentialChangeWriter writer;
    @Autowired
    private TokenIssuer issuer;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RefreshFamilyRevoker familyRevoker;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v180-%'");
    }

    private long newUser(String name, boolean enabled, String password) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com")
                .password(password).role(UserRole.PRO_USER).enabled(enabled).passwordChangeRequired(true)
                .marketingEmailOptIn(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the two columns exist with the expected types, nullability and default")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'app_user' AND column_name IN "
                        + "('credentials_changed_at', 'credentials_version')");

        assertThat(column(columns, "credentials_changed_at"))
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
        assertThat(column(columns, "credentials_version"))
                .containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(column(columns, "credentials_version").get("column_default"))).isEqualTo("0");
    }

    @Test
    @DisplayName("a row inserted WITHOUT the columns (the way every pre-V180 writer did) reads version 0 and no "
            + "stamp: every existing token stays valid")
    void defaultsForARowInsertedWithoutThem() {
        jdbc.update("INSERT INTO app_user (username, password, role, enabled, created_at, "
                + "password_change_required, marketing_email_opt_in) "
                + "VALUES ('v180-legacy', 'hash', 'LITE_USER', TRUE, now(), FALSE, TRUE)");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credentials_version, credentials_changed_at FROM app_user WHERE username = 'v180-legacy'");

        assertThat(((Number) row.get("credentials_version")).longValue()).isZero();
        assertThat(row.get("credentials_changed_at")).isNull();
    }

    @Test
    @DisplayName("the locked projection reads every column type Postgres hands back: bigint, timestamptz, "
            + "booleans, the role as text")
    void lockAccountProjection() {
        long id = newUser("v180-lock", true, "hash");
        jdbc.update("UPDATE app_user SET credentials_version = 4, credentials_changed_at = ?::timestamptz, "
                + "terms_version = 'April 2026' WHERE id = ?", "2026-10-16T12:00:00Z", id);

        LockedAccount locked = users.lockAccount(id).orElseThrow();

        assertThat(locked.credentialsVersion()).isEqualTo(4L);
        assertThat(locked.credentialsChangedAt()).isEqualTo(Instant.parse("2026-10-16T12:00:00Z"));
        assertThat(locked.passwordChangeRequired()).isTrue();
        assertThat(locked.marketingEmailOptIn()).isTrue();
        assertThat(locked.termsVersion()).isEqualTo("April 2026");
        assertThat(locked.role()).isEqualTo(UserRole.PRO_USER);
        assertThat(locked.active()).isTrue();
    }

    @Test
    @DisplayName("a change-password applies the whole change on Postgres: hash, cleared gate, version 0 -> 1, a "
            + "stamp, and a stale exact() expectation is refused and writes nothing")
    void changePasswordOnPostgres() {
        long id = newUser("v180-change", true, "old-hash");

        CredentialChangeOutcome outcome = writer.apply(id, "new-hash", Change.changePassword(),
                VersionExpectation.exact(0));

        assertThat(outcome.credentialsVersion()).isEqualTo(1L);
        LockedAccount after = users.lockAccount(id).orElseThrow();
        assertThat(after.password()).isEqualTo("new-hash");
        assertThat(after.passwordChangeRequired()).isFalse();
        assertThat(after.credentialsChangedAt()).isNotNull();

        assertThatThrownBy(() -> writer.apply(id, "stale-hash", Change.changePassword(),
                VersionExpectation.exact(0))).isInstanceOf(StaleCredentialsException.class);
        assertThat(users.lockAccount(id).orElseThrow().password()).isEqualTo("new-hash");
        assertThat(users.lockAccount(id).orElseThrow().credentialsVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a disable with a NULL password leaves the hash out of the UPDATE on Postgres, bumps the "
            + "version and flips enabled; the scoped re-enable bumps nothing")
    void disableAndReEnableOnPostgres() {
        long id = newUser("v180-disable", true, "the-hash");

        writer.apply(id, null, Change.disable(), VersionExpectation.trusted());
        LockedAccount disabled = users.lockAccount(id).orElseThrow();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.password()).isEqualTo("the-hash");
        assertThat(disabled.credentialsVersion()).isEqualTo(1L);

        assertThat(users.updateEnabled(id, true)).isEqualTo(1);
        LockedAccount reEnabled = users.lockAccount(id).orElseThrow();
        assertThat(reEnabled.enabled()).isTrue();
        assertThat(reEnabled.credentialsVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("activation enables a pending account on Postgres, and refuses one that is not pending")
    void activationOnPostgres() {
        long pending = newUser("v180-pending", false, "");
        long active = newUser("v180-active", true, "live-hash");

        writer.apply(pending, "chosen-hash", Change.activation(), VersionExpectation.trusted());
        assertThat(users.lockAccount(pending).orElseThrow().active()).isTrue();

        assertThatThrownBy(() -> writer.apply(active, "overwrite", Change.activation(),
                VersionExpectation.trusted())).isInstanceOf(IllegalArgumentException.class);
        assertThat(users.lockAccount(active).orElseThrow().password()).isEqualTo("live-hash");
    }

    @Test
    @DisplayName("the issuer issues a token bound to the id and the version on Postgres, and a rotation "
            + "consumes the presented refresh token")
    void issueAndRotateOnPostgres() {
        long id = newUser("v180-issue", true, "the-hash");
        writer.apply(id, "the-hash", Change.changePassword(), VersionExpectation.trusted());

        AuthTokens tokens = issuer.issue(id, "the-hash");

        assertThat(jwtService.extractUserId(tokens.accessToken())).isEqualTo(id);
        assertThat(jwtService.extractCredentialsVersion(tokens.accessToken())).isEqualTo(1L);
        TokenIssuer.Rotation rotation = issuer.rotate(jwtService.hashToken(tokens.refreshToken()));
        assertThat(rotation.status()).isEqualTo(TokenIssuer.RotationStatus.ROTATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked = FALSE",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("touchLastActiveAt and updateRole are column-scoped on Postgres")
    void scopedUpdatesOnPostgres() {
        long id = newUser("v180-scoped", true, "the-hash");

        assertThat(users.touchLastActiveAt(id, LocalDateTime.of(2026, 10, 16, 9, 0))).isEqualTo(1);
        assertThat(users.updateRole(id, UserRole.LITE_USER)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT role, last_active_at, password, credentials_version FROM app_user WHERE id = ?", id);
        assertThat(row.get("role")).isEqualTo("LITE_USER");
        assertThat(row.get("last_active_at")).isNotNull();
        assertThat(row.get("password")).isEqualTo("the-hash");
        assertThat(((Number) row.get("credentials_version")).longValue()).isZero();
    }

    @Test
    @DisplayName("the theft response revokes the family on Postgres when called with NO surrounding "
            + "transaction, as AuthenticationService.refresh calls it: Postgres refuses a bulk UPDATE on a "
            + "read-only connection, which H2 does not")
    void familyRevocationCommitsOutsideAnyTransaction() {
        long id = newUser("v180-family", true, "the-hash");
        issuer.issue(id, "the-hash");
        issuer.issue(id, "the-hash");

        familyRevoker.revokeAll(id, "already-revoked token presented");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked = FALSE",
                Integer.class, id)).isZero();
    }
}
