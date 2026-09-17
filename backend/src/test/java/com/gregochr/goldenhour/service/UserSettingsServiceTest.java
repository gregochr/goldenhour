package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserSettingsService}.
 *
 * <p>The writes are pinned at the repository boundary: each one goes through its column-scoped
 * method with the exact values it should write, and none goes through {@code save()} on the whole
 * entity. A mocked repository cannot show the lost update those rules exist to prevent — that
 * needs a database, and lives in {@code AppUserRepositoryTest} (the updates' semantics),
 * {@code UserSettingsRaceSequenceTest} (the interleavings, through the real service) and the CI-only
 * {@code UserSettingsRowLockIntegrationTest} (the row lock on Postgres).
 */
@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    private static final String USERNAME = "testuser";
    private static final long USER_ID = 42L;

    /** Fixed rather than the wall clock, per this codebase's date-fixture rule. */
    private static final Instant NOW = Instant.parse("2026-08-29T10:15:30Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String DURHAM = "DH1 3LE";
    private static final double DURHAM_LAT = 54.7761;
    private static final double DURHAM_LON = -1.5733;
    private static final String NEWCASTLE = "NE1 4ST";
    private static final double NEWCASTLE_LAT = 54.9714;
    private static final double NEWCASTLE_LON = -1.6174;

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PostcodesIoClient postcodesIoClient;
    @Mock
    private DriveDurationService driveDurationService;
    @Mock
    private UserDriveTimeWriter driveTimeWriter;
    @Mock
    private Authentication auth;

    private UserSettingsService service;

    @BeforeEach
    void setUp() {
        service = new UserSettingsService(userRepository, postcodesIoClient, driveDurationService,
                driveTimeWriter, clock);
    }

    /** Stub {@code auth.getName()} — call in every test that passes {@code auth} to the service. */
    private void stubAuth() {
        when(auth.getName()).thenReturn(USERNAME);
    }

    private AppUserEntity buildUser() {
        return AppUserEntity.builder()
                .id(USER_ID)
                .username(USERNAME)
                .email("test@example.com")
                .role(UserRole.PRO_USER)
                .build();
    }

    /** A stored home, as the save's locked read and the refresh's read find it. */
    private AppUserEntity home(String postcode, double lat, double lon) {
        AppUserEntity user = buildUser();
        user.setHomePostcode(postcode);
        user.setHomeLatitude(lat);
        user.setHomeLongitude(lon);
        return user;
    }

    // ── saveHome ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveHome")
    class SaveHome {

        /** The locked read the decision is made from, and the read-back the response is built from. */
        private void stubRows(AppUserEntity stored, AppUserEntity afterSave) {
            stubAuth();
            when(userRepository.findByUsernameForUpdate(USERNAME)).thenReturn(Optional.of(stored));
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(afterSave));
        }

        @Test
        @DisplayName("moving home writes the new home and discards drive times measured from the old one")
        void originMoved_writesHomeAndClearsDriveTimes() {
            // A drive time is measured FROM an origin. The moment the origin moves, every stored row
            // describes a journey nobody is going to make — and unlike a missing one, a wrong one is
            // invisible: the reach lens gates a spot in or out on a figure tens of minutes off, with
            // nothing on screen saying so. Unknown is safe here; wrong is not.
            AppUserEntity stored = home(DURHAM, DURHAM_LAT, DURHAM_LON);
            stored.setDriveTimesCalculatedAt(NOW.minusSeconds(3600));
            stubRows(stored, home(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON));

            service.saveHome(auth, new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));

            verify(userRepository).updateHome(USER_ID, NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null);
            verify(driveTimeWriter).clearForUser(USER_ID);
            // The stamp is the refresh cooldown's own input: leaving it set would lock someone who
            // has just moved house out of recalculating while they are served no drive times at all.
            verify(userRepository).clearDriveTimesCalculatedAt(USER_ID);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("decides from a LOCKED read, taken before anything is written")
        void readsUnderTheLockBeforeWriting() {
            // Decided from an unlocked read, a save re-sending the old home could write it back
            // over a move that committed in between — keeping the drive times measured from the
            // new home. And the lock must be held before the drive-time rows are touched: a refresh
            // storing its result takes the user row first, so the opposite order can deadlock.
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), home(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON));

            service.saveHome(auth, new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));

            // Before EACH write — the writes' order among themselves is free.
            InOrder beforeHome = inOrder(userRepository);
            beforeHome.verify(userRepository).findByUsernameForUpdate(USERNAME);
            beforeHome.verify(userRepository).updateHome(USER_ID, NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null);
            InOrder beforeRows = inOrder(userRepository, driveTimeWriter);
            beforeRows.verify(userRepository).findByUsernameForUpdate(USERNAME);
            beforeRows.verify(driveTimeWriter).clearForUser(USER_ID);
            InOrder beforeStamp = inOrder(userRepository);
            beforeStamp.verify(userRepository).findByUsernameForUpdate(USERNAME);
            beforeStamp.verify(userRepository).clearDriveTimesCalculatedAt(USER_ID);
        }

        @Test
        @DisplayName("dragging the radius slider does NOT throw away a full set of routed drive times")
        void sameHome_keepsDriveTimes() {
            // saveHome is also the radius slider's save path: the settings modal re-sends the user's
            // EXISTING postcode and coordinates whenever the radius changes. Clearing unconditionally
            // would bin a whole roster of routed times every time somebody moved that slider — and
            // each refresh is an external routing call per location.
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), home(DURHAM, DURHAM_LAT, DURHAM_LON));

            // new String, not the literal: two compile-time literals are interned, so a reference
            // comparison would pass here and the guard would look correct while being blind to any
            // postcode that arrived off the wire rather than out of the constant pool.
            service.saveHome(auth, new SaveHomeRequest(new String(DURHAM), DURHAM_LAT, DURHAM_LON, 45));

            verify(userRepository).updateHome(USER_ID, DURHAM, DURHAM_LAT, DURHAM_LON, 45);
            verify(driveTimeWriter, never()).clearForUser(USER_ID);
            verify(userRepository, never()).clearDriveTimesCalculatedAt(USER_ID);
        }

        // Each of the three fields gets its own case, varying that field ALONE. `originMoved` is a
        // three-way OR, so a test that moves two fields at once leaves either term deletable: the
        // other one still fires and the suite stays green.

        @Test
        @DisplayName("a re-geocode that shifts the latitude alone counts as moving")
        void onlyLatitudeChanged_clearsDriveTimes() {
            // Distance and routing are computed from the COORDINATES, not the postcode text, so a
            // postcode-only comparison would keep drive times measured from a different point.
            assertMoved(new SaveHomeRequest(DURHAM, NEWCASTLE_LAT, DURHAM_LON, null));
        }

        @Test
        @DisplayName("a re-geocode that shifts the longitude alone counts as moving")
        void onlyLongitudeChanged_clearsDriveTimes() {
            assertMoved(new SaveHomeRequest(DURHAM, DURHAM_LAT, NEWCASTLE_LON, null));
        }

        @Test
        @DisplayName("a new postcode counts as moving even if the coordinates are unchanged")
        void onlyPostcodeChanged_clearsDriveTimes() {
            // Two postcodes can geocode to the same point, and the postcode is what the user sees and
            // reasons about. Dropping the postcode term would make that move invisible.
            assertMoved(new SaveHomeRequest(NEWCASTLE, DURHAM_LAT, DURHAM_LON, null));
        }

        /** Saves {@code request} over a Durham home and asserts both halves of the discard. */
        private void assertMoved(SaveHomeRequest request) {
            AppUserEntity afterSave = home(request.postcode(), request.latitude(), request.longitude());
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), afterSave);

            service.saveHome(auth, request);

            verify(driveTimeWriter).clearForUser(USER_ID);
            verify(userRepository).clearDriveTimesCalculatedAt(USER_ID);
        }

        @Test
        @DisplayName("a first home is a move from nothing, and clears nothing that exists")
        void firstHome_isTreatedAsAMove() {
            // From null there is nothing to discard, but the branch must not NPE on the comparison —
            // this is the path every new user takes.
            stubRows(buildUser(), home(DURHAM, DURHAM_LAT, DURHAM_LON));

            service.saveHome(auth, new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, null));

            verify(userRepository).updateHome(USER_ID, DURHAM, DURHAM_LAT, DURHAM_LON, null);
            verify(driveTimeWriter).clearForUser(USER_ID);
            verify(userRepository).clearDriveTimesCalculatedAt(USER_ID);
        }

        @ParameterizedTest(name = "{0} miles is stored as {1}")
        @CsvSource({"500, 50", "51, 50", "50, 50", "30, 30", "10, 10", "9, 10", "1, 10"})
        @DisplayName("an out-of-range radius is CLAMPED, not rejected and not honoured")
        void clampsRadiusToBounds(int requested, int stored) {
            // It arrives from a slider whose bounds the client enforces, so out-of-range means a stale
            // client or a direct API call. Honouring 500 miles would make "close to home" meaningless;
            // rejecting would fail a save whose postcode part was perfectly valid.
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), home(DURHAM, DURHAM_LAT, DURHAM_LON));

            service.saveHome(auth, new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, requested));

            verify(userRepository).updateHome(USER_ID, DURHAM, DURHAM_LAT, DURHAM_LON, stored);
        }

        @Test
        @DisplayName("a null radius is passed as null — the update keeps the stored one, it is not a reset")
        void nullRadius_isNotAReset() {
            // The client omits the field when only the postcode changed. Treating that as "set to
            // default" would silently undo a radius the user had deliberately widened. The keeping
            // is the update's coalesce, proven in AppUserRepositoryTest; here, that nothing turns the
            // null into a number on the way.
            AppUserEntity afterSave = home(DURHAM, DURHAM_LAT, DURHAM_LON);
            afterSave.setLocalRadiusMiles(40);
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), afterSave);

            UserSettingsResponse response = service.saveHome(auth,
                    new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, null));

            verify(userRepository).updateHome(USER_ID, DURHAM, DURHAM_LAT, DURHAM_LON, null);
            assertThat(response.localRadiusMiles()).isEqualTo(40);
        }

        @Test
        @DisplayName("the response is the row read back after the write, not the request echoed")
        void responseIsReadBackFromTheRow() {
            AppUserEntity afterSave = home(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON);
            afterSave.setLocalRadiusMiles(35);
            afterSave.setMapColourScale("temp");
            stubRows(home(DURHAM, DURHAM_LAT, DURHAM_LON), afterSave);

            UserSettingsResponse response = service.saveHome(auth,
                    new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));

            assertThat(response.homePostcode()).isEqualTo(NEWCASTLE);
            assertThat(response.homeLatitude()).isEqualTo(NEWCASTLE_LAT);
            assertThat(response.localRadiusMiles()).isEqualTo(35);
            assertThat(response.mapColourScale()).isEqualTo("temp");
            assertThat(response.driveTimesCalculatedAt()).isNull();
            assertThat(response.username()).isEqualTo(USERNAME);
            // Read back AFTER every write, or it reports the row as it stood before them.
            InOrder afterHome = inOrder(userRepository);
            afterHome.verify(userRepository).updateHome(USER_ID, NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null);
            afterHome.verify(userRepository).findByUsername(USERNAME);
            InOrder afterRows = inOrder(driveTimeWriter, userRepository);
            afterRows.verify(driveTimeWriter).clearForUser(USER_ID);
            afterRows.verify(userRepository).findByUsername(USERNAME);
            InOrder afterStamp = inOrder(userRepository);
            afterStamp.verify(userRepository).clearDriveTimesCalculatedAt(USER_ID);
            afterStamp.verify(userRepository).findByUsername(USERNAME);
        }

        @Test
        @DisplayName("an unknown user fails before anything is written")
        void unknownUser_writesNothing() {
            stubAuth();
            when(userRepository.findByUsernameForUpdate(USERNAME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.saveHome(auth,
                    new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, null)))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining(USERNAME);

            verify(userRepository).findByUsernameForUpdate(USERNAME);
            verifyNoMoreInteractions(userRepository);
            verifyNoInteractions(driveTimeWriter);
        }
    }

    // ── refreshDriveTimes ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshDriveTimes")
    class RefreshDriveTimes {

        private List<UserDriveTimeEntity> rows(int count) {
            return java.util.stream.IntStream.rangeClosed(1, count)
                    .mapToObj(i -> new UserDriveTimeEntity(USER_ID, (long) i, 600 * i))
                    .toList();
        }

        private AppUserEntity durhamHome(Instant calculatedAt) {
            AppUserEntity user = home(DURHAM, DURHAM_LAT, DURHAM_LON);
            user.setDriveTimesCalculatedAt(calculatedAt);
            stubAuth();
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
            return user;
        }

        @Test
        @DisplayName("stores what it measured, against the coordinates it measured from, and reports it")
        void measured_storesThroughTheGuardAndReports() {
            durhamHome(null);
            List<UserDriveTimeEntity> measured = rows(15);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, measured, NOW))
                    .thenReturn(true);

            DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

            assertThat(response.locationsUpdated()).isEqualTo(15);
            assertThat(response.calculatedAt()).isEqualTo(NOW);
            verify(driveTimeWriter).storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, measured, NOW);
            // The lost update this method used to cause: it saved the whole row it had loaded
            // before routing, putting the old home back over a postcode saved in between. Its only
            // use of the user repository now is that first read.
            verify(userRepository).findByUsername(USERNAME);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        @DisplayName("an answer with no valid duration is stored too — it clears, and says zero")
        void emptyAnswer_isStored() {
            durhamHome(null);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.of(List.of()));
            when(driveTimeWriter.storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, List.of(), NOW))
                    .thenReturn(true);

            DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

            assertThat(response.locationsUpdated()).isZero();
            assertThat(response.calculatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("no answer at all still stamps the attempt — through the same guard — and keeps "
                + "the stored rows")
        void noAnswer_stampsThroughTheGuard() {
            durhamHome(null);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.empty());
            when(driveTimeWriter.stampIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, NOW)).thenReturn(true);

            DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

            assertThat(response.locationsUpdated()).isZero();
            assertThat(response.calculatedAt()).isEqualTo(NOW);
            verify(driveTimeWriter).stampIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, NOW);
            verifyNoMoreInteractions(driveTimeWriter);
        }

        @Test
        @DisplayName("409 when the home moved while it was measuring — nothing reported as stored")
        void homeMovedWhileMeasuring_conflict() {
            durhamHome(null);
            List<UserDriveTimeEntity> measured = rows(15);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, measured, NOW))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        // The reason reaches the client as the error body's `error` field.
                        assertThat(e.getReason()).contains("home location changed")
                                .contains("Refresh again");
                    });
            verify(userRepository).findByUsername(USERNAME);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        @DisplayName("409 on the no-answer path too — a stamp after a move would re-arm the cooldown")
        void homeMovedWithNoAnswer_conflict() {
            durhamHome(null);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.empty());
            when(driveTimeWriter.stampIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, NOW)).thenReturn(false);

            assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("400 when no home location is set — and nothing is measured")
        void noHome_throws400() {
            stubAuth();
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(buildUser()));

            assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("Set a home location");
                    });
            verifyNoInteractions(driveDurationService, driveTimeWriter);
        }

        // The cooldown reads the injected clock, not the wall clock: 30 minutes from the stamp.

        @ParameterizedTest(name = "stamped {0}s ago is refused")
        @ValueSource(longs = {60, 1799})
        @DisplayName("429 inside the 30-minute cooldown — and nothing is measured")
        void insideCooldown_throws429(long secondsAgo) {
            durhamHome(NOW.minusSeconds(secondsAgo));

            assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(e.getReason()).contains("recently");
                    });
            verifyNoInteractions(driveDurationService, driveTimeWriter);
        }

        @ParameterizedTest(name = "stamped {0}s ago is allowed")
        @ValueSource(longs = {1800, 1801, 31 * 60})
        @DisplayName("allowed from exactly 30 minutes after the last stamp")
        void atOrAfterCooldown_refreshes(long secondsAgo) {
            durhamHome(NOW.minusSeconds(secondsAgo));
            List<UserDriveTimeEntity> measured = rows(10);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, measured, NOW))
                    .thenReturn(true);

            assertThat(service.refreshDriveTimes(auth).locationsUpdated()).isEqualTo(10);
        }

        @Test
        @DisplayName("a first-ever refresh (no stamp) is allowed")
        void firstEver_refreshes() {
            durhamHome(null);
            List<UserDriveTimeEntity> measured = rows(200);
            when(driveDurationService.measureForUser(USER_ID, DURHAM_LAT, DURHAM_LON))
                    .thenReturn(Optional.of(measured));
            when(driveTimeWriter.storeIfHomeUnchanged(USER_ID, DURHAM_LAT, DURHAM_LON, measured, NOW))
                    .thenReturn(true);

            assertThat(service.refreshDriveTimes(auth).locationsUpdated()).isEqualTo(200);
        }
    }

    // ── saveMapColourPreferences (Stage 6) ───────────────────────────────────────

    @Nested
    @DisplayName("saveMapColourPreferences")
    class SaveMapColourPreferences {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"temp", "verdict"})
        @DisplayName("writes the scale alone, then answers with the row read back")
        void writesTheScaleAlone(String scale) {
            stubAuth();
            AppUserEntity afterSave = home(DURHAM, DURHAM_LAT, DURHAM_LON);
            afterSave.setMapColourScale(scale);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(afterSave));

            UserSettingsResponse response = service.saveMapColourPreferences(auth,
                    new MapColourPreferencesRequest(scale));

            assertThat(response.mapColourScale()).isEqualTo(scale);
            // The home rides along in the response because it is read back, not because it was
            // written: a whole-entity save here would write back the home it had loaded.
            assertThat(response.homePostcode()).isEqualTo(DURHAM);
            InOrder order = inOrder(userRepository);
            order.verify(userRepository).updateMapColourScaleByUsername(USERNAME, scale);
            order.verify(userRepository).findByUsername(USERNAME);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an unrecognised scale before touching the row")
        void invalidScale_throws400() {
            // Validated before the user is even looked up, so auth.getName() is never called here.
            assertThatThrownBy(() -> service.saveMapColourPreferences(auth,
                    new MapColourPreferencesRequest("rainbow")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("temp' or 'verdict'");

            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("rejects a null scale with 400, not a 500")
        void nullScale_throws400NotNpe() {
            // VALID_MAP_COLOUR_SCALES is Set.of(...), whose contains() throws NullPointerException on
            // a null argument rather than returning false — an omitted field must still 400.
            assertThatThrownBy(() -> service.saveMapColourPreferences(auth,
                    new MapColourPreferencesRequest(null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("temp' or 'verdict'");

            verifyNoInteractions(userRepository);
        }
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSettings returns profile with no home location")
    void getSettings_noHome_returnsNullFields() {
        stubAuth();
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
        stubAuth();
        AppUserEntity user = home(DURHAM, DURHAM_LAT, DURHAM_LON);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(postcodesIoClient.lookup(DURHAM)).thenReturn(
                new PostcodeLookupResult(DURHAM, DURHAM_LAT, DURHAM_LON, "Durham, County Durham"));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.homePostcode()).isEqualTo(DURHAM);
        assertThat(response.homePlaceName()).isEqualTo("Durham, County Durham");
    }

    @Test
    @DisplayName("getSettings gracefully handles postcode lookup failure")
    void getSettings_lookupFails_returnsNullPlaceName() {
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomePostcode(DURHAM);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(postcodesIoClient.lookup(DURHAM)).thenThrow(
                new PostcodeLookupException("Service unavailable"));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.homePostcode()).isEqualTo(DURHAM);
        assertThat(response.homePlaceName()).isNull();
    }

    @Test
    @DisplayName("getSettings returns null comingUpLastSeenDate when the account never opened "
            + "the tab")
    void getSettings_neverSeenComingUp_returnsNullDate() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.comingUpLastSeenDate()).isNull();
    }

    @Test
    @DisplayName("getSettings derives the London civil date from the stored last-seen instant")
    void getSettings_withLastSeen_derivesLondonCivilDate() {
        stubAuth();
        AppUserEntity user = buildUser();
        // 23:30 UTC in August is 00:30 BST the next day — the exact hour the London-derived date
        // must disagree with a bare UTC read, or the badge would flag a fresh visit as one from
        // "yesterday".
        user.setComingUpLastSeenAt(Instant.parse("2026-08-28T23:30:00Z"));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.comingUpLastSeenDate()).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("markComingUpSeen writes through the column-scoped update, never save()")
    void markComingUpSeen_writesThroughTargetedUpdate_neverWholeEntitySave() {
        stubAuth();
        // Simulates the row a fresh read would return once the targeted update below has landed
        // — this test is about the SERVICE's own orchestration (write the one column, then
        // re-read), not about proving Hibernate's bulk-update SQL against a real database (which
        // AppUserRepositoryTest does).
        AppUserEntity user = buildUser();
        user.setComingUpLastSeenAt(NOW);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.markComingUpSeen(auth);

        verify(userRepository).updateComingUpLastSeenAtByUsername(USERNAME, NOW);
        // Never a whole-entity save — the race a column-scoped update exists to avoid (Codex
        // review finding, PR #695): a concurrent saveHome/saveMapColourPreferences in another tab
        // must not be discardable by this write landing last.
        verify(userRepository, never()).save(any());
        // 2026-08-29T10:15:30Z is well inside the London civil day it falls on.
        assertThat(response.comingUpLastSeenDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        // No request body reaches this method at all (plan D3: "a client with a wrong clock
        // cannot mark the future seen") — the only instant it can ever write is the server's own.
    }

    @Test
    @DisplayName("lookupPostcode delegates to client")
    void lookupPostcode_delegatesToClient() {
        PostcodeLookupResult expected = new PostcodeLookupResult(
                DURHAM, DURHAM_LAT, DURHAM_LON, "Durham, County Durham");
        when(postcodesIoClient.lookup(DURHAM)).thenReturn(expected);

        PostcodeLookupResult result = service.lookupPostcode(DURHAM);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("getSettings includes driveTimesCalculatedAt in response")
    void getSettings_includesDriveTimesCalculatedAt() {
        stubAuth();
        AppUserEntity user = buildUser();
        Instant calculated = Instant.parse("2026-04-04T18:30:00Z");
        user.setDriveTimesCalculatedAt(calculated);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.driveTimesCalculatedAt()).isEqualTo(calculated);
    }

    @Test
    @DisplayName("getSettings returns null driveTimesCalculatedAt when never refreshed")
    void getSettings_noDriveTimes_returnsNullTimestamp() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.driveTimesCalculatedAt()).isNull();
    }

    @Test
    @DisplayName("getUserId returns user primary key")
    void getUserId_returnsId() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        Long userId = service.getUserId(auth);

        assertThat(userId).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("getSettings returns null mapColourScale when never chosen — round-trips as such")
    void getSettings_neverChosenScale_returnsNull() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.getSettings(auth);

        assertThat(response.mapColourScale()).isNull();
    }

    @Test
    @DisplayName("getSettings throws NoSuchElementException for unknown user")
    void getSettings_unknownUser_throws() {
        stubAuth();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettings(auth))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(USERNAME);
    }
}
