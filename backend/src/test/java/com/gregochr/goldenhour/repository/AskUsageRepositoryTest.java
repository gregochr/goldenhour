package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AskUsageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the counters on {@link AskUsageEntity} follow the repository's scoped-update rule (the one
 * {@code AppUserRepositoryTest} proves for {@code app_user}'s settings columns): only the conditional
 * updates move them, so a whole-entity save of a copy read earlier cannot write a stale count back.
 *
 * <p>Every assertion reads the row through {@link JdbcTemplate}, never the persistence context.
 */
@DataJpaTest
class AskUsageRepositoryTest {

    private static final long USER_ID = 41L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);

    @Autowired
    private AskUsageRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertTheDaysRow() {
        repository.insertRow(USER_ID, DAY);
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> row(LocalDate day) {
        return jdbc.queryForMap("SELECT used, engine_calls FROM ask_usage WHERE user_id = ? AND usage_date = ?",
                USER_ID, day);
    }

    @Test
    @DisplayName("a whole-entity save of a copy read before a reservation writes neither counter back")
    void staleWholeEntitySave_cannotRevertTheCounters() {
        AskUsageEntity staleCopy = repository.findAll().getFirst();
        entityManager.detach(staleCopy);
        assertThat(staleCopy.getUsed()).isZero();

        assertThat(repository.reserve(USER_ID, DAY, 3, 9)).isEqualTo(1);

        // Make the stale copy dirty on a column that IS updatable, so the save really writes.
        LocalDate moved = DAY.plusDays(1);
        staleCopy.setUsageDate(moved);
        repository.saveAndFlush(staleCopy);

        // Positive control: the whole-entity save wrote (the date moved), so the counters below
        // survived a write rather than the absence of one.
        Map<String, Object> row = row(moved);
        assertThat(row.get("used")).isEqualTo(1);
        assertThat(row.get("engine_calls")).isEqualTo(1);
    }

    @Test
    @DisplayName("the scoped updates still move the counters: reserve adds to both, refund gives back used only")
    void scopedUpdatesMoveTheCounters() {
        assertThat(repository.reserve(USER_ID, DAY, 3, 9)).isEqualTo(1);
        assertThat(repository.refund(USER_ID, DAY)).isEqualTo(1);

        Map<String, Object> row = row(DAY);
        assertThat(row.get("used")).isEqualTo(0);
        assertThat(row.get("engine_calls")).isEqualTo(1);
    }
}
