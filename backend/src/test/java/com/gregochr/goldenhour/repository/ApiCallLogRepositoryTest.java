package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for {@link ApiCallLogRepository#findBatchCallOutcomes}, the projection
 * the location auto-disable rule reads. Runs on H2 with the schema generated from the entities.
 */
@DataJpaTest
class ApiCallLogRepositoryTest {

    private static final LocalDateTime CALLED_AT = LocalDateTime.of(2026, 10, 2, 1, 5);

    @Autowired
    private ApiCallLogRepository repository;

    private void save(String batchId, String customId, boolean succeeded, String errorType,
            boolean isBatch) {
        repository.save(ApiCallLogEntity.builder()
                .jobRunId(1L)
                .service(ServiceName.ANTHROPIC)
                .calledAt(CALLED_AT)
                .succeeded(succeeded)
                .isBatch(isBatch)
                .batchId(batchId)
                .customId(customId)
                .errorType(errorType)
                .build());
    }

    @Test
    @DisplayName("projects custom id, success flag and error type for batch rows of the given "
            + "batches, and nothing from another batch, a non-batch call or a row with no custom id")
    void projectsBatchRowsOfTheGivenBatchesOnly() {
        save("msgbatch_a", "fc-1-2026-10-02-SUNSET", true, null, true);
        save("msgbatch_a", "fc-2-2026-10-02-SUNSET", false, "errored", true);
        save("msgbatch_b", "fc-3-2026-10-02-SUNSET", false, "expired", true);
        save("msgbatch_c", "fc-4-2026-10-02-SUNSET", true, null, true);
        save("msgbatch_a", "fc-5-2026-10-02-SUNSET", true, null, false);
        save("msgbatch_a", null, false, "errored", true);

        List<BatchCallOutcome> rows =
                repository.findBatchCallOutcomes(List.of("msgbatch_a", "msgbatch_b"));

        assertThat(rows).containsExactlyInAnyOrder(
                new BatchCallOutcome("fc-1-2026-10-02-SUNSET", true, null),
                new BatchCallOutcome("fc-2-2026-10-02-SUNSET", false, "errored"),
                new BatchCallOutcome("fc-3-2026-10-02-SUNSET", false, "expired"));
    }
}
