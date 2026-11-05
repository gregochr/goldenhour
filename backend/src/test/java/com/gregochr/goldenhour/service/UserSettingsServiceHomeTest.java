package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Home-location edges of {@link UserSettingsService}: the coordinates-only reader, a drive-time refresh with only
 * half a home, and the exact-coordinate comparison on a pending drive-time attempt.
 */
class UserSettingsServiceHomeTest {

    private static final String USERNAME = "testuser";
    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-29T10:15:30Z");

    private AppUserRepository userRepository;
    private DriveDurationService driveDurationService;
    private UserDriveTimeWriter driveTimeWriter;
    private Authentication auth;
    private UserSettingsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        driveDurationService = mock(DriveDurationService.class);
        driveTimeWriter = mock(UserDriveTimeWriter.class);
        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(USERNAME);
        service = new UserSettingsService(userRepository, mock(PostcodesIoClient.class), driveDurationService,
                driveTimeWriter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AppUserEntity user(Double lat, Double lon) {
        AppUserEntity user = AppUserEntity.builder().id(USER_ID).username(USERNAME).email("t@example.com")
                .role(UserRole.PRO_USER).build();
        user.setHomePostcode("DH1 3LE");
        user.setHomeLatitude(lat);
        user.setHomeLongitude(lon);
        user.setLocalRadiusMiles(30);
        return user;
    }

    @Test
    @DisplayName("getHomeLocation returns the stored coordinates, radius and postcode without any lookup")
    void getHomeLocation_returnsStoredHome() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(54.7761, -1.5733)));

        UserSettingsService.HomeLocation home = service.getHomeLocation(auth);

        assertThat(home).isEqualTo(
                new UserSettingsService.HomeLocation(USER_ID, 54.7761, -1.5733, 30, "DH1 3LE", null));
        verifyNoInteractions(driveDurationService);
    }

    @Test
    @DisplayName("a drive-time refresh needs both a home latitude and longitude; either missing is a 400")
    void refreshDriveTimes_halfAHome_badRequest() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(54.7761, null)));
        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Set a home location before refreshing drive times");

        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user(null, -1.5733)));
        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(driveDurationService);
        verifyNoInteractions(driveTimeWriter);
    }

    @Test
    @DisplayName("a pending attempt matches its origin only when both coordinates are exactly equal")
    void pendingAttempt_matchesOrigin_exactOnBothCoordinates() {
        UserSettingsService.PendingAttempt attempt = new UserSettingsService.PendingAttempt(NOW, 54.7761, -1.5733);

        assertThat(attempt.matchesOrigin(54.7761, -1.5733)).isTrue();
        assertThat(attempt.matchesOrigin(54.7762, -1.5733)).isFalse();
        assertThat(attempt.matchesOrigin(54.7761, -1.5734)).isFalse();
    }
}
