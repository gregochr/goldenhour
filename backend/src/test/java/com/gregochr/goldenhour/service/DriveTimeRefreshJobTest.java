package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DriveTimeRefreshJob}.
 *
 * <p>The behaviours worth pinning are the ones that decide whether a user silently keeps stale,
 * missing or WRONG drive times: who is selected, that one user's failure does not abandon the rest,
 * that only a measurement with drive times in it is stored, and that it is stored through the
 * guarded writer — never by saving the user row the run read at the start.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DriveTimeRefreshJob")
class DriveTimeRefreshJobTest {

    private static final Instant NOW = Instant.parse("2026-09-16T02:40:00Z");

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private DriveDurationService driveDurationService;

    @Mock
    private UserDriveTimeWriter driveTimeWriter;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private DriveTimeRefreshJob job;

    @BeforeEach
    void setUp() {
        job = new DriveTimeRefreshJob(userRepository, locationRepository, driveDurationService,
                driveTimeWriter, dynamicSchedulerService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

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

    private static List<UserDriveTimeEntity> rowsFor(long userId, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new UserDriveTimeEntity(userId, (long) i, 1200 * i))
                .toList();
    }

    @Test
    @DisplayName("Registers itself with the dynamic scheduler under the seeded job key")
    void registersJobTarget() {
        job.registerJob();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Boolean>> target = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamicSchedulerService).registerJobTarget(eq("drive_time_refresh"), target.capture());
        // The registered target is the run itself: firing it reads the roster.
        when(userRepository.findAll()).thenReturn(List.of());
        target.getValue().accept(false);
        verify(userRepository).findAll();
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

            job.run(false);

            verifyNoInteractions(driveDurationService, driveTimeWriter);
        }

        @Test
        @DisplayName("Disabled users are skipped")
        void skipsDisabledUsers() {
            when(userRepository.findAll()).thenReturn(List.of(user(1L, 54.97, -1.61, false)));

            job.run(false);

            verifyNoInteractions(driveDurationService, driveTimeWriter);
        }

        @Test
        @DisplayName("An empty roster is a no-op, not an error")
        void emptyRosterIsNoOp() {
            when(userRepository.findAll()).thenReturn(List.of());

            job.run(false);

            verifyNoInteractions(driveDurationService, driveTimeWriter);
        }

        @Test
        @DisplayName("Every eligible user is measured from, and stored against, their own home")
        void refreshesEachEligibleUser() {
            List<UserDriveTimeEntity> first = rowsFor(1L, 5);
            List<UserDriveTimeEntity> second = rowsFor(2L, 3);
            when(userRepository.findAll()).thenReturn(List.of(
                    user(1L, 54.97, -1.61, true),
                    user(2L, 51.50, -0.12, true)));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(first));
            when(driveDurationService.measureForUser(2L, 51.50, -0.12)).thenReturn(Optional.of(second));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, first, NOW)).thenReturn(true);
            when(driveTimeWriter.storeIfHomeUnchanged(2L, 51.50, -0.12, second, NOW)).thenReturn(true);

            job.run(false);

            // The coordinates handed to the store are the ones measured from — the store's guard
            // compares against them, so passing anything else would defeat it.
            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, first, NOW);
            verify(driveTimeWriter).storeIfHomeUnchanged(2L, 51.50, -0.12, second, NOW);
        }
    }

    @Nested
    @DisplayName("Resilience and what gets stored")
    class Resilience {

        @Test
        @DisplayName("One user's failure does not deny the others their refresh")
        void oneFailureDoesNotAbandonTheRun() {
            List<UserDriveTimeEntity> second = rowsFor(2L, 4);
            List<UserDriveTimeEntity> third = rowsFor(3L, 4);
            when(userRepository.findAll()).thenReturn(List.of(withHome(1L), withHome(2L), withHome(3L)));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61))
                    .thenThrow(new IllegalStateException("ORS exploded"));
            when(driveDurationService.measureForUser(2L, 54.97, -1.61)).thenReturn(Optional.of(second));
            when(driveDurationService.measureForUser(3L, 54.97, -1.61)).thenReturn(Optional.of(third));
            when(driveTimeWriter.storeIfHomeUnchanged(2L, 54.97, -1.61, second, NOW)).thenReturn(true);
            when(driveTimeWriter.storeIfHomeUnchanged(3L, 54.97, -1.61, third, NOW)).thenReturn(true);

            job.run(false);

            // The run continues past the thrower rather than propagating out of the scheduler tick,
            // and the thrower itself stores nothing.
            verify(driveTimeWriter).storeIfHomeUnchanged(2L, 54.97, -1.61, second, NOW);
            verify(driveTimeWriter).storeIfHomeUnchanged(3L, 54.97, -1.61, third, NOW);
            verifyNoMoreInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("Only a measurement with drive times in it is stored — the stamp cannot claim a "
                + "refresh that measured nothing")
        void storesOnlyAMeasurementWithRows() {
            List<UserDriveTimeEntity> measured = rowsFor(1L, 7);
            when(userRepository.findAll()).thenReturn(List.of(withHome(1L), withHome(2L), withHome(3L)));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            // ORS answered, but with no valid duration to anywhere.
            when(driveDurationService.measureForUser(2L, 54.97, -1.61)).thenReturn(Optional.of(List.of()));
            // ORS unconfigured, rate-limited or empty: no answer at all.
            when(driveDurationService.measureForUser(3L, 54.97, -1.61)).thenReturn(Optional.empty());
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(false);

            // Storing an empty measurement would stamp a fresh "last calculated" over drive times
            // that never changed — or, for user 2, clear them on one bad night.
            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW);
            verifyNoMoreInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("A home that moved while it was measured is stored nothing, and the run goes on")
        void homeMovedMidRun_storesNothingAndContinues() {
            List<UserDriveTimeEntity> movedAway = rowsFor(1L, 6);
            List<UserDriveTimeEntity> next = rowsFor(2L, 6);
            when(userRepository.findAll()).thenReturn(List.of(withHome(1L), withHome(2L)));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(movedAway));
            when(driveDurationService.measureForUser(2L, 54.97, -1.61)).thenReturn(Optional.of(next));
            // The guard found the home had moved: the save that moved it discarded the old drive
            // times, and nothing measured from the old home may land on top of that.
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, movedAway, NOW)).thenReturn(false);
            when(driveTimeWriter.storeIfHomeUnchanged(2L, 54.97, -1.61, next, NOW)).thenReturn(true);

            job.run(false);

            // Not retried, not cleared, and nothing else written for that user.
            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, movedAway, NOW);
            verify(driveTimeWriter).storeIfHomeUnchanged(2L, 54.97, -1.61, next, NOW);
            verifyNoMoreInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("The user row the run read is never saved back")
        void neverSavesTheUserRow() {
            // The lost update this job used to cause: it read every user at the start, routed for
            // seconds each, then saved the whole row it had read — writing the old home back over a
            // postcode saved in between. The roster read is its only use of the user repository.
            List<UserDriveTimeEntity> measured = rowsFor(1L, 3);
            when(userRepository.findAll()).thenReturn(List.of(withHome(1L)));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(false);

            verify(userRepository).findAll();
            verifyNoMoreInteractions(userRepository);
        }

        // The Settings dialog's manual REFRESH BUTTON enforces its own 30-minute cooldown, in
        // UserSettingsService — that is a distinct concept from this job entirely and is untouched
        // here. A manual "Run now" trigger of this job carries no cooldown of its own: it is the
        // deliberate override tool, and it measures a user regardless of when they were last
        // measured (unlike a scheduled fire, which now skips them — see the Selection nested class).
        @Test
        @DisplayName("A manual Run Now trigger has no cooldown of its own: a user refreshed "
                + "moments ago is still measured")
        void manualTriggerIgnoresAnyCooldown() {
            AppUserEntity justRefreshed = withHome(1L);
            justRefreshed.setDriveTimesCalculatedAt(NOW.minusSeconds(60));
            List<UserDriveTimeEntity> measured = rowsFor(1L, 3);
            when(userRepository.findAll()).thenReturn(List.of(justRefreshed));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(true);

            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW);
            // Manual measures everyone outright — it never needs to know the roster's newest
            // location, unlike a scheduled fire.
            verifyNoInteractions(locationRepository);
        }
    }

    @Nested
    @DisplayName("Scheduled fire — only re-measures a user who needs it")
    class ScheduledSkipLogic {

        @Test
        @DisplayName("A null stamp — never measured, or a postcode change discarded it — measures")
        void nullStampMeasures() {
            AppUserEntity neverMeasured = withHome(1L);
            List<UserDriveTimeEntity> measured = rowsFor(1L, 2);
            when(userRepository.findAll()).thenReturn(List.of(neverMeasured));
            when(locationRepository.findMaxCreatedAt()).thenReturn(LocalDateTime.parse("2026-09-01T00:00:00"));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(false);

            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW);
        }

        @Test
        @DisplayName("A stamp present with the roster unchanged skips — no ORS call at all")
        void stampPresentRosterUnchangedSkips() {
            AppUserEntity upToDate = withHome(1L);
            Instant stamp = NOW.minusSeconds(3600);
            upToDate.setDriveTimesCalculatedAt(stamp);
            when(userRepository.findAll()).thenReturn(List.of(upToDate));
            // Nothing in the roster is newer than the stamp.
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(stamp.minusSeconds(60), ZoneOffset.UTC));

            job.run(false);

            verifyNoInteractions(driveDurationService);
            verifyNoInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("A location created after the stamp measures")
        void locationCreatedAfterStampMeasures() {
            AppUserEntity user = withHome(1L);
            Instant stamp = NOW.minusSeconds(3600);
            user.setDriveTimesCalculatedAt(stamp);
            List<UserDriveTimeEntity> measured = rowsFor(1L, 2);
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(stamp.plusSeconds(1), ZoneOffset.UTC));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(false);

            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW);
        }

        @Test
        @DisplayName("A location created before the stamp does not measure")
        void locationCreatedBeforeStampDoesNotMeasure() {
            AppUserEntity user = withHome(1L);
            Instant stamp = NOW.minusSeconds(3600);
            user.setDriveTimesCalculatedAt(stamp);
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(stamp.minusSeconds(1), ZoneOffset.UTC));

            job.run(false);

            verifyNoInteractions(driveDurationService);
            verifyNoInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("The exact boundary — a location created AT the stamp — does not measure")
        void locationCreatedExactlyAtStampDoesNotMeasure() {
            AppUserEntity user = withHome(1L);
            Instant stamp = NOW.minusSeconds(3600);
            user.setDriveTimesCalculatedAt(stamp);
            when(userRepository.findAll()).thenReturn(List.of(user));
            // Equal, not later — "later than the stamp" excludes the boundary itself.
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(stamp, ZoneOffset.UTC));

            job.run(false);

            verifyNoInteractions(driveDurationService);
            verifyNoInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("An empty roster's newest created_at is null and never measures anyone "
                + "already up to date")
        void nullNewestCreatedAtDoesNotMeasureAnUpToDateUser() {
            AppUserEntity user = withHome(1L);
            user.setDriveTimesCalculatedAt(NOW.minusSeconds(60));
            when(userRepository.findAll()).thenReturn(List.of(user));
            when(locationRepository.findMaxCreatedAt()).thenReturn(null);

            job.run(false);

            verifyNoInteractions(driveDurationService);
        }

        @Test
        @DisplayName("A quiet scheduled night makes zero OpenRouteService calls, decided from one "
                + "location query")
        void quietNightMakesNoCalls() {
            AppUserEntity a = withHome(1L);
            AppUserEntity b = withHome(2L);
            Instant stamp = NOW.minusSeconds(3600);
            a.setDriveTimesCalculatedAt(stamp);
            b.setDriveTimesCalculatedAt(stamp);
            when(userRepository.findAll()).thenReturn(List.of(a, b));
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(stamp.minusSeconds(1), ZoneOffset.UTC));

            job.run(false);

            verifyNoInteractions(driveDurationService, driveTimeWriter);
            verify(locationRepository, times(1)).findMaxCreatedAt();
        }

        @Test
        @DisplayName("Mixed users in one run: due users are measured, unchanged users are skipped, "
                + "and the counts add up")
        void mixedUsersInOneRun() {
            Instant newestLocation = NOW.minusSeconds(1800);
            AppUserEntity dueByNullStamp = withHome(1L);
            AppUserEntity upToDate = withHome(2L);
            // Refreshed AFTER the newest location was added — nothing new to see.
            upToDate.setDriveTimesCalculatedAt(newestLocation.plusSeconds(100));
            AppUserEntity dueByRosterGrowth = withHome(3L);
            // Refreshed BEFORE the newest location was added — due again.
            dueByRosterGrowth.setDriveTimesCalculatedAt(newestLocation.minusSeconds(100));

            List<UserDriveTimeEntity> firstRows = rowsFor(1L, 2);
            List<UserDriveTimeEntity> thirdRows = rowsFor(3L, 2);

            when(userRepository.findAll()).thenReturn(List.of(dueByNullStamp, upToDate, dueByRosterGrowth));
            when(locationRepository.findMaxCreatedAt())
                    .thenReturn(LocalDateTime.ofInstant(newestLocation, ZoneOffset.UTC));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(firstRows));
            when(driveDurationService.measureForUser(3L, 54.97, -1.61)).thenReturn(Optional.of(thirdRows));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, firstRows, NOW)).thenReturn(true);
            when(driveTimeWriter.storeIfHomeUnchanged(3L, 54.97, -1.61, thirdRows, NOW)).thenReturn(true);

            job.run(false);

            verify(driveDurationService).measureForUser(1L, 54.97, -1.61);
            verify(driveDurationService).measureForUser(3L, 54.97, -1.61);
            // upToDate (id 2) never reaches ORS at all.
            verify(driveDurationService, never()).measureForUser(eq(2L), anyDouble(), anyDouble());
            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, firstRows, NOW);
            verify(driveTimeWriter).storeIfHomeUnchanged(3L, 54.97, -1.61, thirdRows, NOW);
            verifyNoMoreInteractions(driveTimeWriter);
        }
    }

    @Nested
    @DisplayName("Manual trigger vs scheduled fire")
    class ManualVsScheduled {

        @Test
        @DisplayName("A manual trigger measures a user the scheduled fire would skip")
        void manualMeasuresAUserScheduledWouldSkip() {
            AppUserEntity upToDate = withHome(1L);
            Instant stamp = NOW.minusSeconds(3600);
            upToDate.setDriveTimesCalculatedAt(stamp);
            List<UserDriveTimeEntity> measured = rowsFor(1L, 2);
            when(userRepository.findAll()).thenReturn(List.of(upToDate));
            when(driveDurationService.measureForUser(1L, 54.97, -1.61)).thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW)).thenReturn(true);

            job.run(true);

            verify(driveTimeWriter).storeIfHomeUnchanged(1L, 54.97, -1.61, measured, NOW);
        }
    }
}
