package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocalAdminSeeder}: it seeds an empty table with the default admin, skips a non-empty
 * one, and exists only under the {@code local} profile.
 */
@ExtendWith(MockitoExtension.class)
class LocalAdminSeederTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private LocalAdminSeeder seeder;

    @Test
    @DisplayName("an empty user table is seeded with an enabled ADMIN named admin holding the encoded default")
    void emptyTable_isSeeded() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("golden2026")).thenReturn("encoded-default");

        seeder.seedDefaultAdmin();

        ArgumentCaptor<AppUserEntity> saved = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded-default");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a non-empty user table is left alone")
    void nonEmptyTable_isSkipped() {
        when(userRepository.count()).thenReturn(3L);

        seeder.seedDefaultAdmin();

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("the seeder exists under the local profile ONLY: a positive profile, so it fails closed")
    void profile_isLocalOnly() {
        Profile profile = LocalAdminSeeder.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("local");
    }
}
