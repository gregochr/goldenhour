package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    @DisplayName("createUser saves user with hashed password and returns entity")
    void createUser_newUsername_savesAndReturns() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(passwordEncoder.encode("pass")).thenReturn("hashed");
        AppUserEntity saved = buildUser(2L, "bob", UserRole.LITE_USER);
        when(userRepository.save(any())).thenReturn(saved);

        AppUserEntity result = userService.createUser("bob", "pass", UserRole.LITE_USER, "bob@example.com");

        assertThat(result.getUsername()).isEqualTo("bob");
        verify(passwordEncoder).encode("pass");
    }

    @Test
    @DisplayName("createUser throws IllegalArgumentException when username already exists")
    void createUser_duplicateUsername_throwsException() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("alice", "pass", UserRole.LITE_USER, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");
    }

    @Test
    @DisplayName("createUser throws IllegalArgumentException when email already exists")
    void createUser_duplicateEmail_throwsException() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("bob", "pass", UserRole.LITE_USER, "taken@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taken@example.com");
    }

    @Test
    @DisplayName("listAllUsers delegates to repository findAll")
    void listAllUsers_returnsAllUsers() {
        List<AppUserEntity> all = List.of(buildUser(1L, "alice", UserRole.ADMIN));
        when(userRepository.findAll()).thenReturn(all);

        assertThat(userService.listAllUsers()).hasSize(1);
    }

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

    @Test
    @DisplayName("createPendingUser creates a disabled user with empty password and marketing opt-in true")
    void createPendingUser_newUser_createsDisabledUser() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        AppUserEntity saved = AppUserEntity.builder()
                .id(10L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("new@example.com").enabled(false).createdAt(LocalDateTime.now())
                .marketingEmailOptIn(true).build();
        when(userRepository.save(any())).thenReturn(saved);

        AppUserEntity result = userService.createPendingUser("newuser", "new@example.com", true);

        assertThat(result.getUsername()).isEqualTo("newuser");
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isMarketingEmailOptIn()).isTrue();
    }

    @Test
    @DisplayName("createPendingUser stores marketing opt-in as false when user opts out")
    void createPendingUser_optOut_storesMarketingOptInFalse() {
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.empty());
        AppUserEntity saved = AppUserEntity.builder()
                .id(11L).username("bob").password("").role(UserRole.LITE_USER)
                .email("bob@example.com").enabled(false).createdAt(LocalDateTime.now())
                .marketingEmailOptIn(false).build();
        when(userRepository.save(any())).thenReturn(saved);

        AppUserEntity result = userService.createPendingUser("bob", "bob@example.com", false);

        assertThat(result.isMarketingEmailOptIn()).isFalse();
    }

    @Test
    @DisplayName("createPendingUser throws when username already exists")
    void createPendingUser_duplicateUsername_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.createPendingUser("alice", "alice@example.com", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username already exists");
    }

    @Test
    @DisplayName("createPendingUser throws when email is registered to an active account")
    void createPendingUser_activeEmailDuplicate_throws() {
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
    @DisplayName("createPendingUser deletes abandoned pending registration and re-creates")
    void createPendingUser_abandonedPending_deletesAndReCreates() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        AppUserEntity abandoned = AppUserEntity.builder()
                .id(5L).username("old").password("").role(UserRole.LITE_USER)
                .email("reuse@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(userRepository.findByEmail("reuse@example.com")).thenReturn(Optional.of(abandoned));
        AppUserEntity saved = AppUserEntity.builder()
                .id(6L).username("newuser").password("").role(UserRole.LITE_USER)
                .email("reuse@example.com").enabled(false).createdAt(LocalDateTime.now()).build();
        when(userRepository.save(any())).thenReturn(saved);

        AppUserEntity result = userService.createPendingUser("newuser", "reuse@example.com", true);

        verify(userRepository).delete(abandoned);
        assertThat(result.getUsername()).isEqualTo("newuser");
    }

    @Test
    @DisplayName("activateUser sets password, enables account, and clears passwordChangeRequired")
    void activateUser_setsPasswordAndEnables() {
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
    @DisplayName("activateUser throws when user not found")
    void activateUser_missingUser_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activateUser(99L, "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

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
