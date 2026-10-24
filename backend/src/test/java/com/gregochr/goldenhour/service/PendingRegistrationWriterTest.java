package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.EmailVerificationTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.PendingStage;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Claim;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link PendingRegistrationWriter} and the sweep's repository statements (candidate selection, the
 * advance and revert compare-and-sets) against a real JPA stack. No surrounding transaction, so each
 * writer transaction is a real one.
 */
@DataJpaTest
@Import({PendingRegistrationWriter.class, VerificationTokenIssuer.class, PendingRegistrationWriterTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PendingRegistrationWriterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    /** The beans the writer's collaborators inject that the JPA slice does not provide. */
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
    private PendingRegistrationWriter writer;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private EmailVerificationTokenRepository tokens;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM app_user");
    }

    private long user(String name, boolean enabled, String password, UserRole role, LocalDateTime createdAt) {
        return users.save(AppUserEntity.builder().username(name).email(name + "@example.com").password(password)
                .role(role).enabled(enabled).createdAt(createdAt).build()).getId();
    }

    private long pending(String name) {
        return user(name, false, "", UserRole.LITE_USER, NOW.minusHours(100));
    }

    private long token(long userId, String hash, boolean verified, LocalDateTime expiresAt) {
        return tokens.save(EmailVerificationTokenEntity.builder().tokenHash(hash).userId(userId)
                .expiresAt(expiresAt).verified(verified).createdAt(NOW.minusDays(3)).build()).getId();
    }

    private int sent(long id) {
        return jdbc.queryForObject("SELECT verification_reminders_sent FROM app_user WHERE id = ?",
                Integer.class, id);
    }

    private LocalDateTime finalAt(long id) {
        return jdbc.queryForObject("SELECT final_reminder_sent_at FROM app_user WHERE id = ?",
                LocalDateTime.class, id);
    }

    @Test
    @DisplayName("a new row has stage 0 and no final stamp (the column default applies to an insert)")
    void newRowDefaults() {
        long id = pending("fresh");

        assertThat(sent(id)).isZero();
        assertThat(finalAt(id)).isNull();
    }

    @Test
    @DisplayName("candidates: the pending shape older than the cutoff, never an admin or a hashed disabled account; "
            + "the admin-cycled pending shape IS selected")
    void candidateSelection() {
        long old = user("old", false, "", UserRole.LITE_USER, NOW.minusHours(100));
        long adminCycled = user("cycled", false, "", UserRole.PRO_USER, NOW.minusHours(200));
        user("young", false, "", UserRole.LITE_USER, NOW.minusHours(10));
        user("admin", false, "", UserRole.ADMIN, NOW.minusHours(200));
        user("hashed", false, "a-hash", UserRole.LITE_USER, NOW.minusHours(200));
        user("active", true, "a-hash", UserRole.LITE_USER, NOW.minusHours(200));

        List<PendingStage> found = users.findPendingSweepCandidates(NOW.minusHours(96), UserRole.ADMIN);

        assertThat(found).extracting(PendingStage::getId).containsExactlyInAnyOrder(old, adminCycled);
    }

    @Test
    @DisplayName("advance is a compare-and-set: 0 rows when expected mismatches or the row is no longer pending")
    void advanceCompareAndSet() {
        long id = pending("cas");

        assertThat(users.advanceReminderStage(id, 1, 2, NOW)).isZero();
        assertThat(sent(id)).isZero();

        jdbc.update("UPDATE app_user SET enabled = TRUE, password = 'h' WHERE id = ?", id);
        assertThat(users.advanceReminderStage(id, 0, 1, NOW)).isZero();
    }

    @Test
    @DisplayName("the final stamp is written only for next = 2; revert restores both columns")
    void finalStampOnlyForStageTwoAndRevert() {
        long id = pending("stamp");

        assertThat(users.advanceReminderStage(id, 0, 1, NOW)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(1);
        assertThat(finalAt(id)).isNull();

        assertThat(users.advanceReminderStage(id, 1, 2, NOW)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(2);
        assertThat(finalAt(id)).isEqualTo(NOW);

        assertThat(users.revertReminderStage(id, 1, 2, null)).isEqualTo(1);
        assertThat(sent(id)).isEqualTo(1);
        assertThat(finalAt(id)).isNull();
        assertThat(users.revertReminderStage(id, 0, 2, null)).isZero();
    }

    @Test
    @DisplayName("claim advances the stage, mints a fresh link and returns the stored address and its hash")
    void claimAdvancesAndMints() {
        long id = pending("claim");
        token(id, "expired-old", false, NOW.minusHours(1));

        Claim claim = writer.claim(id, 0, 1, NOW);

        assertThat(claim.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(claim.issued().email()).isEqualTo("claim@example.com");
        assertThat(claim.tokenHash()).isEqualTo(jwtService.hashToken(claim.issued().rawToken()));
        assertThat(claim.previousFinalAt()).isNull();
        assertThat(sent(id)).isEqualTo(1);
        assertThat(tokens.findByTokenHash(claim.tokenHash())).isPresent();
        assertThat(tokens.findByTokenHash("expired-old")).isEmpty();
    }

    @Test
    @DisplayName("claim defers, writing nothing, while an UNEXPIRED unverified link exists")
    void claimDefersForUnexpiredUnverifiedLink() {
        long id = pending("defer");
        token(id, "live", false, NOW.plusHours(5));

        Claim claim = writer.claim(id, 0, 1, NOW);

        assertThat(claim.outcome()).isEqualTo(Outcome.DEFERRED);
        assertThat(sent(id)).isZero();
        assertThat(tokens.findByTokenHash("live")).isPresent();
    }

    @Test
    @DisplayName("claim does NOT defer for an expired link, nor for an unexpired VERIFIED one (which survives)")
    void claimIgnoresExpiredAndVerifiedLinks() {
        long expired = pending("expired");
        token(expired, "e", false, NOW.minusMinutes(1));
        long verified = pending("verified");
        token(verified, "v", true, NOW.plusHours(5));

        assertThat(writer.claim(expired, 0, 1, NOW).outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(writer.claim(verified, 0, 1, NOW).outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(tokens.findByTokenHash("v")).isPresent();
    }

    @Test
    @DisplayName("claim skips an activated account, an admin, a mismatched stage and a missing account")
    void claimSkips() {
        long active = user("act", true, "h", UserRole.LITE_USER, NOW.minusHours(100));
        long admin = user("adm", false, "", UserRole.ADMIN, NOW.minusHours(100));
        long staged = pending("staged");
        jdbc.update("UPDATE app_user SET verification_reminders_sent = 1 WHERE id = ?", staged);

        assertThat(writer.claim(active, 0, 1, NOW).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(writer.claim(admin, 0, 1, NOW).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(writer.claim(staged, 0, 1, NOW).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(writer.claim(staged + 1000, 0, 1, NOW).outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    @DisplayName("revert restores the stage and deletes only the link just minted")
    void revertDeletesOnlyTheMintedLink() {
        long id = pending("revert");
        token(id, "verified-survivor", true, NOW.plusHours(5));
        Claim claim = writer.claim(id, 0, 2, NOW);
        assertThat(sent(id)).isEqualTo(2);
        assertThat(finalAt(id)).isEqualTo(NOW);

        writer.revert(id, 0, 2, claim.previousFinalAt(), claim.tokenHash());

        assertThat(sent(id)).isZero();
        assertThat(finalAt(id)).isNull();
        assertThat(tokens.findByTokenHash(claim.tokenHash())).isEmpty();
        assertThat(tokens.findByTokenHash("verified-survivor")).isPresent();
    }
}
