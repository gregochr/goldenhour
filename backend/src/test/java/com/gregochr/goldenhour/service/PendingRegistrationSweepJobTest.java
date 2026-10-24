package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.RegistrationProperties;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.PendingStage;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Claim;
import com.gregochr.goldenhour.service.PendingRegistrationWriter.Outcome;
import com.gregochr.goldenhour.service.VerificationTokenIssuer.IssuedVerification;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingRegistrationSweepJob}'s decisions, on a fixed clock in the system default zone
 * (the zone the job reads), with the writer, user service and mailer mocked.
 */
@ExtendWith(MockitoExtension.class)
class PendingRegistrationSweepJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneId.systemDefault());
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Mock
    private AppUserRepository users;
    @Mock
    private PendingRegistrationWriter writer;
    @Mock
    private UserService userService;
    @Mock
    private UserEmailService mail;
    @Mock
    private DynamicSchedulerService scheduler;

    private PendingRegistrationSweepJob job;

    /** A candidate row. */
    private record Stage(Long id, LocalDateTime createdAt, int remindersSent, LocalDateTime finalAt)
            implements PendingStage {
        @Override
        public Long getId() {
            return id;
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        @Override
        public int getRemindersSent() {
            return remindersSent;
        }

        @Override
        public LocalDateTime getFinalReminderSentAt() {
            return finalAt;
        }
    }

    @BeforeEach
    void setUp() {
        job = new PendingRegistrationSweepJob(users, writer, userService, mail, scheduler,
                new RegistrationProperties(), CLOCK);
    }

    private void candidates(Stage... stages) {
        when(users.findPendingSweepCandidates(NOW.minusHours(96), UserRole.ADMIN)).thenReturn(List.of(stages));
    }

    private static LocalDateTime ageHours(long hours) {
        return NOW.minusHours(hours);
    }

    private void configured() {
        when(mail.isConfigured()).thenReturn(true);
    }

    private Claim claimed(String token) {
        return new Claim(Outcome.CLAIMED, new IssuedVerification("a@example.com", "alice", token), "hash-" + token,
                null);
    }

    @Test
    @DisplayName("registers itself with the scheduler under the V190 job key")
    void registersItself() {
        job.registerJob();

        verify(scheduler).registerJobTarget(org.mockito.ArgumentMatchers.eq("pending_registration_sweep"),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("at exactly 96 h a never-reminded account gets the first reminder, lapsing at created + 168 h")
    void firstReminderAtExactly96h() {
        configured();
        candidates(new Stage(1L, ageHours(96), 0, null));
        when(writer.claim(1L, 0, 1, NOW)).thenReturn(claimed("t1"));
        when(mail.sendVerificationReminderEmail("a@example.com", "alice", "t1", false,
                ageHours(96).plusHours(168))).thenReturn(true);

        job.sweep();

        verify(mail).sendVerificationReminderEmail("a@example.com", "alice", "t1", false,
                ageHours(96).plusHours(168));
        verify(writer, never()).revert(1L, 0, 1, null, "hash-t1");
    }

    @Test
    @DisplayName("one minute before 96 h nothing is sent")
    void nothingOneMinuteBefore96h() {
        configured();
        candidates(new Stage(1L, ageHours(96).plus(MINUTE), 0, null));

        job.sweep();

        verifyNoInteractions(writer, userService);
    }

    @Test
    @DisplayName("at exactly 144 h (first already sent) the final reminder goes, lapsing 24 h from now")
    void finalReminderAtExactly144h() {
        configured();
        candidates(new Stage(2L, ageHours(144), 1, null));
        when(writer.claim(2L, 1, 2, NOW)).thenReturn(claimed("t2"));
        when(mail.sendVerificationReminderEmail("a@example.com", "alice", "t2", true, NOW.plusHours(24)))
                .thenReturn(true);

        job.sweep();

        verify(mail).sendVerificationReminderEmail("a@example.com", "alice", "t2", true, NOW.plusHours(24));
    }

    @Test
    @DisplayName("one minute before 144 h, with the first reminder sent, nothing happens")
    void nothingOneMinuteBefore144h() {
        configured();
        candidates(new Stage(2L, ageHours(144).plus(MINUTE), 1, null));

        job.sweep();

        verifyNoInteractions(writer, userService);
    }

    @Test
    @DisplayName("a stage already sent is not resent: sent=1 at 100 h does nothing")
    void alreadySentStageIsNotResent() {
        configured();
        candidates(new Stage(3L, ageHours(100), 1, null));

        job.sweep();

        verifyNoInteractions(writer, userService);
    }

    @Test
    @DisplayName("a 30-day account with sent=0 (created after deploy, mailer was down) gets the final reminder, "
            + "NOT a purge")
    void thirtyDayAccountWithNoReminderGetsFinalReminder() {
        configured();
        candidates(new Stage(4L, ageHours(30 * 24), 0, null));
        when(writer.claim(4L, 0, 2, NOW)).thenReturn(claimed("t4"));
        when(mail.sendVerificationReminderEmail("a@example.com", "alice", "t4", true, NOW.plusHours(24)))
                .thenReturn(true);

        job.sweep();

        verify(mail).sendVerificationReminderEmail("a@example.com", "alice", "t4", true, NOW.plusHours(24));
        verify(userService, never()).purgeAbandonedPending(4L, NOW);
    }

    @Test
    @DisplayName("purges at exactly final-sent + 24 h")
    void purgesAtExactlyFinalPlus24h() {
        configured();
        candidates(new Stage(5L, ageHours(170), 2, NOW.minusHours(24)));

        job.sweep();

        verify(userService).purgeAbandonedPending(5L, NOW);
    }

    @Test
    @DisplayName("one minute before final-sent + 24 h nothing is purged")
    void doesNotPurgeOneMinuteEarly() {
        configured();
        candidates(new Stage(5L, ageHours(170), 2, NOW.minusHours(24).plus(MINUTE)));

        job.sweep();

        verifyNoInteractions(userService, writer);
    }

    @Test
    @DisplayName("a deploy-backfilled account (sent=2, final = created + 144 h) at age 168 h is purged and "
            + "never emailed")
    void backfilledAccountIsPurgedWithoutEmail() {
        configured();
        LocalDateTime created = ageHours(168);
        candidates(new Stage(6L, created, 2, created.plusHours(144)));

        job.sweep();

        verify(userService).purgeAbandonedPending(6L, NOW);
        verify(mail, never()).sendVerificationReminderEmail("a@example.com", "alice", "t", false, NOW);
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("with no sender configured an account is purged at 168 h and nothing is sent")
    void unconfiguredSenderPurgesAtHold() {
        when(mail.isConfigured()).thenReturn(false);
        candidates(new Stage(7L, ageHours(168), 0, null), new Stage(8L, ageHours(168).plus(MINUTE), 0, null),
                new Stage(9L, ageHours(100), 0, null));

        job.sweep();

        verify(userService).purgeAbandonedPending(7L, NOW);
        verify(userService, never()).purgeAbandonedPending(8L, NOW);
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a failed send reverts the claim with the minted token's hash and previous final stamp")
    void failedSendReverts() {
        configured();
        candidates(new Stage(10L, ageHours(100), 0, null));
        LocalDateTime previous = NOW.minusDays(1);
        when(writer.claim(10L, 0, 1, NOW)).thenReturn(new Claim(Outcome.CLAIMED,
                new IssuedVerification("a@example.com", "alice", "t10"), "hash-t10", previous));
        when(mail.sendVerificationReminderEmail("a@example.com", "alice", "t10", false,
                ageHours(100).plusHours(168))).thenReturn(false);

        job.sweep();

        verify(writer).revert(10L, 0, 1, previous, "hash-t10");
    }

    @Test
    @DisplayName("a deferred or skipped claim sends nothing and reverts nothing")
    void deferredAndSkippedSendNothing() {
        configured();
        candidates(new Stage(11L, ageHours(100), 0, null), new Stage(12L, ageHours(100), 0, null));
        when(writer.claim(11L, 0, 1, NOW)).thenReturn(new Claim(Outcome.DEFERRED, null, null, null));
        when(writer.claim(12L, 0, 1, NOW)).thenReturn(new Claim(Outcome.SKIPPED, null, null, null));

        job.sweep();

        verifyNoInteractions(userService);
        verify(writer, never()).revert(11L, 0, 1, null, null);
    }

    @Test
    @DisplayName("one account throwing does not stop the others")
    void oneFailureDoesNotStopTheRest() {
        configured();
        candidates(new Stage(13L, ageHours(170), 2, NOW.minusHours(30)),
                new Stage(14L, ageHours(170), 2, NOW.minusHours(30)));
        when(userService.purgeAbandonedPending(13L, NOW)).thenThrow(new IllegalStateException("boom"));

        job.sweep();

        verify(userService).purgeAbandonedPending(14L, NOW);
    }
}
