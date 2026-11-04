package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.ModelSelectionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stands in for the {@code ASK} and {@code ASK_READY} {@code model_selection} rows in Ask tests that do
 * not care which model is selected (production seeds both HAIKU, V218).
 */
final class AskModelFixtures {

    private AskModelFixtures() {
    }

    /**
     * A model selection that answers every run type with one model.
     *
     * @param model the model every lookup returns
     * @return a stubbed service
     */
    static ModelSelectionService fixed(EvaluationModel model) {
        ModelSelectionService selection = mock(ModelSelectionService.class);
        when(selection.getActiveModel(any(RunType.class))).thenReturn(model);
        return selection;
    }

    /**
     * A model selection on Haiku, the seeded value.
     *
     * @return a stubbed service
     */
    static ModelSelectionService haiku() {
        return fixed(EvaluationModel.HAIKU);
    }
}
