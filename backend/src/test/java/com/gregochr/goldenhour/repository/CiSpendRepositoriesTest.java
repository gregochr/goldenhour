package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CiSpendAttemptEntity;
import com.gregochr.goldenhour.entity.CiSpendModelUsageEntity;
import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the CI-spend entities map and behave on H2 (Postgres equivalence of the migration itself is proven by
 * {@code CiSpendRunMigrationTest} in CI): the composite attempt key, the run's cascade to its model rows, the
 * unique (run, attempt, suite) key, the lock query and the state query.
 */
@DataJpaTest
class CiSpendRepositoriesTest {

    private static final Instant T = Instant.parse("2025-03-14T10:00:00Z");

    @Autowired
    private CiSpendRunRepository runs;

    @Autowired
    private CiSpendAttemptRepository attempts;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private static CiSpendRunEntity run(long runId, int attempt, String suite, String state) {
        CiSpendRunEntity r = new CiSpendRunEntity();
        r.setGithubRunId(runId);
        r.setRunAttempt(attempt);
        r.setSuite(suite);
        r.setUsageState(state);
        r.setHtmlUrl("https://github.com/o/r/actions/runs/" + runId + "/attempts/" + attempt);
        r.setStartedAt(T);
        r.setFetchedAt(T);
        return r;
    }

    private static CiSpendModelUsageEntity model(String id) {
        CiSpendModelUsageEntity m = new CiSpendModelUsageEntity();
        m.setModel(id);
        m.setCalls(3);
        m.setInputTokens(1000L);
        m.setInputUsdPerMtok(3.0);
        m.setCostMicroDollars(3000L);
        return m;
    }

    @Test
    @DisplayName("a run saves with its model rows, finds by key, and reads them back")
    void savesAndFindsARunWithModels() {
        CiSpendRunEntity r = run(1L, 1, "sky", "KNOWN");
        r.setCostMicroDollars(3000L);
        r.setExchangeRateGbpPerUsd(0.8);
        r.addModel(model("claude-sonnet-5-5"));
        runs.saveAndFlush(r);
        em.clear();

        CiSpendRunEntity found = runs.findByGithubRunIdAndRunAttemptAndSuite(1L, 1, "sky").orElseThrow();

        assertThat(found.getUsageState()).isEqualTo("KNOWN");
        assertThat(found.getCostMicroDollars()).isEqualTo(3000L);
        assertThat(found.getErroredCalls()).isZero();
        assertThat(found.getModels()).hasSize(1);
        assertThat(found.getModels().get(0).getInputUsdPerMtok()).isEqualTo(3.0);
        assertThat(found.getModels().get(0).getCacheWrite1hTokens()).isZero();
        assertThat(runs.findByGithubRunIdAndRunAttemptAndSuite(1L, 1, "ask")).isEmpty();
    }

    @Test
    @DisplayName("getModels is a read-only view; rows change only through the run's own methods")
    void modelsAreReadOnly() {
        CiSpendRunEntity r = run(8L, 1, "sky", "KNOWN");
        r.addModel(model("a"));

        assertThatThrownBy(() -> r.getModels().add(model("b"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.getModels().clear()).isInstanceOf(UnsupportedOperationException.class);
        r.replaceModels(List.of(model("c"), model("d")));
        assertThat(r.getModels()).extracting(CiSpendModelUsageEntity::getModel).containsExactly("c", "d");
        r.clearModels();
        assertThat(r.getModels()).isEmpty();
    }

    @Test
    @DisplayName("deleting a run deletes its model rows")
    void deletingARunCascadesToItsModels() {
        CiSpendRunEntity r = run(2L, 1, "ask", "UNPRICED");
        r.addModel(model("claude-future-9"));
        r.addModel(model("claude-future-10"));
        runs.saveAndFlush(r);

        runs.delete(r);
        runs.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_spend_model_usage", Integer.class)).isZero();
    }

    @Test
    @DisplayName("replacing the model rows of a run removes the old ones")
    void replacingModelsRemovesOrphans() {
        CiSpendRunEntity r = run(3L, 1, "sky", "UNKNOWN");
        r.addModel(model("claude-old-1"));
        runs.saveAndFlush(r);

        r.clearModels();
        r.addModel(model("claude-sonnet-5-5"));
        runs.saveAndFlush(r);

        assertThat(jdbc.queryForList("SELECT model FROM ci_spend_model_usage", String.class))
                .containsExactly("claude-sonnet-5-5");
    }

    @Test
    @DisplayName("the (run, attempt, suite) key is unique, but the same suite in another attempt is fine")
    void uniqueKey() {
        runs.saveAndFlush(run(4L, 1, "sky", "KNOWN"));
        runs.saveAndFlush(run(4L, 2, "sky", "KNOWN"));
        runs.saveAndFlush(run(4L, 1, "ask", "KNOWN"));

        assertThatThrownBy(() -> runs.saveAndFlush(run(4L, 1, "sky", "UNKNOWN")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("lockByKey finds the row for its key, and findByUsageState lists one state oldest first")
    void lockAndStateQueries() {
        CiSpendRunEntity older = run(5L, 1, "sky", "UNPRICED");
        older.setStartedAt(T.minusSeconds(3600));
        CiSpendRunEntity newer = run(6L, 1, "sky", "UNPRICED");
        runs.saveAndFlush(newer);
        runs.saveAndFlush(older);
        runs.saveAndFlush(run(7L, 1, "sky", "KNOWN"));

        assertThat(runs.lockByKey(6L, 1, "sky")).isPresent();
        assertThat(runs.lockByKey(6L, 2, "sky")).isEmpty();
        List<CiSpendRunEntity> unpriced = runs.findByUsageStateOrderByStartedAtAsc("UNPRICED");
        assertThat(unpriced).extracting(CiSpendRunEntity::getGithubRunId).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("an attempt marker saves and is found by its composite key")
    void attemptMarker() {
        attempts.saveAndFlush(new CiSpendAttemptEntity(9L, 2, T, "sky,ask"));
        em.clear();

        assertThat(attempts.existsById(new CiSpendAttemptEntity.Key(9L, 2))).isTrue();
        assertThat(attempts.existsById(new CiSpendAttemptEntity.Key(9L, 1))).isFalse();
        CiSpendAttemptEntity found = attempts.findById(new CiSpendAttemptEntity.Key(9L, 2)).orElseThrow();
        assertThat(found.getSuitesRan()).isEqualTo("sky,ask");
        assertThat(found.getSettledAt()).isEqualTo(T);
    }
}
