package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.TermsConstants;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
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
    private UserEmailService userEmailService;

    @Mock
    private EmailClaims emailClaims;

    @InjectMocks
    private UserService userService;

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

    @Test
    @DisplayName("setEnabled updates the enabled flag on the user")
    void setEnabled_existingUser_updatesFlag() {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        userService.setEnabled(1L, false);

        assertThat(user.isEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("setEnabled throws IllegalArgumentException when user is not found")
    void setEnabled_missingUser_throwsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setEnabled(99L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── setRole ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setRole updates the role on the user")
    void setRole_existingUser_updatesRole() {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        userService.setRole(1L, UserRole.ADMIN);

        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("setRole throws IllegalArgumentException when user is not found")
    void setRole_missingUser_throwsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setRole(99L, UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── setEmail ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setEmail updates the email on the user")
    void setEmail_existingUser_updatesEmail() {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        userService.setEmail(1L, "newemail@example.com");

        assertThat(user.getEmail()).isEqualTo("newemail@example.com");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("setEmail throws IllegalArgumentException when user is not found")
    void setEmail_missingUser_throwsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setEmail(99L, "test@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── resetPassword ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("resetPassword returns a PasswordResetResult and sets passwordChangeRequired")
    void resetPassword_existingUser_returnsResultAndSetsFlag() {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        user.setEmail("alice@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any(String.class))).thenReturn("hashed-temp");
        when(userRepository.save(any())).thenReturn(user);

        PasswordResetResult result = userService.resetPassword(1L);

        assertThat(result.temporaryPassword()).isNotNull().isNotBlank().hasSize(12);
        assertThat(result.username()).isEqualTo("alice");
        assertThat(result.email()).isEqualTo("alice@example.com");
        assertThat(user.isPasswordChangeRequired()).isTrue();
        verify(passwordEncoder).encode(result.temporaryPassword());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("resetPassword throws IllegalArgumentException when user is not found")
    void resetPassword_missingUser_throwsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
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

    // ── activateUser ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("activateUser")
    class ActivateUser {

        @Test
        @DisplayName("sets password, enables account, and clears passwordChangeRequired")
        void setsPasswordAndEnables() {
            AppUserEntity user = AppUserEntity.builder()
                    .id(1L).username("alice").password("").role(UserRole.LITE_USER)
                    .enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("MyP@ss1!")).thenReturn("encoded");
            when(userRepository.save(any())).thenReturn(user);

            userService.activateUser(1L, "MyP@ss1!");

            assertThat(user.isEnabled()).isTrue();
            assertThat(user.getPassword()).isEqualTo("encoded");
            assertThat(user.isPasswordChangeRequired()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("throws when user not found")
        void missingUser_throws() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.activateUser(99L, "pass"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("refuses an already-enabled account, leaving its credentials untouched")
        void enabledAccount_throwsAndDoesNotOverwrite() {
            AppUserEntity active = buildUser(1L, "admin", UserRole.ADMIN);
            when(userRepository.findById(1L)).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> userService.activateUser(1L, "Attacker1!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not awaiting activation");

            assertThat(active.getPassword()).isEqualTo("hashed");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a disabled account that already has a password")
        void disabledAccountWithPassword_throws() {
            AppUserEntity suspended = AppUserEntity.builder()
                    .id(2L).username("bob").password("hashed").role(UserRole.LITE_USER)
                    .enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findById(2L)).thenReturn(Optional.of(suspended));

            assertThatThrownBy(() -> userService.activateUser(2L, "Attacker1!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not awaiting activation");

            assertThat(suspended.getPassword()).isEqualTo("hashed");
            verify(userRepository, never()).save(any());
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
