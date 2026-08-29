package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserSettingsService}.
 */
@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    private static final String USERNAME = "testuser";

    /** Fixed rather than the wall clock, per this codebase's date-fixture rule. */
    private static final Instant NOW = Instant.parse("2026-08-29T10:15:30Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

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
                .id(42L)
                .username(USERNAME)
                .email("test@example.com")
                .role(UserRole.PRO_USER)
                .build();
    }

    // ── moving home invalidates the drive times measured from the old one ────────

    @Test
    @DisplayName("moving home discards drive times measured from the old one")
    void saveHome_originMoved_clearsDriveTimes() {
        // A drive time is measured FROM an origin. The moment the origin moves, every stored row
        // describes a journey nobody is going to make — and unlike a missing one, a wrong one is
        // invisible: the reach lens gates a spot in or out on a figure tens of minutes off, with
        // nothing on screen saying so. Unknown is safe here; wrong is not.
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomePostcode("DH1 3LE");
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        user.setDriveTimesCalculatedAt(Instant.now().minusSeconds(3600));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("NE1 4ST", 54.9714, -1.6174, null));

        verify(driveTimeWriter).clearForUser(42L);
        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDriveTimesCalculatedAt()).isNull();
    }

    @Test
    @DisplayName("dragging the radius slider does NOT throw away a full set of routed drive times")
    void saveHome_sameHome_keepsDriveTimes() {
        // saveHome is also the radius slider's save path: the settings modal re-sends the user's
        // EXISTING postcode and coordinates whenever the radius changes. Clearing unconditionally
        // would bin a whole roster of routed times every time somebody moved that slider — and
        // each refresh is an external routing call per location.
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomePostcode("DH1 3LE");
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        Instant calculated = Instant.now().minusSeconds(3600);
        user.setDriveTimesCalculatedAt(calculated);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        // new String, not the literal: two compile-time literals are interned, so a reference
        // comparison would pass here and the guard would look correct while being blind to any
        // postcode that arrived off the wire rather than out of the constant pool.
        service.saveHome(auth, new SaveHomeRequest(new String("DH1 3LE"), 54.7761, -1.5733, 45));

        verify(driveTimeWriter, never()).clearForUser(anyLong());
        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDriveTimesCalculatedAt()).isEqualTo(calculated);
        assertThat(captor.getValue().getLocalRadiusMiles()).isEqualTo(45);
    }

    // Each of the three fields gets its own case, varying that field ALONE. `originMoved` is a
    // three-way OR, so a test that moves two fields at once leaves either term deletable: the
    // other one still fires and the suite stays green.

    @Test
    @DisplayName("a re-geocode that shifts the latitude alone counts as moving")
    void saveHome_onlyLatitudeChanged_clearsDriveTimes() {
        // Distance and routing are computed from the COORDINATES, not the postcode text, so a
        // postcode-only comparison would keep drive times measured from a different point.
        assertMoved(home("DH1 3LE", 54.7761, -1.5733),
                new SaveHomeRequest("DH1 3LE", 54.9714, -1.5733, null));
    }

    @Test
    @DisplayName("a re-geocode that shifts the longitude alone counts as moving")
    void saveHome_onlyLongitudeChanged_clearsDriveTimes() {
        assertMoved(home("DH1 3LE", 54.7761, -1.5733),
                new SaveHomeRequest("DH1 3LE", 54.7761, -1.6174, null));
    }

    @Test
    @DisplayName("a new postcode counts as moving even if the coordinates are unchanged")
    void saveHome_onlyPostcodeChanged_clearsDriveTimes() {
        // Two postcodes can geocode to the same point, and the postcode is what the user sees and
        // reasons about. Dropping the postcode term would make that move invisible.
        assertMoved(home("DH1 3LE", 54.7761, -1.5733),
                new SaveHomeRequest("NE1 4ST", 54.7761, -1.5733, null));
    }

    /** A stored home to move away from. */
    private AppUserEntity home(String postcode, double lat, double lon) {
        AppUserEntity user = buildUser();
        user.setHomePostcode(postcode);
        user.setHomeLatitude(lat);
        user.setHomeLongitude(lon);
        return user;
    }

    /** Saves {@code request} over {@code stored} and asserts the drive times were discarded. */
    private void assertMoved(AppUserEntity stored, SaveHomeRequest request) {
        stubAuth();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(stored));

        service.saveHome(auth, request);

        verify(driveTimeWriter).clearForUser(42L);
    }

    @Test
    @DisplayName("a first home is a move from nothing, and clears nothing that exists")
    void saveHome_firstHome_isTreatedAsAMove() {
        // From null there is nothing to discard, but the branch must not NPE on the comparison —
        // this is the path every new user takes.
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));

        verify(driveTimeWriter).clearForUser(42L);
    }

    @Test
    @DisplayName("clearing the stamp releases the refresh cooldown, so a mover is not locked out")
    void saveHome_originMoved_releasesTheRefreshCooldown() {
        // The 30-minute cooldown reads driveTimesCalculatedAt. Leaving it set while discarding the
        // times would put a user who has just moved house in the worst state available: no drive
        // times at all, AND a 429 when they try to recalculate. Clearing the stamp is what makes
        // the discard recoverable.
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomePostcode("DH1 3LE");
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        user.setDriveTimesCalculatedAt(Instant.now());
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(driveDurationService.refreshForUser(42L, 54.9714, -1.6174)).thenReturn(17);

        service.saveHome(auth, new SaveHomeRequest("NE1 4ST", 54.9714, -1.6174, null));

        // Immediately afterwards — well inside the cooldown that was running a moment ago.
        assertThat(service.refreshDriveTimes(auth).locationsUpdated()).isEqualTo(17);
    }

    @Test
    @DisplayName("saveHome persists the local radius alongside the home it is measured from")
    void saveHome_persistsLocalRadius() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, 30));

        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getLocalRadiusMiles()).isEqualTo(30);
    }

    @Test
    @DisplayName("a null radius leaves the stored one alone — it is not a reset")
    void saveHome_nullRadiusLeavesStoredValue() {
        // The client omits the field when only the postcode changed. Treating that as "set to
        // default" would silently undo a radius the user had deliberately widened.
        stubAuth();
        AppUserEntity user = buildUser();
        user.setLocalRadiusMiles(40);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));

        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getLocalRadiusMiles()).isEqualTo(40);
    }

    @Test
    @DisplayName("an out-of-range radius is CLAMPED, not rejected and not honoured")
    void saveHome_clampsRadiusToBounds() {
        // It arrives from a slider whose bounds the client enforces, so out-of-range means a stale
        // client or a direct API call. Honouring 500 miles would make "close to home" meaningless;
        // rejecting would fail a save whose postcode part was perfectly valid.
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        // Asserted on the entity after each call rather than via a captor: both saves mutate the
        // SAME user instance, so a captor would hold two references to one object and report the
        // final state twice.
        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, 500));
        assertThat(user.getLocalRadiusMiles())
                .isEqualTo(UserSettingsService.MAX_LOCAL_RADIUS_MILES);

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, 1));
        assertThat(user.getLocalRadiusMiles())
                .isEqualTo(UserSettingsService.MIN_LOCAL_RADIUS_MILES);
    }

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
        stubAuth();
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

        assertThat(response.comingUpLastSeenDate())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("markComingUpSeen stores the clock's instant and returns the derived date")
    void markComingUpSeen_storesNowAndReturnsDerivedDate() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.markComingUpSeen(auth);

        assertThat(user.getComingUpLastSeenAt()).isEqualTo(NOW);
        // 2026-08-29T10:15:30Z is well inside the London civil day it falls on.
        assertThat(response.comingUpLastSeenDate())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 29));
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("markComingUpSeen overwrites a prior last-seen instant — every call is 'now'")
    void markComingUpSeen_overwritesPriorValue() {
        stubAuth();
        AppUserEntity user = buildUser();
        user.setComingUpLastSeenAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.markComingUpSeen(auth);

        assertThat(user.getComingUpLastSeenAt()).isEqualTo(NOW);
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
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));

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
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Set a home location");
    }

    @Test
    @DisplayName("refreshDriveTimes throws 429 when recently refreshed")
    void refreshDriveTimes_recentRefresh_throws429() {
        stubAuth();
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
    @DisplayName("refreshDriveTimes passes correct user ID and coordinates to duration service")
    void refreshDriveTimes_success_passesCorrectArgs() {
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(driveDurationService.refreshForUser(42L, 54.7761, -1.5733)).thenReturn(15);

        DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(15);
        assertThat(response.calculatedAt()).isNotNull();
        verify(driveDurationService).refreshForUser(eq(42L), eq(54.7761), eq(-1.5733));
        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDriveTimesCalculatedAt()).isNotNull();
    }

    @Test
    @DisplayName("refreshDriveTimes allows refresh after cooldown period")
    void refreshDriveTimes_afterCooldown_succeeds() {
        stubAuth();
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
    @DisplayName("refreshDriveTimes succeeds on first-ever refresh (null driveTimesCalculatedAt)")
    void refreshDriveTimes_firstEver_succeeds() {
        stubAuth();
        AppUserEntity user = buildUser();
        user.setHomeLatitude(54.7761);
        user.setHomeLongitude(-1.5733);
        // driveTimesCalculatedAt is null — never refreshed before
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(driveDurationService.refreshForUser(42L, 54.7761, -1.5733)).thenReturn(200);

        DriveTimeRefreshResponse response = service.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(200);
    }

    @Test
    @DisplayName("refreshDriveTimes does not call duration service when home coordinates missing")
    void refreshDriveTimes_noHome_doesNotCallDurationService() {
        stubAuth();
        AppUserEntity user = buildUser();
        // homeLatitude and homeLongitude both null
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refreshDriveTimes(auth))
                .isInstanceOf(ResponseStatusException.class);
        verify(driveDurationService, never()).refreshForUser(eq(42L), eq(0.0), eq(0.0));
    }

    @Test
    @DisplayName("saveHome returns response with persisted postcode")
    void saveHome_returnsResponseWithPostcode() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.saveHome(auth,
                new SaveHomeRequest("NE1 4ST", 54.9738, -1.6131, null));

        assertThat(response.homePostcode()).isEqualTo("NE1 4ST");
        assertThat(response.username()).isEqualTo(USERNAME);
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

        assertThat(userId).isEqualTo(42L);
    }

    // ── map colour preferences (Stage 6) ─────────────────────────────────────────

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
    @DisplayName("saveMapColourPreferences persists an explicit 'temp' choice")
    void saveMapColourPreferences_persistsTemp() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        UserSettingsResponse response = service.saveMapColourPreferences(auth,
                new MapColourPreferencesRequest("temp"));

        assertThat(response.mapColourScale()).isEqualTo("temp");
        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getMapColourScale()).isEqualTo("temp");
    }

    @Test
    @DisplayName("saveMapColourPreferences persists an explicit 'verdict' choice")
    void saveMapColourPreferences_persistsVerdict() {
        stubAuth();
        AppUserEntity user = buildUser();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        service.saveMapColourPreferences(auth, new MapColourPreferencesRequest("verdict"));

        assertThat(user.getMapColourScale()).isEqualTo("verdict");
    }

    @Test
    @DisplayName("saveMapColourPreferences rejects an unrecognised scale")
    void saveMapColourPreferences_invalidScale_throws400() {
        // Validated before the user is even looked up, so auth.getName() is never called here.

        assertThatThrownBy(() -> service.saveMapColourPreferences(auth,
                new MapColourPreferencesRequest("rainbow")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("temp' or 'verdict'");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("saveMapColourPreferences rejects a null scale with 400, not a 500")
    void saveMapColourPreferences_nullScale_throws400NotNpe() {
        // VALID_MAP_COLOUR_SCALES is Set.of(...), whose contains() throws NullPointerException on
        // a null argument rather than returning false — an omitted field must still 400.
        assertThatThrownBy(() -> service.saveMapColourPreferences(auth,
                new MapColourPreferencesRequest(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("temp' or 'verdict'");

        verify(userRepository, never()).save(any());
    }


    @Test
    @DisplayName("getSettings throws NoSuchElementException for unknown user")
    void getSettings_unknownUser_throws() {
        stubAuth();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettings(auth))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining(USERNAME);
    }
}
