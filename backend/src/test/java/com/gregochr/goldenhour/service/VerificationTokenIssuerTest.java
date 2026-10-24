package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.VerificationTokenIssuer.IssuedVerification;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link VerificationTokenIssuer} against a real JPA stack, with the request-scoped persistence
 * context of open-session-in-view reproduced (see {@code CredentialChangeWriterTest}): the address and the
 * pending state are read as a scalar projection under the row lock, so a resend that resolved the account
 * before an admin's email change committed mails the NEW address and stores no token for the old one
 * (login-registration plan §2 #14 rule 4).
 */
@DataJpaTest
@Import({VerificationTokenIssuer.class, VerificationTokenIssuerTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerificationTokenIssuerTest {

    /** The beans the issuer injects that the JPA slice does not provide. */
    @TestConfiguration
    static class Config {
        @Bean
        JwtProperties jwtProperties() {
            JwtProperties properties = new JwtProperties();
            properties.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktMzI=");
            return properties;
        }

        @Bean
        JwtService jwtService(JwtProperties properties) {
            return new JwtService(properties);
        }
    }

    @Autowired
    private VerificationTokenIssuer issuer;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private EmailVerificationTokenRepository tokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private EntityManager requestScoped;
    private long pendingId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));
        pendingId = users.save(AppUserEntity.builder().username("pending").email("Pending@Example.com")
                .password("").role(UserRole.LITE_USER).enabled(false)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM app_user");
    }

    private long token(String hash, boolean verified) {
        return tokens.save(EmailVerificationTokenEntity.builder().tokenHash(hash).userId(pendingId)
                .expiresAt(LocalDateTime.now().plusHours(12)).verified(verified)
                .createdAt(LocalDateTime.now().minusHours(1)).build()).getId();
    }

    @Test
    @DisplayName("issues a 24-hour link: stores only the HASH of the raw token it returns, with the stored "
            + "address and the username")
    void issue_storesTheHashAndReturnsTheStoredAddress() {
        Optional<IssuedVerification> issued = issuer.issue(pendingId);

        assertThat(issued).isPresent();
        assertThat(issued.get().email()).isEqualTo("Pending@Example.com");
        assertThat(issued.get().username()).isEqualTo("pending");
        List<EmailVerificationTokenEntity> stored = tokens.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash()).isEqualTo(jwtService.hashToken(issued.get().rawToken()));
        assertThat(stored.get(0).getTokenHash()).isNotEqualTo(issued.get().rawToken());
        assertThat(stored.get(0).isVerified()).isFalse();
        assertThat(stored.get(0).getUserId()).isEqualTo(pendingId);
        assertThat(stored.get(0).getExpiresAt())
                .isAfter(LocalDateTime.now().plusHours(23)).isBefore(LocalDateTime.now().plusHours(25));
    }

    @Test
    @DisplayName("a new link supersedes the account's unverified ones, keeps a verified one")
    void issue_deletesUnverifiedLinksOnly() {
        token("unverified", false);
        token("verified", true);

        issuer.issue(pendingId);

        assertThat(tokens.findAll()).extracting(EmailVerificationTokenEntity::getTokenHash)
                .doesNotContain("unverified").contains("verified").hasSize(2);
    }

    @Test
    @DisplayName("an email change committed AFTER the request loaded the account mails the NEW address — the "
            + "stale managed entity is never what is read")
    void issue_afterAnEmailChange_returnsTheNewAddress() {
        AppUserEntity loadedEarlier = users.findById(pendingId).orElseThrow();
        jdbc.update("UPDATE app_user SET email = 'moved@example.com' WHERE id = ?", pendingId);

        Optional<IssuedVerification> issued = issuer.issue(pendingId);

        assertThat(issued).isPresent();
        assertThat(issued.get().email()).isEqualTo("moved@example.com");
        assertThat(loadedEarlier.getEmail()).isEqualTo("Pending@Example.com");
    }

    @Test
    @DisplayName("an account activated after the request loaded it PENDING gets no link, and nothing is stored")
    void issue_afterActivation_storesNothing() {
        AppUserEntity loadedWhilePending = users.findById(pendingId).orElseThrow();
        jdbc.update("UPDATE app_user SET enabled = TRUE, password = 'live-hash' WHERE id = ?", pendingId);

        assertThat(issuer.issue(pendingId)).isEmpty();

        assertThat(tokens.findAll()).isEmpty();
        assertThat(loadedWhilePending.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a disabled account with a password, an account with no address and a missing account get "
            + "no link")
    void issue_otherShapes_storeNothing() {
        jdbc.update("UPDATE app_user SET password = 'hash' WHERE id = ?", pendingId);
        assertThat(issuer.issue(pendingId)).isEmpty();

        jdbc.update("UPDATE app_user SET password = '', email = NULL WHERE id = ?", pendingId);
        assertThat(issuer.issue(pendingId)).isEmpty();

        assertThat(issuer.issue(pendingId + 1000)).isEmpty();
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the raw token is redacted from the record's string form, so a log line cannot leak it")
    void issuedVerification_toStringRedactsTheToken() {
        IssuedVerification issued = new IssuedVerification("a@example.com", "alice", "secret-raw-token");

        assertThat(issued.toString()).doesNotContain("secret-raw-token").contains("a@example.com");
    }

    @Test
    @DisplayName("expiresAt and createdAt come from the injected clock, not the wall clock")
    void issue_usesTheInjectedClock() {
        java.time.Clock fixed = java.time.Clock.fixed(java.time.Instant.parse("2031-03-12T10:00:00Z"),
                java.time.ZoneId.systemDefault());
        VerificationTokenIssuer fixedIssuer = new VerificationTokenIssuer(users, tokens, jwtService, fixed);
        LocalDateTime now = LocalDateTime.now(fixed);

        fixedIssuer.issue(pendingId);

        EmailVerificationTokenEntity stored = tokens.findAll().get(0);
        assertThat(stored.getExpiresAt()).isEqualTo(now.plusHours(VerificationTokenIssuer.TOKEN_EXPIRY_HOURS));
        assertThat(stored.getCreatedAt()).isEqualTo(now);
    }
}
