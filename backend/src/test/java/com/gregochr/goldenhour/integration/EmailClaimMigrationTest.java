package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserEmailClaimRepository;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.EmailClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code app_user_email_claim} half of V179 against a real Postgres: the table's shape, the
 * backfill over a seeded case-duplicate pair (the real SQL, read from the migration file and run against
 * rows seeded afterwards), the primary key's refusal of a second claimant, and {@link EmailClaims} and the
 * repository's native statements on the production dialect — including the one thing H2 cannot show, that
 * the transaction is aborted by the violation.
 *
 * <p>CI-only: it needs the Postgres Testcontainer (no Docker on the development machine, see CLAUDE.md).
 * The same logic runs locally on H2 in {@code AppUserEmailClaimRepositoryTest} and {@code EmailClaimsTest}.
 */
class EmailClaimMigrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private AppUserEmailClaimRepository claims;
    @Autowired
    private EmailClaims emailClaims;
    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user_email_claim");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'v179c-%'");
    }

    private long newUser(String name, String email) {
        return users.save(AppUserEntity.builder().username(name).email(email)
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    private int claimCount(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user_email_claim WHERE email_lower = ?",
                Integer.class, key);
    }

    private static String backfillSql() throws Exception {
        String migration = StreamUtils.copyToString(
                new ClassPathResource("db/migration/V179__password_reset_token_and_email_claim.sql")
                        .getInputStream(), StandardCharsets.UTF_8);
        int start = migration.indexOf("INSERT INTO app_user_email_claim");
        assertThat(start).as("the backfill statement is in the migration").isPositive();
        return migration.substring(start);
    }

    @Test
    @DisplayName("app_user_email_claim has the expected columns and a primary key on email_lower")
    void tableShape() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'app_user_email_claim' ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("claimed_at", "email_lower");
        assertThat(columns).allSatisfy(c -> assertThat(c).containsEntry("is_nullable", "NO"));
        assertThat(columns.get(0)).containsEntry("data_type", "timestamp with time zone");
        assertThat(jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_name = 'app_user_email_claim' AND constraint_type = 'PRIMARY KEY'", String.class))
                .containsExactly("pk_app_user_email_claim");
    }

    @Test
    @DisplayName("the backfill gives a legacy case-duplicate pair ONE reservation, one per other address, and "
            + "none for an account with no address")
    void backfillCollapsesACaseDuplicatePair() throws Exception {
        newUser("v179c-upper", "A@x.com");
        newUser("v179c-lower", "a@x.com");
        newUser("v179c-other", "B@x.com");
        newUser("v179c-none", null);
        jdbc.update("DELETE FROM app_user_email_claim");

        jdbc.update(backfillSql());

        assertThat(claimCount("a@x.com")).isEqualTo(1);
        assertThat(claimCount("b@x.com")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_email_claim WHERE email_lower LIKE '%x.com'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_email_claim WHERE email_lower IS NULL",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("the primary key refuses a second claimant of the same key")
    void primaryKeyRefusesADuplicate() {
        transactions.executeWithoutResult(status -> claims.insertClaim("dup@x.com", NOW));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                claims.insertClaim("dup@x.com", NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(claimCount("dup@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("EmailClaims on Postgres: a new address is reserved, a live case variant is refused, an orphan "
            + "is reused")
    void claimOnPostgres() {
        transactions.executeWithoutResult(status -> emailClaims.claim("Fresh@x.com", null));
        assertThat(claimCount("fresh@x.com")).isEqualTo(1);

        newUser("v179c-holder", "Fresh@x.com");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                emailClaims.claim("FRESH@x.com", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Email already registered");

        jdbc.update("DELETE FROM app_user WHERE username = 'v179c-holder'");
        transactions.executeWithoutResult(status -> emailClaims.claim("fresh@x.com", null));
        assertThat(claimCount("fresh@x.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("releaseIfUnused keeps the key while an account still uses it and frees it for the last, "
            + "on Postgres")
    void releaseOnPostgres() {
        long first = newUser("v179c-first", "Pair@x.com");
        newUser("v179c-second", "PAIR@x.com");
        transactions.executeWithoutResult(status -> claims.insertClaim("pair@x.com", NOW));

        users.deleteById(first);
        Boolean releasedWhileInUse = transactions.execute(status -> emailClaims.release("pair@x.com"));
        assertThat(releasedWhileInUse).isFalse();
        assertThat(claimCount("pair@x.com")).isEqualTo(1);

        jdbc.update("DELETE FROM app_user WHERE username = 'v179c-second'");
        Boolean releasedWhenUnused = transactions.execute(status -> emailClaims.release("pair@x.com"));
        assertThat(releasedWhenUnused).isTrue();
        assertThat(claimCount("pair@x.com")).isZero();
    }
}
