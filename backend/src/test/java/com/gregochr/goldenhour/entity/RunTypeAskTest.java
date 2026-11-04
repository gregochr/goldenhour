package com.gregochr.goldenhour.entity;

import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Ask is a model-selection run type: the Models screen's configurable set has ASK and "
            + "ASK_READY entries, because the model is their model_selection rows (V218)")
    void configurableOnTheModelsScreen() {
        ModelSelectionService service = new ModelSelectionService(mock(ModelSelectionRepository.class));

        assertThat(service.getAllConfigs()).containsKeys(RunType.ASK, RunType.ASK_READY);
        assertThat(service.getAllExtendedThinkingConfigs()).containsKeys(RunType.ASK, RunType.ASK_READY);
    }
}
