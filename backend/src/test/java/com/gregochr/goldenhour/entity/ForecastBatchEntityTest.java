package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForecastBatchEntity}.
 */
class ForecastBatchEntityTest {

    @Test
    @DisplayName("Constructor sets all fields correctly")
    void constructor_setsFields() {
        Instant expiresAt = Instant.parse("2026-04-07T06:00:00Z");
        ForecastBatchEntity entity = new ForecastBatchEntity(
                "msgbatch_test", BatchType.FORECAST, 10, expiresAt);

        assertThat(entity.getAnthropicBatchId()).isEqualTo("msgbatch_test");
        assertThat(entity.getBatchType()).isEqualTo(BatchType.FORECAST);
        assertThat(entity.getRequestCount()).isEqualTo(10);
        assertThat(entity.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(entity.getStatus()).isEqualTo(BatchStatus.SUBMITTED);
        assertThat(entity.getSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("Setters update status and counts correctly")
    void setters_updateState() {
        ForecastBatchEntity entity = new ForecastBatchEntity(
                "msgbatch_abc", BatchType.AURORA, 1, Instant.now().plusSeconds(3600));

        entity.setStatus(BatchStatus.COMPLETED);
        entity.setSucceededCount(1);
        entity.setErroredCount(0);
        Instant endedAt = Instant.now();
        entity.setEndedAt(endedAt);

        assertThat(entity.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(entity.getSucceededCount()).isEqualTo(1);
        assertThat(entity.getErroredCount()).isEqualTo(0);
        assertThat(entity.getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    @DisplayName("setLastPolledAt updates lastPolledAt")
    void setLastPolledAt_updatesField() {
        ForecastBatchEntity entity = new ForecastBatchEntity(
                "msgbatch_xyz", BatchType.FORECAST, 5, Instant.now().plusSeconds(3600));
        assertThat(entity.getLastPolledAt()).isNull();

        Instant polledAt = Instant.now();
        entity.setLastPolledAt(polledAt);
        assertThat(entity.getLastPolledAt()).isEqualTo(polledAt);
    }

    @Test
    @DisplayName("setErrorMessage updates errorMessage")
    void setErrorMessage_updatesField() {
        ForecastBatchEntity entity = new ForecastBatchEntity(
                "msgbatch_err", BatchType.FORECAST, 2, Instant.now().plusSeconds(3600));
        entity.setStatus(BatchStatus.FAILED);
        entity.setErrorMessage("Parsing failed");

        assertThat(entity.getErrorMessage()).isEqualTo("Parsing failed");
    }

    @Test
    @DisplayName("BatchType enum has FORECAST and AURORA values")
    void batchType_enumValues() {
        assertThat(BatchType.values()).containsExactlyInAnyOrder(
                BatchType.FORECAST, BatchType.AURORA);
    }

    @Test
    @DisplayName("BatchStatus enum has all expected values")
    void batchStatus_enumValues() {
        assertThat(BatchStatus.values()).containsExactlyInAnyOrder(
                BatchStatus.SUBMITTED, BatchStatus.COMPLETED,
                BatchStatus.FAILED, BatchStatus.EXPIRED);
    }
}
