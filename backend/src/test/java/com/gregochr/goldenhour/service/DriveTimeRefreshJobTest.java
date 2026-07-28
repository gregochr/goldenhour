package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveTimeRefreshJob}.
 *
 * <p>The behaviours worth pinning are the ones that decide whether a user silently keeps stale or
 * missing drive times: who is selected, that one user's failure does not abandon the rest, and
 * that the "last calculated" stamp only advances when rows were actually written.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DriveTimeRefreshJob")
class DriveTimeRefreshJobTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private DriveDurationService driveDurationService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    @InjectMocks
    private DriveTimeRefreshJob job;

    private static AppUserEntity user(long id, Double lat, Double lon, boolean enabled) {
        AppUserEntity u = new AppUserEntity();
        u.setId(id);
        u.setHomeLatitude(lat);
        u.setHomeLongitude(lon);
        u.setEnabled(enabled);
        return u;
    }

    private static AppUserEntity withHome(long id) {
        return user(id, 54.97, -1.61, true);
    }

    @Test
    @DisplayName("Registers itself with the dynamic scheduler under the seeded job key")
    void registersJobTarget() {
        job.registerJob();
        verify(dynamicSchedulerService).registerJobTarget(eq("drive_time_refresh"), org.mockito.ArgumentMatchers.any());
    }

    @Nested
    @DisplayName("Selection")
    class Selection {

        @Test
        @DisplayName("Users with no saved home location are skipped — there is no origin to route from")
        void skipsUsersWithoutHome() {
            when(userRepository.findAll()).thenReturn(List.of(
                    user(1L, null, null, true),
                    user(2L, 54.97, null, true),
                    user(3L, null, -1.61, true)));

            job.runScheduled();

            verify(driveDurationService, never()).refreshForUser(anyLong(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("Disabled users are skipped")
        void skipsDisabledUsers() {
            when(userRepository.findAll()).thenReturn(List.of(user(1L, 54.97, -1.61, false)));

            job.runScheduled();

            verify(driveDurationService, never()).refreshForUser(anyLong(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("An empty roster is a no-op, not an error")
        void emptyRosterIsNoOp() {
            when(userRepository.findAll()).thenReturn(List.of());

            job.runScheduled();

            verify(driveDurationService, never()).refreshForUser(anyLong(), anyDouble(), anyDouble());
            verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Every eligible user is refreshed from their own home coordinates")
        void refreshesEachEligibleUser() {
            when(userRepository.findAll()).thenReturn(List.of(
                    user(1L, 54.97, -1.61, true),
                    user(2L, 51.50, -0.12, true)));
            when(driveDurationService.refreshForUser(anyLong(), anyDouble(), anyDouble())).thenReturn(5);

            job.runScheduled();

            verify(driveDurationService).refreshForUser(1L, 54.97, -1.61);
            verify(driveDurationService).refreshForUser(2L, 51.50, -0.12);
        }
    }

    @Nested
    @DisplayName("Resilience and the calculated-at stamp")
    class Resilience {

        @Test
        @DisplayName("One user's failure does not deny the others their refresh")
        void oneFailureDoesNotAbandonTheRun() {
            when(userRepository.findAll()).thenReturn(List.of(withHome(1L), withHome(2L), withHome(3L)));
            when(driveDurationService.refreshForUser(eq(1L), anyDouble(), anyDouble()))
                    .thenThrow(new IllegalStateException("ORS exploded"));
            when(driveDurationService.refreshForUser(eq(2L), anyDouble(), anyDouble())).thenReturn(4);
            when(driveDurationService.refreshForUser(eq(3L), anyDouble(), anyDouble())).thenReturn(4);

            job.runScheduled();

            // The run continues past the thrower rather than propagating out of the scheduler tick.
            verify(driveDurationService).refreshForUser(eq(2L), anyDouble(), anyDouble());
            verify(driveDurationService).refreshForUser(eq(3L), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("The stamp advances only when rows were actually written")
        void stampAdvancesOnlyOnRealWork() {
            AppUserEntity wrote = withHome(1L);
            AppUserEntity wroteNothing = withHome(2L);
            when(userRepository.findAll()).thenReturn(List.of(wrote, wroteNothing));
            when(driveDurationService.refreshForUser(eq(1L), anyDouble(), anyDouble())).thenReturn(7);
            when(driveDurationService.refreshForUser(eq(2L), anyDouble(), anyDouble())).thenReturn(0);

            job.runScheduled();

            assertThat(wrote.getDriveTimesCalculatedAt())
                    .as("a real refresh stamps the user").isNotNull();
            // A zero return is ORS unconfigured, rate-limited or empty. Stamping it would claim a
            // refresh that did not happen, and the UI would show a fresh "last calculated" over
            // drive times that never changed.
            assertThat(wroteNothing.getDriveTimesCalculatedAt())
                    .as("an empty refresh must not claim to have run").isNull();
            verify(userRepository).save(wrote);
            verify(userRepository, never()).save(wroteNothing);
        }

        @Test
        @DisplayName("A thrown refresh leaves the previous stamp untouched")
        void throwingLeavesStampAlone() {
            AppUserEntity existing = withHome(1L);
            Instant previously = Instant.parse("2026-07-01T02:40:00Z");
            existing.setDriveTimesCalculatedAt(previously);
            when(userRepository.findAll()).thenReturn(List.of(existing));
            when(driveDurationService.refreshForUser(anyLong(), anyDouble(), anyDouble()))
                    .thenThrow(new IllegalStateException("ORS down"));

            job.runScheduled();

            assertThat(existing.getDriveTimesCalculatedAt()).isEqualTo(previously);
            verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        // The manual path in UserSettingsService enforces a cooldown to stop the button being
        // hammered. A nightly tick is not abuse, and applying the cooldown here would skip exactly
        // the user who pressed the button that evening — the one whose data most likely just moved.
        @Test
        @DisplayName("No cooldown: a user refreshed moments ago is still refreshed by the job")
        void ignoresTheManualCooldown() {
            AppUserEntity justRefreshed = withHome(1L);
            justRefreshed.setDriveTimesCalculatedAt(Instant.now());
            when(userRepository.findAll()).thenReturn(List.of(justRefreshed));
            when(driveDurationService.refreshForUser(anyLong(), anyDouble(), anyDouble())).thenReturn(3);

            job.runScheduled();

            verify(driveDurationService).refreshForUser(eq(1L), anyDouble(), anyDouble());
        }
    }
}
