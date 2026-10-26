package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository.DormancyCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the dormant sweep's candidate query and its conditional warning stamp against a real JPA stack: only an
 * enabled, addressable LITE_USER last active at or before the cutoff is a candidate, and the stamp lands once and
 * only while the account still qualifies.
 */
@DataJpaTest
class AppUserDormancyQueriesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 6, 30, 10, 0);
    private static final LocalDateTime CUTOFF = NOW.minusDays(90);

    @Autowired
    private AppUserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private long user(String name, UserRole role, boolean enabled, String password, String email,
            LocalDateTime createdAt, LocalDateTime lastActiveAt) {
        return users.save(AppUserEntity.builder().username(name).email(email).password(password).role(role)
                .enabled(enabled).createdAt(createdAt).lastActiveAt(lastActiveAt).build()).getId();
    }

    private long lite(String name, LocalDateTime lastActiveAt) {
        return user(name, UserRole.LITE_USER, true, "a-hash", name + "@example.com", NOW.minusDays(300),
                lastActiveAt);
    }

    private LocalDateTime stampOf(long id) {
        Timestamp stamp = jdbc.queryForObject("SELECT dormancy_warning_sent_at FROM app_user WHERE id = ?",
                Timestamp.class, id);
        return stamp == null ? null : stamp.toLocalDateTime();
    }

    @Test
    @DisplayName("only an enabled, addressable LITE_USER at or past the inactivity cutoff is a candidate")
    void candidatesAreOnlyDormantEnabledLiteWithAnAddress() {
        long dormant = lite("dormant", NOW.minusDays(100));
        long atCutoff = lite("boundary", CUTOFF);
        long justActive = lite("active", CUTOFF.plusSeconds(1));
        long neverActive = user("never", UserRole.LITE_USER, true, "a-hash", "never@example.com",
                NOW.minusDays(120), null);
        long neverActiveYoung = user("young", UserRole.LITE_USER, true, "a-hash", "young@example.com",
                NOW.minusDays(10), null);
        user("pro", UserRole.PRO_USER, true, "a-hash", "pro@example.com", NOW.minusDays(300), NOW.minusDays(100));
        user("boss", UserRole.ADMIN, true, "a-hash", "boss@example.com", NOW.minusDays(300), NOW.minusDays(100));
        user("off", UserRole.LITE_USER, false, "a-hash", "off@example.com", NOW.minusDays(300),
                NOW.minusDays(100));
        user("pend", UserRole.LITE_USER, false, "", "pend@example.com", NOW.minusDays(300), null);
        user("blank", UserRole.LITE_USER, true, "a-hash", "  ", NOW.minusDays(300), NOW.minusDays(100));
        user("noemail", UserRole.LITE_USER, true, "a-hash", null, NOW.minusDays(300), NOW.minusDays(100));

        List<DormancyCandidate> found = users.findDormancyCandidates(UserRole.LITE_USER, CUTOFF);

        assertThat(found).extracting(DormancyCandidate::getId)
                .containsExactlyInAnyOrder(dormant, atCutoff, neverActive);
        assertThat(found).extracting(DormancyCandidate::getId).doesNotContain(justActive, neverActiveYoung);
        DormancyCandidate row = found.stream().filter(c -> c.getId() == dormant).findFirst().orElseThrow();
        assertThat(row.getUsername()).isEqualTo("dormant");
        assertThat(row.getEmail()).isEqualTo("dormant@example.com");
        assertThat(row.getCreatedAt()).isEqualTo(NOW.minusDays(300));
        assertThat(row.getLastActiveAt()).isEqualTo(NOW.minusDays(100));
        assertThat(row.getDormancyWarningSentAt()).isNull();
    }

    @Test
    @DisplayName("the stamp lands once; a second stamp finds a valid warning and changes nothing")
    void stampLandsOnce() {
        long id = lite("sleeper", NOW.minusDays(100));

        assertThat(users.stampDormancyWarning(id, NOW, UserRole.LITE_USER, CUTOFF)).isEqualTo(1);
        assertThat(stampOf(id)).isEqualTo(NOW);

        assertThat(users.stampDormancyWarning(id, NOW.plusDays(1), UserRole.LITE_USER, CUTOFF)).isZero();
        assertThat(stampOf(id)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a stale warning (older than the last activity) is replaced by a fresh stamp")
    void staleWarningIsRestamped() {
        long id = lite("returned", NOW.minusDays(100));
        jdbc.update("UPDATE app_user SET dormancy_warning_sent_at = ? WHERE id = ?",
                Timestamp.valueOf(NOW.minusDays(150)), id);

        assertThat(users.stampDormancyWarning(id, NOW, UserRole.LITE_USER, CUTOFF)).isEqualTo(1);
        assertThat(stampOf(id)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the stamp is refused for a sign-in, a promotion, a disable, a pending or an address-less account")
    void stampRefusedWhenTheAccountNoLongerQualifies() {
        long signedIn = lite("signedin", CUTOFF.plusSeconds(1));
        long pro = user("pro", UserRole.PRO_USER, true, "a-hash", "pro@example.com", NOW.minusDays(300),
                NOW.minusDays(100));
        long off = user("off", UserRole.LITE_USER, false, "a-hash", "off@example.com", NOW.minusDays(300),
                NOW.minusDays(100));
        long pending = user("pend", UserRole.LITE_USER, false, "", "pend@example.com", NOW.minusDays(300), null);
        long blank = user("blank", UserRole.LITE_USER, true, "a-hash", " ", NOW.minusDays(300),
                NOW.minusDays(100));

        for (long id : new long[] {signedIn, pro, off, pending, blank}) {
            assertThat(users.stampDormancyWarning(id, NOW, UserRole.LITE_USER, CUTOFF)).isZero();
            assertThat(stampOf(id)).isNull();
        }
    }
}
