package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V194 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}): the four
 * invitation columns' types and nullability, the unique key on the token hash (many NULLs allowed, a repeated
 * hash refused), the entity mapping passing {@code ddl-auto: validate}, and the three scoped writes
 * ({@code markInvited}, {@code redeemInvite}, {@code deleteByEmailIgnoreCase}) behaving as specified.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine (see
 * CLAUDE.md), so the migration is proven before merge only by CI's Backend job.
 */
class WaitlistInvitesMigrationTest extends IntegrationTestBase {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 7, 12, 0);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WaitlistEmailRepository waitlist;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM waitlist_email WHERE email LIKE 'v194-%'");
    }

    private long entry(String name) {
        return waitlist.save(WaitlistEmailEntity.builder().email("v194-" + name + "@example.com")
                .submittedAt(NOW.minusDays(1)).build()).getId();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT invite_token_hash, invited_at, invite_expires_at, invite_used_at "
                + "FROM waitlist_email WHERE id = ?", id);
    }

    @Test
    @DisplayName("the four columns exist: a nullable VARCHAR(64) hash and three nullable zone-less TIMESTAMPs")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns WHERE table_name = 'waitlist_email' "
                        + "AND column_name IN ('invite_token_hash', 'invited_at', 'invite_expires_at', "
                        + "'invite_used_at')");

        assertThat(columns).hasSize(4);
        for (Map<String, Object> column : columns) {
            assertThat(column).containsEntry("is_nullable", "YES");
            if ("invite_token_hash".equals(column.get("column_name"))) {
                assertThat(column).containsEntry("data_type", "character varying")
                        .containsEntry("character_maximum_length", 64);
            } else {
                assertThat(column).containsEntry("data_type", "timestamp without time zone");
            }
        }
    }

    @Test
    @DisplayName("a row saved through the entity reads all four columns NULL, and the mapping passes validation")
    void entityInsertLeavesTheColumnsNull() {
        long id = entry("fresh");

        Map<String, Object> row = row(id);

        assertThat(row.values()).containsOnlyNulls();
        assertThat(waitlist.findById(id).orElseThrow().getInviteTokenHash()).isNull();
    }

    @Test
    @DisplayName("the hash is UNIQUE: many rows may hold NULL, a second row cannot take a stored hash")
    void hashIsUnique() {
        long first = entry("one");
        long second = entry("two");
        entry("three");
        assertThat(waitlist.markInvited(first, "a".repeat(64), NOW, NOW.plusDays(7))).isEqualTo(1);

        assertThatThrownBy(() -> waitlist.markInvited(second, "a".repeat(64), NOW, NOW.plusDays(7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("markInvited stamps hash and dates, and a re-invite replaces the hash and clears the used mark")
    void markInvitedReplacesAndClears() {
        long id = entry("invitee");
        assertThat(waitlist.markInvited(id, "b".repeat(64), NOW, NOW.plusDays(7))).isEqualTo(1);
        assertThat(waitlist.redeemInvite("b".repeat(64), NOW.plusHours(1), "v194-invitee@example.com"))
                .isEqualTo(1);
        assertThat(row(id).get("invite_used_at")).isNotNull();

        assertThat(waitlist.markInvited(id, "c".repeat(64), NOW.plusDays(1), NOW.plusDays(8))).isEqualTo(1);

        Map<String, Object> row = row(id);
        assertThat(row.get("invite_token_hash")).isEqualTo("c".repeat(64));
        assertThat(row.get("invite_used_at")).isNull();
        assertThat(waitlist.markInvited(-1L, "d".repeat(64), NOW, NOW.plusDays(7))).isZero();
    }

    @Test
    @DisplayName("redeemInvite succeeds once, for the matching address in any case, and then never again")
    void redeemIsOnceOnly() {
        long id = entry("once");
        waitlist.markInvited(id, "e".repeat(64), NOW, NOW.plusDays(7));

        assertThat(waitlist.redeemInvite("e".repeat(64), NOW.plusHours(1), "V194-Once@Example.com")).isEqualTo(1);
        assertThat(waitlist.redeemInvite("e".repeat(64), NOW.plusHours(2), "v194-once@example.com")).isZero();
    }

    @Test
    @DisplayName("redeemInvite refuses another address, an expired invitation and an unknown hash")
    void redeemRefusals() {
        long id = entry("guarded");
        waitlist.markInvited(id, "f".repeat(64), NOW, NOW.plusDays(7));

        assertThat(waitlist.redeemInvite("f".repeat(64), NOW.plusHours(1), "v194-other@example.com")).isZero();
        assertThat(waitlist.redeemInvite("f".repeat(64), NOW.plusDays(7), "v194-guarded@example.com")).isZero();
        assertThat(waitlist.redeemInvite("0".repeat(64), NOW.plusHours(1), "v194-guarded@example.com")).isZero();
        assertThat(row(id).get("invite_used_at")).isNull();
    }

    @Test
    @DisplayName("deleteByEmailIgnoreCase removes the row whatever the case, and only that row")
    void deleteByEmail() {
        long gone = entry("gone");
        long kept = entry("kept");

        assertThat(waitlist.deleteByEmailIgnoreCase("V194-Gone@Example.com")).isEqualTo(1);

        assertThat(waitlist.existsById(gone)).isFalse();
        assertThat(waitlist.existsById(kept)).isTrue();
    }
}
