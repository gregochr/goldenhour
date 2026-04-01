package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserSettingsService}.
 */
@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    private static final String USERNAME = "testuser";

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PostcodesIoClient postcodesIoClient;
    @Mock
    private DriveDurationService driveDurationService;
    @Mock
    private Authentication auth;

    private UserSettingsService service;

    @BeforeEach
    void setUp() {
        service = new UserSettingsService(userRepository, postcodesIoClient, driveDurationService);
        org.mockito.Mockito.lenient().when(auth.getName()).thenReturn(USERNAME);
    }

    private AppUserEntity buildUser() {
        return AppUserEntity.builder()
                .id(42L)
                .username(USERNAME)
                .email("test@example.com")
                .role(UserRole.PRO_USER)
                .build();
    }

    @Test
    @DisplayName("getSettings returns profile with no home location")
    void getSettings_noHome_returnsNullFields() {
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.username()).isEqualTo(USERNAME);
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.role()).isEqualTo("PRO_USER");
        assertThat(response.homePostcode()).isNull();
        assertThat(response.homePlaceName()).isNull();
    }

    @Test
    @DisplayName("getSettings resolves place name when home postcode is set")
    void getSettings_withHome_resolvesPlaceName() {
        AppUserEntity user = buildUser();
        user.setHomePostcode("DH1 3LE");
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(postcodesIoClient.lookup("DH1 3LE")).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.homePostcode()).isEqualTo("DH1 3LE");
        assertThat(response.homePlaceName()).isEqualTo("Durham, County Durham");
    }

    @Test
    @DisplayName("getSettings gracefully handles postcode lookup failure")
    void getSettings_lookupFails_returnsNullPlaceName() {
        AppUserEntity user = buildUser();
        user.setHomePostcode("DH1 3LE");
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(postcodesIoClient.lookup("DH1 3LE")).thenThrow(
                new PostcodeLookupException("Service unavailable"));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.homePostcode()).isEqualTo("DH1 3LE");
        assertThat(response.homePlaceName()).isNull();
    }

    @Test
    @DisplayName("lookupPostcode delegates to client")
    void lookupPostcode_delegatesToClient() {
        PostcodeLookupResult expected = new PostcodeLookupResult(
                "DH1 3LE", 54.7761, -1.5733, "Durham, County Durham");
        when(postcodesIoClient.lookup("DH1 3LE")).thenReturn(expected);

        PostcodeLookupResult result = service.lookupPostcode("DH1 3LE");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("saveHome persists postcode and coordinates")
    void saveHome_persistsFields() {
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733));

        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        AppUserEntity saved = captor.getValue();
        assertThat(saved.getHomePostcode()).isEqualTo("DH1 3LE");
        assertThat(saved.getHomeLatitude()).isEqualTo(54.7761);
        assertThat(saved.getHomeLongitude()).isEqualTo(-1.5733);
    }

    @Test
    @DisplayName("refreshDriveTimes throws 400 when no home location set")
    void refreshDriveTimes_noHome_throws400() {
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Set a home location");
    }

    @Test
    @DisplayName("refreshDriveTimes throws 429 when recently refreshed")
    void refreshDriveTimes_recentRefresh_throws429() {
        AppUserEntity user = buildUser();
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        user.setDriveTimesCalculatedAt(Instant.now().minusSeconds(60)); // 1 minute ago
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("recently");
    }

    @Test
    @DisplayName("refreshDriveTimes succeeds and updates timestamp")
    void refreshDriveTimes_success_updatesTimestamp() {
        AppUserEntity user = buildUser();
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(driveDurationService.refreshForUser(42L, 54.7761, -1.5733)).thenReturn(15);

        DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(15);
        assertThat(response.calculatedAt()).isNotNull();
        verify(userRepository).save(any(AppUserEntity.class));
    }

    @Test
    @DisplayName("refreshDriveTimes allows refresh after cooldown period")
    void refreshDriveTimes_afterCooldown_succeeds() {
        AppUserEntity user = buildUser();
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        user.setDriveTimesCalculatedAt(Instant.now().minusSeconds(31 * 60)); // 31 min ago
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(driveDurationService.refreshForUser(42L, 54.7761, -1.5733)).thenReturn(10);

        DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(10);
    }

    @Test
    @DisplayName("getUserId returns user primary key")
    void getUserId_returnsId() {
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        Long userId = service.getUserId(auth);

        assertThat(userId).isEqualTo(42L);
    }
}
