package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link PasswordResetTokenRepository} against a real JPA stack: the lookup by hash, the
 * compare-and-set that consumes a token, and the delete of unused links (login-registration plan §2 #1).
 */
@DataJpaTest
class PasswordResetTokenRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private PasswordResetTokenRepository repository;
    @Autowired
    private TestEntityManager entityManager;

    private PasswordResetTokenEntity token(String hash, long userId, Instant usedAt) {
        return entityManager.persistFlushFind(PasswordResetTokenEntity.builder().tokenHash(hash).userId(userId)
                .expiresAt(NOW.plusSeconds(3600)).createdAt(NOW).usedAt(usedAt).build());
    }

    @Test
    @DisplayName("findByTokenHash finds the token by its hash and only by it")
    void findByTokenHash() {
        PasswordResetTokenEntity saved = token("abc", 1L, null);

        assertThat(repository.findByTokenHash("abc")).map(PasswordResetTokenEntity::getId)
                .contains(saved.getId());
        assertThat(repository.findByTokenHash("ABC")).isEmpty();
    }

    @Test
    @DisplayName("markUsed consumes an unused token once (1) and refuses the second consumer (0), "
            + "keeping the first redemption's instant")
    void markUsed_isACompareAndSet() {
        PasswordResetTokenEntity saved = token("abc", 1L, null);

        assertThat(repository.markUsed(saved.getId(), NOW)).isEqualTo(1);
        assertThat(repository.markUsed(saved.getId(), NOW.plusSeconds(60))).isZero();

        assertThat(repository.findByTokenHash("abc").orElseThrow().getUsedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markUsed on an unknown id consumes nothing")
    void markUsed_unknownId() {
        assertThat(repository.markUsed(999L, NOW)).isZero();
    }

    @Test
    @DisplayName("deleteByUserIdAndUsedAtIsNull removes this user's unused links and keeps their redeemed "
            + "ones and every other user's")
    void deleteUnused_isScopedToUnusedLinksOfOneUser() {
        token("unused-1", 1L, null);
        token("unused-2", 1L, null);
        token("used", 1L, NOW);
        token("others", 2L, null);

        assertThat(repository.deleteByUserIdAndUsedAtIsNull(1L)).isEqualTo(2);

        assertThat(repository.findAll()).extracting(PasswordResetTokenEntity::getTokenHash)
                .containsExactlyInAnyOrder("used", "others");
    }

    @Test
    @DisplayName("a token hash is unique — the last statement of this test")
    void tokenHashIsUnique() {
        token("dup", 1L, null);

        assertThatThrownBy(() -> token("dup", 2L, null)).isInstanceOf(PersistenceException.class);
    }
}
