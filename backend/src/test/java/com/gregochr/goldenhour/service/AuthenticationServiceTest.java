package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.JwtProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RefreshTokenEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.exception.TooManyAttemptsException;
import com.gregochr.goldenhour.model.AuthTokens;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private JwtProperties jwtProperties;

    @Mock
    private AuthAttemptLimiter attemptLimiter;

    private AuthenticationService service;

    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userRepository, refreshTokenRepository,
                jwtService, passwordEncoder, jwtProperties, attemptLimiter);
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

    private void stubTokenIssuance() {
        when(jwtService.generateAccessToken("admin", UserRole.ADMIN)).thenReturn(ACCESS_TOKEN);
        when(jwtService.generateRefreshToken()).thenReturn(RAW_REFRESH);
        when(jwtService.hashToken(RAW_REFRESH)).thenReturn(REFRESH_HASH);
        when(jwtService.extractExpiry(ACCESS_TOKEN))
                .thenReturn(Date.from(Instant.parse("2026-07-16T10:00:00Z")));
        when(jwtProperties.getRefreshTokenExpiryDays()).thenReturn(REFRESH_EXPIRY_DAYS);
    }

    @Nested
    class Login {

        @Test
        @DisplayName("valid credentials stamp lastActiveAt and persist a refresh token in one flow")
        void validCredentials_issuesTokensAndStampsLastActive() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            stubTokenIssuance();

            AuthTokens tokens = service.login("admin", "golden2026");

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(tokens.refreshToken()).isEqualTo(RAW_REFRESH);
            assertThat(tokens.accessTokenExpiresAt())
                    .isEqualTo(Instant.parse("2026-07-16T10:00:00Z"));
            assertThat(tokens.username()).isEqualTo("admin");
            assertThat(tokens.role()).isEqualTo("ADMIN");
            assertThat(tokens.marketingEmailOptIn()).isTrue();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");

            verify(userRepository).save(argThat(saved ->
                    "admin".equals(saved.getUsername()) && saved.getLastActiveAt() != null));

            ArgumentCaptor<RefreshTokenEntity> tokenCaptor =
                    ArgumentCaptor.forClass(RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(tokenCaptor.capture());
            assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(REFRESH_HASH);
            assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(1L);
            assertThat(tokenCaptor.getValue().isRevoked()).isFalse();
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
            stubTokenIssuance();

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
            stubTokenIssuance();

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

        private void stubAnyIssuance() {
            when(jwtService.generateAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);
            when(jwtService.generateRefreshToken()).thenReturn(RAW_REFRESH);
            when(jwtService.hashToken(RAW_REFRESH)).thenReturn(REFRESH_HASH);
            when(jwtService.extractExpiry(ACCESS_TOKEN))
                    .thenReturn(Date.from(Instant.parse("2026-07-16T10:00:00Z")));
            when(jwtProperties.getRefreshTokenExpiryDays()).thenReturn(REFRESH_EXPIRY_DAYS);
        }

        @Test
        @DisplayName("an identifier with no @ never looks at emails")
        void plainUsername_neverTouchesTheEmailQueries() {
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("golden2026", "hashed-password")).thenReturn(true);
            stubTokenIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

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
            stubAnyIssuance();

            assertThat(service.login("a@b.example", "named-pw").username()).isEqualTo("a@b.example");
            verify(passwordEncoder, never()).matches("named-pw", "one-hash");
            verify(passwordEncoder, never()).matches("named-pw", "two-hash");
        }
    }

    @Nested
    class Refresh {

        private RefreshTokenEntity storedToken(boolean revoked, LocalDateTime expiresAt) {
            return RefreshTokenEntity.builder()
                    .id(10L)
                    .tokenHash("old-hash")
                    .userId(1L)
                    .expiresAt(expiresAt)
                    .revoked(revoked)
                    .build();
        }

        @Test
        @DisplayName("valid token is consumed via the atomic compare-and-set and a replacement issued")
        void validToken_rotates() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(refreshTokenRepository.revokeIfActive("old-hash")).thenReturn(1);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            stubTokenIssuance();

            AuthTokens tokens = service.refresh("old-raw");

            assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(tokens.refreshToken()).isEqualTo(RAW_REFRESH);

            verify(refreshTokenRepository).revokeIfActive("old-hash");
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(1L);

            ArgumentCaptor<RefreshTokenEntity> tokenCaptor =
                    ArgumentCaptor.forClass(RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(tokenCaptor.capture());
            RefreshTokenEntity issuedNew = tokenCaptor.getValue();
            assertThat(issuedNew.getTokenHash()).isEqualTo(REFRESH_HASH);
            assertThat(issuedNew.getUserId()).isEqualTo(1L);
            assertThat(issuedNew.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("unknown token throws and revokes nothing")
        void unknownToken_throws() {
            when(jwtService.hashToken("unknown")).thenReturn("unknown-hash");
            when(refreshTokenRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("unknown"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any());
        }

        @Test
        @DisplayName("expired token throws without being consumed")
        void expiredToken_throws() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().minusMinutes(1));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
            verify(refreshTokenRepository, never()).revokeAllActiveByUserId(1L);
        }

        @Test
        @DisplayName("already-revoked token throws and revokes the user's whole token family")
        void revokedToken_revokesWholeFamilyAndThrows() {
            RefreshTokenEntity stored = storedToken(true, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
            verify(refreshTokenRepository, never()).save(any());
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("losing the rotation race revokes the family and issues nothing")
        void lostRotationRace_revokesFamilyAndThrows() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(refreshTokenRepository.revokeIfActive("old-hash")).thenReturn(0);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Refresh token is invalid or expired");

            verify(refreshTokenRepository).revokeAllActiveByUserId(1L);
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("disabled user throws and the old token is NOT consumed")
        void disabledUser_throwsWithoutRevoking() {
            user.setEnabled(false);
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found or disabled");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
        }

        @Test
        @DisplayName("missing user throws")
        void missingUser_throws() {
            RefreshTokenEntity stored = storedToken(false, LocalDateTime.now().plusDays(10));
            when(jwtService.hashToken("old-raw")).thenReturn("old-hash");
            when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(stored));
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("old-raw"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found or disabled");

            verify(refreshTokenRepository, never()).save(any());
            verify(refreshTokenRepository, never()).revokeIfActive("old-hash");
        }
    }

    @Nested
    class IssueTokensFor {

        @Test
        @DisplayName("maps user fields onto the issued tokens")
        void mapsUserFields() {
            user.setPasswordChangeRequired(true);
            stubTokenIssuance();

            AuthTokens tokens = service.issueTokensFor(user);

            assertThat(tokens.passwordChangeRequired()).isTrue();
            assertThat(tokens.marketingEmailOptIn()).isTrue();
            assertThat(tokens.termsVersion()).isEqualTo("April 2026");
            assertThat(tokens.refreshTokenExpiresAt())
                    .isAfter(Instant.now().plus(java.time.Duration.ofDays(REFRESH_EXPIRY_DAYS - 1)));
        }
    }

    @Nested
    class ChangePassword {

        @Test
        @DisplayName("encodes the new password and clears the change-required flag")
        void encodesAndClearsFlag() {
            user.setPasswordChangeRequired(true);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("NewPass1!")).thenReturn("encoded-new");

            service.changePassword("admin", "NewPass1!");

            verify(userRepository).save(argThat(saved ->
                    "encoded-new".equals(saved.getPassword()) && !saved.isPasswordChangeRequired()));
        }

        @Test
        @DisplayName("unknown user throws")
        void unknownUser_throws() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword("ghost", "NewPass1!"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("User not found");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    class RevokeRefreshToken {

        @Test
        @DisplayName("marks a known token revoked")
        void knownToken_revoked() {
            RefreshTokenEntity stored = RefreshTokenEntity.builder()
                    .id(10L).tokenHash("hash").userId(1L)
                    .expiresAt(LocalDateTime.now().plusDays(10)).revoked(false).build();
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(stored));

            service.revokeRefreshToken("raw");

            verify(refreshTokenRepository).save(argThat(RefreshTokenEntity::isRevoked));
        }

        @Test
        @DisplayName("unknown token is a silent no-op")
        void unknownToken_noop() {
            when(jwtService.hashToken("raw")).thenReturn("hash");
            when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

            service.revokeRefreshToken("raw");

            verify(refreshTokenRepository, never()).save(any());
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
