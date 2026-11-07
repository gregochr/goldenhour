package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserSubRegionRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link E2eRoleUsers}: the sub-regions, the membership, the two readers, {@code lite}'s picks and the self-check.
 *
 * <p>Every stub answers only for the exact entity it should be given (matched on name, kind, region, username),
 * and every verify names its arguments or captures them for field-by-field assertions: no {@code any()}. The
 * clock is fixed in 2031, far from the wall clock, so a system-clock read would give a different instant and fail
 * the exact-argument checks.
 */
class E2eRoleUsersTest {

    private static final Instant NOW = Instant.parse("2031-07-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2031, 7, 14, 12, 0);

    private static final long LITE_ID = 5L;
    private static final long PRO_ID = 6L;
    private static final long NORTH_AREA = 11L;
    private static final long LAKES_AREA = 12L;
    private static final long DALES_AREA = 13L;

    private AppUserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AreaRepository areaRepository;
    private SubRegionMembershipService membershipService;
    private AppUserSubRegionRepository pickRepository;
    private E2eRoleUsers roleUsers;

    private RegionEntity north;
    private RegionEntity lakes;
    private RegionEntity dales;
    private List<LocationEntity> places;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        areaRepository = mock(AreaRepository.class);
        membershipService = mock(SubRegionMembershipService.class);
        pickRepository = mock(AppUserSubRegionRepository.class);
        roleUsers = new E2eRoleUsers(userRepository, passwordEncoder, areaRepository, membershipService,
                pickRepository, CLOCK);

        north = region(1L, "Northumberland Coast");
        lakes = region(2L, "Lake District");
        dales = region(3L, "Yorkshire Dales");
        places = new ArrayList<>();
        long id = 100;
        for (E2eLocalFixtureSeeder.Spot spot : E2eLocalFixtureSeeder.SPOTS) {
            RegionEntity region = switch (spot.region()) {
                case "Northumberland Coast" -> north;
                case "Lake District" -> lakes;
                default -> dales;
            };
            places.add(LocationEntity.builder().id(id++).name(spot.name()).region(region).build());
        }

        when(areaRepository.save(argThat(isSubRegion("Northumberland Shore", north))))
                .thenAnswer(inv -> saved(inv.getArgument(0), NORTH_AREA));
        when(areaRepository.save(argThat(isSubRegion("Lakeland Fells", lakes))))
                .thenAnswer(inv -> saved(inv.getArgument(0), LAKES_AREA));
        when(areaRepository.save(argThat(isSubRegion("Dales Limestone", dales))))
                .thenAnswer(inv -> saved(inv.getArgument(0), DALES_AREA));
        when(passwordEncoder.encode("E2eLite#Pass1")).thenReturn("encoded-lite");
        when(passwordEncoder.encode("E2ePro#Pass1")).thenReturn("encoded-pro");
        when(userRepository.save(argThat(isUser("lite")))).thenAnswer(inv -> withId(inv.getArgument(0), LITE_ID));
        when(userRepository.save(argThat(isUser("pro")))).thenAnswer(inv -> withId(inv.getArgument(0), PRO_ID));
    }

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    private static ArgumentMatcher<AreaEntity> isSubRegion(String name, RegionEntity region) {
        return area -> area != null && name.equals(area.getName()) && area.getKind() == AreaKind.SUB_REGION
                && area.getRegion() == region;
    }

    private static ArgumentMatcher<AppUserEntity> isUser(String username) {
        return user -> user != null && username.equals(user.getUsername());
    }

    private static AreaEntity saved(AreaEntity area, long id) {
        area.setId(id);
        return area;
    }

    private static AppUserEntity withId(AppUserEntity user, long id) {
        user.setId(id);
        return user;
    }

    private static AppUserEntity user(long id, String username, UserRole role) {
        return AppUserEntity.builder().id(id).username(username).role(role).build();
    }

    // -- seed --------------------------------------------------------------------------------

    @Test
    @DisplayName("one sub-region per region, in the region's own row, each a SUB_REGION")
    void writesOneSubRegionPerRegion() {
        roleUsers.seed(places);

        ArgumentCaptor<AreaEntity> areas = ArgumentCaptor.forClass(AreaEntity.class);
        verify(areaRepository, times(3)).save(areas.capture());
        assertThat(areas.getAllValues()).extracting(AreaEntity::getName)
                .containsExactly("Northumberland Shore", "Lakeland Fells", "Dales Limestone");
        assertThat(areas.getAllValues()).extracting(AreaEntity::getKind)
                .containsOnly(AreaKind.SUB_REGION);
        assertThat(areas.getAllValues().get(0).getRegion()).isSameAs(north);
        assertThat(areas.getAllValues().get(1).getRegion()).isSameAs(lakes);
        assertThat(areas.getAllValues().get(2).getRegion()).isSameAs(dales);
        verifyNoMoreInteractions(areaRepository);
    }

    @Test
    @DisplayName("every place joins its own region's sub-region, and nothing else is assigned")
    void everyPlaceJoinsItsRegionsSubRegion() {
        roleUsers.seed(places);

        for (int i = 0; i < places.size(); i++) {
            long expected = switch (E2eLocalFixtureSeeder.SPOTS.get(i).region()) {
                case "Northumberland Coast" -> NORTH_AREA;
                case "Lake District" -> LAKES_AREA;
                default -> DALES_AREA;
            };
            verify(membershipService).assign(places.get(i), expected);
        }
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    @DisplayName("lite and pro are inserted with their role, an encoded password, enabled, no forced change and the"
            + " postcode prompt already dismissed")
    void insertsBothReaders() {
        roleUsers.seed(places);

        ArgumentCaptor<AppUserEntity> users = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(userRepository, times(2)).save(users.capture());
        AppUserEntity lite = users.getAllValues().get(0);
        AppUserEntity pro = users.getAllValues().get(1);

        assertThat(lite.getUsername()).isEqualTo("lite");
        assertThat(lite.getRole()).isEqualTo(UserRole.LITE_USER);
        assertThat(lite.getPassword()).isEqualTo("encoded-lite");
        assertThat(pro.getUsername()).isEqualTo("pro");
        assertThat(pro.getRole()).isEqualTo(UserRole.PRO_USER);
        assertThat(pro.getPassword()).isEqualTo("encoded-pro");
        for (AppUserEntity each : users.getAllValues()) {
            assertThat(each.isEnabled()).isTrue();
            assertThat(each.isPasswordChangeRequired()).isFalse();
            assertThat(each.getPostcodePromptDismissedAt()).isEqualTo(NOW);
            assertThat(each.getCreatedAt()).isEqualTo(NOW_UTC);
            // The stable "picks set" state needs no grace stamp and no change lock: both stay unset.
            assertThat(each.getSubRegionsGraceUntil()).isNull();
            assertThat(each.getSubRegionsChangedAt()).isNull();
            assertThat(each.getTermsAcceptedAt()).isNull();
            assertThat(each.getEmail()).isNull();
        }
        verify(passwordEncoder).encode("E2eLite#Pass1");
        verify(passwordEncoder).encode("E2ePro#Pass1");
        verifyNoMoreInteractions(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("lite picks the Northumberland and Lake District sub-regions, not the Dales; pro picks nothing")
    void litePicksSomeButNotAll() {
        roleUsers.seed(places);

        verify(pickRepository).insertPick(LITE_ID, NORTH_AREA, NOW_UTC);
        verify(pickRepository).insertPick(LITE_ID, LAKES_AREA, NOW_UTC);
        verify(pickRepository, never()).insertPick(LITE_ID, DALES_AREA, NOW_UTC);
        verifyNoMoreInteractions(pickRepository);
    }

    @Test
    @DisplayName("writes in the load-bearing order: sub-regions, membership, the readers, then lite's picks")
    void writeOrder() {
        roleUsers.seed(places);

        InOrder order = inOrder(areaRepository, membershipService, userRepository, pickRepository);
        order.verify(areaRepository, times(3)).save(argThat(area -> area != null
                && area.getKind() == AreaKind.SUB_REGION));
        order.verify(membershipService, times(places.size())).assign(argThat(this::isOneOfThePlaces),
                argThat(id -> id != null && id >= NORTH_AREA && id <= DALES_AREA));
        order.verify(userRepository).save(argThat(isUser("lite")));
        order.verify(userRepository).save(argThat(isUser("pro")));
        order.verify(pickRepository).insertPick(LITE_ID, NORTH_AREA, NOW_UTC);
        order.verify(pickRepository).insertPick(LITE_ID, LAKES_AREA, NOW_UTC);
    }

    private boolean isOneOfThePlaces(LocationEntity location) {
        return location != null && places.contains(location);
    }

    @Test
    @DisplayName("the summary names the users, the sub-regions and the picks")
    void summary() {
        assertThat(roleUsers.seed(places)).isEqualTo("2 role users, 3 sub-regions, 2 lite picks");
    }

    @ParameterizedTest
    @ValueSource(strings = {E2eRoleUsers.LITE_PASSWORD, E2eRoleUsers.PRO_PASSWORD})
    @DisplayName("each fixed password meets the rules validatePasswordComplexity enforces")
    void passwordsMeetTheComplexityRules(String password) {
        assertThat(password.length()).isGreaterThanOrEqualTo(8);
        assertThat(Pattern.compile("[A-Z]").matcher(password).find()).isTrue();
        assertThat(Pattern.compile("[a-z]").matcher(password).find()).isTrue();
        assertThat(Pattern.compile("[0-9]").matcher(password).find()).isTrue();
        assertThat(Pattern.compile("[^A-Za-z0-9]").matcher(password).find()).isTrue();
    }

    @Test
    @DisplayName("the accounts and passwords are the ones fixture.js signs in with")
    void credentialsAreTheDocumentedOnes() {
        assertThat(E2eRoleUsers.LITE_USERNAME).isEqualTo("lite");
        assertThat(E2eRoleUsers.LITE_PASSWORD).isEqualTo("E2eLite#Pass1");
        assertThat(E2eRoleUsers.PRO_USERNAME).isEqualTo("pro");
        assertThat(E2eRoleUsers.PRO_PASSWORD).isEqualTo("E2ePro#Pass1");
        assertThat(E2eRoleUsers.LITE_PICKED_REGIONS).containsExactly("Northumberland Coast", "Lake District");
        assertThat(E2eRoleUsers.SUB_REGIONS.keySet()).containsExactlyInAnyOrderElementsOf(
                E2eLocalFixtureSeeder.REGIONS);
    }

    // -- verify ------------------------------------------------------------------------------

    private void stubHealthyReaders() {
        when(userRepository.findByUsername("lite")).thenReturn(Optional.of(user(LITE_ID, "lite",
                UserRole.LITE_USER)));
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(PRO_ID, "pro", UserRole.PRO_USER)));
        when(pickRepository.findAreaIdsByUserId(LITE_ID)).thenReturn(List.of(NORTH_AREA, LAKES_AREA));
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(3L);
    }

    @Test
    @DisplayName("self-check: both readers with their roles and two of three picks passes")
    void verifyPasses() {
        stubHealthyReaders();
        assertThatCode(roleUsers::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("self-check: a missing lite user fails, naming it")
    void verifyNeedsLite() {
        stubHealthyReaders();
        when(userRepository.findByUsername("lite")).thenReturn(Optional.empty());
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessage("Self-check: user 'lite' does not exist");
    }

    @Test
    @DisplayName("self-check: a missing pro user fails, naming it")
    void verifyNeedsPro() {
        stubHealthyReaders();
        when(userRepository.findByUsername("pro")).thenReturn(Optional.empty());
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessage("Self-check: user 'pro' does not exist");
    }

    @Test
    @DisplayName("self-check: lite with the wrong role fails")
    void verifyChecksLiteRole() {
        stubHealthyReaders();
        when(userRepository.findByUsername("lite")).thenReturn(Optional.of(user(LITE_ID, "lite", UserRole.PRO_USER)));
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessage("Self-check: user 'lite' has role PRO_USER, expected LITE_USER");
    }

    @Test
    @DisplayName("self-check: pro with the wrong role fails")
    void verifyChecksProRole() {
        stubHealthyReaders();
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(PRO_ID, "pro", UserRole.LITE_USER)));
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessage("Self-check: user 'pro' has role LITE_USER, expected PRO_USER");
    }

    @Test
    @DisplayName("self-check: lite holding every sub-region (so nothing is gated) fails")
    void verifyNeedsSomeButNotAll() {
        stubHealthyReaders();
        when(pickRepository.findAreaIdsByUserId(LITE_ID)).thenReturn(List.of(NORTH_AREA, LAKES_AREA, DALES_AREA));
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reads back 3 sub-region picks of 3 sub-regions, expected 2");
    }

    @Test
    @DisplayName("self-check: lite with too few picks fails")
    void verifyNeedsAllItsPicks() {
        stubHealthyReaders();
        when(pickRepository.findAreaIdsByUserId(LITE_ID)).thenReturn(List.of(NORTH_AREA));
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reads back 1 sub-region picks of 3 sub-regions, expected 2");
    }

    @Test
    @DisplayName("self-check: two picks when only two sub-regions exist (nothing outside the picks) fails")
    void verifyNeedsASubRegionOutsideThePicks() {
        stubHealthyReaders();
        when(areaRepository.countByKind(AreaKind.SUB_REGION)).thenReturn(2L);
        assertThatThrownBy(roleUsers::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reads back 2 sub-region picks of 2 sub-regions, expected 2");
    }
}
