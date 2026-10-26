package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.model.WaitlistEntryDto;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.WaitlistService.InviteOutcome;
import com.gregochr.goldenhour.service.WaitlistService.JoinOutcome;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WaitlistService}.
 */
@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 7, 12, 0);
    private static final String CLIENT_KEY = "waitlist:client:203.0.113.9";

    @Mock
    private WaitlistEmailRepository repository;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserEmailService userEmailService;
    @Mock
    private TurnstileService turnstileService;
    @Mock
    private AuthAttemptLimiter attemptLimiter;

    private WaitlistService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new WaitlistService(repository, jwtService, userEmailService, turnstileService, attemptLimiter,
                clock);
    }

    private void admit() {
        when(attemptLimiter.tryAcquire("waitlist:global", 60, Window.HOUR)).thenReturn(true);
        when(attemptLimiter.tryAcquire(CLIENT_KEY, 5, Window.QUARTER_HOUR)).thenReturn(true);
    }

    /** Matches a save of exactly this address. */
    private static WaitlistEmailEntity forAddress(String email) {
        return argThat(e -> e != null && email.equals(e.getEmail()));
    }

    private static WaitlistEmailEntity row(Long id, String email) {
        return WaitlistEmailEntity.builder().id(id).email(email).submittedAt(NOW.minusDays(2)).build();
    }

    // --- join ---

    @Test
    @DisplayName("join saves a new address stamped with the clock, after both bounds and the CAPTCHA")
    void join_newAddress_saves() {
        admit();
        when(turnstileService.verify("tok")).thenReturn(true);
        when(repository.existsByEmail("a@example.com")).thenReturn(false);
        when(repository.save(forAddress("a@example.com"))).thenAnswer(inv -> {
            WaitlistEmailEntity e = inv.getArgument(0);
            e.setId(11L);
            return e;
        });

        assertThat(service.join("a@example.com", "tok", "203.0.113.9")).isEqualTo(JoinOutcome.JOINED);

        ArgumentCaptor<WaitlistEmailEntity> saved = ArgumentCaptor.forClass(WaitlistEmailEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("a@example.com");
        assertThat(saved.getValue().getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("join of an address already listed answers JOINED and saves nothing")
    void join_existing_isIdempotent() {
        admit();
        when(turnstileService.verify("tok")).thenReturn(true);
        when(repository.existsByEmail("a@example.com")).thenReturn(true);

        assertThat(service.join("a@example.com", "tok", "203.0.113.9")).isEqualTo(JoinOutcome.JOINED);

        verify(repository, never()).save(forAddress("a@example.com"));
    }

    @Test
    @DisplayName("join is RATE_LIMITED, with no CAPTCHA call and no write, once the global hourly cap is spent")
    void join_globalCapSpent_isRateLimited() {
        when(attemptLimiter.tryAcquire("waitlist:global", 60, Window.HOUR)).thenReturn(false);

        assertThat(service.join("a@example.com", "tok", "203.0.113.9")).isEqualTo(JoinOutcome.RATE_LIMITED);

        verifyNoInteractions(turnstileService);
        verify(repository, never()).save(forAddress("a@example.com"));
    }

    @Test
    @DisplayName("join is RATE_LIMITED once the client address bound is spent")
    void join_clientBoundSpent_isRateLimited() {
        when(attemptLimiter.tryAcquire("waitlist:global", 60, Window.HOUR)).thenReturn(true);
        when(attemptLimiter.tryAcquire(CLIENT_KEY, 5, Window.QUARTER_HOUR)).thenReturn(false);

        assertThat(service.join("a@example.com", "tok", "203.0.113.9")).isEqualTo(JoinOutcome.RATE_LIMITED);

        verifyNoInteractions(turnstileService);
    }

    @Test
    @DisplayName("join with a failing CAPTCHA is CAPTCHA_FAILED and saves nothing")
    void join_captchaFails() {
        admit();
        when(turnstileService.verify("bad")).thenReturn(false);

        assertThat(service.join("a@example.com", "bad", "203.0.113.9")).isEqualTo(JoinOutcome.CAPTCHA_FAILED);

        verify(repository, never()).save(forAddress("a@example.com"));
    }

    @Test
    @DisplayName("join that loses the unique-key race to an identical sign-up still answers JOINED")
    void join_raceLost_isJoined() {
        admit();
        when(turnstileService.verify("tok")).thenReturn(true);
        when(repository.existsByEmail("a@example.com")).thenReturn(false, true);
        when(repository.save(forAddress("a@example.com"))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThat(service.join("a@example.com", "tok", "203.0.113.9")).isEqualTo(JoinOutcome.JOINED);
    }

    @Test
    @DisplayName("join rethrows an integrity failure that is not a duplicate address")
    void join_otherIntegrityFailure_propagates() {
        admit();
        when(turnstileService.verify("tok")).thenReturn(true);
        when(repository.existsByEmail("a@example.com")).thenReturn(false, false);
        when(repository.save(forAddress("a@example.com"))).thenThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> service.join("a@example.com", "tok", "203.0.113.9"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- list ---

    @Test
    @DisplayName("list carries the invitation dates and has no field for the token hash")
    void list_mapsDatesNotHash() {
        WaitlistEmailEntity invited = WaitlistEmailEntity.builder().id(1L).email("a@example.com")
                .submittedAt(NOW.minusDays(3)).inviteTokenHash("h".repeat(64)).invitedAt(NOW.minusDays(1))
                .inviteExpiresAt(NOW.plusDays(6)).build();
        when(repository.findAllByOrderBySubmittedAtAsc()).thenReturn(List.of(invited, row(2L, "b@example.com")));

        assertThat(service.list()).containsExactly(
                new WaitlistEntryDto(1L, "a@example.com", NOW.minusDays(3), NOW.minusDays(1), NOW.plusDays(6)),
                new WaitlistEntryDto(2L, "b@example.com", NOW.minusDays(2), null, null));
        assertThat(WaitlistEntryDto.class.getRecordComponents())
                .extracting(c -> c.getName()).doesNotContain("inviteTokenHash");
    }

    // --- invite ---

    @Test
    @DisplayName("invite stores only the token's hash with a 7-day expiry and mails the RAW token")
    void invite_storesHashAndMailsRaw() {
        when(repository.findById(3L)).thenReturn(Optional.of(row(3L, "a@example.com")));
        when(userEmailService.isConfigured()).thenReturn(true);
        when(jwtService.generateRefreshToken()).thenReturn("raw-invite");
        when(jwtService.hashToken("raw-invite")).thenReturn("hashed-invite");
        when(repository.markInvited(3L, "hashed-invite", NOW, NOW.plusDays(7))).thenReturn(1);

        assertThat(service.invite(3L)).isEqualTo(InviteOutcome.SENT);

        verify(userEmailService).sendWaitlistInviteEmail(3L, "a@example.com", "raw-invite", NOW.plusDays(7));
        verify(repository, never()).save(forAddress("a@example.com"));
    }

    @Test
    @DisplayName("re-inviting mints a NEW token and replaces the stored hash")
    void invite_again_replacesTheToken() {
        when(repository.findById(3L)).thenReturn(Optional.of(row(3L, "a@example.com")));
        when(userEmailService.isConfigured()).thenReturn(true);
        when(jwtService.generateRefreshToken()).thenReturn("first", "second");
        when(jwtService.hashToken("first")).thenReturn("hash-1");
        when(jwtService.hashToken("second")).thenReturn("hash-2");
        when(repository.markInvited(3L, "hash-1", NOW, NOW.plusDays(7))).thenReturn(1);
        when(repository.markInvited(3L, "hash-2", NOW, NOW.plusDays(7))).thenReturn(1);

        service.invite(3L);
        service.invite(3L);

        verify(repository).markInvited(3L, "hash-1", NOW, NOW.plusDays(7));
        verify(repository).markInvited(3L, "hash-2", NOW, NOW.plusDays(7));
        verify(userEmailService).sendWaitlistInviteEmail(3L, "a@example.com", "second", NOW.plusDays(7));
    }

    @Test
    @DisplayName("invite refuses a non-ASCII address before minting anything")
    void invite_nonAscii_refused() {
        when(repository.findById(3L)).thenReturn(Optional.of(row(3L, "jürgen@example.com")));

        assertThat(service.invite(3L)).isEqualTo(InviteOutcome.NON_ASCII);

        verifyNoInteractions(jwtService);
        verifyNoInteractions(userEmailService);
        verify(repository).findById(3L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("invite of an unknown row is NOT_FOUND, and of a row deleted mid-way sends nothing")
    void invite_unknown_notFound() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        when(repository.findById(3L)).thenReturn(Optional.of(row(3L, "a@example.com")));
        when(userEmailService.isConfigured()).thenReturn(true);
        when(jwtService.generateRefreshToken()).thenReturn("raw");
        when(jwtService.hashToken("raw")).thenReturn("h");
        when(repository.markInvited(3L, "h", NOW, NOW.plusDays(7))).thenReturn(0);

        assertThat(service.invite(9L)).isEqualTo(InviteOutcome.NOT_FOUND);
        assertThat(service.invite(3L)).isEqualTo(InviteOutcome.NOT_FOUND);

        verify(userEmailService).isConfigured();
        verifyNoMoreInteractions(userEmailService);
    }

    @Test
    @DisplayName("invite stores nothing when no mail sender is configured")
    void invite_noMailer_stagesNothing() {
        when(repository.findById(3L)).thenReturn(Optional.of(row(3L, "a@example.com")));
        when(userEmailService.isConfigured()).thenReturn(false);

        assertThat(service.invite(3L)).isEqualTo(InviteOutcome.MAIL_UNAVAILABLE);

        verify(repository).findById(3L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(jwtService);
    }

    // --- remove / removeByEmail ---

    @Test
    @DisplayName("remove deletes an existing row and reports a missing one")
    void remove_existingAndMissing() {
        when(repository.existsById(3L)).thenReturn(true);
        when(repository.existsById(9L)).thenReturn(false);

        assertThat(service.remove(3L)).isTrue();
        assertThat(service.remove(9L)).isFalse();

        verify(repository).deleteById(3L);
        verify(repository, never()).deleteById(9L);
    }

    @Test
    @DisplayName("removeByEmail deletes by the trimmed address, and does nothing for a blank or null one")
    void removeByEmail_deletesByAddress() {
        service.removeByEmail(" Alice@Example.com ");
        service.removeByEmail(" ");
        service.removeByEmail(null);

        verify(repository).deleteByEmailIgnoreCase("Alice@Example.com");
        verify(repository, never()).deleteByEmailIgnoreCase(" ");
    }

    // --- redeemInvite ---

    @Test
    @DisplayName("redeemInvite hashes the presented token and redeems it for the request's address: true on 1 row")
    void redeemInvite_matchingRow_true() {
        when(jwtService.hashToken("raw")).thenReturn("hash");
        when(repository.redeemInvite("hash", NOW, "a@example.com")).thenReturn(1);

        assertThat(service.redeemInvite(" raw ", " a@example.com ")).isTrue();
    }

    @Test
    @DisplayName("redeemInvite is false when no row matches (wrong address, expired, already used, unknown)")
    void redeemInvite_noRow_false() {
        when(jwtService.hashToken("raw")).thenReturn("hash");
        when(repository.redeemInvite("hash", NOW, "other@example.com")).thenReturn(0);

        assertThat(service.redeemInvite("raw", "other@example.com")).isFalse();
    }

    @Test
    @DisplayName("redeemInvite with no token, or no address, never touches the repository")
    void redeemInvite_absentInputs_false() {
        assertThat(service.redeemInvite(null, "a@example.com")).isFalse();
        assertThat(service.redeemInvite("  ", "a@example.com")).isFalse();
        assertThat(service.redeemInvite("raw", null)).isFalse();
        assertThat(service.redeemInvite("raw", " ")).isFalse();

        verifyNoInteractions(repository);
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("the public constructor wires the system clock")
    void publicConstructor_usesTheSystemClock() {
        WaitlistService wired = new WaitlistService(repository, jwtService, userEmailService, turnstileService,
                attemptLimiter);
        when(repository.findAllByOrderBySubmittedAtAsc()).thenReturn(List.of());

        assertThat(wired.list()).isEmpty();
    }
}
