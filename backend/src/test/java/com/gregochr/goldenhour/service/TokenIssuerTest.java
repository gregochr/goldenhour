package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.TokenIssuer.Rotation;
import com.gregochr.goldenhour.service.TokenIssuer.RotationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link TokenIssuer} against a real JPA stack (login-registration plan §2 #13, §2 #14 rule 1,
 * rule 9): issuance re-reads the stored hash, credential version and enabled flag as a scalar projection
 * UNDER the user-row lock — with the request's persistence context reproduced as open-session-in-view
 * builds it, holding the entity loaded before the slow hash — and refreshes rotate under the same lock.
 *
 * <p>The class runs with no surrounding transaction ({@code NOT_SUPPORTED}) so that a concurrent change
 * committed through {@link JdbcTemplate} is visible to the next read, and so that the two-thread cases can
 * hold a row lock in one thread while another waits.
 */
@DataJpaTest
@Import({TokenIssuer.class, CredentialChangeWriter.class, TokenIssuerTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** The beans the issuer and the writer inject that the JPA slice does not provide. */
    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

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
    private TokenIssuer issuer;
    @Autowired
    private CredentialChangeWriter writer;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private EmailVerificationTokenRepository verificationTokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private EntityManager requestScoped;
    private long userId;

    @BeforeEach
    void bindRequestScopedPersistenceContext() {
        requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));
        userId = users.save(AppUserEntity.builder().username("reader").email("reader@example.com")
                .password("hash").role(UserRole.PRO_USER).enabled(true).passwordChangeRequired(true)
                .marketingEmailOptIn(false).termsVersion("April 2026")
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build()).getId();
    }

    @AfterEach
    void unbindAndClean() {
        TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        requestScoped.close();
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM app_user");
    }

    private int activeRefreshTokens() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE revoked = FALSE AND user_id = ?",
                Integer.class, userId);
    }

    private String storeRefreshToken(String raw, boolean revoked, LocalDateTime expiresAt) {
        String hash = jwtService.hashToken(raw);
        refreshTokens.save(RefreshTokenEntity.builder().tokenHash(hash).userId(userId).expiresAt(expiresAt)
                .revoked(revoked).build());
        return hash;
    }

    @Nested
    class Issue {

        @Test
        @DisplayName("issues a pair for the hash the caller verified: the access token is bound to the account "
                + "id and the credential version read under the lock, the refresh row is inserted hashed, "
                + "and the response fields come from the row")
        void issuesABoundPair() {
            jdbc.update("UPDATE app_user SET credentials_version = 3 WHERE id = ?", userId);

            AuthTokens tokens = issuer.issue(userId, "hash");

            assertThat(jwtService.extractUserId(tokens.accessToken())).isEqualTo(userId);
            assertThat(jwtService.extractCredentialsVersion(tokens.accessToken())).isEqualTo(3L);
            assertThat(jwtService.extractUsername(tokens.accessToken())).isEqualTo("reader");
            assertThat(JwtTestClaims.roleOf(tokens.accessToken())).isEqualTo(UserRole.PRO_USER);
            assertThat(tokens.username()).isEqualTo("reader");
            assertThat(tokens.role()).isEqualTo("PRO_USER");
            assertThat(tokens.passwordChangeRequired()).isTrue();
            assertThat(tokens.marketingEmailOptIn()).isFalse();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");
            RefreshTokenEntity stored = refreshTokens.findByTokenHash(jwtService.hashToken(tokens.refreshToken()))
                    .orElseThrow();
            assertThat(stored.getUserId()).isEqualTo(userId);
            assertThat(stored.isRevoked()).isFalse();
            assertThat(tokens.refreshTokenExpiresAt()).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
        }

        @Test
        @DisplayName("refuses with the generic failure when the stored hash moved under it — the request "
                + "still holds the entity with the OLD hash — and inserts no refresh row")
        void refusesWhenTheHashMoved() {
            AppUserEntity loadedBeforeTheHash = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET password = 'hash-committed-meanwhile' WHERE id = ?", userId);

            assertThatThrownBy(() -> issuer.issue(userId, "hash"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isZero();
            // The trap, pinned: an issuer that compared against this entity would have passed.
            assertThat(loadedBeforeTheHash.getPassword()).isEqualTo("hash");
        }

        @Test
        @DisplayName("refuses an account an admin disabled after the request loaded it ENABLED")
        void refusesADisabledAccount() {
            AppUserEntity loadedBeforeTheDisable = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

            assertThatThrownBy(() -> issuer.issue(userId, "hash"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isZero();
            assertThat(loadedBeforeTheDisable.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("reads the credential version from the ROW, not the entity the request loaded: a bump "
                + "committed meanwhile is what the token carries")
        void readsTheCredentialVersionUnderTheLock() {
            AppUserEntity loadedBeforeTheBump = users.findById(userId).orElseThrow();
            jdbc.update("UPDATE app_user SET credentials_version = 5 WHERE id = ?", userId);

            AuthTokens tokens = issuer.issue(userId, "hash");

            assertThat(jwtService.extractCredentialsVersion(tokens.accessToken())).isEqualTo(5L);
            assertThat(loadedBeforeTheBump.getCredentialsVersion()).isZero();
        }

        @Test
        @DisplayName("refuses a pending registration, an unknown account and a null hash")
        void refusesTheOtherShapes() {
            jdbc.update("UPDATE app_user SET enabled = FALSE, password = '' WHERE id = ?", userId);
            assertThatThrownBy(() -> issuer.issue(userId, "")).isInstanceOf(InvalidCredentialsException.class);

            jdbc.update("UPDATE app_user SET enabled = TRUE, password = 'hash' WHERE id = ?", userId);
            assertThatThrownBy(() -> issuer.issue(userId, null)).isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> issuer.issue(userId + 1000, "hash"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        @Test
        @DisplayName("the row lock is TAKEN: while another transaction holds the account row, issue() waits for "
                + "it, and returns only after that transaction committed")
        void waitsForTheRowLock() throws Exception {
            CountDownLatch holding = new CountDownLatch(1);
            AtomicBoolean released = new AtomicBoolean();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            users.lockAccount(userId);
                            holding.countDown();
                            sleep(700);
                            released.set(true);
                        }));
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

                long started = System.nanoTime();
                AuthTokens tokens = issuer.issue(userId, "hash");
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(released.get()).as("issue() returned before the lock holder committed").isTrue();
                assertThat(waitedMillis).isGreaterThanOrEqualTo(300);
                assertThat(tokens.accessToken()).isNotBlank();
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    class Rotate {

        @Test
        @DisplayName("consumes the presented token (revoked), inserts the replacement and returns ROTATED "
                + "with a pair bound to the row's role and credential version")
        void rotates() {
            String hash = storeRefreshToken("old-raw", false, LocalDateTime.now().plusDays(10));
            jdbc.update("UPDATE app_user SET credentials_version = 2 WHERE id = ?", userId);

            Rotation rotation = issuer.rotate(hash);

            assertThat(rotation.status()).isEqualTo(RotationStatus.ROTATED);
            assertThat(rotation.userId()).isEqualTo(userId);
            assertThat(jwtService.extractCredentialsVersion(rotation.tokens().accessToken())).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT revoked FROM refresh_token WHERE token_hash = ?",
                    Boolean.class, hash)).isTrue();
            assertThat(activeRefreshTokens()).isEqualTo(1);
        }

        @Test
        @DisplayName("an already-revoked token returns REVOKED_SEEN as an OUTCOME (no exception, so the "
                + "caller's family revocation can commit) and writes nothing")
        void revokedTokenIsAnOutcome() {
            String hash = storeRefreshToken("old-raw", true, LocalDateTime.now().plusDays(10));
            storeRefreshToken("sibling", false, LocalDateTime.now().plusDays(10));

            Rotation rotation = issuer.rotate(hash);

            assertThat(rotation.status()).isEqualTo(RotationStatus.REVOKED_SEEN);
            assertThat(rotation.userId()).isEqualTo(userId);
            assertThat(rotation.tokens()).isNull();
            assertThat(activeRefreshTokens()).isEqualTo(1);
        }

        @Test
        @DisplayName("a revoked token that was issued BEFORE the account's credentials last changed is "
                + "REVOKED_BY_CREDENTIAL_CHANGE, not theft: the change revoked it, and the device that made "
                + "the change must keep its fresh tokens")
        void tokenRevokedByACredentialChange_isNotTheft() {
            // Issued 5 days ago (expires in 25 of its 30 days); the credentials changed 1 day ago.
            String stale = storeRefreshToken("stale-device", true, LocalDateTime.now().plusDays(25));
            jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(24 * 3600)), userId);

            Rotation rotation = issuer.rotate(stale);

            assertThat(rotation.status()).isEqualTo(RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE);
            assertThat(rotation.userId()).isEqualTo(userId);
            assertThat(rotation.tokens()).isNull();
        }

        @Test
        @DisplayName("a revoked token issued AFTER the credentials last changed is still REVOKED_SEEN: only "
                + "rotation or a logout can have revoked it, so replaying it is the theft signal")
        void tokenIssuedAfterTheChange_isStillTheftEvidence() {
            // Issued 1 hour ago; the credentials changed 1 day ago.
            String fresh = storeRefreshToken("same-family", true,
                    LocalDateTime.now().plusDays(30).minusHours(1));
            jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(24 * 3600)), userId);

            assertThat(issuer.rotate(fresh).status()).isEqualTo(RotationStatus.REVOKED_SEEN);
        }

        @Test
        @DisplayName("a token revoked by a LOGOUT (bumpCredentialsVersion, which stamps nothing) is still "
                + "REVOKED_SEEN: logout raises the version, not credentials_changed_at")
        void tokenRevokedByLogout_isStillTheftEvidence() {
            String hash = storeRefreshToken("logged-out", false, LocalDateTime.now().plusDays(10));
            refreshTokens.revokeIfActive(hash);

            assertThat(users.bumpCredentialsVersion(userId)).isEqualTo(1);

            assertThat(jdbc.queryForObject("SELECT credentials_version FROM app_user WHERE id = ?",
                    Long.class, userId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT credentials_changed_at FROM app_user WHERE id = ?",
                    java.sql.Timestamp.class, userId)).isNull();
            assertThat(issuer.rotate(hash).status()).isEqualTo(RotationStatus.REVOKED_SEEN);
        }

        @Test
        @DisplayName("rotateOnVerify is a compare-and-set: the first call rotates the unverified row, a second "
                + "with the same old hash changes nothing")
        void rotateOnVerify_isACompareAndSet() {
            Long id = verificationTokens.save(com.gregochr.goldenhour.entity.EmailVerificationTokenEntity.builder()
                    .tokenHash("old-hash").userId(userId).expiresAt(LocalDateTime.now().plusHours(5))
                    .verified(false).createdAt(LocalDateTime.now()).build()).getId();
            LocalDateTime expiry = LocalDateTime.now().plusMinutes(30);

            assertThat(verificationTokens.rotateOnVerify(id, "old-hash", "new-hash", expiry)).isEqualTo(1);
            assertThat(verificationTokens.rotateOnVerify(id, "old-hash", "other-hash", expiry)).isZero();

            assertThat(verificationTokens.findByTokenHash("new-hash")).get()
                    .satisfies(t -> assertThat(t.isVerified()).isTrue());
            assertThat(verificationTokens.findByTokenHash("old-hash")).isEmpty();
        }

        @Test
        @DisplayName("an account whose credentials never changed has no stamp to compare: REVOKED_SEEN")
        void neverChangedAccount_isTheftEvidence() {
            String revoked = storeRefreshToken("rotated-away", true, LocalDateTime.now().plusDays(10));

            assertThat(issuer.rotate(revoked).status()).isEqualTo(RotationStatus.REVOKED_SEEN);
        }

        @Test
        @DisplayName("losing the compare-and-set to a concurrent refresh returns LOST_RACE and issues "
                + "nothing — the request read the token as unrevoked, the row says otherwise")
        void lostRaceIsAnOutcome() {
            String hash = storeRefreshToken("old-raw", false, LocalDateTime.now().plusDays(10));
            refreshTokens.findByTokenHash(hash).orElseThrow();
            jdbc.update("UPDATE refresh_token SET revoked = TRUE WHERE token_hash = ?", hash);

            Rotation rotation = issuer.rotate(hash);

            assertThat(rotation.status()).isEqualTo(RotationStatus.LOST_RACE);
            assertThat(rotation.tokens()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a token read as LIVE that a credential change revoked while this refresh waited for the "
                + "row lock is REVOKED_BY_CREDENTIAL_CHANGE, not LOST_RACE: the change's doing is not theft")
        void lostToACredentialChange_isNotTheft() {
            String hash = storeRefreshToken("old-raw", false, LocalDateTime.now().plusDays(10));
            refreshTokens.findByTokenHash(hash).orElseThrow();
            jdbc.update("UPDATE refresh_token SET revoked = TRUE WHERE token_hash = ?", hash);
            jdbc.update("UPDATE app_user SET credentials_changed_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), userId);

            assertThat(issuer.rotate(hash).status()).isEqualTo(RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE);
        }

        @Test
        @DisplayName("rotate takes the ACCOUNT row lock before it touches the refresh row: while another "
                + "transaction holds the account row and then revokes this user's refresh rows, the rotation "
                + "waits, completes without deadlock after that commit, and loses the compare-and-set")
        void rotateLocksTheAccountFirst() throws Exception {
            String hash = storeRefreshToken("old-raw", false, LocalDateTime.now().plusDays(10));
            CountDownLatch holding = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            users.lockAccount(userId);
                            holding.countDown();
                            sleep(700);
                            refreshTokens.revokeAllActiveByUserId(userId);
                        }));
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

                long started = System.nanoTime();
                Rotation rotation = issuer.rotate(hash);
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(waitedMillis).isGreaterThanOrEqualTo(300);
                assertThat(rotation.status()).isIn(RotationStatus.LOST_RACE,
                        RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE);
                assertThat(activeRefreshTokens()).isZero();
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("an unknown or expired token is refused 'invalid or expired' and consumes nothing")
        void unknownAndExpiredAreRefused() {
            assertThatThrownBy(() -> issuer.rotate("no-such-hash"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            String expired = storeRefreshToken("old-raw", false, LocalDateTime.now().minusMinutes(1));
            assertThatThrownBy(() -> issuer.rotate(expired))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");
            assertThat(activeRefreshTokens()).isEqualTo(1);
        }

        @Test
        @DisplayName("a disabled or missing account is refused 'User not found or disabled' and the token "
                + "is NOT consumed")
        void disabledAccountIsRefusedWithoutConsuming() {
            String hash = storeRefreshToken("old-raw", false, LocalDateTime.now().plusDays(10));
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", userId);

            assertThatThrownBy(() -> issuer.rotate(hash))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found or disabled");

            assertThat(activeRefreshTokens()).isEqualTo(1);
        }
    }

    @Nested
    class AgainstTheWriter {

        @Test
        @DisplayName("the writer takes the ACCOUNT row lock: while another transaction holds the row, "
                + "apply() waits for it and returns only after that transaction committed")
        void writerWaitsForTheRowLock() throws Exception {
            CountDownLatch holding = new CountDownLatch(1);
            AtomicBoolean released = new AtomicBoolean();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            users.lockAccount(userId);
                            holding.countDown();
                            sleep(700);
                            released.set(true);
                        }));
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

                long started = System.nanoTime();
                writer.apply(userId, "new-hash", Change.changePassword(), VersionExpectation.trusted());
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(released.get()).as("apply() returned before the lock holder committed").isTrue();
                assertThat(waitedMillis).isGreaterThanOrEqualTo(300);
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("a credential change and a refresh of the same session, run concurrently 20 times, never "
                + "deadlock: both take the account row first. A refresh that wins issues a token carrying the "
                + "OLD version and whose refresh row the change then revokes; one that loses is LOST_RACE")
        void writerAndRefreshDoNotDeadlock() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 20; round++) {
                    long version = jdbc.queryForObject("SELECT credentials_version FROM app_user WHERE id = ?",
                            Long.class, userId);
                    String hash = storeRefreshToken("session-" + round, false, LocalDateTime.now().plusDays(10));
                    CountDownLatch go = new CountDownLatch(1);

                    Future<?> change = executor.submit(() -> {
                        go.await();
                        return writer.apply(userId, "hash", Change.changePassword(),
                                VersionExpectation.trusted());
                    });
                    Future<Rotation> refresh = executor.submit(() -> {
                        go.await();
                        return issuer.rotate(hash);
                    });
                    go.countDown();

                    change.get(15, TimeUnit.SECONDS);
                    Rotation rotation = refresh.get(15, TimeUnit.SECONDS);

                    assertThat(jdbc.queryForObject("SELECT credentials_version FROM app_user WHERE id = ?",
                            Long.class, userId)).isEqualTo(version + 1);
                    assertThat(activeRefreshTokens()).as("round %d: no session survives the change", round)
                            .isZero();
                    // Whichever order the two transactions took: the refresh either issued wholly before the
                    // change (ROTATED), found its token already consumed by it (LOST_RACE, when it had read
                    // the token as live first) or read the token after the change had revoked it
                    // (REVOKED_BY_CREDENTIAL_CHANGE, since the change stamped the row).
                    assertThat(rotation.status()).isIn(RotationStatus.ROTATED, RotationStatus.LOST_RACE,
                            RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE, RotationStatus.REVOKED_SEEN);
                    if (rotation.status() == RotationStatus.ROTATED) {
                        assertThat(jwtService.extractCredentialsVersion(rotation.tokens().accessToken()))
                                .as("a rotation that won issued before the change, so its cv is stale")
                                .isEqualTo(version);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
