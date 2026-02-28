package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelSelectionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelSelectionService}.
 */
@ExtendWith(MockitoExtension.class)
class ModelSelectionServiceTest {

    @Mock
    private ModelSelectionRepository modelSelectionRepository;

    private ModelSelectionService modelSelectionService;

    @BeforeEach
    void setUp() {
        modelSelectionService = new ModelSelectionService(modelSelectionRepository);
    }

    @Test
    @DisplayName("getActiveModel(runType) returns HAIKU when no selection exists")
    void getActiveModel_noSelection_returnsHaiku() {
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.empty());

        EvaluationModel active = modelSelectionService.getActiveModel(RunType.SHORT_TERM);

        assertThat(active).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("getActiveModel(runType) returns stored active model")
    void getActiveModel_selectionExists_returnsSonnet() {
        ModelSelectionEntity selection = ModelSelectionEntity.builder()
                .id(1L)
                .runType(RunType.SHORT_TERM)
                .activeModel(EvaluationModel.SONNET)
                .build();
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.of(selection));

        EvaluationModel active = modelSelectionService.getActiveModel(RunType.SHORT_TERM);

        assertThat(active).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("getActiveModel() no-arg delegates to SHORT_TERM config")
    void getActiveModel_noArg_delegatesToShortTerm() {
        ModelSelectionEntity selection = ModelSelectionEntity.builder()
                .id(1L)
                .runType(RunType.SHORT_TERM)
                .activeModel(EvaluationModel.OPUS)
                .build();
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.of(selection));

        EvaluationModel active = modelSelectionService.getActiveModel();

        assertThat(active).isEqualTo(EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("setActiveModel(runType, model) upserts existing row")
    void setActiveModel_existingRow_updatesIt() {
        ModelSelectionEntity existing = ModelSelectionEntity.builder()
                .id(1L)
                .runType(RunType.VERY_SHORT_TERM)
                .activeModel(EvaluationModel.HAIKU)
                .build();
        when(modelSelectionRepository.findByRunType(RunType.VERY_SHORT_TERM))
                .thenReturn(Optional.of(existing));

        EvaluationModel result = modelSelectionService.setActiveModel(
                RunType.VERY_SHORT_TERM, EvaluationModel.SONNET);

        assertThat(result).isEqualTo(EvaluationModel.SONNET);
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        ModelSelectionEntity saved = captor.getValue();
        assertThat(saved.getActiveModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(saved.getRunType()).isEqualTo(RunType.VERY_SHORT_TERM);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("setActiveModel(runType, model) creates new row when none exists")
    void setActiveModel_noExistingRow_createsNew() {
        when(modelSelectionRepository.findByRunType(RunType.LONG_TERM))
                .thenReturn(Optional.empty());

        EvaluationModel result = modelSelectionService.setActiveModel(
                RunType.LONG_TERM, EvaluationModel.OPUS);

        assertThat(result).isEqualTo(EvaluationModel.OPUS);
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        ModelSelectionEntity saved = captor.getValue();
        assertThat(saved.getActiveModel()).isEqualTo(EvaluationModel.OPUS);
        assertThat(saved.getRunType()).isEqualTo(RunType.LONG_TERM);
    }

    @Test
    @DisplayName("setActiveModel(model) no-arg delegates to SHORT_TERM")
    void setActiveModel_noArg_delegatesToShortTerm() {
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.empty());

        EvaluationModel result = modelSelectionService.setActiveModel(EvaluationModel.SONNET);

        assertThat(result).isEqualTo(EvaluationModel.SONNET);
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        assertThat(captor.getValue().getRunType()).isEqualTo(RunType.SHORT_TERM);
    }

    @Test
    @DisplayName("getAllConfigs() returns a model for each configurable run type")
    void getAllConfigs_returnsAllTypes() {
        when(modelSelectionRepository.findByRunType(RunType.VERY_SHORT_TERM))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.VERY_SHORT_TERM)
                        .activeModel(EvaluationModel.OPUS).build()));
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.SHORT_TERM)
                        .activeModel(EvaluationModel.SONNET).build()));
        when(modelSelectionRepository.findByRunType(RunType.LONG_TERM))
                .thenReturn(Optional.empty()); // defaults to HAIKU

        Map<RunType, EvaluationModel> configs = modelSelectionService.getAllConfigs();

        assertThat(configs).hasSize(3);
        assertThat(configs.get(RunType.VERY_SHORT_TERM)).isEqualTo(EvaluationModel.OPUS);
        assertThat(configs.get(RunType.SHORT_TERM)).isEqualTo(EvaluationModel.SONNET);
        assertThat(configs.get(RunType.LONG_TERM)).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("Different run types are independent")
    void differentRunTypes_areIndependent() {
        when(modelSelectionRepository.findByRunType(RunType.VERY_SHORT_TERM))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.VERY_SHORT_TERM)
                        .activeModel(EvaluationModel.OPUS).build()));
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.SHORT_TERM)
                        .activeModel(EvaluationModel.HAIKU).build()));

        assertThat(modelSelectionService.getActiveModel(RunType.VERY_SHORT_TERM))
                .isEqualTo(EvaluationModel.OPUS);
        assertThat(modelSelectionService.getActiveModel(RunType.SHORT_TERM))
                .isEqualTo(EvaluationModel.HAIKU);
    }
}
