package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.UnroutableLocationDto;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.DrivePair;
import com.gregochr.goldenhour.repository.DriveStamp;
import com.gregochr.goldenhour.repository.RegionDriveTimeRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UnroutableLocationService}: the per-origin {@code DriveStatus} judgement, aggregated per location.
 */
@ExtendWith(MockitoExtension.class)
class UnroutableLocationServiceTest {

    private static final long HELVELLYN = 1L;
    private static final long MOVED = 2L;
    private static final long BAMBURGH = 3L;
    private static final List<UserRole> ROLES = List.of(UserRole.PRO_USER, UserRole.ADMIN);

    @Mock
    private LocationService locationService;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private UserDriveTimeRepository userDriveTimeRepository;

    @Mock
    private RegionDriveTimeRepository regionDriveTimeRepository;

    private UnroutableLocationService service;

    @BeforeEach
    void setUp() {
        service = new UnroutableLocationService(locationService, userRepository, regionRepository,
                userDriveTimeRepository, regionDriveTimeRepository);
    }

    private static LocationEntity location(Long id, String name, LocalDateTime targetChangedAt) {
        return LocationEntity.builder().id(id).name(name).lat(54.0).lon(-2.0)
                .driveTargetChangedAt(targetChangedAt).build();
    }

    @Test
    @DisplayName("counts unroutable over judged per location, leaving PENDING pairs out of both counts")
    void findUnroutable_countsPerLocationWithJudgeableDenominator() {
        LocalDateTime longAgo = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 01:30 UTC is inside the one-hour margin of a 02:00 stamp, so a 02:00 origin cannot judge it.
        LocalDateTime recentlyMoved = LocalDateTime.of(2026, 11, 5, 1, 30);
        when(locationService.findAllEnabled()).thenReturn(List.of(
                location(BAMBURGH, "Bamburgh", longAgo),
                location(HELVELLYN, "Helvellyn", longAgo),
                location(MOVED, "Moved", recentlyMoved)));
        when(userRepository.findMeasuredHomeStamps(ROLES)).thenReturn(List.of(
                new DriveStamp(10L, Instant.parse("2026-11-05T02:00:00Z")),
                new DriveStamp(11L, Instant.parse("2026-11-05T03:00:00Z"))));
        when(userDriveTimeRepository.findMeasuredHomePairs(ROLES)).thenReturn(List.of(
                new DrivePair(10L, BAMBURGH),
                new DrivePair(11L, HELVELLYN),
                new DrivePair(11L, BAMBURGH)));
        when(regionRepository.findMeasuredBaseStamps()).thenReturn(List.of(
                new DriveStamp(20L, Instant.parse("2026-11-05T02:00:00Z"))));
        when(regionDriveTimeRepository.findMeasuredBasePairs()).thenReturn(List.of(
                new DrivePair(20L, BAMBURGH)));

        List<UnroutableLocationDto> result = service.findUnroutable();

        assertThat(result).containsExactly(
                // Home 10 has no row and was measured long after the move: unroutable. Home 11 has one.
                // The one base has no row either.
                new UnroutableLocationDto(HELVELLYN, "Helvellyn", 1, 2, 1, 1),
                // Only home 11 (stamp 03:00) is late enough to judge it; the 02:00 home and base are PENDING.
                new UnroutableLocationDto(MOVED, "Moved", 1, 1, 0, 0));
    }

    @Test
    @DisplayName("a location every origin can route to is not listed")
    void findUnroutable_fullyRoutableLocation_isOmitted() {
        LocalDateTime longAgo = LocalDateTime.of(2026, 11, 1, 0, 0);
        when(locationService.findAllEnabled()).thenReturn(List.of(location(BAMBURGH, "Bamburgh", longAgo)));
        when(userRepository.findMeasuredHomeStamps(ROLES)).thenReturn(List.of(
                new DriveStamp(10L, Instant.parse("2026-11-05T02:00:00Z"))));
        when(userDriveTimeRepository.findMeasuredHomePairs(ROLES)).thenReturn(List.of(
                new DrivePair(10L, BAMBURGH)));
        when(regionRepository.findMeasuredBaseStamps()).thenReturn(List.of());

        assertThat(service.findUnroutable()).isEmpty();
    }

    @Test
    @DisplayName("with no measured origin there is nothing to judge and no pair read is made")
    void findUnroutable_noMeasuredOrigins_readsNoPairs() {
        when(locationService.findAllEnabled()).thenReturn(List.of(
                location(HELVELLYN, "Helvellyn", LocalDateTime.of(2026, 11, 1, 0, 0))));
        when(userRepository.findMeasuredHomeStamps(ROLES)).thenReturn(List.of());
        when(regionRepository.findMeasuredBaseStamps()).thenReturn(List.of());

        assertThat(service.findUnroutable()).isEmpty();

        verify(userDriveTimeRepository, never()).findMeasuredHomePairs(ROLES);
        verify(regionDriveTimeRepository, never()).findMeasuredBasePairs();
    }

    @Test
    @DisplayName("a location without an id is skipped")
    void findUnroutable_locationWithoutId_isSkipped() {
        when(locationService.findAllEnabled()).thenReturn(List.of(
                location(null, "Unsaved", LocalDateTime.of(2026, 11, 1, 0, 0))));
        when(userRepository.findMeasuredHomeStamps(ROLES)).thenReturn(List.of());
        when(regionRepository.findMeasuredBaseStamps()).thenReturn(List.of());

        assertThat(service.findUnroutable()).isEmpty();
    }
}
