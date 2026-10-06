package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAlertService}.
 *
 * <p>A real (non-mocked) {@link MimeMessage} backs each recipient's send so the subject and
 * body content can be read back and asserted on directly, rather than merely verifying that
 * {@code send(...)} was called — {@code MimeMessageHelper} writes through to the real JavaMail
 * object, which a Mockito mock cannot reproduce.
 */
@ExtendWith(MockitoExtension.class)
class AdminAlertServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private AppUserRepository appUserRepository;

    private static final Instant TRIGGER = Instant.parse("2026-09-29T14:00:00Z");

    private static final String DETAIL =
            "3 of 3 forecast batch submissions failed (510 requests not submitted — "
                    + "near-term inland, near-term coastal, far-term inland)";

    private static AppUserEntity admin(String username, String email, boolean enabled) {
        AppUserEntity user = new AppUserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(UserRole.ADMIN);
        user.setEnabled(enabled);
        return user;
    }

    private static MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getDefaultInstance(new Properties()));
    }

    /** The enabled-by-default service under test — most tests exercise the enabled path. */
    private AdminAlertService enabledService() {
        return new AdminAlertService(mailSender, appUserRepository, true);
    }

    @Test
    @DisplayName("notifications.admin-alerts.enabled=false — the repository is never queried and "
            + "the mail sender is never touched, regardless of whether one is configured")
    void disabled_skipsEntirelyWithoutTouchingRepositoryOrMailSender() {
        AdminAlertService service = new AdminAlertService(mailSender, appUserRepository, false);

        service.sendPipelineDegradedAlert(249L, CycleType.INTRADAY, TRIGGER, DETAIL);

        verify(appUserRepository, never()).findByRoleAndEnabledTrue(any());
        verify(mailSender, never()).createMimeMessage();
    }

    @Test
    @DisplayName("no mail sender configured — the repository is never even queried")
    void nullMailSender_skipsEntirely() {
        AdminAlertService service = new AdminAlertService(null, appUserRepository, true);

        service.sendPipelineDegradedAlert(249L, CycleType.INTRADAY, TRIGGER, DETAIL);

        verify(appUserRepository, never()).findByRoleAndEnabledTrue(any());
    }

    @Test
    @DisplayName("a repository failure (e.g. a transient DB error) is caught and logged rather "
            + "than escaping the @Async method — the whole body is guarded, not only the "
            + "per-recipient send loop")
    void repositoryThrows_neverEscapes() {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenThrow(new RuntimeException("DB unavailable"));

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, TRIGGER, DETAIL);

        // Reaching this line at all is the assertion — the exception never escaped.
        verify(mailSender, never()).createMimeMessage();
    }

    @Test
    @DisplayName("emails every enabled ADMIN with a non-blank email — subject names the run and "
            + "the short failure clause, body carries run id, cycle type, trigger time, the full "
            + "detail, and the still-serving-previous-ratings line")
    void sendsToEveryEnabledAdminWithEmail_subjectAndBodyContent() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true),
                admin("bob", "bob@example.com", true)));
        MimeMessage aliceMessage = newMimeMessage();
        MimeMessage bobMessage = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(aliceMessage, bobMessage);

        enabledService().sendPipelineDegradedAlert(249L, CycleType.INTRADAY, TRIGGER, DETAIL);

        verify(mailSender).send(aliceMessage);
        verify(mailSender).send(bobMessage);

        assertThat(aliceMessage.getSubject()).isEqualTo(
                "PhotoCast: pipeline run 249 degraded — 3 of 3 forecast batch submissions failed");
        assertThat(aliceMessage.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        String aliceBody = (String) aliceMessage.getContent();
        assertThat(aliceBody).contains("Pipeline run 249 (INTRADAY) degraded.");
        assertThat(aliceBody).contains("Trigger time: 2026-09-29 14:00 UTC");
        assertThat(aliceBody).contains(DETAIL);
        assertThat(aliceBody).contains(
                "The app is still serving ratings from the previous successful run.");

        assertThat(bobMessage.getSubject()).isEqualTo(aliceMessage.getSubject());
        assertThat(bobMessage.getAllRecipients()[0].toString()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("a disabled admin (enabled=false, excluded by the repository query itself) and "
            + "a blank/null email (excluded by this service's own filter) are both kept out of "
            + "the recipient list — proven by the exact createMimeMessage() call count, not "
            + "merely that alice's own send happened, since a deleted email filter would still "
            + "let alice's send succeed while the others fail silently inside the swallowed "
            + "per-recipient catch")
    void excludesDisabledAndBlankOrNullEmailAdmins() {
        // findByRoleAndEnabledTrue is mocked here to return what a real "enabled=true" query
        // would — the disabled admin below stands for one the repository itself would already
        // have excluded; it is included in this fixture only to name that it is never even a
        // candidate, not to re-prove the repository's own WHERE clause.
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true),
                admin("noemail", "", true),
                admin("nullemail", null, true)));
        MimeMessage aliceMessage = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(aliceMessage);

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, TRIGGER, DETAIL);

        // Exactly one message object is ever created — if the blank/null-email filter were
        // deleted, createMimeMessage() would be called three times (once per candidate, before
        // helper.setTo("")/setTo(null) could throw), even though only alice's send() would
        // ultimately succeed. This assertion fails in that case; times(1).send(aliceMessage)
        // alone would not have.
        verify(mailSender, times(1)).createMimeMessage();
        verify(mailSender, times(1)).send(aliceMessage);
    }

    @Test
    @DisplayName("every enabled ADMIN has a blank or null email — the post-filter recipient list "
            + "is empty, so nothing is sent (distinct from the repository itself returning no "
            + "ADMIN at all)")
    void everyEnabledAdminHasNoUsableEmail_sendsNothing() {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("noemail", "", true),
                admin("nullemail", null, true)));

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, TRIGGER, DETAIL);

        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("no enabled ADMIN at all (the repository itself returns empty) — nothing sent, "
            + "no exception")
    void noEnabledAdminAtAll_sendsNothing() {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of());

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, TRIGGER, DETAIL);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("one admin's send failing is caught and logged — the other admin's email still "
            + "goes out, and nothing escapes the method")
    void oneRecipientFails_othersStillSent() {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true),
                admin("bob", "bob@example.com", true)));
        MimeMessage aliceMessage = newMimeMessage();
        MimeMessage bobMessage = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(aliceMessage, bobMessage);
        doThrow(new MailSendException("smtp connection refused"))
                .when(mailSender).send(aliceMessage);

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, TRIGGER, DETAIL);

        verify(mailSender).send(aliceMessage);
        verify(mailSender).send(bobMessage);
        // Reaching this line at all is the assertion that the exception never escaped.
    }

    @Test
    @DisplayName("a null triggerTime renders as 'unknown' in the body rather than throwing")
    void nullTriggerTime_rendersUnknown() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true)));
        MimeMessage message = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        enabledService().sendPipelineDegradedAlert(249L, CycleType.NIGHTLY, null, DETAIL);

        String body = (String) message.getContent();
        assertThat(body).contains("Trigger time: unknown");
    }

    private static final String DISABLED_REASON =
            "Auto-disabled after 3 consecutive failed scheduled runs "
                    + "(last 2026-10-02: weather data could not be fetched).";

    @Test
    @DisplayName("the auto-disabled alert goes to every enabled admin with the literal subject "
            + "and a body listing each place with its reason")
    void autoDisabledAlert_literalSubjectAndBody() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true)));
        MimeMessage message = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        enabledService().sendLocationsAutoDisabledAlert(300L, CycleType.NIGHTLY, TRIGGER,
                List.of(new AdminAlertService.DisabledLocation("Bamburgh", DISABLED_REASON),
                        new AdminAlertService.DisabledLocation("Alnwick", DISABLED_REASON)));

        verify(mailSender).send(message);
        assertThat(message.getSubject())
                .isEqualTo("PhotoCast: 2 locations auto-disabled after pipeline run 300");
        assertThat((String) message.getContent()).isEqualTo(
                "Pipeline run 300 (NIGHTLY) auto-disabled 2 locations.\n\n"
                        + "Trigger time: 2026-09-29 14:00 UTC\n\n"
                        + "- Bamburgh: " + DISABLED_REASON + "\n"
                        + "- Alnwick: " + DISABLED_REASON + "\n\n"
                        + "These places no longer appear in any forecast. Re-enable each one from "
                        + "Manage > Locations (Location Issues) once the cause is fixed.\n");
    }

    @Test
    @DisplayName("a single disabled place reads in the singular")
    void autoDisabledAlert_singular() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true)));
        MimeMessage message = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        enabledService().sendLocationsAutoDisabledAlert(300L, CycleType.INTRADAY, TRIGGER,
                List.of(new AdminAlertService.DisabledLocation("Bamburgh", DISABLED_REASON)));

        assertThat(message.getSubject())
                .isEqualTo("PhotoCast: 1 location auto-disabled after pipeline run 300");
        assertThat((String) message.getContent())
                .startsWith("Pipeline run 300 (INTRADAY) auto-disabled 1 location.\n");
    }

    @Test
    @DisplayName("the cap alert says none was disabled and names every qualifying place")
    void capAlert_literalSubjectAndBody() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true)));
        MimeMessage message = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        enabledService().sendLocationDisableCapAlert(300L, CycleType.NIGHTLY, TRIGGER,
                List.of("A", "B", "C", "D", "E", "F"), 5);

        verify(mailSender).send(message);
        assertThat(message.getSubject()).isEqualTo(
                "PhotoCast: 6 locations failed repeatedly on pipeline run 300 — none disabled");
        assertThat((String) message.getContent()).isEqualTo(
                "Pipeline run 300 (NIGHTLY): 6 locations reached the consecutive-failure "
                        + "threshold in this one cycle, more than the cap of 5 disabled per "
                        + "cycle.\n\n"
                        + "That points at something systemic rather than that many broken places, "
                        + "so NONE was disabled.\n\n"
                        + "Trigger time: 2026-09-29 14:00 UTC\n\n"
                        + "Locations: A, B, C, D, E, F\n");
    }

    @Test
    @DisplayName("with admin alerts disabled neither location alert touches the repository or the "
            + "mail sender")
    void locationAlerts_disabled_skipEntirely() {
        AdminAlertService service = new AdminAlertService(mailSender, appUserRepository, false);

        service.sendLocationsAutoDisabledAlert(300L, CycleType.NIGHTLY, TRIGGER,
                List.of(new AdminAlertService.DisabledLocation("Bamburgh", DISABLED_REASON)));
        service.sendLocationDisableCapAlert(300L, CycleType.NIGHTLY, TRIGGER, List.of("A"), 5);

        verify(appUserRepository, never()).findByRoleAndEnabledTrue(any());
        verify(mailSender, never()).createMimeMessage();
    }

    @Test
    @DisplayName("the Ask spend-cap alert emails every enabled ADMIN with an address, naming the day, the "
            + "spend and the cap in dollars")
    void askSpendCapAlert_sendsToEveryAdmin() throws Exception {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of(
                admin("alice", "alice@example.com", true), admin("seed", "", true)));
        MimeMessage message = newMimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);

        enabledService().sendAskSpendCapAlert(java.time.LocalDate.of(2026, 10, 6), 512_345L, 500_000L);

        verify(mailSender, times(1)).send(message);
        assertThat(message.getSubject()).isEqualTo("PhotoCast: Ask spend cap reached on 2026-10-06");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        String body = (String) message.getContent();
        assertThat(body).contains("$0.51 on 2026-10-06").contains("daily cap of $0.50")
                .contains("photocast.ask.daily-spend-cap-usd").contains("until UK midnight");
    }

    @Test
    @DisplayName("with admin alerts disabled, or no mail sender, or no admin address, the Ask alert sends "
            + "nothing and throws nothing")
    void askSpendCapAlert_silentWhenItCannotSend() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 6);
        new AdminAlertService(mailSender, appUserRepository, false).sendAskSpendCapAlert(day, 1L, 1L);
        new AdminAlertService(null, appUserRepository, true).sendAskSpendCapAlert(day, 1L, 1L);
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(List.of());
        enabledService().sendAskSpendCapAlert(day, 1L, 1L);

        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a location alert whose recipient lookup throws never escapes the method")
    void locationAlert_repositoryThrows_neverEscapes() {
        when(appUserRepository.findByRoleAndEnabledTrue(UserRole.ADMIN))
                .thenThrow(new RuntimeException("DB unavailable"));

        enabledService().sendLocationDisableCapAlert(300L, CycleType.NIGHTLY, TRIGGER,
                List.of("A"), 5);

        verify(mailSender, never()).createMimeMessage();
    }
}
