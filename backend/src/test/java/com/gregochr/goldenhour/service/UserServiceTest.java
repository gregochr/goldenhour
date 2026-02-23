package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
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

        AppUserEntity result = userService.createUser("bob", "pass", UserRole.LITE_USER);

        assertThat(result.getUsername()).isEqualTo("bob");
        verify(passwordEncoder).encode("pass");
    }

    @Test
    @DisplayName("createUser throws IllegalArgumentException when username already exists")
    void createUser_duplicateUsername_throwsException() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("alice", "pass", UserRole.LITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice");
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
