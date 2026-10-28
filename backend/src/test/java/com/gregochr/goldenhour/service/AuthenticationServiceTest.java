package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.CurrentPasswordMismatchException;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.exception.TooManyAttemptsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthenticationService}.
 *
 * <p>The refresh tests focus on the race-safety of token rotation: the old token must be
 * consumed via the atomic {@code revokeIfActive} compare-and-set, and any refresh attempt
 * with an already-consumed token must revoke the user's whole token family.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final String ACCESS_TOKEN = "access-token";
    private static final String RAW_REFRESH = "raw-refresh-token";
    private static final String REFRESH_HASH = "refresh-hash";
    private static final int REFRESH_EXPIRY_DAYS = 30;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthAttemptLimiter attemptLimiter;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private RefreshFamilyRevoker familyRevoker;

    @Mock
    private CredentialChangeWriter credentialChangeWriter;

    private AuthenticationService service;

    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userRepository, refreshTokenRepository,
                jwtService, passwordEncoder, attemptLimiter, tokenIssuer, familyRevoker,
                credentialChangeWriter);
        user = AppUserEntity.builder()
                .id(1L)
                .username("admin")
                .password("hashed-password")
                .role(UserRole.ADMIN)
                .enabled(true)
                .marketingEmailOptIn(true)
                .termsVersion("April 2026")
                .build();
    }

    /** Stubs the issuer to return tokens for {@code who}, keyed on the id and hash login must pass it. */
    private void issuing(AppUserEntity who) {
        when(tokenIssuer.issue(who.getId(), who.getPassword())).thenReturn(new AuthTokens(ACCESS_TOKEN,
                RAW_REFRESH, Instant.parse("2026-07-16T10:00:00Z"), Instant.parse("2026-08-16T10:00:00Z"),
                who.getUsername(), who.getRole().name(), who.isPasswordChangeRequired(),
                who.isMarketingEmailOptIn(), who.getTermsVersion()));
    }

    @Nested
    class Login {

        @Test
        @DisplayName("valid credentials stamp lastActiveAt with a scoped update, then issue through the issuer")
        void validCredentials_issuesTokensAndStampsLastActive() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            issuing(user);

            AuthTokens tokens = service.login("admin", "golden2026");

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(tokens.refreshToken()).isEqualTo(RAW_REFRESH);
            assertThat(tokens.accessTokenExpiresAt())
                    .isEqualTo(Instant.parse("2026-07-16T10:00:00Z"));
            assertThat(tokens.username()).isEqualTo("admin");
            assertThat(tokens.role()).isEqualTo("ADMIN");
            assertThat(tokens.marketingEmailOptIn()).isTrue();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");

            InOrder order = inOrder(userRepository, tokenIssuer);
            order.verify(userRepository).touchLastActiveAt(eq(1L), any(LocalDateTime.class));
            order.verify(tokenIssuer).issue(1L, "hashed-password");
            // The stamp is column-scoped: login never saves the entity it loaded before the BCrypt check,
            // which would write that entity's OLD hash back over a change committed meanwhile.
            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("wrong password throws and writes nothing")
        void wrongPassword_throwsWithoutWrites() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashed-password")).thenReturn(false);

            assertThatThrownBy(() -> service.login("admin", "wrong"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("unknown username throws the same message as a wrong password")
        void unknownUser_throwsWithoutWrites() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login("ghost", "anything"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("a stored hash that is not BCrypt-shaped (a neutralised default credential) yields the "
                + "generic failure, and a dummy comparison keeps its response time uniform")
        void nonBcryptStoredHash_isTheGenericFailureAndSpendsADummyComparison() {
            user.setPassword("!disabled-default-admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "!disabled-default-admin")).thenReturn(false);
            when(passwordEncoder.encode("not-a-real-password")).thenReturn("$2a$10$dummy");

            assertThatThrownBy(() -> service.login("admin", "golden2026"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(passwordEncoder).matches("golden2026", "$2a$10$dummy");
            verify(userRepository, never()).touchLastActiveAt(any(), any());
            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("a BCrypt-shaped stored hash that fails to match spends no extra comparison")
        void bcryptStoredHash_wrongPassword_spendsNoDummyComparison() {
            user.setPassword("$2a$10$storedhashvalue");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "$2a$10$storedhashvalue")).thenReturn(false);

            assertThatThrownBy(() -> service.login("admin", "wrong"))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(passwordEncoder).matches("wrong", "$2a$10$storedhashvalue");
            verifyNoMoreInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("disabled account throws even with correct credentials")
        void disabledUser_throws() {
            user.setEnabled(false);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);

            assertThatThrownBy(() -> service.login("admin", "golden2026"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Account is disabled");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("the ordinary path never touches the attempt limiter")
        void ordinaryLogin_takesNoBudget() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            issuing(user);

            service.login("admin", "golden2026");
            service.login("admin", "golden2026", false);

            verifyNoInteractions(attemptLimiter);
        }

        @Test
        @DisplayName("the fallback takes the account's budget (user id, 5, quarter hour) before the password")
        void fallback_takesTheAccountBudgetFirst() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(attemptLimiter.tryAcquire("fallback:login:user:1", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(true);
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            issuing(user);

            AuthTokens tokens = service.login("admin", "golden2026", true);

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            InOrder order = inOrder(attemptLimiter, passwordEncoder);
            order.verify(attemptLimiter).tryAcquire("fallback:login:user:1", 5,
                    AuthAttemptLimiter.Window.QUARTER_HOUR);
            order.verify(passwordEncoder).matches("golden2026", "hashed-password");
        }

        @Test
        @DisplayName("a spent account budget throws TooManyAttempts and the password is never compared")
        void fallback_budgetSpent_throwsBeforeThePasswordCheck() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(attemptLimiter.tryAcquire("fallback:login:user:1", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.login("admin", "golden2026", true))
                    .isInstanceOf(TooManyAttemptsException.class);

            verifyNoInteractions(passwordEncoder);
            verify(userRepository, never()).save(any());
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("the fallback with no resolving user takes no budget and fails like any bad login")
        void fallback_unknownUser_takesNoBudget() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login("ghost", "anything", true))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verifyNoInteractions(attemptLimiter);
        }
    }

    @Nested
    class LoginByEmail {

        private static final String ADDRESS = "Reader@Example.com";

        private AppUserEntity reader;

        @BeforeEach
        void readerAccount() {
            reader = AppUserEntity.builder()
                    .id(7L).username("reader").email(ADDRESS).password("reader-hash")
                    .role(UserRole.PRO_USER).enabled(true).build();
        }

        @Test
        @DisplayName("an identifier with no @ never looks at emails")
        void plainUsername_neverTouchesTheEmailQueries() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            issuing(user);

            service.login("admin", "golden2026");

            verify(userRepository, never()).findByEmail(any());
            verify(userRepository, never()).findActiveByEmailLower(any());
        }

        @Test
        @DisplayName("one EXACT email match signs in and the lower-cased query is not asked")
        void exactEmailMatch_signsIn() {
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(passwordEncoder.matches("MyP@ssw0rd!", "reader-hash")).thenReturn(true);
            issuing(reader);

            AuthTokens tokens = service.login(ADDRESS, "MyP@ssw0rd!");

            assertThat(tokens.username()).isEqualTo("reader");
            verify(userRepository, never()).findActiveByEmailLower(any());
        }

        @Test
        @DisplayName("one case-insensitive match signs in, the key lower-cased with Locale.ROOT and trimmed")
        void caseInsensitiveEmailMatch_signsIn() {
            when(userRepository.findByUsername("  reader@EXAMPLE.com ")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("reader@EXAMPLE.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader));
            when(passwordEncoder.matches("MyP@ssw0rd!", "reader-hash")).thenReturn(true);
            issuing(reader);

            AuthTokens tokens = service.login("  reader@EXAMPLE.com ", "MyP@ssw0rd!");

            assertThat(tokens.username()).isEqualTo("reader");
        }

        @Test
        @DisplayName("two case-insensitive matches are no match: the generic failure, no password compared "
                + "against either account")
        void twoCaseInsensitiveMatches_areNoMatch() {
            AppUserEntity twin = AppUserEntity.builder().id(8L).username("twin").email("READER@example.com")
                    .password("twin-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("reader@example.com")).thenReturn(List.of(reader, twin));

            assertThatThrownBy(() -> service.login("reader@example.com", "MyP@ssw0rd!"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(passwordEncoder, never()).matches("MyP@ssw0rd!", "reader-hash");
            verify(passwordEncoder, never()).matches("MyP@ssw0rd!", "twin-hash");
            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("a disabled email match is ignored: the query only returns active accounts, and an "
                + "exact match that is disabled is not an account to sign in")
        void disabledExactEmailMatch_isIgnored() {
            reader.setEnabled(false);
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));

            assertThatThrownBy(() -> service.login(ADDRESS, "MyP@ssw0rd!"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(passwordEncoder, never()).matches("MyP@ssw0rd!", "reader-hash");
            verify(userRepository, never()).findActiveByEmailLower(any());
        }

        @Test
        @DisplayName("a non-ASCII address is matched exactly and never folded")
        void nonAsciiAddress_isMatchedExactlyAndNeverFolded() {
            String confusable = "bob@gmaıl.com";
            AppUserEntity bob = AppUserEntity.builder().id(9L).username("bob").email(confusable)
                    .password("bob-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername(confusable)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(confusable)).thenReturn(Optional.of(bob));
            when(passwordEncoder.matches("MyP@ssw0rd!", "bob-hash")).thenReturn(true);
            issuing(bob);

            assertThat(service.login(confusable, "MyP@ssw0rd!").username()).isEqualTo("bob");

            // ...and a non-ASCII identifier with no exact match goes no further.
            String other = "ann@gmaıl.com";
            when(userRepository.findByUsername(other)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(other)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.login(other, "x"))
                    .isInstanceOf(InvalidCredentialsException.class);
            verify(userRepository, never()).findActiveByEmailLower(any());
        }

        @Test
        @DisplayName("a username that is another account's address: the username account verifies first")
        void usernameCandidateWinsWhenItsPasswordVerifies() {
            AppUserEntity namedLikeAnAddress = AppUserEntity.builder().id(3L).username(ADDRESS)
                    .password("named-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.of(namedLikeAnAddress));
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(passwordEncoder.matches("named-pw", "named-hash")).thenReturn(true);
            issuing(namedLikeAnAddress);

            AuthTokens tokens = service.login(ADDRESS, "named-pw");

            assertThat(tokens.username()).isEqualTo(ADDRESS);
            verify(passwordEncoder, never()).matches("named-pw", "reader-hash");
        }

        @Test
        @DisplayName("a username that is another account's address: the email owner still signs in when the "
                + "username account's password fails (neither account is locked out)")
        void emailCandidateIsTriedWhenTheUsernameCandidateFails() {
            AppUserEntity namedLikeAnAddress = AppUserEntity.builder().id(3L).username(ADDRESS)
                    .password("named-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.of(namedLikeAnAddress));
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(passwordEncoder.matches("reader-pw", "named-hash")).thenReturn(false);
            when(passwordEncoder.matches("reader-pw", "reader-hash")).thenReturn(true);
            issuing(reader);

            AuthTokens tokens = service.login(ADDRESS, "reader-pw");

            assertThat(tokens.username()).isEqualTo("reader");
            InOrder order = inOrder(passwordEncoder);
            order.verify(passwordEncoder).matches("reader-pw", "named-hash");
            order.verify(passwordEncoder).matches("reader-pw", "reader-hash");
        }

        @Test
        @DisplayName("an account whose username is its own address is one candidate, not two")
        void sameAccountBothWays_isOneCandidate() {
            reader.setUsername(ADDRESS);
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.of(reader));
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(attemptLimiter.tryAcquire("fallback:login:user:7", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(true);
            when(passwordEncoder.matches("wrong", "reader-hash")).thenReturn(false);

            assertThatThrownBy(() -> service.login(ADDRESS, "wrong", true))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(passwordEncoder, times(1)).matches("wrong", "reader-hash");
            verify(attemptLimiter, times(1)).tryAcquire(any(), anyInt(), any());
        }

        @Test
        @DisplayName("the username account that verifies but is disabled does not block the email account")
        void disabledUsernameCandidate_doesNotBlockTheEmailOwner() {
            AppUserEntity disabledNamed = AppUserEntity.builder().id(3L).username(ADDRESS)
                    .password("shared-hash").role(UserRole.LITE_USER).enabled(false).build();
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.of(disabledNamed));
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(passwordEncoder.matches("pw", "shared-hash")).thenReturn(true);
            when(passwordEncoder.matches("pw", "reader-hash")).thenReturn(true);
            issuing(reader);

            assertThat(service.login(ADDRESS, "pw").username()).isEqualTo("reader");
        }

        @Test
        @DisplayName("no candidate resolves: a BCrypt comparison against the dummy hash still runs, "
                + "for an email and a username alike, encoding the dummy once")
        void noCandidate_runsTheDummyHashComparison() {
            when(passwordEncoder.encode(any())).thenReturn("dummy-hash");
            when(userRepository.findByUsername("ghost@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("ghost@example.com")).thenReturn(List.of());
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login("ghost@example.com", "pw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");
            assertThatThrownBy(() -> service.login("ghost", "pw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");

            verify(passwordEncoder, times(2)).matches("pw", "dummy-hash");
            verify(passwordEncoder, times(1)).encode(any());
        }

        @Test
        @DisplayName("the fallback budget is taken for the RESOLVED account, so a username and an email of one "
                + "account share one budget")
        void fallback_emailAndUsernameShareTheAccountBudget() {
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(userRepository.findByUsername("reader")).thenReturn(Optional.of(reader));
            when(attemptLimiter.tryAcquire("fallback:login:user:7", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(true, true, false);
            when(passwordEncoder.matches("wrong", "reader-hash")).thenReturn(false);

            assertThatThrownBy(() -> service.login(ADDRESS, "wrong", true))
                    .isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login("reader", "wrong", true))
                    .isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login(ADDRESS, "wrong", true))
                    .isInstanceOf(TooManyAttemptsException.class);
        }

        @Test
        @DisplayName("with two candidates the fallback takes BOTH accounts' budgets before any password is "
                + "compared, and a spent second budget refuses without comparing")
        void fallback_twoCandidates_takeBothBudgetsFirst() {
            AppUserEntity namedLikeAnAddress = AppUserEntity.builder().id(3L).username(ADDRESS)
                    .password("named-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername(ADDRESS)).thenReturn(Optional.of(namedLikeAnAddress));
            when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(reader));
            when(attemptLimiter.tryAcquire("fallback:login:user:3", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(true);
            when(attemptLimiter.tryAcquire("fallback:login:user:7", 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.login(ADDRESS, "pw", true))
                    .isInstanceOf(TooManyAttemptsException.class);

            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("an ambiguous or unmatched email falls back to the exact username candidate")
        void ambiguousEmail_fallsBackToTheUsernameCandidate() {
            AppUserEntity namedLikeAnAddress = AppUserEntity.builder().id(3L).username("a@b.example")
                    .password("named-hash").role(UserRole.LITE_USER).enabled(true).build();
            AppUserEntity first = AppUserEntity.builder().id(4L).username("one").email("A@B.example")
                    .password("one-hash").role(UserRole.LITE_USER).enabled(true).build();
            AppUserEntity second = AppUserEntity.builder().id(5L).username("two").email("a@B.example")
                    .password("two-hash").role(UserRole.LITE_USER).enabled(true).build();
            when(userRepository.findByUsername("a@b.example")).thenReturn(Optional.of(namedLikeAnAddress));
            when(userRepository.findByEmail("a@b.example")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailLower("a@b.example")).thenReturn(List.of(first, second));
            when(passwordEncoder.matches("named-pw", "named-hash")).thenReturn(true);
            issuing(namedLikeAnAddress);

            assertThat(service.login("a@b.example", "named-pw").username()).isEqualTo("a@b.example");
            verify(passwordEncoder, never()).matches("named-pw", "one-hash");
            verify(passwordEncoder, never()).matches("named-pw", "two-hash");
        }
    }

    @Nested
    class Refresh {

        @Test
        @DisplayName("a rotated token returns the new pair and revokes no family: the orchestrator is "
                + "non-transactional and hashes the raw token before the rotation")
        void validToken_rotates() {
            AuthTokens issued = new AuthTokens(ACCESS_TOKEN, RAW_REFRESH, Instant.parse("2026-07-16T10:00:00Z"),
                    Instant.parse("2026-08-16T10:00:00Z"), "admin", "ADMIN", false, true, "April 2026");
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(tokenIssuer.rotate("old-hash"))
                    .thenReturn(new TokenIssuer.Rotation(TokenIssuer.RotationStatus.ROTATED, 1L, issued));

            AuthTokens tokens = service.refresh("old-raw");

            assertThat(tokens).isSameAs(issued);
            verifyNoInteractions(familyRevoker);
        }

        @Test
        @DisplayName("a token revoked BY A CREDENTIAL CHANGE is refused but is not theft: the family is NOT "
                + "revoked, so the device that changed the password keeps the fresh tokens it was handed")
        void tokenRevokedByACredentialChange_isRefusedWithoutRevokingTheFamily() {
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(tokenIssuer.rotate("old-hash")).thenReturn(new TokenIssuer.Rotation(
                    TokenIssuer.RotationStatus.REVOKED_BY_CREDENTIAL_CHANGE, 1L, null));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verifyNoInteractions(familyRevoker);
        }

        @Test
        @DisplayName("an unknown or expired token: the rotation's refusal propagates and no family is revoked")
        void invalidToken_propagatesWithoutRevoking() {
            when(jwtService.hashToken("unknown")).thenReturn("unknown-hash");
            when(tokenIssuer.rotate("unknown-hash"))
                    .thenThrow(new InvalidCredentialsException("Refresh token is invalid or expired"));

            assertThatThrownBy(() -> service.refresh("unknown"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verifyNoInteractions(familyRevoker);
        }

        @Test
        @DisplayName("an already-revoked token revokes the whole family IN ITS OWN TRANSACTION, then throws — the "
                + "revocation is made after rotate() has returned, so it commits instead of rolling back with "
                + "the refusal")
        void revokedToken_revokesWholeFamilyThenThrows() {
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(tokenIssuer.rotate("old-hash")).thenReturn(
                    new TokenIssuer.Rotation(TokenIssuer.RotationStatus.REVOKED_SEEN, 1L, null));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            InOrder order = inOrder(tokenIssuer, familyRevoker);
            order.verify(tokenIssuer).rotate("old-hash");
            order.verify(familyRevoker).revokeAll(1L, "already-revoked token presented");
        }

        @Test
        @DisplayName("losing the rotation race revokes the family, then throws, and returns no tokens")
        void lostRotationRace_revokesFamilyThenThrows() {
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(tokenIssuer.rotate("old-hash")).thenReturn(
                    new TokenIssuer.Rotation(TokenIssuer.RotationStatus.LOST_RACE, 1L, null));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(familyRevoker).revokeAll(1L, "lost rotation race — token concurrently consumed");
        }

        @Test
        @DisplayName("the service itself carries no @Transactional on refresh — the theft response must "
                + "commit before the refusal is thrown")
        void refresh_isNotTransactional() throws NoSuchMethodException {
            assertThat(AuthenticationService.class.getMethod("refresh", String.class)
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                    .isFalse();
            assertThat(AuthenticationService.class
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                    .isFalse();
        }
    }

    @Nested
    class IssueTokensFor {

        @Test
        @DisplayName("delegates to the issuer with the id and the hash the caller just wrote")
        void delegatesWithTheHash() {
            AuthTokens issued = new AuthTokens(ACCESS_TOKEN, RAW_REFRESH, Instant.now(), Instant.now(),
                    "admin", "ADMIN", true, true, "April 2026");
            when(tokenIssuer.issue(1L, "just-written-hash")).thenReturn(issued);

            assertThat(service.issueTokensFor(1L, "just-written-hash")).isSameAs(issued);
        }

        @Test
        @DisplayName("the issuer's generic refusal (the hash moved, or the account was disabled) propagates")
        void issuerRefusal_propagates() {
            when(tokenIssuer.issue(1L, "stale-hash"))
                    .thenThrow(new InvalidCredentialsException("Invalid username or password"));

            assertThatThrownBy(() -> service.issueTokensFor(1L, "stale-hash"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid username or password");
        }
    }

    @Nested
    class ChangePassword {

        private static final String BUDGET_KEY = "change-password:user:1";

        private void budgetAdmits() {
            when(attemptLimiter.tryAcquire(BUDGET_KEY, 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(true);
        }

        @Test
        @DisplayName("takes the budget, verifies the current password, THEN encodes, applies ONE writer change "
                + "with the caller's version expectation, and issues fresh tokens against the hash it wrote")
        void verifiesThenEncodesThenWritesThenIssuesFreshTokens() {
            budgetAdmits();
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "hashed-password")).thenReturn(true);
            when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");
            VersionExpectation expectation = VersionExpectation.exact(3L);
            when(credentialChangeWriter.apply(1L, "encoded-new", Change.changePassword(), expectation))
                    .thenReturn(new CredentialChangeOutcome(1L, "admin", "admin@example.com", 4L));
            AuthTokens fresh = new AuthTokens("new-access", "new-refresh", Instant.now(), Instant.now(),
                    "admin", "ADMIN", false, true, "April 2026");
            when(tokenIssuer.issue(1L, "encoded-new")).thenReturn(fresh);

            AuthTokens result = service.changePassword("admin", "OldPass1!", "NewPass1!", expectation);

            assertThat(result).isSameAs(fresh);
            InOrder order = inOrder(attemptLimiter, passwordEncoder, credentialChangeWriter, tokenIssuer);
            order.verify(attemptLimiter).tryAcquire(BUDGET_KEY, 5, AuthAttemptLimiter.Window.QUARTER_HOUR);
            order.verify(passwordEncoder).matches("OldPass1!", "hashed-password");
            order.verify(passwordEncoder).encode("NewPass1!");
            order.verify(credentialChangeWriter).apply(1L, "encoded-new", Change.changePassword(), expectation);
            order.verify(tokenIssuer).issue(1L, "encoded-new");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a wrong current password throws CurrentPasswordMismatch and hashes, writes and issues "
                + "nothing")
        void wrongCurrentPassword_isRefusedBeforeAnyWrite() {
            budgetAdmits();
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Wrong1!", "hashed-password")).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword("admin", "Wrong1!", "NewPass1!",
                    VersionExpectation.trusted()))
                    .isInstanceOf(CurrentPasswordMismatchException.class)
                    .hasMessage("Current password is incorrect");

            verify(passwordEncoder, never()).encode(any());
            verifyNoInteractions(credentialChangeWriter);
            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("the budget is taken on EVERY attempt, a wrong password included, and a spent budget "
                + "throws TooManyAttempts before the password is compared")
        void spentBudget_throwsBeforeThePasswordCheck() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(attemptLimiter.tryAcquire(BUDGET_KEY, 5, AuthAttemptLimiter.Window.QUARTER_HOUR))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.changePassword("admin", "OldPass1!", "NewPass1!",
                    VersionExpectation.trusted()))
                    .isInstanceOf(TooManyAttemptsException.class);

            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(credentialChangeWriter);
            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("if the issuer refuses AFTER the writer committed (the account changed again in the gap) "
                + "the refusal propagates: the caller signs in again with the password that did change")
        void issuerRefusalAfterTheWrite_propagates() {
            budgetAdmits();
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "hashed-password")).thenReturn(true);
            when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");
            when(credentialChangeWriter.apply(1L, "encoded-new", Change.changePassword(),
                    VersionExpectation.trusted()))
                    .thenReturn(new CredentialChangeOutcome(1L, "admin", "admin@example.com", 4L));
            when(tokenIssuer.issue(1L, "encoded-new"))
                    .thenThrow(new InvalidCredentialsException("Invalid username or password"));

            assertThatThrownBy(() -> service.changePassword("admin", "OldPass1!", "NewPass1!",
                    VersionExpectation.trusted()))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(credentialChangeWriter).apply(1L, "encoded-new", Change.changePassword(),
                    VersionExpectation.trusted());
        }

        @Test
        @DisplayName("a stale session is refused by the writer, and NO fresh tokens are issued")
        void staleSession_issuesNothing() {
            budgetAdmits();
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPass1!", "hashed-password")).thenReturn(true);
            when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");
            when(credentialChangeWriter.apply(1L, "encoded-new", Change.changePassword(),
                    VersionExpectation.legacyUnchanged()))
                    .thenThrow(new StaleCredentialsException());

            assertThatThrownBy(() -> service.changePassword("admin", "OldPass1!", "NewPass1!",
                    VersionExpectation.legacyUnchanged()))
                    .isInstanceOf(StaleCredentialsException.class)
                    .hasMessage("Your session has changed. Sign in again.");

            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("unknown user throws before the budget, any hashing or any write")
        void unknownUser_throws() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword("ghost", "OldPass1!", "NewPass1!",
                    VersionExpectation.trusted()))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found");

            verifyNoInteractions(attemptLimiter);
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(credentialChangeWriter);
            verifyNoInteractions(tokenIssuer);
        }
    }

    @Nested
    class Logout {

        private RefreshTokenEntity stored(boolean revoked, LocalDateTime expiresAt) {
            return RefreshTokenEntity.builder()
                    .id(10L).tokenHash("hash").userId(1L).expiresAt(expiresAt).revoked(revoked).build();
        }

        private void presenting(RefreshTokenEntity token) {
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));
        }

        @Test
        @DisplayName("a live refresh token: lock the account first, revoke that token by compare-and-set, "
                + "then bump the credential version -- in that order")
        void liveRefreshToken_locksRevokesThenBumps() {
            presenting(stored(false, LocalDateTime.now().plusDays(10)));
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(new AppUserRepository.LockedAccount(
                    true, "hashed-password", "a@b.c", "admin", UserRole.ADMIN, 0L, null, false, true, null)));

            service.logout("raw", null);

            InOrder order = inOrder(userRepository, refreshTokenRepository);
            order.verify(userRepository).lockAccount(1L);
            order.verify(refreshTokenRepository).revokeIfActive("hash");
            order.verify(userRepository).bumpCredentialsVersion(1L);
            // Nothing else was touched: no save, no family revoke, no credential-change update.
            verify(refreshTokenRepository).findByTokenHash("hash");
            org.mockito.Mockito.verifyNoMoreInteractions(refreshTokenRepository, userRepository);
            verifyNoInteractions(familyRevoker, credentialChangeWriter);
        }

        @Test
        @DisplayName("an EXPIRED refresh token identifies nobody: no lock, no revoke, no version bump")
        void expiredRefreshToken_doesNotBump() {
            presenting(stored(false, LocalDateTime.now().minusMinutes(1)));

            service.logout("raw", null);

            verify(userRepository, never()).lockAccount(1L);
            verify(userRepository, never()).bumpCredentialsVersion(1L);
            verify(refreshTokenRepository, never()).revokeIfActive("hash");
        }

        @Test
        @DisplayName("an already-REVOKED refresh token identifies nobody: a replayed logout cannot be used to "
                + "log the owner out")
        void revokedRefreshToken_doesNotBump() {
            presenting(stored(true, LocalDateTime.now().plusDays(10)));

            service.logout("raw", null);

            verify(userRepository, never()).lockAccount(1L);
            verify(userRepository, never()).bumpCredentialsVersion(1L);
            verify(refreshTokenRepository, never()).revokeIfActive("hash");
        }

        @Test
        @DisplayName("an unknown token and no principal is a silent no-op")
        void unknownTokenNoPrincipal_noop() {
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

            service.logout("raw", null);

            verify(userRepository, never()).bumpCredentialsVersion(1L);
        }

        @Test
        @DisplayName("no token at all and no principal does nothing")
        void nothingPresented_noop() {
            service.logout(null, null);

            verifyNoInteractions(refreshTokenRepository, jwtService, userRepository);
        }

        @Test
        @DisplayName("an unusable token falls back to the authenticated principal: version bumped, the "
                + "presented (dead) token still passed to the conditional revoke")
        void deadTokenWithPrincipal_bumpsForThePrincipal() {
            presenting(stored(true, LocalDateTime.now().plusDays(10)));
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(new AppUserRepository.LockedAccount(
                    true, "hashed-password", "a@b.c", "admin", UserRole.ADMIN, 0L, null, false, true, null)));

            service.logout("raw", "admin");

            verify(refreshTokenRepository).revokeIfActive("hash");
            verify(userRepository).bumpCredentialsVersion(1L);
        }

        @Test
        @DisplayName("a principal with no refresh token bumps the version and revokes nothing")
        void principalOnly_bumpsWithoutRevoking() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(new AppUserRepository.LockedAccount(
                    true, "hashed-password", "a@b.c", "admin", UserRole.ADMIN, 0L, null, false, true, null)));

            service.logout(null, "admin");

            verify(userRepository).bumpCredentialsVersion(1L);
            verify(refreshTokenRepository, never()).revokeIfActive("hash");
        }

        @Test
        @DisplayName("an account that vanished before the lock is a no-op")
        void accountGone_noop() {
            presenting(stored(false, LocalDateTime.now().plusDays(10)));
            when(userRepository.lockAccount(1L)).thenReturn(Optional.empty());

            service.logout("raw", null);

            verify(userRepository, never()).bumpCredentialsVersion(1L);
            verify(refreshTokenRepository, never()).revokeIfActive("hash");
        }
    }

    @Nested
    class PasswordComplexity {

        @Test
        @DisplayName("null password fails the length rule")
        void nullPassword_failsLength() {
            assertThat(service.validatePasswordComplexity(null))
                    .isEqualTo("Password must be at least 8 characters.");
        }

        @Test
        @DisplayName("7 characters fails, 8 characters with all classes passes (boundary)")
        void lengthBoundary() {
            assertThat(service.validatePasswordComplexity("Abcde1!"))
                    .isEqualTo("Password must be at least 8 characters.");
            assertThat(service.validatePasswordComplexity("Abcdef1!")).isNull();
        }

        @Test
        @DisplayName("missing uppercase is rejected")
        void missingUppercase() {
            assertThat(service.validatePasswordComplexity("abcdefg1!"))
                    .isEqualTo("Password must contain at least one uppercase letter.");
        }

        @Test
        @DisplayName("missing lowercase is rejected")
        void missingLowercase() {
            assertThat(service.validatePasswordComplexity("ABCDEFG1!"))
                    .isEqualTo("Password must contain at least one lowercase letter.");
        }

        @Test
        @DisplayName("missing digit is rejected")
        void missingDigit() {
            assertThat(service.validatePasswordComplexity("Abcdefgh!"))
                    .isEqualTo("Password must contain at least one number.");
        }

        @Test
        @DisplayName("missing special character is rejected")
        void missingSpecial() {
            assertThat(service.validatePasswordComplexity("Abcdefg1"))
                    .isEqualTo("Password must contain at least one special character.");
        }
    }
}
