package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.SubRegionsLockedException;
import com.gregochr.goldenhour.model.SubRegionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The picks service and its change rule (sub-regions-plan §4.3) on a real JPA stack (H2) and a settable clock,
 * at every boundary: first choice, the last grace day and the day after, the last locked day and the day the
 * lock ends, a BST midnight, an identical set, an add-only change after grace, a reader over a lowered limit,
 * and a demoted Pro's first choice. The conditional-update race is proven with mocks in
 * {@code UserSubRegionServiceTest}; the SQL of the conditional update itself in
 * {@code AppUserRepositorySubRegionTest}. Dates are asserted as the UK civil dates the rule compares.
 */
@DataJpaTest
@Import({UserSubRegionService.class, SubRegionPolicyService.class, SubRegionProperties.class,
        UserSubRegionServiceJpaTest.ClockConfig.class})
class UserSubRegionServiceJpaTest {

    /** The first day after British Summer Time ended in 2026 (25 October): GMT from here to March. */
    private static final Instant OCT_26 = Instant.parse("2026-10-26T12:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(OCT_26);
        }
    }

    /** A clock the test moves. */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    private UserSubRegionService service;

    @Autowired
    private SubRegionPolicyService policy;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private RegionEntity northumberland;
    private RegionEntity lakes;
    private Long berwick;
    private Long bamburgh;
    private Long alnwick;
    private Long keswick;
    private Long langdales;
    private Long namedArea;
    private Long userId;

    @BeforeEach
    void plant() {
        clock.set("2026-10-26T12:00:00Z");
        northumberland = region("Northumberland & Tyneside");
        lakes = region("The Lake District");
        berwick = area("Berwick & Holy Island", northumberland);
        bamburgh = area("Bamburgh to Howick", northumberland);
        alnwick = area("Alnwick & the Coquet Coast", northumberland);
        keswick = area("Keswick & Borrowdale", lakes);
        langdales = area("The Langdales", lakes);
        AreaEntity named = new AreaEntity();
        named.setName("Hadrian's Wall");
        namedArea = entityManager.persistAndGetId(named, Long.class);
        userId = user("anna", UserRole.LITE_USER);
    }

    private RegionEntity region(String name) {
        return entityManager.persistAndFlush(RegionEntity.builder().name(name)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build());
    }

    private Long area(String name, RegionEntity region) {
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setKind(AreaKind.SUB_REGION);
        area.setRegion(region);
        return entityManager.persistAndGetId(area, Long.class);
    }

    private Long user(String name, UserRole role) {
        AppUserEntity user = AppUserEntity.builder().username(name).password("hash").role(role).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).email(name + "@example.com").build();
        Long id = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        return id;
    }

    /** Puts the reader in the middle of the rule: picks, grace end and last stamp, written the scoped way. */
    private void standing(Long reader, String graceUntil, String changedAt, Long... picks) {
        jdbc.update("UPDATE app_user SET sub_regions_grace_until = ?, sub_regions_changed_at = ? WHERE id = ?",
                graceUntil == null ? null : LocalDate.parse(graceUntil),
                changedAt == null ? null : LocalDateTime.ofInstant(Instant.parse(changedAt), ZoneOffset.UTC),
                reader);
        for (Long area : picks) {
            jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, ?)",
                    reader, area, LocalDateTime.of(2026, 10, 1, 9, 0).plusMinutes(area));
        }
        entityManager.clear();
    }

    private List<Long> picksOf(Long reader) {
        entityManager.flush();
        return jdbc.queryForList("SELECT area_id FROM app_user_sub_region WHERE user_id = ? ORDER BY area_id",
                Long.class, reader);
    }

    private LocalDateTime changedAtOf(Long reader) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT sub_regions_changed_at FROM app_user WHERE id = ?",
                LocalDateTime.class, reader);
    }

    private LocalDate graceUntilOf(Long reader) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT sub_regions_grace_until FROM app_user WHERE id = ?",
                LocalDate.class, reader);
    }

    // --- first choice ---

    @Test
    @DisplayName("a first choice is allowed, starts grace (today + 7 - 1) and stamps the change")
    void firstChoice_startsGraceAndStamps() {
        SubRegionState state = service.replacePicks("anna", List.of(berwick, keswick));

        assertThat(picksOf(userId)).containsExactlyInAnyOrder(berwick, keswick);
        assertThat(graceUntilOf(userId)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
        assertThat(state.status()).isEqualTo(SubRegionState.FREE_UNTIL);
        assertThat(state.freeUntil()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(state.picks()).extracting(SubRegionState.Pick::name)
                .containsExactlyInAnyOrder("Berwick & Holy Island", "Keswick & Borrowdale");
    }

    @Test
    @DisplayName("a reader with no picks and no grace reads FIRST_CHOICE")
    void noPicks_isFirstChoice() {
        SubRegionState state = service.getState("anna");

        assertThat(state.status()).isEqualTo(SubRegionState.FIRST_CHOICE);
        assertThat(state.picks()).isEmpty();
        assertThat(state.freeUntil()).isNull();
        assertThat(state.nextChangeOn()).isNull();
        assertThat(state.overLimit()).isNull();
        assertThat(state.limit()).isEqualTo(SubRegionPolicyService.DEFAULT_LITE_LIMIT);
        assertThat(state.enforced()).isFalse();
    }

    @Test
    @DisplayName("FIRST_CHOICE states the grace end a save today would set: today + 7 - 1, and no lock date")
    void firstChoice_statesTheFreeUntilASaveWouldSet() {
        SubRegionState state = service.getState("anna");

        assertThat(state.freeUntilIfChosenToday()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(state.nextChangeOnIfSavedToday()).isNull();
        assertThat(state.freeUntil()).as("the stored field stays absent until a save").isNull();
    }

    @Test
    @DisplayName("FIRST_CHOICE with a grace end still running states that end, not a new one")
    void firstChoice_withRunningGrace_statesTheExistingEnd() {
        standing(userId, "2026-10-30", "2026-10-20T10:00:00Z");

        SubRegionState state = service.getState("anna");

        assertThat(state.freeUntilIfChosenToday()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(state.nextChangeOnIfSavedToday()).isNull();
    }

    @Test
    @DisplayName("FIRST_CHOICE after an ended grace states the lock a save would start, not a free week")
    void firstChoice_afterEndedGrace_statesTheLock() {
        standing(userId, "2026-09-01", "2026-09-02T10:00:00Z");

        SubRegionState state = service.getState("anna");

        assertThat(state.freeUntilIfChosenToday()).isNull();
        assertThat(state.nextChangeOnIfSavedToday()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("OPEN states the lock a save today would start: a save on 26 Oct with lock 30 is 25 Nov")
    void open_statesTheNextChangeASaveWouldSet() {
        standing(userId, "2026-09-01", "2026-09-02T10:00:00Z", berwick);

        SubRegionState state = service.getState("anna");

        assertThat(state.status()).isEqualTo(SubRegionState.OPEN);
        assertThat(state.nextChangeOnIfSavedToday()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(state.freeUntilIfChosenToday()).isNull();
    }

    @Test
    @DisplayName("the stated lock date reads the UK day: at 23:30 UTC in summer a save falls on the next UK day")
    void open_statedLockDateReadsTheBstDay() {
        standing(userId, "2026-05-01", "2026-05-02T10:00:00Z", berwick);
        clock.set("2026-06-01T23:30:00Z");

        SubRegionState state = service.getState("anna");

        assertThat(state.status()).isEqualTo(SubRegionState.OPEN);
        assertThat(state.nextChangeOnIfSavedToday()).isEqualTo(LocalDate.of(2026, 7, 2));
    }

    @Test
    @DisplayName("FREE_UNTIL, LOCKED and NOT_APPLICABLE state neither prospective date")
    void otherStatuses_stateNoProspectiveDate() {
        standing(userId, "2026-11-01", "2026-10-26T12:00:00Z", berwick);
        SubRegionState free = service.getState("anna");
        standing(userId, "2026-09-01", "2026-10-20T10:00:00Z");
        SubRegionState locked = service.getState("anna");
        user("pat", UserRole.PRO_USER);
        SubRegionState notApplicable = service.getState("pat");

        assertThat(free.status()).isEqualTo(SubRegionState.FREE_UNTIL);
        assertThat(locked.status()).isEqualTo(SubRegionState.LOCKED);
        assertThat(notApplicable.status()).isEqualTo(SubRegionState.NOT_APPLICABLE);
        for (SubRegionState state : List.of(free, locked, notApplicable)) {
            assertThat(state.freeUntilIfChosenToday()).isNull();
            assertThat(state.nextChangeOnIfSavedToday()).isNull();
        }
    }

    @Test
    @DisplayName("a demoted Pro's first choice starts a grace period, like any first choice")
    void demotedPro_firstChoiceStartsGrace() {
        Long pro = user("promoted-then-demoted", UserRole.PRO_USER);
        jdbc.update("UPDATE app_user SET role = 'LITE_USER' WHERE id = ?", pro);
        entityManager.clear();

        service.replacePicks("promoted-then-demoted", List.of(alnwick));

        assertThat(graceUntilOf(pro)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(picksOf(pro)).containsExactly(alnwick);
    }

    @Test
    @DisplayName("a first choice after grace already ran (an admin cleared every pick) keeps the old grace end")
    void firstChoice_keepsAnExistingGraceEnd() {
        standing(userId, "2026-09-01", "2026-09-02T10:00:00Z");

        service.replacePicks("anna", List.of(berwick));

        assertThat(graceUntilOf(userId)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
    }

    // --- grace boundary ---

    @Test
    @DisplayName("on the last grace day a swap is free and stamps; the day after, the same swap is locked")
    void graceEnd_lastDayAgainstTheDayAfter() {
        standing(userId, "2026-11-01", "2026-10-26T12:00:00Z", berwick);

        clock.set("2026-11-01T23:59:00Z");
        SubRegionState swapped = service.replacePicks("anna", List.of(bamburgh));
        assertThat(picksOf(userId)).containsExactly(bamburgh);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 11, 1, 23, 59));
        assertThat(swapped.status()).isEqualTo(SubRegionState.FREE_UNTIL);

        clock.set("2026-11-02T00:00:00Z");
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(alnwick)))
                .isInstanceOfSatisfying(SubRegionsLockedException.class,
                        e -> assertThat(e.getNextChangeOn()).isEqualTo(LocalDate.of(2026, 12, 1)));
        assertThat(picksOf(userId)).containsExactly(bamburgh);
    }

    @Test
    @DisplayName("once grace is over the lock ends changedOn + lock-days after the last stamp")
    void lockedStatus_endsThirtyDaysAfterTheStamp() {
        // Last change was 1 Nov, the last grace day; the lock ends 1 Dec.
        standing(userId, "2026-11-01", "2026-11-01T10:00:00Z", berwick);
        clock.set("2026-11-20T12:00:00Z");

        SubRegionState state = service.getState("anna");

        assertThat(state.status()).isEqualTo(SubRegionState.LOCKED);
        assertThat(state.nextChangeOn()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    // --- lock boundary ---

    @Test
    @DisplayName("the day before the lock ends a swap is refused with nextChangeOn; on the end day it goes ahead")
    void lockEnd_dayBeforeAgainstTheEndDay() {
        standing(userId, "2026-11-01", "2026-11-05T15:00:00Z", berwick);

        clock.set("2026-12-04T23:59:00Z");
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(bamburgh)))
                .isInstanceOfSatisfying(SubRegionsLockedException.class,
                        e -> assertThat(e.getNextChangeOn()).isEqualTo(LocalDate.of(2026, 12, 5)));
        assertThat(picksOf(userId)).containsExactly(berwick);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 11, 5, 15, 0));

        clock.set("2026-12-05T00:00:00Z");
        SubRegionState state = service.replacePicks("anna", List.of(bamburgh));
        assertThat(picksOf(userId)).containsExactly(bamburgh);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 12, 5, 0, 0));
        assertThat(state.status()).isEqualTo(SubRegionState.LOCKED);
        assertThat(state.nextChangeOn()).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    @DisplayName("a BST midnight: a change at 00:30 BST (23:30 UTC the day before) counts as the UK day it fell on")
    void bstMidnight_isReadOnTheUkCalendar() {
        // Changed 2026-06-01T23:30Z = 00:30 BST on 2 June: the lock ends 2 July (UK), not 1 July (UTC).
        standing(userId, "2026-05-01", "2026-06-01T23:30:00Z", berwick);

        clock.set("2026-07-01T22:59:00Z"); // 23:59 BST on 1 July
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(bamburgh)))
                .isInstanceOfSatisfying(SubRegionsLockedException.class,
                        e -> assertThat(e.getNextChangeOn()).isEqualTo(LocalDate.of(2026, 7, 2)));

        clock.set("2026-07-01T23:00:00Z"); // 00:00 BST on 2 July, still 1 July in UTC
        service.replacePicks("anna", List.of(bamburgh));
        assertThat(picksOf(userId)).containsExactly(bamburgh);
    }

    @Test
    @DisplayName("the lock is read from the stamp alone once grace is long over: an unstamped reader may swap")
    void unstamped_afterGrace_maySwap() {
        standing(userId, "2026-09-01", null, berwick);

        service.replacePicks("anna", List.of(bamburgh));

        assertThat(picksOf(userId)).containsExactly(bamburgh);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
    }

    // --- no-op, add-only ---

    @Test
    @DisplayName("an identical set is a 200 no-op: nothing is written or stamped, even while locked")
    void identicalSet_isANoOp() {
        standing(userId, "2026-10-01", "2026-10-20T10:00:00Z", berwick, bamburgh);

        SubRegionState state = service.replacePicks("anna", List.of(bamburgh, berwick));

        assertThat(picksOf(userId)).containsExactlyInAnyOrder(berwick, bamburgh);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(state.status()).isEqualTo(SubRegionState.LOCKED);
    }

    @Test
    @DisplayName("after grace a change that only adds picks under the limit is allowed, even while locked, "
            + "and stamps nothing")
    void addOnly_afterGrace_stampsNothing() {
        standing(userId, "2026-10-01", "2026-10-20T10:00:00Z", berwick);

        SubRegionState state = service.replacePicks("anna", List.of(berwick, keswick));

        assertThat(picksOf(userId)).containsExactlyInAnyOrder(berwick, keswick);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(state.status()).isEqualTo(SubRegionState.LOCKED);
    }

    @Test
    @DisplayName("an add-only change cannot take a reader over the limit (400), whatever the lock says")
    void addOnly_overTheLimit_isRefused() {
        standing(userId, "2026-10-01", null, berwick, bamburgh, alnwick);

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(berwick, bamburgh, alnwick, keswick)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose at most 3 sub-regions");
        assertThat(picksOf(userId)).hasSize(3);
    }

    // --- a lowered limit ---

    @Test
    @DisplayName("over a lowered limit the reader is flagged overLimit, and removals alone need no lock "
            + "and stamp nothing")
    void overLimit_removalsOnly_areFreeAndUnstamped() {
        standing(userId, "2026-10-01", "2026-10-20T10:00:00Z", berwick, bamburgh, alnwick);
        policy.update(2, "admin");
        assertThat(service.getState("anna").overLimit()).isTrue();
        assertThat(service.getState("anna").status()).isEqualTo(SubRegionState.LOCKED);

        SubRegionState state = service.replacePicks("anna", List.of(berwick, bamburgh));

        assertThat(picksOf(userId)).containsExactlyInAnyOrder(berwick, bamburgh);
        assertThat(changedAtOf(userId)).isEqualTo(LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(state.overLimit()).isNull();
    }

    @Test
    @DisplayName("over a lowered limit a swap (a removal with an addition) is still locked")
    void overLimit_aSwapIsStillLocked() {
        standing(userId, "2026-10-01", "2026-10-20T10:00:00Z", berwick, bamburgh, alnwick);
        policy.update(2, "admin");

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(berwick, keswick)))
                .isInstanceOf(SubRegionsLockedException.class);
        assertThat(picksOf(userId)).containsExactlyInAnyOrder(berwick, bamburgh, alnwick);
    }

    @Test
    @DisplayName("a request over the lowered limit is refused even if it only removes")
    void overLimit_stillOverAfterTheChange_isRefused() {
        standing(userId, "2026-10-01", null, berwick, bamburgh, alnwick);
        policy.update(1, "admin");

        assertThatThrownBy(() -> service.replacePicks("anna", List.of(berwick, bamburgh)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose at most 1 sub-region");
    }

    // --- validation and roles ---

    @Test
    @DisplayName("an empty list, a missing list, a duplicate, a null id, a named area and an unknown id are all 400s")
    void validation() {
        assertThatThrownBy(() -> service.replacePicks("anna", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose at least one sub-region");
        assertThatThrownBy(() -> service.replacePicks("anna", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose at least one sub-region");
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(berwick, berwick)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Each sub-region may be chosen only once");
        assertThatThrownBy(() -> service.replacePicks("anna", java.util.Arrays.asList(berwick, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A sub-region id must not be null");
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(namedArea)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Not a sub-region: " + namedArea);
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(9_999_999L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Not a sub-region: 9999999");
        assertThatThrownBy(() -> service.replacePicks("anna", List.of(berwick, bamburgh, alnwick, keswick)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose at most 3 sub-regions");
        assertThat(picksOf(userId)).isEmpty();
        assertThat(graceUntilOf(userId)).isNull();
    }

    @Test
    @DisplayName("a Pro or Admin reader reads NOT_APPLICABLE with no options, and may not pick")
    void proAndAdmin_doNotPick() {
        Long pro = user("pro", UserRole.PRO_USER);
        Long admin = user("boss", UserRole.ADMIN);
        standing(pro, "2026-10-30", "2026-10-26T10:00:00Z", berwick);

        SubRegionState proState = service.getState("pro");
        assertThat(proState.status()).isEqualTo(SubRegionState.NOT_APPLICABLE);
        assertThat(proState.options()).isEmpty();
        assertThat(proState.suggested()).isEmpty();
        assertThat(proState.overLimit()).isNull();
        assertThat(service.getState("boss").status()).isEqualTo(SubRegionState.NOT_APPLICABLE);
        assertThatThrownBy(() -> service.replacePicks("pro", List.of(keswick)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.replacePicks("boss", List.of(keswick)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(picksOf(pro)).as("a Pro keeps the picks they held as Lite").containsExactly(berwick);
        assertThat(picksOf(admin)).isEmpty();
    }

    @Test
    @DisplayName("an unknown username is a 404")
    void unknownUser() {
        assertThatThrownBy(() -> service.getState("nobody")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.replacePicks("nobody", List.of(berwick)))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- options and suggestions ---

    private Long location(String name, double lat, double lon, RegionEntity region, Long area, boolean enabled) {
        LocationEntity location = LocationEntity.builder().name(name).lat(lat).lon(lon).region(region)
                .enabled(enabled).createdAt(LocalDateTime.of(2026, 2, 22, 12, 0)).build();
        Long id = entityManager.persistAndGetId(location, Long.class);
        entityManager.flush();
        jdbc.update("INSERT INTO location_sub_region (location_id, area_id) VALUES (?, ?)", id, area);
        return id;
    }

    @Test
    @DisplayName("options are grouped by region, name-ordered, count only enabled locations and carry the two "
            + "alphabetically first names")
    void options_groupedWithCountsAndExamples() {
        location("Spittal Beach", 55.76, -1.98, northumberland, berwick, true);
        location("Cocklawburn Beach", 55.69, -1.98, northumberland, berwick, true);
        location("Ross Sands", 55.65, -1.80, northumberland, berwick, true);
        location("Shut Beach", 55.60, -1.80, northumberland, berwick, false);
        location("Bamburgh Castle", 55.60, -1.71, northumberland, bamburgh, true);
        location("Aira Force", 54.57, -2.93, lakes, keswick, true);

        SubRegionState state = service.getState("anna");

        assertThat(state.options()).extracting(SubRegionState.RegionOptions::regionName)
                .containsExactly("Northumberland & Tyneside", "The Lake District");
        SubRegionState.RegionOptions north = state.options().get(0);
        assertThat(north.subRegions()).extracting(SubRegionState.Option::name)
                .containsExactly("Alnwick & the Coquet Coast", "Bamburgh to Howick", "Berwick & Holy Island");
        SubRegionState.Option berwickOption = north.subRegions().get(2);
        assertThat(berwickOption.areaId()).isEqualTo(berwick);
        assertThat(berwickOption.locationCount()).isEqualTo(3);
        assertThat(berwickOption.exampleLocations()).containsExactly("Cocklawburn Beach", "Ross Sands");
        assertThat(north.subRegions().get(0).locationCount()).isZero();
        assertThat(north.subRegions().get(0).exampleLocations()).isEmpty();
    }

    @Test
    @DisplayName("with a home and no picks, the limit nearest sub-regions (by member centroid) are suggested; "
            + "a sub-region with no enabled locations is never suggested")
    void suggested_isTheNearestByCentroid() {
        location("Bamburgh Castle", 55.60, -1.71, northumberland, bamburgh, true);
        location("Berwick Walls", 55.77, -2.00, northumberland, berwick, true);
        location("Alnmouth", 55.39, -1.60, northumberland, alnwick, true);
        location("Aira Force", 54.57, -2.93, lakes, keswick, true);
        location("Blea Tarn", 54.43, -3.05, lakes, langdales, true);
        jdbc.update("UPDATE app_user SET home_latitude = 55.0, home_longitude = -1.6 WHERE id = ?", userId);
        entityManager.clear();

        policy.update(2, "admin");
        SubRegionState two = service.getState("anna");
        policy.update(4, "admin");
        SubRegionState four = service.getState("anna");

        assertThat(two.suggested()).containsExactly(alnwick, bamburgh);
        assertThat(four.suggested()).containsExactly(alnwick, bamburgh, berwick, keswick);
        assertThat(four.suggested()).doesNotContain(namedArea);
    }

    @Test
    @DisplayName("no suggestions without a home, and none once the reader holds a pick")
    void suggested_emptyWithoutAHomeOrWithPicks() {
        location("Bamburgh Castle", 55.60, -1.71, northumberland, bamburgh, true);
        assertThat(service.getState("anna").suggested()).isEmpty();

        jdbc.update("UPDATE app_user SET home_latitude = 55.0, home_longitude = -1.6 WHERE id = ?", userId);
        entityManager.clear();
        assertThat(service.getState("anna").suggested()).containsExactly(bamburgh);

        standing(userId, "2026-11-01", "2026-10-26T10:00:00Z", berwick);
        assertThat(service.getState("anna").suggested()).isEmpty();
    }

    // --- admin ---

    @Test
    @DisplayName("an admin sets a reader's picks past the lock, clears the lock stamp and leaves the grace end")
    void admin_setsPicksAndClearsTheLock() {
        standing(userId, "2026-10-01", "2026-10-25T10:00:00Z", berwick);

        SubRegionState state = service.adminReplacePicks(userId, List.of(keswick, langdales));

        assertThat(picksOf(userId)).containsExactlyInAnyOrder(keswick, langdales);
        assertThat(changedAtOf(userId)).isNull();
        assertThat(graceUntilOf(userId)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(state.status()).isEqualTo(SubRegionState.OPEN);
        assertThat(state.options()).as("the admin view carries no options").isEmpty();
    }

    @Test
    @DisplayName("an admin's first picks for a reader with no grace start it (today + 7 - 1); a reader whose "
            + "grace has started keeps its end date")
    void admin_firstPicksStartGrace() {
        service.adminReplacePicks(userId, List.of(berwick));

        assertThat(graceUntilOf(userId)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(changedAtOf(userId)).as("the admin set clears the lock stamp").isNull();

        Long other = user("started", UserRole.LITE_USER);
        standing(other, "2026-09-01", null);
        service.adminReplacePicks(other, List.of(keswick));
        assertThat(graceUntilOf(other)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("an admin may clear a reader's picks with an empty list, but not exceed the limit or name a "
            + "non-sub-region")
    void admin_validation() {
        standing(userId, "2026-10-01", null, berwick);

        assertThatThrownBy(() -> service.adminReplacePicks(userId, List.of(berwick, bamburgh, alnwick, keswick)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.adminReplacePicks(userId, List.of(namedArea)))
                .isInstanceOf(IllegalArgumentException.class);
        service.adminReplacePicks(userId, List.of());

        assertThat(picksOf(userId)).isEmpty();
    }

    @Test
    @DisplayName("an admin lifts a reader's lock: the next swap goes ahead; an unknown user is a 404")
    void admin_clearLock() {
        standing(userId, "2026-10-01", "2026-10-25T10:00:00Z", berwick);
        assertThat(service.getState("anna").status()).isEqualTo(SubRegionState.LOCKED);

        service.clearLock(userId);

        assertThat(changedAtOf(userId)).isNull();
        service.replacePicks("anna", List.of(bamburgh));
        assertThat(picksOf(userId)).containsExactly(bamburgh);
        assertThatThrownBy(() -> service.clearLock(9_999_999L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.getStateForAdmin(9_999_999L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.adminReplacePicks(9_999_999L, List.of(berwick)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("the admin list names each reader's picks, by name, and omits readers with none")
    void pickNamesByUser() {
        Long other = user("bert", UserRole.LITE_USER);
        standing(userId, "2026-11-01", "2026-10-26T10:00:00Z", keswick, berwick);
        standing(other, null, null);

        Map<Long, List<String>> names = service.pickNamesByUser();

        assertThat(names).containsOnlyKeys(userId);
        assertThat(names.get(userId)).containsExactly("Berwick & Holy Island", "Keswick & Borrowdale");
    }
}
