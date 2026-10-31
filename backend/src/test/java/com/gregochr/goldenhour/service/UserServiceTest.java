package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.TermsConstants;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserRepository.LockedAccount;
import com.gregochr.goldenhour.repository.EmailVerificationTokenRepository;
import com.gregochr.goldenhour.repository.PasswordResetTokenRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailClaims emailClaims;

    @Mock
    private CredentialChangeWriter credentialChangeWriter;

    @Mock
    private PasswordResetTokenRepository resetTokenRepository;

    @Mock
    private EmailVerificationTokenRepository verificationTokenRepository;

    @Mock
    private UserDriveTimeRepository userDriveTimeRepository;

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, emailClaims,
                credentialChangeWriter, resetTokenRepository, verificationTokenRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), userDriveTimeRepository);
    }

    // ── loadUserByUsername ──────────────────────────────────────────────────────

    @Test
    @DisplayName("loadUserByUsername returns the user when found")
    void loadUserByUsername_userFound_returnsUser() {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(userService.loadUserByUsername("alice").getUsername()).isEqualTo("alice");
    }

    @Test
    @DisplayName("loadUserByUsername throws UsernameNotFoundException when user is missing")
    void loadUserByUsername_userMissing_throwsException() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    // ── createUser ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createUser")
    class CreateUser {

        @Test
        @DisplayName("saves user with correct fields and returns entity")
        void newUsername_savesWithCorrectFields() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            AppUserEntity captured = captor.getValue();

            assertThat(captured.getUsername()).isEqualTo("bob");
            assertThat(captured.getPassword()).isEqualTo("hashed");
            assertThat(captured.getRole()).isEqualTo(UserRole.LITE_USER);
            assertThat(captured.getEmail()).isEqualTo("bob@example.com");
            verify(passwordEncoder).encode("pass");
        }

        @Test
        @DisplayName("an admin-created user has NOT consented to marketing email")
        void adminCreatedUser_hasMarketingOptInFalse() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isMarketingEmailOptIn()).isFalse();
        }

        @Test
        @DisplayName("sets enabled=true on new user")
        void setsEnabledTrue() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("sets passwordChangeRequired=true on new user")
        void setsPasswordChangeRequiredTrue() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isPasswordChangeRequired()).isTrue();
        }

        @Test
        @DisplayName("sets createdAt to current time")
        void setsCreatedAt() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            LocalDateTime before = LocalDateTime.now();
            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCreatedAt())
                    .isNotNull()
                    .isAfterOrEqualTo(before)
                    .isCloseTo(LocalDateTime.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("throws when username already exists")
        void duplicateUsername_throwsException() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> userService.createUser("alice", "pass", UserRole.LITE_USER, "alice@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("alice");
        }

        @Test
        @DisplayName("throws when the email claim is refused, and saves nothing")
        void duplicateEmail_throwsException() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            doThrow(new IllegalArgumentException("Email already registered"))
                    .when(emailClaims).claim("Taken@example.com", null);

            assertThatThrownBy(() -> userService.createUser("bob", "pass", UserRole.LITE_USER, "Taken@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Email already registered");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("claims the email for a NEW account (null id) before the account is saved")
        void claimsTheEmailBeforeSaving() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "Bob@Example.com");

            InOrder order = inOrder(emailClaims, userRepository);
            order.verify(emailClaims).claim("Bob@Example.com", null);
            order.verify(userRepository).save(any());
        }

        @Test
        @DisplayName("refuses a username containing @, which a sign-in would read as an email address")
        void usernameWithAt_isRefused() {
            assertThatThrownBy(() -> userService.createUser("bob@home", "pass", UserRole.LITE_USER,
                    "bob@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Username must not contain '@'");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("stamps credentialsChangedAt at creation, so even a legacy token meets a non-null stamp "
                + "on a recreated row")
        void stampsCredentialsChangedAtAtCreation() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCredentialsChangedAt()).isEqualTo(NOW);
            assertThat(captor.getValue().getCredentialsVersion()).isZero();
        }

        @Test
        @DisplayName("refuses a non-ASCII address, claiming and saving nothing: the claim key is lower-cased "
                + "in Java while the database's LOWER() may fold it differently")
        void nonAsciiEmail_isRefused() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);

            assertThatThrownBy(() -> userService.createUser("bob", "pass", UserRole.LITE_USER,
                    "bob@gmaıl.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Please use an email address without accented or non-Latin characters");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("null email bypasses duplicate check")
        void nullEmail_bypassesDuplicateCheck() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, null);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getEmail()).isNull();
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("blank email bypasses duplicate check")
        void blankEmail_bypassesDuplicateCheck() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(passwordEncoder.encode("pass")).thenReturn("hashed");
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createUser("bob", "pass", UserRole.LITE_USER, "  ");

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getEmail()).isEqualTo("  ");
            verifyNoInteractions(emailClaims);
        }
    }

    // ── listAllUsers ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listAllUsers delegates to repository findAll")
    void listAllUsers_returnsAllUsers() {
        List<AppUserEntity> all = List.of(buildUser(1L, "alice", UserRole.ADMIN));
        when(userRepository.findAll()).thenReturn(all);

        assertThat(userService.listAllUsers()).hasSize(1);
    }

    // ── setEnabled ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setEnabled")
    class SetEnabled {

        @Test
        @DisplayName("DISABLE goes through the credential writer with a NULL password, the disable change and "
                + "no version check — and never touches the entity or saves it")
        void disable_goesThroughTheWriterWithANullPassword() {
            when(credentialChangeWriter.apply(1L, null, Change.disable(), VersionExpectation.trusted()))
                    .thenReturn(new CredentialChangeOutcome(1L, "alice", "alice@example.com", 5L));

            userService.setEnabled(1L, false);

            verify(credentialChangeWriter).apply(1L, null, Change.disable(), VersionExpectation.trusted());
            verify(userRepository, never()).save(any());
            verify(userRepository, never()).updateEnabled(any(), anyBoolean());
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("RE-ENABLE is the scoped updateEnabled(id, true) and nothing else: no writer call, no "
                + "version bump, no entity save")
        void reEnable_isTheScopedUpdate() {
            when(userRepository.updateEnabled(1L, true)).thenReturn(1);

            userService.setEnabled(1L, true);

            verify(userRepository).updateEnabled(1L, true);
            verifyNoInteractions(credentialChangeWriter);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("re-enabling an unknown account throws 'User not found'")
        void reEnable_missingUser_throwsException() {
            when(userRepository.updateEnabled(99L, true)).thenReturn(0);

            assertThatThrownBy(() -> userService.setEnabled(99L, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("User not found: 99");
        }

        @Test
        @DisplayName("disabling an unknown account throws: the writer's refusal propagates")
        void disable_missingUser_throwsException() {
            when(credentialChangeWriter.apply(99L, null, Change.disable(), VersionExpectation.trusted()))
                    .thenThrow(new IllegalArgumentException("User not found: 99"));

            assertThatThrownBy(() -> userService.setEnabled(99L, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }
    }

    // ── setRole ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setRole lands through the scoped updateRole — a demotion never goes through save()")
    void setRole_existingUser_updatesRole() {
        when(userRepository.updateRole(1L, UserRole.LITE_USER)).thenReturn(1);

        userService.setRole(1L, UserRole.LITE_USER);

        verify(userRepository).updateRole(1L, UserRole.LITE_USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("setRole throws IllegalArgumentException when user is not found")
    void setRole_missingUser_throwsException() {
        when(userRepository.updateRole(99L, UserRole.ADMIN)).thenReturn(0);

        assertThatThrownBy(() -> userService.setRole(99L, UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── setEmail ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setEmail")
    class SetEmail {

        private LockedAccount account(boolean enabled, String password, String email) {
            return new LockedAccount(enabled, password, email, "alice", UserRole.LITE_USER, 0L, null, false,
                    true, null);
        }

        @Test
        @DisplayName("locks the row FIRST, then claims the new address for THIS account, writes it, deletes "
                + "the account's unused reset links and ALL its verification links, and only then releases "
                + "the old key — in the writer's lock order")
        void setEmail_doesEverythingInOneOrderedTransaction() {
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(account(true, "hash", "Old@Example.com")));

            boolean pending = userService.setEmail(1L, "New@Example.com");

            assertThat(pending).isFalse();
            InOrder order = inOrder(userRepository, emailClaims, resetTokenRepository,
                    verificationTokenRepository);
            order.verify(userRepository).lockAccount(1L);
            order.verify(emailClaims).claim("New@Example.com", 1L);
            order.verify(userRepository).updateEmail(1L, "New@Example.com");
            order.verify(resetTokenRepository).deleteByUserIdAndUsedAtIsNull(1L);
            order.verify(verificationTokenRepository).deleteAllByUserIdInBulk(1L);
            order.verify(emailClaims).release("Old@Example.com");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate (another account holds the address under any casing) is refused with "
                + "'Email already registered' and nothing is written or deleted")
        void duplicateAddress_isRefusedBeforeAnyWrite() {
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(account(true, "hash", "old@example.com")));
            doThrow(new IllegalArgumentException("Email already registered"))
                    .when(emailClaims).claim("TAKEN@example.com", 1L);

            assertThatThrownBy(() -> userService.setEmail(1L, "TAKEN@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Email already registered");

            verify(userRepository, never()).updateEmail(any(), any());
            verifyNoInteractions(resetTokenRepository);
            verifyNoInteractions(verificationTokenRepository);
            verify(emailClaims, never()).release(any());
        }

        @Test
        @DisplayName("an account with no previous address releases nothing")
        void noPreviousAddress_releasesNothing() {
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(account(true, "hash", null)));

            userService.setEmail(1L, "new@example.com");

            verify(emailClaims, never()).release(any());
        }

        @Test
        @DisplayName("a PENDING account reports true so the response can say the verification link was "
                + "voided and the admin must resend")
        void pendingAccount_returnsTrue() {
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(account(false, "", "old@example.com")));

            assertThat(userService.setEmail(1L, "new@example.com")).isTrue();
            verify(verificationTokenRepository).deleteAllByUserIdInBulk(1L);
        }

        @Test
        @DisplayName("a non-ASCII address is refused before the row is even locked: the claim key is "
                + "lower-cased in Java and the database's LOWER() may fold it differently")
        void nonAsciiAddress_isRefused() {
            assertThatThrownBy(() -> userService.setEmail(1L, "bob@gmaıl.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Please use an email address without accented or non-Latin characters");

            verifyNoInteractions(userRepository);
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("setEmail throws IllegalArgumentException when user is not found")
        void setEmail_missingUser_throwsException() {
            when(userRepository.lockAccount(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.setEmail(99L, "test@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");

            verifyNoInteractions(emailClaims);
        }
    }

    // ── resetPassword ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        @Test
        @DisplayName("hashes the temporary password FIRST, applies the admin-temporary-password change through "
                + "the writer, and answers from the WRITER's outcome — the username and address read under the "
                + "lock after the change, never an entity loaded before the hash")
        void hashesFirst_thenWrites_andMailsTheOutcomesAddress() {
            when(userRepository.existsById(1L)).thenReturn(true);
            when(passwordEncoder.encode(any(String.class))).thenReturn("hashed-temp");
            when(credentialChangeWriter.apply(eq(1L), eq("hashed-temp"), eq(Change.adminTemporaryPassword()),
                    eq(VersionExpectation.trusted())))
                    .thenReturn(new CredentialChangeOutcome(1L, "alice", "moved@example.com", 2L));

            PasswordResetResult result = userService.resetPassword(1L);

            assertThat(result.temporaryPassword()).isNotNull().isNotBlank().hasSize(12);
            assertThat(result.username()).isEqualTo("alice");
            assertThat(result.email()).isEqualTo("moved@example.com");
            InOrder order = inOrder(passwordEncoder, credentialChangeWriter);
            order.verify(passwordEncoder).encode(result.temporaryPassword());
            order.verify(credentialChangeWriter).apply(1L, "hashed-temp", Change.adminTemporaryPassword(),
                    VersionExpectation.trusted());
            verify(userRepository, never()).save(any());
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("resetPassword is not transactional: BCrypt runs with no connection held")
        void resetPassword_isNotTransactional() throws NoSuchMethodException {
            assertThat(UserService.class.getMethod("resetPassword", Long.class)
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                    .isFalse();
        }

        @Test
        @DisplayName("resetPassword throws IllegalArgumentException when user is not found, hashing nothing")
        void resetPassword_missingUser_throwsException() {
            when(userRepository.existsById(99L)).thenReturn(false);

            assertThatThrownBy(() -> userService.resetPassword(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");

            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(credentialChangeWriter);
        }
    }

    // ── createPendingUser ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createPendingUser")
    class CreatePendingUser {

        @Test
        @DisplayName("creates disabled user with correct fields")
        void newUser_createsWithCorrectFields() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            AppUserEntity captured = captor.getValue();

            assertThat(captured.getUsername()).isEqualTo("newuser");
            assertThat(captured.getEmail()).isEqualTo("new@example.com");
            assertThat(captured.isEnabled()).isFalse();
            assertThat(captured.isMarketingEmailOptIn()).isTrue();
            assertThat(captured.getRole()).isEqualTo(UserRole.PRO_USER);
            assertThat(captured.getCredentialsChangedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("sets empty password on pending user")
        void setsEmptyPassword() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEmpty();
        }

        @Test
        @DisplayName("sets passwordChangeRequired=false on pending user")
        void setsPasswordChangeRequiredFalse() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isPasswordChangeRequired()).isFalse();
        }

        @Test
        @DisplayName("uses the role passed by the caller")
        void usesPassedRole() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true, UserRole.LITE_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(UserRole.LITE_USER);
        }

        @Test
        @DisplayName("stores marketing opt-in as false when user opts out")
        void optOut_storesMarketingOptInFalse() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("bob", "bob@example.com", false, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isMarketingEmailOptIn()).isFalse();
            assertThat(captor.getValue().getUsername()).isEqualTo("bob");
        }

        @Test
        @DisplayName("stores current terms version on pending user")
        void setsTermsVersion() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getTermsVersion()).isEqualTo(TermsConstants.CURRENT_TERMS_VERSION);
        }

        @Test
        @DisplayName("stores terms accepted timestamp on pending user")
        void setsTermsAcceptedAt() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            java.time.Instant before = java.time.Instant.now();
            userService.createPendingUser("newuser", "new@example.com", true, UserRole.PRO_USER);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getTermsAcceptedAt())
                    .isNotNull()
                    .isAfterOrEqualTo(before)
                    .isBefore(java.time.Instant.now().plusSeconds(5));
        }

        @Test
        @DisplayName("throws when username already exists")
        void duplicateUsername_throws() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> userService.createPendingUser("alice", "alice@example.com", true,
                    UserRole.PRO_USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Username already exists");
        }

        @Test
        @DisplayName("throws when email is registered to an active account")
        void activeEmailDuplicate_throws() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            AppUserEntity active = buildUser(1L, "existing", UserRole.LITE_USER);
            active.setEmail("taken@example.com");
            active.setPassword("hashed");
            when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(active));
            doThrow(new IllegalArgumentException("Email already registered"))
                    .when(emailClaims).claim("taken@example.com", null);

            assertThatThrownBy(() -> userService.createPendingUser("newuser", "taken@example.com", true,
                    UserRole.PRO_USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Email already registered");

            // An ACTIVE account is never deleted to make way for a registration.
            verify(userRepository, never()).deletePendingById(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("replaces an abandoned pending registration: lock, conditional delete, claim, then save, "
                + "in that order")
        void abandonedPending_deletesAndReCreates() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            AppUserEntity abandoned = AppUserEntity.builder()
                    .id(5L).username("old").password("").role(UserRole.LITE_USER)
                    .email("reuse@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findByEmail("reuse@example.com")).thenReturn(Optional.of(abandoned));
            when(userRepository.deletePendingById(5L)).thenReturn(1);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "reuse@example.com", true, UserRole.PRO_USER);

            InOrder order = inOrder(userRepository, emailClaims);
            order.verify(userRepository).findByIdForUpdate(5L);
            order.verify(userRepository).deletePendingById(5L);
            order.verify(emailClaims).claim("reuse@example.com", null);
            order.verify(userRepository).save(any());
            verify(userRepository, never()).delete(any());

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            AppUserEntity captured = captor.getValue();
            assertThat(captured.getUsername()).isEqualTo("newuser");
            assertThat(captured.getEmail()).isEqualTo("reuse@example.com");
            assertThat(captured.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("finds the abandoned registration by a case-insensitive ASCII match too")
        void abandonedPending_foundByCaseInsensitiveMatch() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            AppUserEntity abandoned = AppUserEntity.builder()
                    .id(5L).username("old").password("").role(UserRole.LITE_USER)
                    .email("Reuse@Example.com").enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findByEmail("reuse@example.com")).thenReturn(Optional.empty());
            when(userRepository.findPendingByEmailLower("reuse@example.com")).thenReturn(List.of(abandoned));
            when(userRepository.deletePendingById(5L)).thenReturn(1);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "reuse@example.com", true, UserRole.PRO_USER);

            verify(userRepository).deletePendingById(5L);
            verify(emailClaims).claim("reuse@example.com", null);
        }

        @Test
        @DisplayName("a retry racing the activation deletes nothing (0 rows) and is told the email is taken: "
                + "the account that has just been activated is never erased")
        void abandonedPending_activatedMeanwhile_deletesNothingAndRefuses() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            AppUserEntity abandoned = AppUserEntity.builder()
                    .id(5L).username("old").password("").role(UserRole.LITE_USER)
                    .email("reuse@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findByEmail("reuse@example.com")).thenReturn(Optional.of(abandoned));
            when(userRepository.deletePendingById(5L)).thenReturn(0);

            assertThatThrownBy(() -> userService.createPendingUser("newuser", "reuse@example.com", true,
                    UserRole.PRO_USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Email already registered");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("claims a brand-new address once, as a new account")
        void newAddress_isClaimedOnce() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "fresh@example.com", true, UserRole.PRO_USER);

            verify(emailClaims).claim("fresh@example.com", null);
            verify(userRepository, never()).deletePendingById(any());
        }
    }

    // ── account deletion ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteOwnAccount")
    class DeleteOwnAccount {

        private LockedAccount row(UserRole role, boolean enabled, String hash, long version, String email) {
            return new LockedAccount(enabled, hash, email, "reader", role, version, null, false, true, null);
        }

        private void theRowIs(LockedAccount row) {
            when(userRepository.findByUsernameForUpdate("reader"))
                    .thenReturn(Optional.of(buildUser(7L, "reader", UserRole.PRO_USER)));
            when(userRepository.lockAccount(7L)).thenReturn(Optional.of(row));
        }

        @Test
        @DisplayName("locks the row FIRST, purges the dependent rows, deletes by compare-and-set on the "
                + "verified hash and the session's version, and only then releases the email claim")
        void happyPath_runsInTheLockOrder() {
            theRowIs(row(UserRole.PRO_USER, true, "hash", 3L, "Reader@Example.com"));
            when(userRepository.deleteOwnAccountIfUnchanged(7L, "hash", 3L, UserRole.ADMIN)).thenReturn(1);

            DeletedAccount deleted = userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L));

            assertThat(deleted).isEqualTo(new DeletedAccount("reader", "Reader@Example.com"));
            InOrder order = inOrder(userRepository, userDriveTimeRepository, emailClaims);
            order.verify(userRepository).findByUsernameForUpdate("reader");
            order.verify(userRepository).lockAccount(7L);
            order.verify(userDriveTimeRepository).deleteAllByUserId(7L);
            order.verify(userRepository).deleteOwnAccountIfUnchanged(7L, "hash", 3L, UserRole.ADMIN);
            order.verify(emailClaims).release("Reader@Example.com");
            verify(userRepository, never()).delete(any());
        }

        @Test
        @DisplayName("a token with NO cv is held to a never-changed row: the delete's version is 0")
        void legacyToken_isHeldToVersionZero() {
            theRowIs(row(UserRole.LITE_USER, true, "hash", 0L, null));
            when(userRepository.deleteOwnAccountIfUnchanged(7L, "hash", 0L, UserRole.ADMIN)).thenReturn(1);

            userService.deleteOwnAccount("reader", "hash", VersionExpectation.legacyUnchanged());

            verify(userRepository).deleteOwnAccountIfUnchanged(7L, "hash", 0L, UserRole.ADMIN);
        }

        @Test
        @DisplayName("an account with no address releases no claim")
        void noAddress_releasesNothing() {
            theRowIs(row(UserRole.LITE_USER, true, "hash", 0L, null));
            when(userRepository.deleteOwnAccountIfUnchanged(7L, "hash", 0L, UserRole.ADMIN)).thenReturn(1);

            userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(0L));

            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("a locked row whose role is ADMIN is refused with the admin sentence BEFORE the purge — "
                + "an admin who promoted the account during BCrypt wins")
        void adminUnderTheLock_isRefusedBeforeThePurge() {
            theRowIs(row(UserRole.ADMIN, true, "hash", 3L, "a@example.com"));

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Admin accounts are deleted by another admin");

            verifyNoInteractions(userDriveTimeRepository);
            verify(userRepository, never()).deleteOwnAccountIfUnchanged(any(), any(), anyLong(), any());
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("a password changed while the typed one was being checked (the stored hash is no longer "
                + "the verified one) is a StaleCredentialsException and nothing is purged")
        void hashMoved_isStale() {
            theRowIs(row(UserRole.PRO_USER, true, "newer-hash", 4L, "a@example.com"));

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class)
                    .hasMessage("Your session has changed. Sign in again.");

            verifyNoInteractions(userDriveTimeRepository);
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("a credential version that moved with the SAME hash (a disable and re-enable, an admin "
                + "reset to a colliding hash) is stale too")
        void versionMoved_isStale() {
            theRowIs(row(UserRole.PRO_USER, true, "hash", 4L, "a@example.com"));

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class);

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("a legacy token (no cv) on a row that has since changed is stale")
        void legacyToken_onAChangedRow_isStale() {
            theRowIs(row(UserRole.PRO_USER, true, "hash", 1L, "a@example.com"));

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash",
                    VersionExpectation.legacyUnchanged())).isInstanceOf(StaleCredentialsException.class);

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("an account disabled meanwhile is stale: only an active account deletes itself")
        void disabledMeanwhile_isStale() {
            theRowIs(row(UserRole.PRO_USER, false, "hash", 3L, "a@example.com"));

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class);

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("an account that is already gone — at the name lookup or at the scalar read — is stale")
        void accountGone_isStale() {
            when(userRepository.findByUsernameForUpdate("reader")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class);

            when(userRepository.findByUsernameForUpdate("reader"))
                    .thenReturn(Optional.of(buildUser(7L, "reader", UserRole.PRO_USER)));
            when(userRepository.lockAccount(7L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class);

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("when the compare-and-set deletes 0 rows the method throws — which rolls the purge back — "
                + "and releases no claim")
        void compareAndSetDeletesNothing_throwsAndReleasesNothing() {
            theRowIs(row(UserRole.PRO_USER, true, "hash", 3L, "a@example.com"));
            when(userRepository.deleteOwnAccountIfUnchanged(7L, "hash", 3L, UserRole.ADMIN)).thenReturn(0);

            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.exact(3L)))
                    .isInstanceOf(StaleCredentialsException.class);

            verify(userDriveTimeRepository).deleteAllByUserId(7L);
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("a trusted expectation is refused outright: a deletion must be held to a version")
        void trustedExpectation_isRefused() {
            assertThatThrownBy(() -> userService.deleteOwnAccount("reader", "hash", VersionExpectation.trusted()))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(userRepository);
        }
    }

    @Nested
    @DisplayName("verifyPassword")
    class VerifyPassword {

        @Test
        @DisplayName("returns the stored hash the typed password matched")
        void matching_returnsTheStoredHash() {
            AppUserEntity user = buildUser(7L, "reader", UserRole.PRO_USER);
            user.setPassword("stored-hash");
            when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("typed", "stored-hash")).thenReturn(true);

            assertThat(userService.verifyPassword("reader", "typed")).contains("stored-hash");
        }

        @Test
        @DisplayName("a wrong password, and an account with no hash to match, answer empty")
        void mismatchOrNoHash_isEmpty() {
            AppUserEntity user = buildUser(7L, "reader", UserRole.PRO_USER);
            user.setPassword("stored-hash");
            when(userRepository.findByUsername("reader")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);
            assertThat(userService.verifyPassword("reader", "wrong")).isEmpty();

            user.setPassword("");
            assertThat(userService.verifyPassword("reader", "anything")).isEmpty();
            user.setPassword(null);
            assertThat(userService.verifyPassword("reader", "anything")).isEmpty();
            verify(passwordEncoder, never()).matches("anything", "");
            verify(passwordEncoder, never()).matches("anything", null);
        }

        @Test
        @DisplayName("an account that no longer exists is a stale session, not a wrong password")
        void missingAccount_isStale() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.verifyPassword("ghost", "typed"))
                    .isInstanceOf(StaleCredentialsException.class);
        }
    }

    @Nested
    @DisplayName("deleteUser (admin path)")
    class DeleteUser {

        private LockedAccount row(String username, String email) {
            return new LockedAccount(true, "hash", email, username, UserRole.LITE_USER, 0L, null, false, true,
                    null);
        }

        @Test
        @DisplayName("locks the row FIRST, purges the dependent rows, deletes, then releases the email claim; "
                + "hands back the STORED name and address for the after-commit email and sends none itself")
        void happyPath_runsInTheLockOrder() {
            when(userRepository.lockAccount(7L)).thenReturn(Optional.of(row("bob", "Bob@Example.com")));
            when(userRepository.deleteAccountById(7L)).thenReturn(1);

            DeletedAccount deleted = userService.deleteUser(7L, "admin");

            assertThat(deleted).isEqualTo(new DeletedAccount("bob", "Bob@Example.com"));
            InOrder order = inOrder(userRepository, userDriveTimeRepository, emailClaims);
            order.verify(userRepository).lockAccount(7L);
            order.verify(userDriveTimeRepository).deleteAllByUserId(7L);
            order.verify(userRepository).deleteAccountById(7L);
            order.verify(emailClaims).release("Bob@Example.com");
        }

        @Test
        @DisplayName("an admin deleting their own account is refused before anything is purged")
        void selfDeletion_isRefusedBeforeThePurge() {
            when(userRepository.lockAccount(1L)).thenReturn(Optional.of(row("admin", "a@example.com")));

            assertThatThrownBy(() -> userService.deleteUser(1L, "admin"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot delete your own account");

            verifyNoInteractions(userDriveTimeRepository);
            verify(userRepository, never()).deleteAccountById(any());
            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("a missing user is an IllegalArgumentException and purges nothing")
        void missingUser_isRefused() {
            when(userRepository.lockAccount(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.deleteUser(99L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("a delete that removes no row throws (rolling the purge back) and releases no claim")
        void noRowDeleted_throwsAndReleasesNothing() {
            when(userRepository.lockAccount(7L)).thenReturn(Optional.of(row("bob", "bob@example.com")));
            when(userRepository.deleteAccountById(7L)).thenReturn(0);

            assertThatThrownBy(() -> userService.deleteUser(7L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(emailClaims);
        }

        @Test
        @DisplayName("an account with a blank address releases no claim")
        void blankAddress_releasesNothing() {
            when(userRepository.lockAccount(7L)).thenReturn(Optional.of(row("bob", " ")));
            when(userRepository.deleteAccountById(7L)).thenReturn(1);

            userService.deleteUser(7L, "admin");

            verifyNoInteractions(emailClaims);
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private AppUserEntity buildUser(Long id, String username, UserRole role) {
        return AppUserEntity.builder()
                .id(id)
                .username(username)
                .password("hashed")
                .role(role)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 2, 1, 9, 0))
                .build();
    }
}
