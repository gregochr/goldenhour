package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ReachEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-user half of the spot strip's two-contract join.
 *
 * <p>What is pinned here is the set of rules that make the reach lens honest rather than merely
 * present: it covers the <em>whole</em> roster, an absent figure means "unknown" and never "out of
 * reach", and a caller with no home postcode — the normal first-run state — still gets a usable
 * payload rather than an empty one.
 */
@ExtendWith(MockitoExtension.class)
class ReachServiceTest {

    private static final long USER_ID = 7L;
    private static final long ID_BAMBURGH = 1L;
    private static final long ID_SIMONSIDE = 2L;
    private static final long ID_BUTTERMERE = 3L;

    /** Newcastle-ish, so the fixture distances are recognisable rather than arbitrary. */
    private static final double HOME_LAT = 54.978;
    private static final double HOME_LON = -1.618;

    @Mock
    private UserSettingsService userSettingsService;

    @Mock
    private LocationService locationService;

    @Mock
    private DriveTimeResolver driveTimeResolver;

    @Mock
    private Authentication auth;

    private ReachService service;

    @BeforeEach
    void setUp() {
        service = new ReachService(userSettingsService, locationService, driveTimeResolver);
    }

    @Nested
    @DisplayName("the whole roster")
    class Roster {

        @Test
        @DisplayName("every enabled location appears, including ones with no drive time")
        void coversTheWholeRosterNotJustReachableSpots() {
            // Reach is a LENS over everything; localRadiusMiles gates only "Close to home". If the
            // payload pre-filtered, the widest tier ("Any") could never show what it promises, and
            // the client would be unable to tell "too far" from "never measured".
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(bamburgh(), simonside(), buttermere());
            when(driveTimeResolver.getAllMinutes(USER_ID))
                    .thenReturn(Map.of(ID_BAMBURGH, 62, ID_SIMONSIDE, 19));

            List<ReachEntry> reach = service.getReach(auth);

            assertThat(reach).extracting(ReachEntry::locationId)
                    .containsExactly(ID_BAMBURGH, ID_SIMONSIDE, ID_BUTTERMERE);
            assertThat(reach).extracting(ReachEntry::driveMinutes)
                    .containsExactly(62, 19, null);
        }

        @Test
        @DisplayName("ordered by id, so a rename cannot reshuffle an unchanged payload")
        void orderedByIdRatherThanByName() {
            // The roster query orders by NAME. Passing that ordering through would make the
            // response reshuffle whenever a location is renamed, with no change to its content.
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(buttermere(), bamburgh(), simonside());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            assertThat(service.getReach(auth)).extracting(ReachEntry::locationId)
                    .containsExactly(ID_BAMBURGH, ID_SIMONSIDE, ID_BUTTERMERE);
        }

        @Test
        @DisplayName("an empty roster yields an empty list, not a failure")
        void anEmptyRosterIsEmpty() {
            stubHome(HOME_LAT, HOME_LON);
            stubRoster();
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            assertThat(service.getReach(auth)).isEmpty();
        }
    }

    @Nested
    @DisplayName("absence means unknown, never out of reach")
    class Absence {

        @Test
        @DisplayName("no home postcode still returns the full roster, with no figures")
        void noHomeStillReturnsTheRoster() {
            // The normal FIRST-RUN state, not a failure. An empty list, a 204 or a homeSet flag
            // would each make the lens look broken to the user who most needs it to be honest —
            // and a second source of truth for "is a home set" could disagree with
            // GET /api/user/settings, which already answers that.
            stubHome(null, null);
            stubRoster(bamburgh(), simonside());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            List<ReachEntry> reach = service.getReach(auth);

            assertThat(reach).extracting(ReachEntry::locationId)
                    .containsExactly(ID_BAMBURGH, ID_SIMONSIDE);
            assertThat(reach).allSatisfy(e -> {
                assertThat(e.distanceMiles()).isNull();
                assertThat(e.driveMinutes()).isNull();
            });
        }

        @Test
        @DisplayName("a half-saved home is treated as no home, not as the equator")
        void aPartialHomeYieldsNoDistance() {
            // Latitude without longitude would otherwise compute a distance to lon=0 — a real
            // number, wrong by hundreds of miles, and indistinguishable from a real one on screen.
            stubHome(HOME_LAT, null);
            stubRoster(bamburgh());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of(ID_BAMBURGH, 62));

            List<ReachEntry> reach = service.getReach(auth);

            assertThat(reach.get(0).distanceMiles()).isNull();
            // The drive time is independent of the home coordinates and must survive.
            assertThat(reach.get(0).driveMinutes()).isEqualTo(62);
        }

        @Test
        @DisplayName("drive times and distances are independently absent")
        void theTwoFiguresAreIndependent() {
            // A user can have a home and no calculated drive times — that is the state between
            // saving a postcode and the first refresh — so one must not suppress the other.
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(simonside());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            ReachEntry entry = service.getReach(auth).get(0);

            assertThat(entry.driveMinutes()).isNull();
            assertThat(entry.distanceMiles()).isNotNull().isPositive();
        }
    }

    @Nested
    @DisplayName("the figures themselves")
    class Figures {

        @Test
        @DisplayName("distance is straight-line miles from home, rounded to the card's precision")
        void distanceIsWholeMilesFromHome() {
            // Exact values, not a band: the haversine is deterministic, so a range would pass just
            // as happily on a formula that had drifted. Rounded whole miles is what the spot card
            // renders — a more precise figure would invite a second, more authoritative-looking
            // number onto a screen that already states this one.
            //
            // Simonside is the load-bearing one, and an earlier draft wrongly left it out as too
            // close to the rounding boundary to assert. It is the opposite: at 24.497 it is the
            // only fixture where rounding DOWN and rounding UP differ, so it is the single case
            // that pins Math.round rather than letting Math.ceil pass unnoticed.
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(bamburgh(), simonside(), buttermere());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            List<ReachEntry> reach = service.getReach(auth);

            assertThat(reach.get(0).distanceMiles()).isEqualTo(44);   // 43.773
            assertThat(reach.get(1).distanceMiles()).isEqualTo(24);   // 24.497 — ceil would give 25
            assertThat(reach.get(2).distanceMiles()).isEqualTo(73);   // 72.661
        }

        @Test
        @DisplayName("drive minutes come from the caller's own stored times")
        void driveMinutesArePerUser() {
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(bamburgh());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of(ID_BAMBURGH, 62));

            assertThat(service.getReach(auth).get(0).driveMinutes()).isEqualTo(62);
        }

        @Test
        @DisplayName("a location with no id is dropped rather than published without a join key")
        void aLocationWithNoIdIsDropped() {
            // locationId is the join key. An entry without one cannot be matched to a window's
            // slot, so it would be payload the client can only ignore.
            stubHome(HOME_LAT, HOME_LON);
            stubRoster(bamburgh(), LocationEntity.builder().name("Unsaved").lat(55.0).lon(-1.5).build());
            when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

            assertThat(service.getReach(auth)).extracting(ReachEntry::locationId)
                    .containsExactly(ID_BAMBURGH);
        }
    }

    @Test
    @DisplayName("the roster is read once, however many locations it holds")
    void theRosterIsReadOnce() {
        // An earlier draft resolved each location's distance by re-querying the roster, which is a
        // DB read per location on a per-user endpoint the Plan tab calls on every load.
        stubHome(HOME_LAT, HOME_LON);
        stubRoster(bamburgh(), simonside(), buttermere());
        when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

        service.getReach(auth);

        org.mockito.Mockito.verify(locationService, org.mockito.Mockito.times(1)).findAllEnabled();
        org.mockito.Mockito.verify(driveTimeResolver, org.mockito.Mockito.times(1))
                .getAllMinutes(USER_ID);
    }

    @Test
    @DisplayName("reach is never radius-gated — localRadiusMiles is Close to home's rule, not this one")
    void theRadiusIsNotConsulted() {
        // A 30-mile radius must not hide Buttermere at ~85 miles: the lens's widest tier promises
        // everything, and the drill-down's own filters are what narrow it.
        when(userSettingsService.getHomeLocation(auth)).thenReturn(
                new UserSettingsService.HomeLocation(USER_ID, HOME_LAT, HOME_LON, 30, "NE66 1NG"));
        stubRoster(bamburgh(), simonside(), buttermere());
        when(driveTimeResolver.getAllMinutes(USER_ID)).thenReturn(Map.of());

        assertThat(service.getReach(auth)).hasSize(3);
    }

    @Test
    @DisplayName("no authenticated home means no roster read at all")
    void aFailedHomeLookupDoesNotQueryTheRoster() {
        when(userSettingsService.getHomeLocation(auth))
                .thenThrow(new IllegalStateException("no such user"));

        try {
            service.getReach(auth);
        } catch (IllegalStateException expected) {
            // The caller's identity is resolved first; nothing else should have run.
        }

        verifyNoInteractions(locationService, driveTimeResolver);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void stubHome(Double lat, Double lon) {
        when(userSettingsService.getHomeLocation(auth))
                .thenReturn(new UserSettingsService.HomeLocation(
                        USER_ID, lat, lon, null, lat == null ? null : "NE66 1NG"));
    }

    private void stubRoster(LocationEntity... locations) {
        when(locationService.findAllEnabled()).thenReturn(List.of(locations));
    }

    private static LocationEntity bamburgh() {
        return LocationEntity.builder().id(ID_BAMBURGH).name("Bamburgh")
                .lat(55.6094).lon(-1.7092).build();
    }

    private static LocationEntity simonside() {
        return LocationEntity.builder().id(ID_SIMONSIDE).name("Simonside")
                .lat(55.2833).lon(-1.9333).build();
    }

    private static LocationEntity buttermere() {
        return LocationEntity.builder().id(ID_BUTTERMERE).name("Buttermere")
                .lat(54.5400).lon(-3.2750).build();
    }
}
