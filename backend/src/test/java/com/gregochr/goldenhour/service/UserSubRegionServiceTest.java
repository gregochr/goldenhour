package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.exception.SubRegionsLockedException;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserSubRegionRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationSubRegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserSubRegionService} with mocked repositories: the pure date arithmetic of the change rule, and the
 * two paths a single-threaded database test cannot reach, the lost race on the conditional lock update (0 rows
 * means 409 and nothing is written) and a duplicate-key race on a pick. Every boundary of the rule on real SQL
 * is in {@link UserSubRegionServiceJpaTest}.
 */
@ExtendWith(MockitoExtension.class)
class UserSubRegionServiceTest {

    private static final Long USER_ID = 5L;
    private static final Long BERWICK = 10L;
    private static final Long BAMBURGH = 11L;
    private static final Instant NOW = Instant.parse("2026-12-10T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2026, 12, 10, 12, 0);

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AppUserSubRegionRepository pickRepository;
    @Mock
    private AreaRepository areaRepository;
    @Mock
    private LocationSubRegionRepository membership;
    @Mock
    private SubRegionPolicyService policy;

    private UserSubRegionService service;

    @BeforeEach
    void setUp() {
        SubRegionProperties properties = new SubRegionProperties();
        service = new UserSubRegionService(userRepository, pickRepository, areaRepository, membership, policy,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AppUserEntity lite(LocalDate graceUntil, LocalDateTime changedAt) {
        return AppUserEntity.builder().id(USER_ID).username("anna").role(UserRole.LITE_USER).enabled(true)
                .subRegionsGraceUntil(graceUntil).subRegionsChangedAt(changedAt).build();
    }

    private static AreaEntity subRegion(Long id) {
        AreaEntity area = new AreaEntity();
        area.setId(id);
        area.setName("Sub-region " + id);
        area.setKind(AreaKind.SUB_REGION);
        return area;
    }

    /** Stubs everything a PUT of {@code [BAMBURGH]} over {@code [BERWICK]} reads before the lock decision. */
    private void stubSwap(AppUserEntity user) {
        when(userRepository.findIdByUsername("anna")).thenReturn(Optional.of(USER_ID));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(policy.getLiteLimit()).thenReturn(3);
        when(areaRepository.findAllById(Set.of(BAMBURGH))).thenReturn(List.of(subRegion(BAMBURGH)));
        when(pickRepository.findAreaIdsByUserId(USER_ID)).thenReturn(List.of(BERWICK));
    }

    // --- the pure arithmetic ---

    @Test
    @DisplayName("nextChangeOn is the later of graceUntil + 1 and the stamp's UK day + lock days; null inputs "
            + "constrain nothing")
    void nextChangeOn_arithmetic() {
        LocalDate grace = LocalDate.of(2026, 11, 1);

        assertThat(UserSubRegionService.nextChangeOn(grace, LocalDateTime.of(2026, 11, 1, 10, 0), 30))
                .isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(UserSubRegionService.nextChangeOn(grace, LocalDateTime.of(2026, 9, 1, 10, 0), 30))
                .as("an old stamp: grace end + 1 is later").isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(UserSubRegionService.nextChangeOn(grace, null, 30)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(UserSubRegionService.nextChangeOn(null, LocalDateTime.of(2026, 11, 1, 10, 0), 30))
                .isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(UserSubRegionService.nextChangeOn(null, null, 30)).isNull();
    }

    @Test
    @DisplayName("nextChangeOn reads the stamp on the UK calendar: 23:30 UTC in summer is the next UK day")
    void nextChangeOn_readsTheUkDay() {
        assertThat(UserSubRegionService.nextChangeOn(null, LocalDateTime.of(2026, 6, 1, 23, 30), 30))
                .isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(UserSubRegionService.nextChangeOn(null, LocalDateTime.of(2026, 12, 1, 23, 30), 30))
                .as("in winter UK time is UTC").isEqualTo(LocalDate.of(2026, 12, 31));
    }

    // --- the lost race ---

    @Test
    @DisplayName("when the conditional lock update changes 0 rows the change is refused with the fresh "
            + "nextChangeOn, and no pick is written")
    void lostRace_zeroRows_isLockedAndWritesNothing() {
        AppUserEntity stale = lite(LocalDate.of(2026, 10, 1), LocalDateTime.of(2026, 10, 1, 10, 0));
        stubSwap(stale);
        // Today is 10 Dec; the cutoff is the start of the UK day 11 Nov (10 Dec - 30 + 1).
        LocalDateTime cutoff = LocalDateTime.of(2026, 11, 11, 0, 0);
        when(userRepository.stampSubRegionsChangedIfUnlocked(USER_ID, NOW_LOCAL, cutoff)).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                lite(LocalDate.of(2026, 10, 1), LocalDateTime.of(2026, 12, 10, 11, 59))));

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(BAMBURGH)))
                .isInstanceOfSatisfying(SubRegionsLockedException.class,
                        e -> assertThat(e.getNextChangeOn()).isEqualTo(LocalDate.of(2027, 1, 9)));

        verify(pickRepository, never()).deletePicks(USER_ID, Set.of(BERWICK));
        verify(pickRepository, never()).insertPick(USER_ID, BAMBURGH, NOW_LOCAL);
    }

    @Test
    @DisplayName("when the conditional update succeeds the swap is written: the old pick deleted, the new inserted")
    void conditionalUpdateSucceeds_writesTheSwap() {
        AppUserEntity user = lite(LocalDate.of(2026, 10, 1), LocalDateTime.of(2026, 10, 1, 10, 0));
        stubSwap(user);
        when(userRepository.stampSubRegionsChangedIfUnlocked(USER_ID, NOW_LOCAL, LocalDateTime.of(2026, 11, 11, 0, 0)))
                .thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(areaRepository.findByKind(AreaKind.SUB_REGION)).thenReturn(List.of());
        when(membership.findEnabledMemberRows()).thenReturn(List.of());

        service.replacePicks("anna", List.of(BAMBURGH));

        verify(pickRepository).deletePicks(USER_ID, Set.of(BERWICK));
        verify(pickRepository).insertPick(USER_ID, BAMBURGH, NOW_LOCAL);
    }

    @Test
    @DisplayName("a duplicate-key race on a pick surfaces as a 409 conflict, not a 500")
    void duplicateKeyRace_isAConflict() {
        AppUserEntity user = lite(LocalDate.of(2026, 10, 1), null);
        when(userRepository.findIdByUsername("anna")).thenReturn(Optional.of(USER_ID));
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(policy.getLiteLimit()).thenReturn(3);
        when(areaRepository.findAllById(Set.of(BAMBURGH))).thenReturn(List.of(subRegion(BAMBURGH)));
        when(pickRepository.findAreaIdsByUserId(USER_ID)).thenReturn(List.of());
        when(pickRepository.insertPick(USER_ID, BAMBURGH, NOW_LOCAL))
                .thenThrow(new DataIntegrityViolationException("app_user_sub_region_pkey"));

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(BAMBURGH)))
                .isInstanceOf(AreaConflictException.class)
                .hasMessageContaining("changed at the same moment");
    }

    @Test
    @DisplayName("the user row is locked FOR UPDATE before the picks are read, so concurrent PUTs for one "
            + "reader run one after the other")
    void rowLock_isTakenBeforeThePicksAreRead() {
        AppUserEntity user = lite(LocalDate.of(2026, 10, 1), null);
        stubSwap(user);
        when(userRepository.stampSubRegionsChangedIfUnlocked(USER_ID, NOW_LOCAL, LocalDateTime.of(2026, 11, 11, 0, 0)))
                .thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(areaRepository.findByKind(AreaKind.SUB_REGION)).thenReturn(List.of());
        when(membership.findEnabledMemberRows()).thenReturn(List.of());

        service.replacePicks("anna", List.of(BAMBURGH));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userRepository, pickRepository);
        order.verify(userRepository).findByIdForUpdate(USER_ID);
        order.verify(pickRepository).findAreaIdsByUserId(USER_ID);
    }

    @Test
    @DisplayName("while the Java check says locked the conditional update is never attempted")
    void lockedInJava_neverReachesTheUpdate() {
        stubSwap(lite(LocalDate.of(2026, 10, 1), LocalDateTime.of(2026, 12, 1, 10, 0)));

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(BAMBURGH)))
                .isInstanceOfSatisfying(SubRegionsLockedException.class,
                        e -> assertThat(e.getNextChangeOn()).isEqualTo(LocalDate.of(2026, 12, 31)));

        verify(userRepository, never()).stampSubRegionsChangedIfUnlocked(USER_ID, NOW_LOCAL,
                LocalDateTime.of(2026, 11, 11, 0, 0));
    }

    // --- enforcement: which locations a reader's picks cover (LitePickShaper's input) ---

    private static SubRegionLocationRow member(Long areaId, Long locationId, String name) {
        return new SubRegionLocationRow(areaId, locationId, name, 55.0, -1.5);
    }

    @Test
    @DisplayName("pickedLocationRows returns the enabled members of the picked sub-regions only")
    void pickedLocationRows_returnsMembersOfThePickedSubRegionsOnly() {
        when(userRepository.findIdByUsername("anna")).thenReturn(Optional.of(USER_ID));
        when(policy.getLiteLimit()).thenReturn(3);
        when(pickRepository.findAreaIdsByUserId(USER_ID)).thenReturn(List.of(BERWICK));
        SubRegionLocationRow inside = member(BERWICK, 1L, "Holy Island");
        when(membership.findEnabledMemberRows()).thenReturn(List.of(inside, member(BAMBURGH, 2L, "Craster")));

        assertThat(service.pickedLocationRows("anna")).containsExactly(inside);
    }

    @Test
    @DisplayName("pickedLocationRows serves the first n picks by pick time when the limit was lowered")
    void pickedLocationRows_overLimitUsesTheFirstNPicks() {
        when(userRepository.findIdByUsername("anna")).thenReturn(Optional.of(USER_ID));
        when(policy.getLiteLimit()).thenReturn(1);
        when(pickRepository.findAreaIdsByUserId(USER_ID)).thenReturn(List.of(BERWICK, BAMBURGH));
        SubRegionLocationRow first = member(BERWICK, 1L, "Holy Island");
        when(membership.findEnabledMemberRows()).thenReturn(List.of(first, member(BAMBURGH, 2L, "Craster")));

        assertThat(service.pickedLocationRows("anna")).containsExactly(first);
    }

    @Test
    @DisplayName("pickedLocationRows is empty for an unknown user and for a reader with no picks")
    void pickedLocationRows_emptyForNoUserOrNoPicks() {
        when(userRepository.findIdByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findIdByUsername("anna")).thenReturn(Optional.of(USER_ID));
        when(policy.getLiteLimit()).thenReturn(3);
        when(pickRepository.findAreaIdsByUserId(USER_ID)).thenReturn(List.of());

        assertThat(service.pickedLocationRows("ghost")).isEmpty();
        assertThat(service.pickedLocationRows("anna")).isEmpty();
        verify(membership, never()).findEnabledMemberRows();
    }
}
