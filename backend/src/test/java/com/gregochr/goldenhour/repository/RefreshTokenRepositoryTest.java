package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link RefreshTokenRepository#deleteAllByExpiresAtBefore} on H2 the way the scheduler calls it: with
 * no surrounding transaction. {@code NOT_SUPPORTED} suspends the {@code @DataJpaTest} transaction, so a delete
 * that does not carry its own fails with "No EntityManager with actual transaction available".
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenRepositoryTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2027, 3, 10, 3, 0);

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM refresh_token");
    }

    private void insert(String hash, LocalDateTime expiresAt) {
        repository.save(RefreshTokenEntity.builder()
                .tokenHash(hash).userId(1L).expiresAt(expiresAt).revoked(false).build());
    }

    @Test
    @DisplayName("deleteAllByExpiresAtBefore outside any transaction deletes only expired rows and returns the count")
    void deleteAllByExpiresAtBefore_noTransaction_deletesOnlyExpired() {
        insert("expired-a", CUTOFF.minusDays(1));
        insert("expired-b", CUTOFF.minusSeconds(1));
        insert("exactly-at-cutoff", CUTOFF);
        insert("live", CUTOFF.plusDays(30));

        long deleted = repository.deleteAllByExpiresAtBefore(CUTOFF);

        assertThat(deleted).isEqualTo(2L);
        List<String> remaining = jdbc.queryForList(
                "SELECT token_hash FROM refresh_token ORDER BY token_hash", String.class);
        assertThat(remaining).containsExactly("exactly-at-cutoff", "live");
    }

    @Test
    @DisplayName("deleteAllByExpiresAtBefore with nothing expired returns zero and keeps every row")
    void deleteAllByExpiresAtBefore_nothingExpired_returnsZero() {
        insert("live", CUTOFF.plusDays(1));

        assertThat(repository.deleteAllByExpiresAtBefore(CUTOFF)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isEqualTo(1);
    }
}
