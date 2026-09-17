package com.gregochr.goldenhour.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link OptimisationStrategyRepository#deleteByStrategyTypeIn} against a real database (H2).
 *
 * <p>⚠️ Every other test of the startup prune mocks this method, so none of them could notice its SQL
 * being wrong — and the likeliest wrong version is also the most dangerous one. {@code IN} flipped to
 * {@code NOT IN} would still boot every context and pass every mocked test, while deleting every
 * LIVE row on each start in production (and re-seeding them with defaults, erasing an admin's
 * settings). Rows are inserted with raw SQL because the retired types no longer exist in the enum,
 * so JPA cannot write them.
 */
@DataJpaTest
class OptimisationStrategyRepositoryTest {

    @Autowired
    private OptimisationStrategyRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private void insert(String runType, String strategyType, boolean enabled, Integer param) {
        jdbc.update("INSERT INTO optimisation_strategy (run_type, strategy_type, enabled, param_value, updated_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", runType, strategyType, enabled, param);
    }

    private List<String> remaining() {
        return jdbc.queryForList("SELECT run_type || '/' || strategy_type FROM optimisation_strategy "
                + "ORDER BY run_type, strategy_type", String.class);
    }

    /**
     * Models a local database built before V153. Hibernate generates this column as an H2 ENUM of the
     * CURRENT values, which cannot hold a retired type at all. Production's column is a plain
     * VARCHAR(30) (V41, no check constraint), and a local database generated before V153 allowed the
     * old values — so widen the test table to production's shape first, or the state the delete
     * exists for could not even be seeded. Not a {@code @BeforeEach}:
     * {@link #prunesOnTheSchemaHibernateGenerates} must run on the untouched ENUM column.
     */
    private void seedPreV153Table() {
        jdbc.execute("ALTER TABLE optimisation_strategy ALTER COLUMN strategy_type SET DATA TYPE VARCHAR(30)");
        // Live rows, one carrying an admin's non-default settings.
        insert("SHORT_TERM", "SENTINEL_SAMPLING", false, 4);
        insert("SHORT_TERM", "TIDE_ALIGNMENT", true, null);
        // Retired rows, as a local database built before V153 still holds them.
        insert("SHORT_TERM", "SKIP_LOW_RATED", true, 3);
        insert("LONG_TERM", "SKIP_EXISTING", true, null);
    }

    /**
     * The second boot of a fresh local database. Hibernate generates {@code strategy_type} on H2 as a
     * native {@code ENUM} of the current constants, and H2 refuses to compare that column with a value
     * outside them — so the startup prune, which by design names only types the enum no longer has,
     * threw on every start after the first ({@code Value not permitted for column
     * "('SENTINEL_SAMPLING', 'TIDE_ALIGNMENT')": "SKIP_LOW_RATED"}). The query now compares the column
     * through {@code CAST(... AS VARCHAR)}. This runs against the schema exactly as Hibernate generated
     * it — no ALTER — holding only live rows, with the prune's own list of all seven retired names:
     * the statement the second boot runs. Deleting the cast fails it with that error.
     */
    @Test
    @DisplayName("the prune may name a retired type against the ENUM column Hibernate generates on H2")
    void prunesOnTheSchemaHibernateGenerates() {
        insert("SHORT_TERM", "SENTINEL_SAMPLING", true, 2);
        insert("SHORT_TERM", "TIDE_ALIGNMENT", true, null);
        String dataType = jdbc.queryForObject("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'OPTIMISATION_STRATEGY' AND COLUMN_NAME = 'STRATEGY_TYPE'", String.class);
        // The precondition that makes this test the second boot rather than a copy of the ones below.
        assertThat(dataType).isEqualTo("ENUM");

        int deleted = repository.deleteByStrategyTypeIn(List.of(
                "SKIP_LOW_RATED", "SKIP_EXISTING", "FORCE_IMMINENT", "FORCE_STALE",
                "EVALUATE_ALL", "NEXT_EVENT_ONLY", "BATCH_API"));

        assertThat(deleted).isZero();
        assertThat(remaining()).containsExactly("SHORT_TERM/SENTINEL_SAMPLING", "SHORT_TERM/TIDE_ALIGNMENT");
    }

    @Test
    @DisplayName("deletes exactly the rows naming a listed retired type and reports how many")
    void deletesOnlyTheListedRetiredTypes() {
        seedPreV153Table();

        int deleted = repository.deleteByStrategyTypeIn(List.of("SKIP_LOW_RATED", "SKIP_EXISTING"));

        assertThat(deleted).isEqualTo(2);
        assertThat(remaining()).containsExactly("SHORT_TERM/SENTINEL_SAMPLING", "SHORT_TERM/TIDE_ALIGNMENT");
        assertThat(jdbc.queryForObject("SELECT param_value FROM optimisation_strategy "
                + "WHERE strategy_type = 'SENTINEL_SAMPLING'", Integer.class)).isEqualTo(4);
    }

    /**
     * The rollback case the explicit list exists for: a row naming a type this binary has never heard
     * of — one a newer image's migration added — must survive, and fail loudly on read rather than
     * vanish. A {@code NOT IN (known)} delete would have removed it along with the retired rows.
     */
    @Test
    @DisplayName("leaves a type it was not told about untouched, even one the enum does not know")
    void leavesAnUnlistedUnknownTypeAlone() {
        seedPreV153Table();
        insert("SHORT_TERM", "SOME_FUTURE_STRATEGY", true, null);

        repository.deleteByStrategyTypeIn(List.of("SKIP_LOW_RATED", "SKIP_EXISTING"));

        assertThat(remaining()).containsExactly(
                "SHORT_TERM/SENTINEL_SAMPLING", "SHORT_TERM/SOME_FUTURE_STRATEGY", "SHORT_TERM/TIDE_ALIGNMENT");
    }

    @Test
    @DisplayName("deletes nothing on a table that holds no retired rows")
    void deletesNothingWhenNoneAreRetired() {
        seedPreV153Table();
        jdbc.update("DELETE FROM optimisation_strategy WHERE strategy_type IN ('SKIP_LOW_RATED', 'SKIP_EXISTING')");

        int deleted = repository.deleteByStrategyTypeIn(List.of("SKIP_LOW_RATED", "SKIP_EXISTING"));

        assertThat(deleted).isZero();
        assertThat(remaining()).hasSize(2);
    }
}
