package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.DormancyProperties;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.DormancyCandidate;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DormantAccountSweepJob}'s decisions, on a fixed clock in the system default zone (the
 * zone the job reads), with the repository, user service and mailer mocked. The default windows are 90 days
 * inactive and 14 days' grace.
 */
@ExtendWith(MockitoExtension.class)
class DormantAccountSweepJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-06-30T10:00:00Z"), ZoneId.systemDefault());
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDateTime INACTIVE_CUTOFF = NOW.minusDays(90);
    private static final LocalDateTime WARN_CUTOFF = NOW.minusDays(14);

    @Mock
    private AppUserRepository users;
    @Mock
    private UserService userService;
    @Mock
    private UserEmailService mail;
    @Mock
    private DynamicSchedulerService scheduler;

    private DormancyProperties properties;
    private DormantAccountSweepJob job;

    /** A candidate row. */
    private record Candidate(Long id, String username, String email, LocalDateTime createdAt,
            LocalDateTime lastActiveAt, LocalDateTime warnedAt) implements DormancyCandidate {
        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getEmail() {
            return email;
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        @Override
        public LocalDateTime getLastActiveAt() {
            return lastActiveAt;
        }

        @Override
        public LocalDateTime getDormancyWarningSentAt() {
            return warnedAt;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new DormancyProperties();
        job = new DormantAccountSweepJob(users, userService, mail, scheduler, properties, CLOCK);
    }

    private static Candidate unwarned(long id) {
        return new Candidate(id, "user" + id, "user" + id + "@example.com", NOW.minusDays(200), NOW.minusDays(95),
                null);
    }

    private static Candidate warned(long id, LocalDateTime warnedAt) {
        return new Candidate(id, "user" + id, "user" + id + "@example.com", NOW.minusDays(200), NOW.minusDays(95),
                warnedAt);
    }

    private void candidates(Candidate... rows) {
        when(users.findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("the defaults are a dry run, 90 days inactive and 14 days' grace")
    void defaults() {
        assertThat(properties.isDryRun()).isTrue();
        assertThat(properties.getInactiveDays()).isEqualTo(90);
        assertThat(properties.getGraceDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("registers itself with the scheduler under the V193 job key")
    void registersItself() {
        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(eq("dormant_account_sweep"), target.capture());
        when(users.findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(List.of());
        target.getValue().run();
        verify(users).findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF);
    }

    @Test
    @DisplayName("a dry run sends nothing, stamps nothing, deletes nothing, and reads only the candidates")
    void dryRunIsInert() {
        candidates(unwarned(1L), warned(2L, NOW.minusDays(20)), warned(3L, NOW.minusDays(5)));

        job.sweep();

        verify(users).findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(userService);
        verify(mail, never()).sendDormancyWarningEmail(eq(1L), eq("user1@example.com"), eq("user1"), eq(90),
                eq(NOW.plusDays(14)));
        verifyNoMoreInteractions(mail);
    }

    @Test
    @DisplayName("a dry run still runs with no mail sender (it sends nothing anyway)")
    void dryRunWithoutSenderStillReports() {
        candidates(unwarned(1L));

        job.sweep();

        verify(users).findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoInteractions(mail);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("live with no mail sender configured: nothing is read, warned or deleted")
    void noSenderDoesNothing() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(false);

        job.sweep();

        verifyNoInteractions(users);
        verifyNoInteractions(userService);
        verify(mail).isConfigured();
        verifyNoMoreInteractions(mail);
    }

    @Test
    @DisplayName("an unwarned account is warned: sent first, then stamped with the passed cutoff")
    void warnsThenStamps() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(unwarned(1L));
        when(mail.sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14)))
                .thenReturn(true);
        when(users.stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(1);

        job.sweep();

        var order = org.mockito.Mockito.inOrder(mail, users);
        order.verify(mail).sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14));
        order.verify(users).stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a failed send stamps nothing and deletes nothing")
    void failedSendStampsNothing() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(unwarned(1L));
        when(mail.sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14)))
                .thenReturn(false);

        job.sweep();

        verify(users, never()).stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a crash between the send and the stamp leaves the account unwarned and never purges it")
    void crashBetweenSendAndStampLeavesItUnwarned() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(unwarned(1L), unwarned(2L));
        when(mail.sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14)))
                .thenReturn(true);
        when(users.stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF))
                .thenThrow(new IllegalStateException("db down"));
        when(mail.sendDormancyWarningEmail(2L, "user2@example.com", "user2", 90, NOW.plusDays(14)))
                .thenReturn(true);
        when(users.stampDormancyWarning(2L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(1);

        job.sweep();

        verify(users).stampDormancyWarning(2L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a warning that is due but whose grace has not ended waits; the purge is not called")
    void warnedWithinGraceWaits() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(warned(1L, NOW.minusDays(13)));

        job.sweep();

        verifyNoInteractions(userService);
        verify(users).findDormancyCandidates(UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoMoreInteractions(users);
    }

    @Test
    @DisplayName("a warning exactly the grace period old is purged, and the deleted mail follows the purge")
    void warnedAtExactlyGraceIsPurged() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(warned(1L, WARN_CUTOFF));
        when(userService.purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF))
                .thenReturn(Optional.of(new DeletedAccount("user1", "user1@example.com")));

        job.sweep();

        var order = org.mockito.Mockito.inOrder(userService, mail);
        order.verify(userService).purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF);
        order.verify(mail).sendAccountDeletedEmail(1L, "user1@example.com", "user1");
    }

    @Test
    @DisplayName("a purge that finds the account changed deletes nothing and sends no deleted mail")
    void purgeRefusedSendsNoMail() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(warned(1L, NOW.minusDays(20)));
        when(userService.purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF)).thenReturn(Optional.empty());

        job.sweep();

        verify(mail, never()).sendAccountDeletedEmail(1L, "user1@example.com", "user1");
    }

    @Test
    @DisplayName("a sign-in after the warning makes it stale: the account is warned afresh, never purged")
    void signInAfterWarningCancelsDeletion() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        // Last active 95 days ago, but the stamp is OLDER than that activity: a stale warning.
        Candidate stale = new Candidate(1L, "user1", "user1@example.com", NOW.minusDays(200), NOW.minusDays(95),
                NOW.minusDays(100));
        candidates(stale);
        when(mail.sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14)))
                .thenReturn(true);
        when(users.stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(1);

        job.sweep();

        verifyNoInteractions(userService);
        verify(users).stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF);
    }

    @Test
    @DisplayName("a never-active account is judged from created_at")
    void neverActiveUsesCreatedAt() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        Candidate neverActive = new Candidate(1L, "user1", "user1@example.com", NOW.minusDays(120), null,
                NOW.minusDays(20));
        candidates(neverActive);
        when(userService.purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF)).thenReturn(Optional.empty());

        job.sweep();

        verify(userService).purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF);
    }

    @Test
    @DisplayName("a throw for one account is contained and the loop carries on")
    void oneThrowDoesNotStopTheLoop() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(warned(1L, NOW.minusDays(20)), warned(2L, NOW.minusDays(20)));
        when(userService.purgeDormant(1L, INACTIVE_CUTOFF, WARN_CUTOFF)).thenThrow(new IllegalStateException("x"));
        when(userService.purgeDormant(2L, INACTIVE_CUTOFF, WARN_CUTOFF))
                .thenReturn(Optional.of(new DeletedAccount("user2", "user2@example.com")));

        job.sweep();

        verify(mail).sendAccountDeletedEmail(2L, "user2@example.com", "user2");
    }

    @Test
    @DisplayName("a warning sent but not stamped (the account just signed in) is counted, not retried")
    void unstampedWarningIsNotRetried() {
        properties.setDryRun(false);
        when(mail.isConfigured()).thenReturn(true);
        candidates(unwarned(1L));
        when(mail.sendDormancyWarningEmail(1L, "user1@example.com", "user1", 90, NOW.plusDays(14)))
                .thenReturn(true);
        when(users.stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF)).thenReturn(0);

        job.sweep();

        verify(users).stampDormancyWarning(1L, NOW, UserRole.LITE_USER, INACTIVE_CUTOFF);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("custom windows move both cutoffs")
    void customWindowsMoveTheCutoffs() {
        properties.setDryRun(false);
        properties.setInactiveDays(60);
        properties.setGraceDays(7);
        when(mail.isConfigured()).thenReturn(true);
        when(users.findDormancyCandidates(UserRole.LITE_USER, NOW.minusDays(60))).thenReturn(List.of(
                warned(1L, NOW.minusDays(7))));
        when(userService.purgeDormant(1L, NOW.minusDays(60), NOW.minusDays(7))).thenReturn(Optional.empty());

        job.sweep();

        verify(userService).purgeDormant(1L, NOW.minusDays(60), NOW.minusDays(7));
    }
}
