package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins {@link SchedulerFireOutcome}: reports land in the bound carrier, the last one wins, and with no fire
 * bound every call is a harmless no-op.
 */
class SchedulerFireOutcomeTest {

    @Test
    @DisplayName("report and linkPipelineRun outside a fire are no-ops that do not throw")
    void outsideAFire_isNoOp() {
        assertThatCode(() -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "nobody is listening");
            SchedulerFireOutcome.linkPipelineRun(5L);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inside a bound fire the carrier holds the reported status, summary and pipeline run")
    void insideAFire_carrierHoldsTheReport() {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();

        SchedulerFireOutcome.runBound(carrier, () -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.PARTIAL, "half done");
            SchedulerFireOutcome.linkPipelineRun(12L);
        });

        assertThat(carrier.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(carrier.summary()).isEqualTo("half done");
        assertThat(carrier.pipelineRunId()).isEqualTo(12L);
    }

    @Test
    @DisplayName("with nothing reported the carrier is empty")
    void nothingReported_carrierIsEmpty() {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();

        SchedulerFireOutcome.runBound(carrier, () -> { });

        assertThat(carrier.status()).isNull();
        assertThat(carrier.summary()).isNull();
        assertThat(carrier.pipelineRunId()).isNull();
    }

    @Test
    @DisplayName("the last report wins")
    void lastReportWins() {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();

        SchedulerFireOutcome.runBound(carrier, () -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "first");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "second");
        });

        assertThat(carrier.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
        assertThat(carrier.summary()).isEqualTo("second");
    }

    @Test
    @DisplayName("RUNNING and null statuses are ignored")
    void runningAndNull_areIgnored() {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();

        SchedulerFireOutcome.runBound(carrier, () -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.PARTIAL, "kept");
            SchedulerFireOutcome.report(SchedulerFireStatus.RUNNING, "ignored");
            SchedulerFireOutcome.report(null, "ignored too");
        });

        assertThat(carrier.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(carrier.summary()).isEqualTo("kept");
    }

    @Test
    @DisplayName("the binding ends with the fire: a report after it, on the same thread, is a no-op")
    void bindingDoesNotOutliveTheFire() {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();
        SchedulerFireOutcome.runBound(carrier, () -> { });

        SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "late");

        assertThat(carrier.status()).isNull();
    }

    @Test
    @DisplayName("a report from another thread started inside the fire is not seen (by design)")
    void otherThreads_cannotReport() throws InterruptedException {
        SchedulerFireOutcome.Carrier carrier = new SchedulerFireOutcome.Carrier();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        SchedulerFireOutcome.runBound(carrier, () -> {
            Thread other = new Thread(() -> {
                try {
                    SchedulerFireOutcome.report(SchedulerFireStatus.FAILED, "from elsewhere");
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(failure.get()).isNull();
        assertThat(carrier.status()).isNull();
    }

    @Test
    @DisplayName("capture returns what the body reported, and an all-null report when it reported nothing")
    void capture_returnsTheReport() {
        SchedulerFireOutcome.Report reported = SchedulerFireOutcome.capture(() -> {
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "off");
            SchedulerFireOutcome.linkPipelineRun(3L);
        });
        SchedulerFireOutcome.Report silent = SchedulerFireOutcome.capture(() -> { });

        assertThat(reported).isEqualTo(new SchedulerFireOutcome.Report(SchedulerFireStatus.SKIPPED, "off", 3L));
        assertThat(silent).isEqualTo(new SchedulerFireOutcome.Report(null, null, null));
    }

    @Test
    @DisplayName("count words one item in the singular and anything else in the plural")
    void count_choosesSingularOrPlural() {
        assertThat(SchedulerFireOutcome.count(1, "row", "rows")).isEqualTo("1 row");
        assertThat(SchedulerFireOutcome.count(0, "row", "rows")).isEqualTo("0 rows");
        assertThat(SchedulerFireOutcome.count(12, "row", "rows")).isEqualTo("12 rows");
    }

    @Test
    @DisplayName("statusFor: none failed is SUCCEEDED, all failed is FAILED, otherwise PARTIAL")
    void statusFor_mapsFailureCounts() {
        assertThat(SchedulerFireOutcome.statusFor(0, 5)).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(SchedulerFireOutcome.statusFor(0, 0)).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(SchedulerFireOutcome.statusFor(2, 5)).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(SchedulerFireOutcome.statusFor(5, 5)).isEqualTo(SchedulerFireStatus.FAILED);
    }
}
