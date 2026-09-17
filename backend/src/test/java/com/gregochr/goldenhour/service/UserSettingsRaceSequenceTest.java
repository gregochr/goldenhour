package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.MapColourPreferencesRequest;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Replays the settings lost updates in the order they happen, through the real services, the real
 * repositories and real transactions (H2), with only the routing call and the geocoder stubbed.
 *
 * <p>Each interleaving is driven from inside the stubbed measurement: a drive-time refresh reads
 * the home, then spends seconds routing outside any transaction, and whatever the reader does in
 * those seconds commits before the refresh stores. Answering the stub by performing that save is
 * that gap, made deterministic. No transaction wraps a test method — each service call commits on
 * its own, as it does in production.
 *
 * <p>What this does not cover is truly concurrent execution, where a row lock decides who waits:
 * that is Postgres's, and {@code UserSettingsRowLockIntegrationTest} proves it in CI.
 */
@DataJpaTest
@Import({UserSettingsService.class, UserDriveTimeWriter.class, DriveTimeRefreshJob.class,
        UserSettingsRaceSequenceTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserSettingsRaceSequenceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    private static final String DURHAM = "DH1 3LE";
    private static final double DURHAM_LAT = 54.7761;
    private static final double DURHAM_LON = -1.5733;
    private static final String NEWCASTLE = "NE1 4ST";
    private static final double NEWCASTLE_LAT = 54.9714;
    private static final double NEWCASTLE_LON = -1.6174;

    /** H2's LOCK_TIMEOUT_1: "Timeout trying to lock table". */
    private static final int H2_LOCK_TIMEOUT = 50200;

    /** The one clock every service in this context reads. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private DriveDurationService driveDurationService;

    @MockitoBean
    private PostcodesIoClient postcodesIoClient;

    @MockitoBean
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private UserSettingsService settingsService;

    @Autowired
    private DriveTimeRefreshJob job;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserDriveTimeRepository userDriveTimeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void deleteCommittedRows() {
        // Nothing rolls back without a test transaction, and these rows would leak into the next
        // test's roster.
        jdbcTemplate.update("DELETE FROM user_drive_time");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    /** A reader in Durham with two drive times measured from there, stamped {@code calculatedAt}. */
    private Long durhamReader(String username, Instant calculatedAt) {
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
                .driveTimesCalculatedAt(calculatedAt)
                .mapColourScale("verdict")
                .build());
        userDriveTimeRepository.saveAll(List.of(
                new UserDriveTimeEntity(user.getId(), 1L, 900),
                new UserDriveTimeEntity(user.getId(), 2L, 1800)));
        return user.getId();
    }

    private static Authentication signedInAs(String username) {
        return new TestingAuthenticationToken(username, null);
    }

    private static List<UserDriveTimeEntity> measuredFrom(Long userId, int firstSeconds, int secondSeconds) {
        return List.of(new UserDriveTimeEntity(userId, 1L, firstSeconds),
                new UserDriveTimeEntity(userId, 2L, secondSeconds));
    }

    private Map<String, Object> row(Long userId) {
        return jdbcTemplate.queryForMap("SELECT home_postcode, home_latitude, home_longitude, "
                + "local_radius_miles, map_colour_scale FROM app_user WHERE id = ?", userId);
    }

    private Instant stamp(Long userId) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT drive_times_calculated_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
        return value == null ? null : value.toInstant();
    }

    /** Stored drive times as "locationId=seconds", in location order. */
    private List<String> driveTimes(Long userId) {
        return jdbcTemplate.queryForList("SELECT location_id || '=' || drive_duration_seconds "
                + "FROM user_drive_time WHERE user_id = ? ORDER BY location_id", String.class, userId);
    }

    @Test
    @DisplayName("a postcode saved while a refresh routes is KEPT, the refresh stores nothing and "
            + "answers 409, and pressing again measures from the new home")
    void postcodeSavedWhileRefreshRoutes() {
        Long id = durhamReader("mover", NOW.minusSeconds(7200));
        Authentication auth = signedInAs("mover");
        when(driveDurationService.measureForUser(id, DURHAM_LAT, DURHAM_LON)).thenAnswer(routing -> {
            // The reader saves Newcastle while the refresh is still routing from Durham.
            settingsService.saveHome(auth, new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));
            return Optional.of(measuredFrom(id, 950, 1850));
        });

        assertThatThrownBy(() -> settingsService.refreshDriveTimes(auth))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Before the fix: the Durham drive times were stored over the save's discard, and the
        // refresh then saved the user row it had loaded — putting Durham back as the home.
        Map<String, Object> afterConflict = row(id);
        assertThat(afterConflict.get("home_postcode")).isEqualTo(NEWCASTLE);
        assertThat(afterConflict.get("home_latitude")).isEqualTo(NEWCASTLE_LAT);
        assertThat(afterConflict.get("home_longitude")).isEqualTo(NEWCASTLE_LON);
        assertThat(driveTimes(id)).as("nothing measured from Durham may be stored").isEmpty();
        assertThat(stamp(id)).as("the move released the cooldown, and nothing re-armed it").isNull();

        // Pressing again is allowed, and measures from where the reader now lives.
        when(driveDurationService.measureForUser(id, NEWCASTLE_LAT, NEWCASTLE_LON))
                .thenReturn(Optional.of(measuredFrom(id, 2400, 600)));

        DriveTimeRefreshResponse retry = settingsService.refreshDriveTimes(auth);

        assertThat(retry.locationsUpdated()).isEqualTo(2);
        assertThat(retry.calculatedAt()).isEqualTo(NOW);
        assertThat(driveTimes(id)).containsExactly("1=2400", "2=600");
        assertThat(stamp(id)).isEqualTo(NOW);
        assertThat(row(id).get("home_postcode")).isEqualTo(NEWCASTLE);
    }

    @Test
    @DisplayName("a colour saved while a refresh routes is kept, and the refresh still stores — a "
            + "write that does not move the home is no conflict")
    void colourSavedWhileRefreshRoutes() {
        Long id = durhamReader("painter", NOW.minusSeconds(7200));
        Authentication auth = signedInAs("painter");
        when(driveDurationService.measureForUser(id, DURHAM_LAT, DURHAM_LON)).thenAnswer(routing -> {
            settingsService.saveMapColourPreferences(auth, new MapColourPreferencesRequest("temp"));
            return Optional.of(measuredFrom(id, 950, 1850));
        });

        DriveTimeRefreshResponse response = settingsService.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(2);
        // Before the fix, the refresh's whole-row save wrote "verdict" back.
        assertThat(row(id).get("map_colour_scale")).isEqualTo("temp");
        assertThat(driveTimes(id)).containsExactly("1=950", "2=1850");
        assertThat(stamp(id)).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a home saved while the nightly job routes is kept, that user is stored nothing, "
            + "and the run still refreshes everyone else")
    void homeSavedWhileTheNightlyJobRoutes() {
        Long mover = durhamReader("night-mover", NOW.minusSeconds(86_400));
        Long stayer = durhamReader("night-stayer", NOW.minusSeconds(86_400));
        Authentication moverAuth = signedInAs("night-mover");
        when(driveDurationService.measureForUser(mover, DURHAM_LAT, DURHAM_LON)).thenAnswer(routing -> {
            settingsService.saveHome(moverAuth,
                    new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));
            return Optional.of(measuredFrom(mover, 950, 1850));
        });
        when(driveDurationService.measureForUser(stayer, DURHAM_LAT, DURHAM_LON))
                .thenReturn(Optional.of(measuredFrom(stayer, 960, 1860)));

        job.runScheduled();

        assertThat(row(mover).get("home_postcode")).isEqualTo(NEWCASTLE);
        assertThat(row(mover).get("home_latitude")).isEqualTo(NEWCASTLE_LAT);
        assertThat(driveTimes(mover)).isEmpty();
        assertThat(stamp(mover)).isNull();

        assertThat(driveTimes(stayer)).containsExactly("1=960", "2=1860");
        assertThat(stamp(stayer)).isEqualTo(NOW);
        assertThat(row(stayer).get("home_postcode")).isEqualTo(DURHAM);
    }

    @Test
    @DisplayName("a move releases the cooldown: a refresh straight after it is allowed and "
            + "measures from the new home")
    void moveReleasesTheCooldown() {
        // Drive times recalculated a minute ago — well inside the 30-minute cooldown.
        Long id = durhamReader("fresh-mover", NOW.minusSeconds(60));
        Authentication auth = signedInAs("fresh-mover");
        settingsService.saveHome(auth, new SaveHomeRequest(NEWCASTLE, NEWCASTLE_LAT, NEWCASTLE_LON, null));
        when(driveDurationService.measureForUser(id, NEWCASTLE_LAT, NEWCASTLE_LON))
                .thenReturn(Optional.of(measuredFrom(id, 2400, 600)));

        DriveTimeRefreshResponse response = settingsService.refreshDriveTimes(auth);

        assertThat(response.locationsUpdated()).isEqualTo(2);
        assertThat(driveTimes(id)).containsExactly("1=2400", "2=600");
    }

    @Test
    @DisplayName("saveHome's locked read holds the row: another connection cannot write it until "
            + "the reading transaction ends")
    void lockedReadHoldsTheRow() throws SQLException {
        // The lock is what makes saveHome's "did this move the home?" decision safe, and a missing
        // @Lock changes nothing any sequence above can see — each of their writes happens after
        // the last one committed. So hold the lock and try to write past it, from a second
        // connection with a short lock timeout.
        Long id = durhamReader("locker", NOW.minusSeconds(7200));
        TransactionTemplate saveHomeTransaction = new TransactionTemplate(transactionManager);
        try (Connection otherSession = dataSource.getConnection()) {
            try (Statement settings = otherSession.createStatement()) {
                settings.execute("SET LOCK_TIMEOUT 300");
            }
            saveHomeTransaction.executeWithoutResult(status -> {
                userRepository.findByUsernameForUpdate("locker").orElseThrow();

                assertThatThrownBy(() -> writeColour(otherSession, id))
                        .isInstanceOfSatisfying(SQLException.class,
                                e -> assertThat(e.getErrorCode()).as(e.getMessage()).isEqualTo(H2_LOCK_TIMEOUT));
            });

            // Control: with the transaction over, the same write goes straight through — so the
            // refusal above was the lock, and not a statement that could never have worked.
            assertThat(writeColour(otherSession, id)).isEqualTo(1);
        }
        assertThat(row(id).get("map_colour_scale")).isEqualTo("temp");
    }

    private static int writeColour(Connection session, Long userId) throws SQLException {
        try (PreparedStatement update = session.prepareStatement(
                "UPDATE app_user SET map_colour_scale = 'temp' WHERE id = ?")) {
            update.setLong(1, userId);
            return update.executeUpdate();
        }
    }

    @Test
    @DisplayName("a radius-only save keeps the stored drive times, their stamp and — when it carries "
            + "no radius — the stored radius")
    void sameHomeSave_keepsDriveTimes() {
        Instant calculated = NOW.minusSeconds(7200);
        Long id = durhamReader("slider", calculated);
        Authentication auth = signedInAs("slider");

        settingsService.saveHome(auth, new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, 25));
        assertThat(row(id).get("local_radius_miles")).isEqualTo(25);

        settingsService.saveHome(auth, new SaveHomeRequest(DURHAM, DURHAM_LAT, DURHAM_LON, null));

        assertThat(row(id).get("local_radius_miles")).isEqualTo(25);
        assertThat(driveTimes(id)).containsExactly("1=900", "2=1800");
        assertThat(stamp(id)).isEqualTo(calculated);
    }
}
