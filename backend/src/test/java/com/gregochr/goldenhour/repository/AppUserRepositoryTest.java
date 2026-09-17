package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the settings writes on {@link AppUserRepository} against a real JPA stack (H2, schema from
 * the entity annotations): each column-scoped update writes its own columns and no others, the
 * drive-time compare-and-set takes both branches, and a whole-entity save can no longer write a
 * stale home back.
 *
 * <p>Every assertion reads the row through {@link JdbcTemplate} after a flush, never through the
 * persistence context — a managed instance reports what Hibernate holds in memory, which is exactly
 * the thing a lost update makes disagree with the database.
 *
 * <p>What this cannot show is behaviour under real concurrency on the production engine (a row lock
 * making the compare-and-set wait, then re-check). That is Postgres's, and is proven in CI by
 * {@code UserSettingsRowLockIntegrationTest}.
 */
@DataJpaTest
class AppUserRepositoryTest {

    private static final String USERNAME = "reader";

    /** Durham — the home the drive times were measured from. */
    private static final String OLD_POSTCODE = "DH1 3LE";
    private static final double OLD_LAT = 54.7761;
    private static final double OLD_LON = -1.5733;

    /** Newcastle — the home a save moves to. */
    private static final String NEW_POSTCODE = "NE1 4ST";
    private static final double NEW_LAT = 54.9714;
    private static final double NEW_LON = -1.6174;

    private static final Instant STAMP = Instant.parse("2026-09-01T06:30:00Z");
    private static final Instant LAST_SEEN = Instant.parse("2026-08-30T19:00:00Z");
    private static final LocalDateTime LAST_ACTIVE = LocalDateTime.of(2026, 9, 1, 7, 0);
    private static final Instant RECALCULATED = Instant.parse("2026-09-02T08:15:00Z");

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void persistReader() {
        AppUserEntity user = AppUserEntity.builder()
                .username(USERNAME)
                .password("{bcrypt}hash")
                .role(UserRole.PRO_USER)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .email("reader@example.com")
                .lastActiveAt(LAST_ACTIVE)
                .homePostcode(OLD_POSTCODE)
                .homeLatitude(OLD_LAT)
                .homeLongitude(OLD_LON)
                .localRadiusMiles(40)
                .driveTimesCalculatedAt(STAMP)
                .mapColourScale("verdict")
                .comingUpLastSeenAt(LAST_SEEN)
                .build();
        userId = entityManager.persistAndGetId(user, Long.class);
        entityManager.flush();
        entityManager.clear();
    }

    /** The row as the database holds it, bypassing the persistence context. */
    private Map<String, Object> row() {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT home_postcode, home_latitude, home_longitude, "
                + "local_radius_miles, drive_times_calculated_at, map_colour_scale, "
                + "coming_up_last_seen_at, last_active_at, email FROM app_user WHERE id = ?", userId);
    }

    private Instant stamp() {
        entityManager.flush();
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT drive_times_calculated_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
        return value == null ? null : value.toInstant();
    }

    private Instant lastSeen() {
        entityManager.flush();
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT coming_up_last_seen_at FROM app_user WHERE id = ?", OffsetDateTime.class, userId);
        return value == null ? null : value.toInstant();
    }

    private LocalDateTime lastActive() {
        // Flushed first, like every read here: a whole-entity save's UPDATE waits in the
        // persistence context until flush, and a read that ran before it would see the row as it
        // stood — which is how this control first failed.
        entityManager.flush();
        return jdbcTemplate.queryForObject(
                "SELECT last_active_at FROM app_user WHERE id = ?", LocalDateTime.class, userId);
    }

    @Test
    @DisplayName("a new row is inserted with its settings columns — updatable = false does not "
            + "reach an insert")
    void insert_writesTheSettingsColumns() {
        // The control every other test leans on: if the fixture's settings never reached the row,
        // "unchanged" below would be comparing null with null.
        Map<String, Object> row = row();
        assertThat(row.get("home_postcode")).isEqualTo(OLD_POSTCODE);
        assertThat(row.get("home_latitude")).isEqualTo(OLD_LAT);
        assertThat(row.get("home_longitude")).isEqualTo(OLD_LON);
        assertThat(row.get("local_radius_miles")).isEqualTo(40);
        assertThat(row.get("map_colour_scale")).isEqualTo("verdict");
        assertThat(stamp()).isEqualTo(STAMP);
        assertThat(lastSeen()).isEqualTo(LAST_SEEN);
    }

    @Nested
    @DisplayName("updateHome")
    class UpdateHome {

        @Test
        @DisplayName("writes the postcode, coordinates and radius, and no other column")
        void writesTheHomeFieldsTogether() {
            int updated = repository.updateHome(userId, NEW_POSTCODE, NEW_LAT, NEW_LON, 25);

            assertThat(updated).isEqualTo(1);
            Map<String, Object> row = row();
            assertThat(row.get("home_postcode")).isEqualTo(NEW_POSTCODE);
            assertThat(row.get("home_latitude")).isEqualTo(NEW_LAT);
            assertThat(row.get("home_longitude")).isEqualTo(NEW_LON);
            assertThat(row.get("local_radius_miles")).isEqualTo(25);
            // The stamp belongs to the drive-time writes: a save that moves the home clears it
            // separately, and one that does not must leave it alone.
            assertThat(stamp()).isEqualTo(STAMP);
            assertThat(row.get("map_colour_scale")).isEqualTo("verdict");
            assertThat(lastSeen()).isEqualTo(LAST_SEEN);
            assertThat(lastActive()).isEqualTo(LAST_ACTIVE);
            assertThat(row.get("email")).isEqualTo("reader@example.com");
        }

        @Test
        @DisplayName("a null radius keeps the stored one — it is not a reset")
        void nullRadiusKeepsTheStoredOne() {
            repository.updateHome(userId, NEW_POSTCODE, NEW_LAT, NEW_LON, null);

            Map<String, Object> row = row();
            assertThat(row.get("local_radius_miles")).isEqualTo(40);
            assertThat(row.get("home_postcode")).isEqualTo(NEW_POSTCODE);
        }

        @Test
        @DisplayName("matches no row for an unknown id")
        void unknownId_updatesNothing() {
            assertThat(repository.updateHome(userId + 1000, NEW_POSTCODE, NEW_LAT, NEW_LON, 25)).isZero();
            assertThat(row().get("home_postcode")).isEqualTo(OLD_POSTCODE);
        }
    }

    @Test
    @DisplayName("clearDriveTimesCalculatedAt nulls the stamp and nothing else")
    void clearDriveTimesCalculatedAt_nullsOnlyTheStamp() {
        assertThat(repository.clearDriveTimesCalculatedAt(userId)).isEqualTo(1);

        assertThat(stamp()).isNull();
        Map<String, Object> row = row();
        assertThat(row.get("home_postcode")).isEqualTo(OLD_POSTCODE);
        assertThat(row.get("local_radius_miles")).isEqualTo(40);
        assertThat(row.get("map_colour_scale")).isEqualTo("verdict");
    }

    @Test
    @DisplayName("updateMapColourScaleByUsername writes the scale and nothing else")
    void updateMapColourScale_writesOnlyTheScale() {
        assertThat(repository.updateMapColourScaleByUsername(USERNAME, "temp")).isEqualTo(1);

        Map<String, Object> row = row();
        assertThat(row.get("map_colour_scale")).isEqualTo("temp");
        assertThat(row.get("home_postcode")).isEqualTo(OLD_POSTCODE);
        assertThat(row.get("home_latitude")).isEqualTo(OLD_LAT);
        assertThat(row.get("local_radius_miles")).isEqualTo(40);
        assertThat(stamp()).isEqualTo(STAMP);
        assertThat(lastSeen()).isEqualTo(LAST_SEEN);
    }

    @Nested
    @DisplayName("stampDriveTimesIfHomeIs — the compare-and-set")
    class StampIfHomeIs {

        @Test
        @DisplayName("stamps while the home is still the one measured from")
        void homeUnchanged_stamps() {
            int stamped = repository.stampDriveTimesIfHomeIs(userId, OLD_LAT, OLD_LON, RECALCULATED);

            assertThat(stamped).isEqualTo(1);
            assertThat(stamp()).isEqualTo(RECALCULATED);
        }

        @Test
        @DisplayName("after a save has moved the home, a result measured from the old one matches "
                + "nothing and stamps nothing")
        void homeMovedSinceMeasuring_stampsNothing() {
            // The refresh race in the order it happens: routing from the old home, the save lands,
            // then the refresh tries to store.
            repository.updateHome(userId, NEW_POSTCODE, NEW_LAT, NEW_LON, null);
            repository.clearDriveTimesCalculatedAt(userId);

            int stamped = repository.stampDriveTimesIfHomeIs(userId, OLD_LAT, OLD_LON, RECALCULATED);

            assertThat(stamped).isZero();
            assertThat(stamp()).isNull();
            assertThat(row().get("home_postcode")).isEqualTo(NEW_POSTCODE);
        }

        @Test
        @DisplayName("a latitude that differs alone is a moved home")
        void latitudeAloneDiffers_stampsNothing() {
            // Two conditions in one WHERE: a test that moved both coordinates would leave either
            // one deletable with the suite still green.
            assertThat(repository.stampDriveTimesIfHomeIs(userId, NEW_LAT, OLD_LON, RECALCULATED))
                    .isZero();
            assertThat(stamp()).isEqualTo(STAMP);
        }

        @Test
        @DisplayName("a longitude that differs alone is a moved home")
        void longitudeAloneDiffers_stampsNothing() {
            assertThat(repository.stampDriveTimesIfHomeIs(userId, OLD_LAT, NEW_LON, RECALCULATED))
                    .isZero();
            assertThat(stamp()).isEqualTo(STAMP);
        }

        @Test
        @DisplayName("measures the other user's row not at all — the id is part of the match")
        void anotherUsersId_stampsNothing() {
            assertThat(repository.stampDriveTimesIfHomeIs(userId + 1000, OLD_LAT, OLD_LON, RECALCULATED))
                    .isZero();
            assertThat(stamp()).isEqualTo(STAMP);
        }
    }

    @Test
    @DisplayName("a whole-entity save of a copy read before the settings changed writes none of "
            + "them back")
    void staleWholeEntitySave_cannotRevertTheSettings() {
        // The lost update itself. The JWT filter's hourly last-active write, login, a password
        // change and the admin paths all load the user and save the whole entity back. A copy
        // loaded before a settings save commits carries the OLD home, colour and stamps; merged
        // back, Hibernate counts every stale value as a change and — without updatable = false —
        // writes it, silently undoing the save in between.
        AppUserEntity staleCopy = repository.findByUsername(USERNAME).orElseThrow();
        entityManager.detach(staleCopy);

        repository.updateHome(userId, NEW_POSTCODE, NEW_LAT, NEW_LON, 25);
        repository.stampDriveTimesIfHomeIs(userId, NEW_LAT, NEW_LON, RECALCULATED);
        repository.updateMapColourScaleByUsername(USERNAME, "temp");
        repository.updateComingUpLastSeenAtByUsername(USERNAME, RECALCULATED);

        LocalDateTime nowActive = LocalDateTime.of(2026, 9, 2, 9, 0);
        staleCopy.setLastActiveAt(nowActive);
        repository.save(staleCopy);

        // Positive control: the whole-entity save really wrote, so the survivors below survived a
        // write rather than the absence of one.
        assertThat(lastActive()).isEqualTo(nowActive);
        Map<String, Object> row = row();
        assertThat(row.get("home_postcode")).isEqualTo(NEW_POSTCODE);
        assertThat(row.get("home_latitude")).isEqualTo(NEW_LAT);
        assertThat(row.get("home_longitude")).isEqualTo(NEW_LON);
        assertThat(row.get("local_radius_miles")).isEqualTo(25);
        assertThat(row.get("map_colour_scale")).isEqualTo("temp");
        assertThat(stamp()).isEqualTo(RECALCULATED);
        assertThat(lastSeen()).isEqualTo(RECALCULATED);
    }

    @Test
    @DisplayName("findByUsernameForUpdate reads the row it locks")
    void findByUsernameForUpdate_returnsTheUser() {
        // The lock itself is proven against Postgres in CI, where it matters; here, that the query
        // with its lock mode is accepted and answers like findByUsername.
        AppUserEntity locked = repository.findByUsernameForUpdate(USERNAME).orElseThrow();

        assertThat(locked.getId()).isEqualTo(userId);
        assertThat(locked.getHomePostcode()).isEqualTo(OLD_POSTCODE);
        assertThat(repository.findByUsernameForUpdate("nobody")).isEmpty();
    }
}
