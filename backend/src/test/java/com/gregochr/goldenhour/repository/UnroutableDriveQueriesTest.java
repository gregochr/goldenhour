package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RegionDriveTimeEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-SQL tests (H2 slice) for the four reads behind the admin unroutable-places view
 * (reach-scoped-card-plan R1b): the measured homes, the measured bases and the stored (origin, location) pairs.
 */
@DataJpaTest
class UnroutableDriveQueriesTest {

    private static final Instant STAMP = Instant.parse("2026-11-05T02:00:00Z");
    private static final List<UserRole> MEASURED_ROLES = List.of(UserRole.PRO_USER, UserRole.ADMIN);

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private UserDriveTimeRepository userDriveTimeRepository;

    @Autowired
    private RegionDriveTimeRepository regionDriveTimeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long user(String name, UserRole role, boolean enabled, Double lat, Double lon, Instant stamp) {
        AppUserEntity entity = AppUserEntity.builder()
                .username(name)
                .password("{bcrypt}hash")
                .role(role)
                .enabled(enabled)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .email(name + "@example.com")
                .homeLatitude(lat)
                .homeLongitude(lon)
                .driveTimesCalculatedAt(stamp)
                .build();
        Long id = entityManager.persistAndGetId(entity, Long.class);
        entityManager.flush();
        return id;
    }

    private Long region(String name, boolean enabled, Double lat, Double lon) {
        RegionEntity region = RegionEntity.builder().name(name).enabled(enabled)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0)).build();
        region.setBaseLat(lat);
        region.setBaseLon(lon);
        if (lat != null) {
            region.setBaseName("Base");
        }
        return regionRepository.save(region).getId();
    }

    @Test
    @DisplayName("findMeasuredHomeStamps returns only enabled Pro/Admin accounts with a home and a stamp")
    void findMeasuredHomeStamps_filtersToMeasuredProAndAdminHomes() {
        Long pro = user("pro", UserRole.PRO_USER, true, 54.6, -3.1, STAMP);
        Long admin = user("admin", UserRole.ADMIN, true, 54.7, -3.2, STAMP.plusSeconds(60));
        user("lite", UserRole.LITE_USER, true, 54.6, -3.1, STAMP);
        user("disabled", UserRole.PRO_USER, false, 54.6, -3.1, STAMP);
        user("nohome", UserRole.PRO_USER, true, null, null, STAMP);
        user("unmeasured", UserRole.PRO_USER, true, 54.6, -3.1, null);

        List<DriveStamp> stamps = userRepository.findMeasuredHomeStamps(MEASURED_ROLES);

        assertThat(stamps).containsExactlyInAnyOrder(
                new DriveStamp(pro, STAMP), new DriveStamp(admin, STAMP.plusSeconds(60)));
    }

    @Test
    @DisplayName("findMeasuredBaseStamps returns only enabled regions with a base and a stamp")
    void findMeasuredBaseStamps_filtersToMeasuredBases() {
        Long measured = region("Lakes", true, 54.601, -3.135);
        Long off = region("Off", false, 54.5, -3.0);
        Long unmeasured = region("Unmeasured", true, 54.4, -2.9);
        region("Baseless", true, null, null);
        regionRepository.stampDrivesMeasuredAtIfBaseIs(measured, 54.601, -3.135, STAMP);
        regionRepository.stampDrivesMeasuredAtIfBaseIs(off, 54.5, -3.0, STAMP);
        entityManager.clear();

        List<DriveStamp> stamps = regionRepository.findMeasuredBaseStamps();

        assertThat(stamps).containsExactly(new DriveStamp(measured, STAMP));
        assertThat(stamps).extracting(DriveStamp::originId).doesNotContain(unmeasured);
    }

    @Test
    @DisplayName("findMeasuredHomePairs returns the rows of measured Pro/Admin homes only")
    void findMeasuredHomePairs_returnsMeasuredHomesRowsOnly() {
        Long pro = user("pro", UserRole.PRO_USER, true, 54.6, -3.1, STAMP);
        Long admin = user("admin", UserRole.ADMIN, true, 54.7, -3.2, STAMP);
        Long lite = user("lite", UserRole.LITE_USER, true, 54.6, -3.1, STAMP);
        Long unmeasured = user("unmeasured", UserRole.PRO_USER, true, 54.6, -3.1, null);
        Long disabled = user("disabled", UserRole.PRO_USER, false, 54.6, -3.1, STAMP);
        Long nohome = user("nohome", UserRole.PRO_USER, true, null, null, STAMP);
        for (Long id : List.of(pro, lite, unmeasured, disabled, nohome)) {
            userDriveTimeRepository.save(new UserDriveTimeEntity(id, 100L, 1200));
        }
        userDriveTimeRepository.save(new UserDriveTimeEntity(admin, 101L, 900));
        entityManager.flush();

        List<DrivePair> pairs = userDriveTimeRepository.findMeasuredHomePairs(MEASURED_ROLES);

        assertThat(pairs).containsExactlyInAnyOrder(new DrivePair(pro, 100L), new DrivePair(admin, 101L));
    }

    @Test
    @DisplayName("findMeasuredBasePairs returns the rows of enabled, based, stamped regions only")
    void findMeasuredBasePairs_returnsMeasuredBasesRowsOnly() {
        Long measured = region("Lakes", true, 54.601, -3.135);
        Long off = region("Off", false, 54.5, -3.0);
        Long unmeasured = region("Unmeasured", true, 54.4, -2.9);
        regionRepository.stampDrivesMeasuredAtIfBaseIs(measured, 54.601, -3.135, STAMP);
        regionRepository.stampDrivesMeasuredAtIfBaseIs(off, 54.5, -3.0, STAMP);
        regionDriveTimeRepository.save(new RegionDriveTimeEntity(measured, 100L, 600));
        regionDriveTimeRepository.save(new RegionDriveTimeEntity(off, 100L, 600));
        regionDriveTimeRepository.save(new RegionDriveTimeEntity(unmeasured, 100L, 600));
        entityManager.flush();
        entityManager.clear();

        assertThat(regionDriveTimeRepository.findMeasuredBasePairs())
                .containsExactly(new DrivePair(measured, 100L));
    }
}
