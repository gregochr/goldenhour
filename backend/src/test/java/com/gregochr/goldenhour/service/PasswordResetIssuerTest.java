package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.service.PasswordResetIssuer.IssuedLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link PasswordResetIssuer} against a real JPA stack, with the request-scoped persistence context
 * of open-session-in-view reproduced (see {@code CredentialChangeWriterTest}): the account's address and
 * state are read as a scalar projection under the lock, so an email change or a disable that committed
 * after the request loaded the entity is seen, and no link is minted for the old mailbox.
 */
@DataJpaTest
@Import({PasswordResetIssuer.class, PasswordResetIssuerTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetIssuerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** Supplies the application {@link Clock} bean the issuer injects. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private PasswordResetIssuer issuer;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private EntityManager requestScoped;
    private long userId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));
        userId = users.save(AppUserEntity.builder().username("reader").email("Reader@Example.com")
                .password("hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    @DisplayName("issues a link expiring in exactly one hour, stores only the hash, and returns the stored "
            + "address and username")
    void issue_storesTheHashAndReturnsTheStoredAddress() {
        Optional<IssuedLink> link = issuer.issue(userId, "hash-of-raw");

        assertThat(link).contains(new IssuedLink("Reader@Example.com", "reader"));
        List<PasswordResetTokenEntity> stored = tokens.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash()).isEqualTo("hash-of-raw");
        assertThat(stored.get(0).getUserId()).isEqualTo(userId);
        assertThat(stored.get(0).getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.get(0).getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(stored.get(0).getUsedAt()).isNull();
    }

    @Test
    @DisplayName("a new link supersedes the account's unused ones but keeps redeemed ones")
    void issue_deletesUnusedLinksOnly() {
        tokens.save(PasswordResetTokenEntity.builder().tokenHash("unused").userId(userId)
                .expiresAt(NOW.plusSeconds(60)).createdAt(NOW.minusSeconds(60)).build());
        tokens.save(PasswordResetTokenEntity.builder().tokenHash("used").userId(userId)
                .expiresAt(NOW.plusSeconds(60)).createdAt(NOW.minusSeconds(60))
                .usedAt(NOW.minusSeconds(30)).build());

        issuer.issue(userId, "fresh");

        assertThat(tokens.findAll()).extracting(PasswordResetTokenEntity::getTokenHash)
                .containsExactlyInAnyOrder("used", "fresh");
    }

    @Test
    @DisplayName("an email change committed AFTER the request loaded the account mails the NEW address — "
            + "the stale managed entity is never what is read")
    void issue_afterAnEmailChange_returnsTheNewAddress() {
        AppUserEntity loadedEarlier = users.findById(userId).orElseThrow();
        jdbc.update("UPDATE app_user SET email = ? WHERE id = ?", "moved@example.com", userId);

        Optional<IssuedLink> link = issuer.issue(userId, "hash");

        assertThat(link).contains(new IssuedLink("moved@example.com", "reader"));
        // The trap, pinned: the entity the request holds still carries the old address.
        assertThat(loadedEarlier.getEmail()).isEqualTo("Reader@Example.com");
    }

    @Test
    @DisplayName("an account disabled after the request loaded it gets no link, and nothing is stored")
    void issue_afterADisable_storesNothing() {
        users.findById(userId).orElseThrow();
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

        assertThat(issuer.issue(userId, "hash")).isEmpty();
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a pending registration, an account with no address and a missing account get no link")
    void issue_inactiveShapes_storeNothing() {
        jdbc.update("UPDATE app_user SET enabled = FALSE, password = '' WHERE id = ?", userId);
        assertThat(issuer.issue(userId, "pending")).isEmpty();

        jdbc.update("UPDATE app_user SET enabled = TRUE, password = 'hash', email = NULL WHERE id = ?", userId);
        assertThat(issuer.issue(userId, "no-address")).isEmpty();

        assertThat(issuer.issue(userId + 1000, "missing")).isEmpty();
        assertThat(tokens.findAll()).isEmpty();
    }
}
