package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
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
        @DisplayName("throws when email already exists")
        void duplicateEmail_throwsException() {
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.createUser("bob", "pass", UserRole.LITE_USER, "taken@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("taken@example.com");
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

            userService.createPendingUser("newuser", "new@example.com", true);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            AppUserEntity captured = captor.getValue();

            assertThat(captured.getUsername()).isEqualTo("newuser");
            assertThat(captured.getEmail()).isEqualTo("new@example.com");
            assertThat(captured.isEnabled()).isFalse();
            assertThat(captured.isMarketingEmailOptIn()).isTrue();
            assertThat(captured.getRole()).isEqualTo(UserRole.LITE_USER);
        }

        @Test
        @DisplayName("sets empty password on pending user")
        void setsEmptyPassword() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true);

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

            userService.createPendingUser("newuser", "new@example.com", true);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isPasswordChangeRequired()).isFalse();
        }

        @Test
        @DisplayName("sets role to LITE_USER regardless of other state")
        void setsRole_LITE_USER() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "new@example.com", true);

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

            userService.createPendingUser("bob", "bob@example.com", false);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isMarketingEmailOptIn()).isFalse();
            assertThat(captor.getValue().getUsername()).isEqualTo("bob");
        }

        @Test
        @DisplayName("throws when username already exists")
        void duplicateUsername_throws() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> userService.createPendingUser("alice", "alice@example.com", true))
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

            assertThatThrownBy(() -> userService.createPendingUser("newuser", "taken@example.com", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Email already registered");
        }

        @Test
        @DisplayName("deletes abandoned pending registration and re-creates")
        void abandonedPending_deletesAndReCreates() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            AppUserEntity abandoned = AppUserEntity.builder()
                    .id(5L).username("old").password("").role(UserRole.LITE_USER)
                    .email("reuse@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
            when(userRepository.findByEmail("reuse@example.com")).thenReturn(Optional.of(abandoned));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.createPendingUser("newuser", "reuse@example.com", true);

            verify(userRepository).delete(abandoned);

            ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
            verify(userRepository).save(captor.capture());
            AppUserEntity captured = captor.getValue();
            assertThat(captured.getUsername()).isEqualTo("newuser");
            assertThat(captured.getEmail()).isEqualTo("reuse@example.com");
            assertThat(captured.isEnabled()).isFalse();
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
