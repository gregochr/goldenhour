package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The two Ask run types: where they must fit, and where they must NOT appear. */
class RunTypeAskTest {

    @Test
    @DisplayName("both names fit job_run.run_type VARCHAR(20), the column's declared length")
    void namesFitTheColumn() throws Exception {
        int length = JobRunEntity.class.getDeclaredField("runType")
                .getAnnotation(jakarta.persistence.Column.class).length();

        assertThat(length).isEqualTo(20);
        assertThat(Arrays.asList(RunType.ASK, RunType.ASK_READY)).allSatisfy(
                type -> assertThat(type.name().length()).isLessThanOrEqualTo(length));
    }

    @Test
    @DisplayName("the default date range is the six-day window like every other non-forecast run type")
    void defaultDateRange() {
        LocalDate today = LocalDate.of(2026, 10, 5);

        for (RunType type : new RunType[] {RunType.ASK, RunType.ASK_READY}) {
            assertThat(type.defaultDateRange(today)).as(type.name()).hasSize(RunType.FORECAST_HORIZON_DAYS + 1)
                    .startsWith(today).endsWith(today.plusDays(RunType.FORECAST_HORIZON_DAYS));
        }
    }

    @Test
    @DisplayName("Ask is not a model-selection run type: the Models screen's configurable set has no ASK entry, "
            + "because the model is the photocast.ask.model property")
    void notConfigurableOnTheModelsScreen() {
        ModelSelectionService service = new ModelSelectionService(mock(ModelSelectionRepository.class));

        assertThat(service.getAllConfigs()).doesNotContainKeys(RunType.ASK, RunType.ASK_READY);
        assertThat(service.getAllExtendedThinkingConfigs()).doesNotContainKeys(RunType.ASK, RunType.ASK_READY);
    }
}
