package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AskUsageRepository;
import com.gregochr.goldenhour.service.ask.AskUsageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V166 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the {@code ask_usage} columns and nullability, the unique {@code (user_id, usage_date)} key and the
 * non-negative check, <b>{@code ON DELETE CASCADE}</b> (deleting a user that has usage rows must
 * succeed and take the rows with it), and the store's reservation, refund and first-question insert
 * race on the production SQL dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same store
 * logic is proven locally on H2 by {@code AskUsageStoreTest}.
 */
class AskUsageMigrationTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private AskUsageRepository usage;
    @Autowired
    private AskUsageStore store;

    private final List<Long> createdUsers = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ask_usage");
        users.deleteAllById(createdUsers);
    }

    private long newUser(String name) {
        AppUserEntity user = AppUserEntity.builder().username(name).email(name + "@example.com")
                .password("{bcrypt}hash").role(UserRole.LITE_USER).enabled(true)
                .createdAt(java.time.LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        long id = users.save(user).getId();
        createdUsers.add(id);
        return id;
    }

    @Test
    @DisplayName("ask_usage has the expected columns, types and nullability")
    void columnsExist() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'ask_usage' ORDER BY column_name");

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("engine_calls", "id",
                "usage_date", "used", "user_id");
        assertThat(column(columns, "user_id")).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat(column(columns, "usage_date")).containsEntry("data_type", "date")
                .containsEntry("is_nullable", "NO");
        assertThat(column(columns, "used")).containsEntry("data_type", "integer").containsEntry("is_nullable", "NO");
        assertThat(column(columns, "engine_calls")).containsEntry("data_type", "integer")
                .containsEntry("is_nullable", "NO");
        assertThat(String.valueOf(column(columns, "used").get("column_default"))).isEqualTo("0");
        assertThat(String.valueOf(column(columns, "engine_calls").get("column_default"))).isEqualTo("0");
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(c -> name.equals(c.get("column_name"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("(user_id, usage_date) is unique: a second row for the same user and day is refused, another "
            + "day or user is not")
    void uniquePerUserAndDay() {
        long user = newUser("usage-unique");
        long other = newUser("usage-unique-other");
        usage.insertRow(user, DAY);

        assertThatThrownBy(() -> usage.insertRow(user, DAY)).isInstanceOf(DataIntegrityViolationException.class);
        usage.insertRow(user, DAY.plusDays(1));
        usage.insertRow(other, DAY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_usage", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("a counter can never be negative: the check constraint refuses it")
    void countersAreNonNegative() {
        long user = newUser("usage-check");
        usage.insertRow(user, DAY);

        assertThatThrownBy(() -> jdbc.update("UPDATE ask_usage SET used = -1 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ask_usage SET engine_calls = -1 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a usage row for a user that does not exist is refused: the foreign key is real")
    void foreignKeyIsEnforced() {
        assertThatThrownBy(() -> usage.insertRow(987_654_321L, DAY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a user that has usage rows succeeds and takes the rows with it (ON DELETE CASCADE)")
    void deletingAUserWithUsageRowsSucceeds() {
        long user = newUser("usage-cascade");
        long bystander = newUser("usage-bystander");
        store.reserve(user, DAY, 3, 9);
        store.reserve(user, DAY.minusDays(1), 3, 9);
        store.reserve(bystander, DAY, 3, 9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_usage WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2);

        users.deleteById(user);
        createdUsers.remove(Long.valueOf(user));

        assertThat(users.findById(user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_usage WHERE user_id = ?", Integer.class, user))
                .isZero();
        assertThat(store.read(bystander, DAY)).as("another user's usage is untouched")
                .isEqualTo(new AskUsageStore.Usage(1, 1));
    }

    @Test
    @DisplayName("reserve takes both counters, names the ceiling that stopped it, and a refund returns used only, "
            + "never below zero")
    void reserveAndRefundOnPostgres() {
        long user = newUser("usage-store");

        assertThat(store.reserve(user, DAY, 2, 6)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(user, DAY, 2, 6)).isEqualTo(AskUsageStore.Reservation.RESERVED);
        assertThat(store.reserve(user, DAY, 2, 6)).isEqualTo(AskUsageStore.Reservation.ALLOWANCE_EXHAUSTED);
        assertThat(store.refund(user, DAY)).isTrue();
        assertThat(store.refund(user, DAY)).isTrue();
        assertThat(store.refund(user, DAY)).isFalse();
        assertThat(store.read(user, DAY)).isEqualTo(new AskUsageStore.Usage(0, 2));
        for (int i = 0; i < 4; i++) {
            store.reserve(user, DAY, 2, 6);
            store.refund(user, DAY);
        }
        assertThat(store.reserve(user, DAY, 2, 6)).isEqualTo(AskUsageStore.Reservation.DAILY_LIMIT);
        assertThat(store.read(user, DAY)).isEqualTo(new AskUsageStore.Usage(0, 6));
    }

    @Test
    @DisplayName("the first-question insert race on Postgres: concurrent reservations make one row and the "
            + "allowance is never exceeded")
    void insertRaceAndAtomicReserve() throws Exception {
        long user = newUser("usage-race");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<AskUsageStore.Reservation>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return store.reserve(user, DAY, 5, 15);
                }));
            }
            go.countDown();
            int reserved = 0;
            for (Future<AskUsageStore.Reservation> future : futures) {
                if (future.get() == AskUsageStore.Reservation.RESERVED) {
                    reserved++;
                }
            }

            assertThat(reserved).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ask_usage WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            assertThat(store.read(user, DAY)).isEqualTo(new AskUsageStore.Usage(5, 5));
        } finally {
            pool.shutdownNow();
        }
    }
}
