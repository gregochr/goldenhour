package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V153 ({@code retire_dead_optimisation_strategies}) against a real Postgres 17 engine.
 *
 * <p>Flyway is driven directly, as {@link PipelineRunPickDedupeMigrationTest} does, so the test can
 * stop at V152 — where V41/V49/V52/V54 have seeded the table exactly as a production database holds
 * it — inspect it, change a surviving row the way an admin would, and only then apply V153. A test
 * that booted the Spring context would migrate straight to latest and never see the rows V153 is
 * there to remove.
 *
 * <p>⚠️ What makes this migration load-bearing rather than tidy: the enum values it retires are
 * deleted in the same change, and {@code strategy_type} maps through {@code @Enumerated(STRING)}. A
 * single row left behind naming a retired type makes Hibernate throw on read — taking down the Models
 * screen and any manual run that loads it. So the assertion that matters most is the last one: every
 * row that survives names a type the enum can still load.
 */
@Testcontainers
class RetireDeadOptimisationStrategiesMigrationTest {

    private static final List<String> RETIRED = List.of(
            "SKIP_LOW_RATED", "SKIP_EXISTING", "FORCE_IMMINENT", "FORCE_STALE",
            "EVALUATE_ALL", "NEXT_EVENT_ONLY", "BATCH_API");

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("goldenhour_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    private Flyway flywayTo(String targetVersion) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            config = config.target(targetVersion);
        }
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private List<String> rows(Statement st) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT run_type, strategy_type, enabled, param_value "
                + "FROM optimisation_strategy ORDER BY run_type, strategy_type")) {
            while (rs.next()) {
                Object param = rs.getObject("param_value");
                out.add(rs.getString("run_type") + "/" + rs.getString("strategy_type")
                        + "/" + rs.getBoolean("enabled") + "/" + param);
            }
        }
        return out;
    }

    @Test
    @DisplayName("V153 deletes every retired strategy row and leaves the two live types exactly as "
            + "configured, so every surviving row names a type the enum can still load")
    void v153_retiresDeadStrategies_andLeavesLiveConfigurationUntouched() throws SQLException {
        // 1. Stop at V152: the table as a production database holds it before this change.
        flywayTo("152").migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            // Positive control — the rows V153 exists to remove are really there, so a green
            // result below cannot come from a table that never held them.
            List<String> before = rows(st);
            assertThat(before).anyMatch(r -> r.contains("/SKIP_LOW_RATED/"));
            assertThat(before).anyMatch(r -> r.contains("/NEXT_EVENT_ONLY/"));

            // An admin's change to a live row, which V153 must not touch.
            st.execute("UPDATE optimisation_strategy SET enabled = FALSE, param_value = 4 "
                    + "WHERE run_type = 'SHORT_TERM' AND strategy_type = 'SENTINEL_SAMPLING'");
        }

        // 2. Apply V153 alone — it is the latest migration in this change.
        flywayTo(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            List<String> after = rows(st);

            assertThat(after).containsExactly(
                    "LONG_TERM/SENTINEL_SAMPLING/true/2",
                    "LONG_TERM/TIDE_ALIGNMENT/true/null",
                    "SHORT_TERM/SENTINEL_SAMPLING/false/4",
                    "SHORT_TERM/TIDE_ALIGNMENT/true/null",
                    "VERY_SHORT_TERM/SENTINEL_SAMPLING/true/2",
                    "VERY_SHORT_TERM/TIDE_ALIGNMENT/true/null");

            assertThat(after).noneMatch(r -> RETIRED.stream().anyMatch(t -> r.contains("/" + t + "/")));

            // The load-bearing property: nothing left for @Enumerated(STRING) to choke on.
            List<String> loadable = Arrays.stream(OptimisationStrategyType.values()).map(Enum::name).toList();
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT strategy_type FROM optimisation_strategy")) {
                while (rs.next()) {
                    assertThat(loadable).contains(rs.getString(1));
                }
            }
        }
    }
}
