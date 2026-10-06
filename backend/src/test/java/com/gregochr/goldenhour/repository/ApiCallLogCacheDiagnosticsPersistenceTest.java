package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code api_call_log.cache_diagnostics} on H2 with the schema generated from the entity: the column
 * round-trips a compact JSON string and is null for a call that carries none, and the measurement
 * query CLAUDE.md gives for the 2026-10-04 cache-miss question runs on it unchanged. The migration
 * itself (V168) is proved against Postgres by {@code ApiCallLogCacheDiagnosticsMigrationTest} in CI.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ApiCallLogCacheDiagnosticsPersistenceTest {

    /** The query CLAUDE.md's "Batch cache primer" bullet gives, with the since-date parameterised. */
    public static final String MEASUREMENT_QUERY = """
            SELECT batch_id,
                   COUNT(*) AS calls,
                   SUM(CASE WHEN cache_read_input_tokens > 0 THEN 1 ELSE 0 END) AS calls_that_read,
                   SUM(cache_read_input_tokens) AS read_tokens,
                   SUM(cache_creation_input_tokens) AS write_tokens,
                   SUM(CASE WHEN cache_diagnostics IS NULL THEN 0 ELSE 1 END) AS with_diagnostics,
                   SUM(CASE WHEN cache_diagnostics LIKE '%"reason":"messages_changed"%' THEN 1 ELSE 0 END)
                       AS only_messages_differ,
                   SUM(CASE WHEN cache_diagnostics LIKE '%"reason":"system_changed"%'
                              OR cache_diagnostics LIKE '%"reason":"tools_changed"%'
                              OR cache_diagnostics LIKE '%"reason":"model_changed"%' THEN 1 ELSE 0 END)
                       AS prefix_changed,
                   SUM(CASE WHEN cache_diagnostics LIKE '%"reason":"previous_message_not_found"%'
                              OR cache_diagnostics LIKE '%"reason":"unavailable"%' THEN 1 ELSE 0 END)
                       AS no_comparison
            FROM api_call_log
            WHERE service = 'ANTHROPIC' AND is_batch = TRUE AND succeeded = TRUE
              AND called_at >= ?
            GROUP BY batch_id
            ORDER BY MIN(called_at), batch_id
            """;

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 8, 0, 0);

    @Autowired
    private ApiCallLogRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private ApiCallLogEntity save(String batchId, boolean batch, long read, long write, String diagnostics) {
        return repository.save(ApiCallLogEntity.builder()
                .jobRunId(1L)
                .service(ServiceName.ANTHROPIC)
                .calledAt(SINCE.plusHours(1))
                .succeeded(true)
                .isBatch(batch)
                .batchId(batchId)
                .cacheReadInputTokens(read)
                .cacheCreationInputTokens(write)
                .cacheDiagnostics(diagnostics)
                .build());
    }

    @Test
    @DisplayName("a compact JSON string round-trips, and a call that carries none stays null")
    void roundTripsAndDefaultsToNull() {
        String json = "{\"status\":\"MISS\",\"reason\":\"messages_changed\",\"missedInputTokens\":1234}";
        long withId = save("msgbatch_a", true, 4726, 0, json).getId();
        long withoutId = save("msgbatch_a", true, 4726, 0, null).getId();

        assertThat(repository.findById(withId)).get()
                .extracting(ApiCallLogEntity::getCacheDiagnostics).isEqualTo(json);
        assertThat(repository.findById(withoutId)).get()
                .extracting(ApiCallLogEntity::getCacheDiagnostics).isNull();
    }

    @Test
    @DisplayName("the measurement query groups a cycle's batch calls by what the diagnostics said")
    void measurementQueryRuns() {
        String messagesChanged = "{\"status\":\"MISS\",\"reason\":\"messages_changed\",\"missedInputTokens\":900}";
        String systemChanged = "{\"status\":\"MISS\",\"reason\":\"system_changed\",\"missedInputTokens\":4800}";
        String notFound = "{\"status\":\"MISS\",\"reason\":\"previous_message_not_found\"}";
        save("msgbatch_a", true, 4726, 0, messagesChanged);
        save("msgbatch_a", true, 4726, 0, messagesChanged);
        save("msgbatch_a", true, 0, 4726, systemChanged);
        save("msgbatch_b", true, 0, 4726, notFound);
        save("msgbatch_b", true, 4726, 0, null);
        save(null, false, 0, 0, null);

        List<Map<String, Object>> rows = jdbc.queryForList(MEASUREMENT_QUERY, SINCE);

        assertThat(rows).hasSize(2);
        Map<String, Object> a = rows.get(0);
        assertThat(a).containsEntry("BATCH_ID", "msgbatch_a");
        assertThat(((Number) a.get("CALLS")).longValue()).isEqualTo(3);
        assertThat(((Number) a.get("CALLS_THAT_READ")).longValue()).isEqualTo(2);
        assertThat(((Number) a.get("READ_TOKENS")).longValue()).isEqualTo(9452);
        assertThat(((Number) a.get("WRITE_TOKENS")).longValue()).isEqualTo(4726);
        assertThat(((Number) a.get("WITH_DIAGNOSTICS")).longValue()).isEqualTo(3);
        assertThat(((Number) a.get("ONLY_MESSAGES_DIFFER")).longValue()).isEqualTo(2);
        assertThat(((Number) a.get("PREFIX_CHANGED")).longValue()).isEqualTo(1);
        assertThat(((Number) a.get("NO_COMPARISON")).longValue()).isZero();
        Map<String, Object> b = rows.get(1);
        assertThat(((Number) b.get("CALLS")).longValue()).isEqualTo(2);
        assertThat(((Number) b.get("WITH_DIAGNOSTICS")).longValue()).isEqualTo(1);
        assertThat(((Number) b.get("NO_COMPARISON")).longValue()).isEqualTo(1);
    }
}
