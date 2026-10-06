package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogCacheDiagnosticsPersistenceTest;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V168 against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * {@code api_call_log} gains a nullable {@code cache_diagnostics TEXT}, a real {@link JobRunService}
 * writes the compact diagnostics JSON into it and a call that carries none leaves it NULL, and the
 * measurement query CLAUDE.md gives for the batch cache-miss question runs on the production dialect.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md), so the migration is proven before merge only by CI's Backend job. The same column
 * and query are proven locally on H2 by {@code ApiCallLogCacheDiagnosticsPersistenceTest}.
 */
class ApiCallLogCacheDiagnosticsMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JobRunService jobRunService;

    @Test
    @DisplayName("V168 adds cache_diagnostics (text) to api_call_log, nullable")
    void columnExistsAndIsNullable() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'api_call_log' AND column_name = 'cache_diagnostics'");

        assertThat(columns).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("data_type", "text")
                        .containsEntry("is_nullable", "YES"));
    }

    @Test
    @DisplayName("a batch result written through JobRunService stores the diagnostics JSON, and a call without "
            + "them stores NULL; the measurement query reads both")
    void diagnosticsAreWrittenAndQueryable() {
        JobRunEntity run = jobRunService.startRun(RunType.SHORT_TERM, false, EvaluationModel.HAIKU);
        TokenUsage usage = new TokenUsage(1_000, 100, 0, 4_726);
        jobRunService.logBatchResult(run.getId(), "msgbatch_v168", "fc-1-2026-10-08-SUNRISE", true, "SUCCESS",
                null, null, EvaluationModel.HAIKU, usage, LocalDate.of(2026, 10, 8), TargetType.SUNRISE, null,
                new CacheDiagnostics(CacheDiagnostics.Status.MISS, "messages_changed", 1234L));
        jobRunService.logBatchResult(run.getId(), "msgbatch_v168", "fc-2-2026-10-08-SUNRISE", true, "SUCCESS",
                null, null, EvaluationModel.HAIKU, usage, LocalDate.of(2026, 10, 8), TargetType.SUNRISE, null,
                CacheDiagnostics.EMPTY);

        List<String> stored = jdbc.queryForList(
                "SELECT cache_diagnostics FROM api_call_log WHERE batch_id = 'msgbatch_v168' "
                        + "ORDER BY custom_id", String.class);
        assertThat(stored).containsExactly(
                "{\"status\":\"MISS\",\"reason\":\"messages_changed\",\"missedInputTokens\":1234}", null);

        List<Map<String, Object>> rows = jdbc.queryForList(
                ApiCallLogCacheDiagnosticsPersistenceTest.MEASUREMENT_QUERY,
                LocalDateTime.now().minusDays(1));
        Map<String, Object> mine = rows.stream()
                .filter(r -> "msgbatch_v168".equals(r.get("batch_id"))).findFirst().orElseThrow();
        assertThat(((Number) mine.get("calls")).longValue()).isEqualTo(2);
        assertThat(((Number) mine.get("calls_that_read")).longValue()).isEqualTo(2);
        assertThat(((Number) mine.get("with_diagnostics")).longValue()).isEqualTo(1);
        assertThat(((Number) mine.get("only_messages_differ")).longValue()).isEqualTo(1);
    }
}
