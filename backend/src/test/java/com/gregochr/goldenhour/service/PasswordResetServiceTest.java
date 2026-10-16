package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.PasswordResetTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.service.PasswordResetIssuer.IssuedLink;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PasswordResetService}: what it asks of its collaborators, in what order, and
 * what it refuses. The locking, the compare-and-set and the stale-entity cases live where they can fail
 * for real — {@code PasswordResetRedeemerTest}, {@code PasswordResetIssuerTest} (both on a JPA stack) and
 * {@code PasswordResetFlowTest} (the whole sequence).
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private PasswordResetIssuer issuer;
    @Mock
    private PasswordResetRedeemer redeemer;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private UserEmailService userEmailService;
    @Mock
    private JwtService jwtService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthAttemptLimiter attemptLimiter;
    private PasswordResetService service;
    private AppUserEntity reader;

    @BeforeEach
    void setUp() {
        attemptLimiter = new AuthAttemptLimiter();
        service = new PasswordResetService(userRepository, tokenRepository, issuer, redeemer,
                authenticationService, userEmailService, attemptLimiter, jwtService, passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
        reader = AppUserEntity.builder().id(7L).username("reader").email("Reader@Example.com")
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true).build();
    }

    @Nested
    class RequestReset {

        private void stubMinting() {
            when(jwtService.generateRefreshToken()).thenReturn("raw-reset-token");
            when(jwtService.hashToken("raw-reset-token")).thenReturn("hashed-reset-token");
        }

        @Test
        @DisplayName("an active account gets a link issued under its lock and mailed to the STORED address, "
                + "though the request's casing differs")
        void activeAccount_mailsTheStoredAddress() {
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token"))
                    .thenReturn(Optional.of(new IssuedLink("Reader@Example.com", "reader")));

            service.requestReset("reader@example.com");

            verify(userEmailService).sendPasswordResetLinkEmail("Reader@Example.com", "reader",
                    "raw-reset-token");
            verify(userEmailService, never()).sendPasswordResetLinkEmail(eq("reader@example.com"),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("the address stored is the one the issuer read under the lock, even if the account's "
                + "email changed after the lookup")
        void issuerAddressWins_overTheAddressResolvedEarlier() {
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token"))
                    .thenReturn(Optional.of(new IssuedLink("moved@example.com", "reader")));

            service.requestReset("reader@example.com");

            verify(userEmailService).sendPasswordResetLinkEmail("moved@example.com", "reader",
                    "raw-reset-token");
        }

        @Test
        @DisplayName("an unknown address sends nothing and never reaches the issuer")
        void unknownAddress_doesNothing() {
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("ghost@example.com")).thenReturn(List.of());

            assertThatCode(() -> service.requestReset("ghost@example.com")).doesNotThrowAnyException();

            verifyNoInteractions(issuer);
            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("a disabled account (exact match) sends nothing")
        void disabledAccount_sendsNothing() {
            reader.setEnabled(false);
            when(userRepository.findByEmail("Reader@Example.com")).thenReturn(Optional.of(reader));

            service.requestReset("Reader@Example.com");

            verifyNoInteractions(issuer);
            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("a pending registration (exact match) sends nothing")
        void pendingAccount_sendsNothing() {
            reader.setEnabled(false);
            reader.setPassword("");
            when(userRepository.findByEmail("Reader@Example.com")).thenReturn(Optional.of(reader));

            service.requestReset("Reader@Example.com");

            verifyNoInteractions(issuer);
            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("two case-insensitive matches are no match: nothing is sent to either")
        void ambiguousAddress_sendsNothing() {
            AppUserEntity twin = AppUserEntity.builder().id(8L).username("twin").email("READER@example.com")
                    .password("{bcrypt}hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader, twin));

            service.requestReset("reader@example.com");

            verifyNoInteractions(issuer);
            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("a non-ASCII address is matched exactly and never folded to an ASCII account")
        void nonAsciiAddress_neverReachesTheFoldingQuery() {
            when(userRepository.findByEmail("bob@gmaıl.com")).thenReturn(Optional.empty());

            service.requestReset("bob@gmaıl.com");

            verify(userRepository, never()).findActiveByEmailLower(anyString());
            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("the fourth request in the window sends nothing and throws nothing: the account's "
                + "budget is three per quarter hour")
        void fourthRequestInTheWindow_isSilentlyDropped() {
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token"))
                    .thenReturn(Optional.of(new IssuedLink("Reader@Example.com", "reader")));

            for (int i = 0; i < 4; i++) {
                assertThatCode(() -> service.requestReset("reader@example.com")).doesNotThrowAnyException();
            }

            verify(issuer, times(3)).issue(7L, "hashed-reset-token");
            verify(userEmailService, times(3)).sendPasswordResetLinkEmail("Reader@Example.com", "reader",
                    "raw-reset-token");
        }

        @Test
        @DisplayName("the budget is per account: another account is unaffected by a spent one")
        void budgetIsPerAccount() {
            AppUserEntity other = AppUserEntity.builder().id(9L).username("other").email("other@example.com")
                    .password("{bcrypt}hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(other));
            stubMinting();
            when(issuer.issue(any(), anyString())).thenReturn(Optional.of(new IssuedLink("x@example.com", "x")));

            for (int i = 0; i < 4; i++) {
                service.requestReset("reader@example.com");
            }
            service.requestReset("other@example.com");

            verify(issuer, times(3)).issue(7L, "hashed-reset-token");
            verify(issuer, times(1)).issue(9L, "hashed-reset-token");
        }

        @Test
        @DisplayName("the budget follows the ACCOUNT, not the typed text: three casings of one address share "
                + "it, so a fourth variant is dropped too")
        void budgetIsSharedAcrossCasingsOfOneAddress() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token"))
                    .thenReturn(Optional.of(new IssuedLink("Reader@Example.com", "reader")));

            List<String> casings = List.of("reader@example.com", "READER@example.com",
                    "Reader@EXAMPLE.com", "rEaDeR@example.com");
            for (String typed : casings) {
                service.requestReset(typed);
            }

            verify(issuer, times(3)).issue(7L, "hashed-reset-token");
        }

        @Test
        @DisplayName("an account the issuer finds no longer active (disabled after the lookup) is not mailed")
        void issuerRefuses_sendsNothing() {
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token")).thenReturn(Optional.empty());

            service.requestReset("reader@example.com");

            verifyNoInteractions(userEmailService);
        }

        @Test
        @DisplayName("the raw token is mailed and only its hash is handed to the issuer")
        void onlyTheHashIsStored() {
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            stubMinting();
            when(issuer.issue(7L, "hashed-reset-token"))
                    .thenReturn(Optional.of(new IssuedLink("Reader@Example.com", "reader")));

            service.requestReset("reader@example.com");

            verify(issuer).issue(7L, "hashed-reset-token");
            verify(issuer, never()).issue(7L, "raw-reset-token");
        }
    }

    @Nested
    class ResetPassword {

        private PasswordResetTokenEntity token(Instant expiresAt, Instant usedAt) {
            return PasswordResetTokenEntity.builder().id(3L).tokenHash("hashed").userId(7L)
                    .expiresAt(expiresAt).usedAt(usedAt).createdAt(NOW.minusSeconds(60)).build();
        }

        private void stubToken(PasswordResetTokenEntity token) {
            when(jwtService.hashToken("raw")).thenReturn("hashed");
            when(tokenRepository.findByTokenHash("hashed")).thenReturn(Optional.ofNullable(token));
        }

        @Test
        @DisplayName("an unknown token is refused as invalid; nothing is hashed or redeemed")
        void unknownToken_isInvalid() {
            stubToken(null);

            assertThatThrownBy(() -> service.resetPassword("raw", "MyP@ssw0rd!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link is invalid");

            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(redeemer);
        }

        @Test
        @DisplayName("a used token is refused with 'already been used'")
        void usedToken_isRefused() {
            stubToken(token(NOW.plusSeconds(600), NOW.minusSeconds(5)));

            assertThatThrownBy(() -> service.resetPassword("raw", "MyP@ssw0rd!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link has already been used");

            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(redeemer);
        }

        @Test
        @DisplayName("an expired token is refused with 'has expired' — including one expiring exactly now")
        void expiredToken_isRefused() {
            for (Instant expiry : new Instant[] {NOW.minusSeconds(1), NOW}) {
                stubToken(token(expiry, null));

                assertThatThrownBy(() -> service.resetPassword("raw", "MyP@ssw0rd!"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("This reset link has expired");
            }

            verifyNoInteractions(redeemer);
        }

        @Test
        @DisplayName("a disabled or pending user's token: the redeemer's refusal propagates untouched")
        void redeemerRefusal_propagates() {
            stubToken(token(NOW.plusSeconds(600), null));
            when(passwordEncoder.encode("MyP@ssw0rd!")).thenReturn("encoded");
            when(redeemer.redeem(3L, 7L, "encoded"))
                    .thenThrow(new IllegalArgumentException("This reset link is invalid"));

            assertThatThrownBy(() -> service.resetPassword("raw", "MyP@ssw0rd!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("This reset link is invalid");

            verifyNoInteractions(authenticationService);
        }

        @Test
        @DisplayName("success encodes BEFORE the transactional redeem, redeems with the token's own ids, and "
                + "signs in from the row the redeemer returned")
        void success_encodesFirst_thenRedeems_thenSignsInFromTheRedeemedRow() {
            stubToken(token(NOW.plusSeconds(600), null));
            when(passwordEncoder.encode("MyP@ssw0rd!")).thenReturn("encoded");
            AppUserEntity redeemed = AppUserEntity.builder().id(7L).username("reader").password("encoded")
                    .role(UserRole.PRO_USER).enabled(true).passwordChangeRequired(false).build();
            when(redeemer.redeem(3L, 7L, "encoded")).thenReturn(redeemed);
            AuthTokens tokens = new AuthTokens("a", "r", NOW, NOW, "reader", "PRO_USER", false, false, null);
            when(authenticationService.issueTokensFor(redeemed)).thenReturn(tokens);

            AuthTokens result = service.resetPassword("raw", "MyP@ssw0rd!");

            assertThat(result).isSameAs(tokens);
            InOrder order = inOrder(passwordEncoder, redeemer, authenticationService);
            order.verify(passwordEncoder).encode("MyP@ssw0rd!");
            order.verify(redeemer).redeem(3L, 7L, "encoded");
            order.verify(authenticationService).issueTokensFor(redeemed);
        }

        @Test
        @DisplayName("the response is built from the REDEEMED row, never the entity resolved before hashing")
        void responseComesFromTheRedeemedRow() {
            stubToken(token(NOW.plusSeconds(600), null));
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");
            AppUserEntity redeemedRow = AppUserEntity.builder().id(7L).username("reader").password("encoded")
                    .role(UserRole.PRO_USER).enabled(true).passwordChangeRequired(false).build();
            when(redeemer.redeem(any(), any(), anyString())).thenReturn(redeemedRow);
            when(authenticationService.issueTokensFor(redeemedRow))
                    .thenReturn(new AuthTokens("a", "r", NOW, NOW, "reader", "PRO_USER", false, false, null));

            AuthTokens result = service.resetPassword("raw", "MyP@ssw0rd!");

            assertThat(result.passwordChangeRequired()).isFalse();
            verify(authenticationService).issueTokensFor(redeemedRow);
            verify(userRepository, never()).findById(any());
        }
    }
}
