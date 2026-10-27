package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.model.VerificationSentRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the one query the admin user list adds to {@link EmailVerificationTokenRepository}: when each
 * account's newest unverified link was minted.
 */
@DataJpaTest
class EmailVerificationTokenRepositoryTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Autowired
    private EmailVerificationTokenRepository repository;
    @Autowired
    private TestEntityManager entityManager;

    private void token(String hash, long userId, boolean verified, LocalDateTime createdAt) {
        entityManager.persist(EmailVerificationTokenEntity.builder().tokenHash(hash).userId(userId)
                .expiresAt(createdAt.plusHours(24)).verified(verified).createdAt(createdAt).build());
    }

    @Test
    @DisplayName("each account's newest unverified link is returned once; verified links and accounts with "
            + "none are left out")
    void findLatestUnverifiedCreatedAt() {
        token("a1", 1L, false, NOON.minusHours(5));
        token("a2", 1L, false, NOON.minusHours(1));
        token("b1", 2L, false, NOON.minusDays(2));
        token("c1", 3L, true, NOON);
        entityManager.flush();

        List<VerificationSentRow> rows = repository.findLatestUnverifiedCreatedAt();

        assertThat(rows).containsExactlyInAnyOrder(
                new VerificationSentRow(1L, NOON.minusHours(1)),
                new VerificationSentRow(2L, NOON.minusDays(2)));
    }
}
