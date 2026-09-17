package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import com.gregochr.goldenhour.service.DriveDurationService;
import com.gregochr.goldenhour.service.UserDriveTimeWriter;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Proves, on Postgres 17, the concurrency the settings lost-update fix rests on — the part H2
 * cannot stand in for.
 *
 * <p>Two properties. First, a drive-time store WAITS on a home save that holds the user row, and
 * when that save commits, the store's compare-and-set is judged against the home the save
 * committed — so it stores nothing. That is Postgres re-checking an {@code UPDATE}'s condition
 * after a lock wait (READ COMMITTED), and it is what makes one guarded statement enough. Second,
 * the failure as a reader met it — a postcode saved while a refresh routes — replayed through the
 * real service against the production engine, so every new statement (the locked read, the
 * {@code coalesce} radius, the {@code DOUBLE PRECISION} equality) runs on the SQL dialect that
 * matters.
 *
 * <p>The H2 versions of the sequences, and the column scoping, are proven locally by
 * {@code UserSettingsRaceSequenceTest} and {@code AppUserRepositoryTest}.
 */
class UserSettingsRowLockIntegrationTest extends IntegrationTestBase {

    private static final String DURHAM = "DH1 3LE";
    private static final double DURHAM_LAT = 54.7761;
    private static final double DURHAM_LON = -1.5733;
    private static final String NEWCASTLE = "NE1 4ST";
    private static final double NEWCASTLE_LAT = 54.9714;
    private static final double NEWCASTLE_LON = -1.6174;

    private static final Instant MEASURED_AT = Instant.parse("2026-09-16T10:15:30Z");

    @MockitoBean
    private DriveDurationService driveDurationService;

    @Autowired
    private UserSettingsService settingsService;

    @Autowired
    private UserDriveTimeWriter driveTimeWriter;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserDriveTimeRepository userDriveTimeRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RegionEntity region;
    private LocationEntity near;
    private LocationEntity far;
    private final List<Long> readers = new ArrayList<>();

    @BeforeEach
    void seedLocations() {
        // user_drive_time.location_id references locations(id) on Postgres, so the rows need
        // real destinations.
        region = regionRepository.save(RegionEntity.builder()
                .name("Row lock test region")
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build());
        near = seedLocation("Row lock test — Durham Cathedral", 54.7733, -1.5762);
        far = seedLocation("Row lock test — Bamburgh", 55.6090, -1.7099);
    }

    @AfterEach
    void deleteSeededRows() {
        // Only this class's own rows: the V10 admin and V31's regions are Flyway's.
        for (Long id : readers) {
            jdbcTemplate.update("DELETE FROM user_drive_time WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id);
        }
        locationRepository.delete(near);
        locationRepository.delete(far);
        regionRepository.delete(region);
    }

    private LocationEntity seedLocation(String name, double lat, double lon) {
        return locationRepository.save(LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build());
    }

    /** A reader in Durham with drive times measured from there, stamped two hours before now. */
    private Long durhamReader(String username) {
        AppUserEntity user = userRepository.save(AppUserEntity.builder()
                .username(username)
                .password("{bcrypt}hash")
                .role(UserRole.PRO_USER)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .homePostcode(DURHAM)
                .homeLatitude(DURHAM_LAT)
                .homeLongitude(DURHAM_LON)
                .localRadiusMiles(40)
                .driveTimesCalculatedAt(Instant.now().minus(Duration.ofHours(2)))
                .build());
        readers.add(user.getId());
        userDriveTimeRepository.saveAll(measured(user.getId(), 900, 1800));
        return user.getId();
    }

    private List<UserDriveTimeEntity> measured(Long userId, int nearSeconds, int farSeconds) {
        return List.of(new UserDriveTimeEntity(userId, near.getId(), nearSeconds),
                new UserDriveTimeEntity(userId, far.getId(), farSeconds));
    }

    private String homePostcode(Long userId) {
        return jdbcTemplate.queryForObject("SELECT home_postcode FROM app_user WHERE id = ?",
                String.class, userId);
    }

    private OffsetDateTime stamp(Long userId) {
        return jdbcTemplate.queryForObject("SELECT drive_times_calculated_at FROM app_user WHERE id = ?",
                OffsetDateTime.class, userId);
    }

    private Integer storedDriveTimes(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM user_drive_time WHERE user_id = ?",
                Integer.class, userId);
    }

    /**
     * Whether the drive-time compare-and-set is, right now, waiting on a lock — matched by its own
     * statement, so no other backend's wait can stand in for it.
     */
    private boolean theCompareAndSetIsWaitingOnALock() {
        Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                + "AND query ILIKE 'update app_user%drive_times_calculated_at%home_latitude%'",
                Integer.class);
        return waiting != null && waiting > 0;
    }

    @Test
    @DisplayName("a drive-time store waits on a home save holding the row, then matches the home "
            + "that save committed — and stores nothing")
    void storeWaitsOnTheSaveAndRechecksTheCommittedHome() throws Exception {
        Long id = durhamReader("row-lock-reader");
        ExecutorService refreshThread = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate homeSave = new TransactionTemplate(transactionManager);
            Future<Boolean> store = homeSave.execute(status -> {
                // saveHome's first step: the locked read. Nothing is written yet.
                userRepository.findByUsernameForUpdate("row-lock-reader").orElseThrow();

                // A refresh that routed from Durham now tries to store what it measured.
                Future<Boolean> pending = refreshThread.submit(() -> driveTimeWriter.storeIfHomeUnchanged(
                        id, DURHAM_LAT, DURHAM_LON, measured(id, 950, 1850), MEASURED_AT));

                // It must be WAITING on the row, not finished: a locked read that took no lock
                // lets the store through before the save has written anything, and it would
                // store Durham's drive times with the home still Durham.
                await().atMost(Duration.ofSeconds(20))
                        .failFast("the store finished instead of waiting on the row lock", pending::isDone)
                        .until(this::theCompareAndSetIsWaitingOnALock);

                // Only now does the save move the home and discard the old drive times, then commit.
                userRepository.updateHome(id, NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null);
                driveTimeWriter.clearForUser(id);
                userRepository.clearDriveTimesCalculatedAt(id);
                return pending;
            });

            // Released by the commit, the compare-and-set is judged against Newcastle.
            assertThat(store.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            refreshThread.shutdownNow();
        }

        assertThat(homePostcode(id)).isEqualTo(NEWCASTLE);
        assertThat(storedDriveTimes(id)).as("nothing measured from Durham may be stored").isZero();
        assertThat(stamp(id)).isNull();
    }

    @Test
    @DisplayName("a postcode saved while a refresh routes is kept on Postgres, the refresh answers 409, "
            + "and pressing again measures from the new home")
    void postcodeSavedWhileRefreshRoutes() {
        Long id = durhamReader("routing-reader");
        Authentication auth = new TestingAuthenticationToken("routing-reader", null);
        when(driveDurationService.measureForUser(id, DURHAM_LAT, DURHAM_LON)).thenAnswer(routing -> {
            settingsService.saveHome(auth, new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));
            return Optional.of(measured(id, 950, 1850));
        });

        assertThatThrownBy(() -> settingsService.refreshDriveTimes(auth))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(homePostcode(id)).isEqualTo(NEWCASTLE);
        assertThat(storedDriveTimes(id)).isZero();
        assertThat(stamp(id)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT local_radius_miles FROM app_user WHERE id = ?",
                Integer.class, id)).as("a save carrying no radius keeps the stored one").isEqualTo(40);

        when(driveDurationService.measureForUser(id, NEWCASTLE_LAT, NEWCASTLE_LON))
                .thenReturn(Optional.of(measured(id, 2400, 600)));

        DriveTimeRefreshResponse retry = settingsService.refreshDriveTimes(auth);

        assertThat(retry.locationsUpdated()).isEqualTo(2);
        assertThat(storedDriveTimes(id)).isEqualTo(2);
        assertThat(stamp(id)).isNotNull();
        assertThat(homePostcode(id)).isEqualTo(NEWCASTLE);
    }
}
