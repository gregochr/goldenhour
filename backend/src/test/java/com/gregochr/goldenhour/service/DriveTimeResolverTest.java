package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveTimeResolver}.
 */
@ExtendWith(MockitoExtension.class)
class DriveTimeResolverTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserDriveTimeRepository repository;

    private DriveTimeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DriveTimeResolver(repository);
    }

    @Test
    @DisplayName("getAllMinutes returns location-to-minutes map")
    void getAllMinutes_withEntries_returnsMap() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                new UserDriveTimeEntity(USER_ID, 1L, 1800),   // 30 min
                new UserDriveTimeEntity(USER_ID, 2L, 5400))); // 90 min

        Map<Long, Integer> result = resolver.getAllMinutes(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(1L)).isEqualTo(30);
        assertThat(result.get(2L)).isEqualTo(90);
    }

    @Test
    @DisplayName("getAllMinutes returns empty map when no drive times")
    void getAllMinutes_noEntries_returnsEmpty() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of());

        Map<Long, Integer> result = resolver.getAllMinutes(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAllMinutes rounds seconds to nearest minute")
    void getAllMinutes_roundsCorrectly() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                new UserDriveTimeEntity(USER_ID, 1L, 89),    // 1.48 min → 1
                new UserDriveTimeEntity(USER_ID, 2L, 150))); // 2.5 min → 3 (round half up)

        Map<Long, Integer> result = resolver.getAllMinutes(USER_ID);

        assertThat(result.get(1L)).isEqualTo(1);
        assertThat(result.get(2L)).isEqualTo(3);
    }

    @Test
    @DisplayName("hasDriveTimes returns true when entries exist")
    void hasDriveTimes_withEntries_returnsTrue() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                new UserDriveTimeEntity(USER_ID, 1L, 1800)));

        assertThat(resolver.hasDriveTimes(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("hasDriveTimes returns false when no entries")
    void hasDriveTimes_noEntries_returnsFalse() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of());

        assertThat(resolver.hasDriveTimes(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("findByUserId delegates to repository")
    void findByUserId_delegatesToRepository() {
        List<UserDriveTimeEntity> entities = List.of(
                new UserDriveTimeEntity(USER_ID, 1L, 1800));
        when(repository.findByUserId(USER_ID)).thenReturn(entities);

        List<UserDriveTimeEntity> result = resolver.findByUserId(USER_ID);

        assertThat(result).isEqualTo(entities);
    }
}
