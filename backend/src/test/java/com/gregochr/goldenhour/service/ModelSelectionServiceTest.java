package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelSelectionEntity;
import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("getActiveModel() returns HAIKU when no selection exists")
    void getActiveModel_noSelection_returnsHaiku() {
        when(modelSelectionRepository.findFirstByOrderByUpdatedAtDesc())
                .thenReturn(Optional.empty());

        EvaluationModel active = modelSelectionService.getActiveModel();

        assertThat(active).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("getActiveModel() returns stored active model")
    void getActiveModel_selectionExists_returnsSonnet() {
        ModelSelectionEntity selection = ModelSelectionEntity.builder()
                .id(1L)
                .activeModel(EvaluationModel.SONNET)
                .updatedAt(LocalDateTime.now())
                .build();
        when(modelSelectionRepository.findFirstByOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(selection));

        EvaluationModel active = modelSelectionService.getActiveModel();

        assertThat(active).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("setActiveModel() deletes old selection and creates new one")
    void setActiveModel_createsNewSelection() {
        modelSelectionService.setActiveModel(EvaluationModel.SONNET);

        verify(modelSelectionRepository).deleteAll();
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());

        ModelSelectionEntity saved = captor.getValue();
        assertThat(saved.getActiveModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("setActiveModel() returns the newly set model")
    void setActiveModel_returnsNewModel() {
        EvaluationModel result = modelSelectionService.setActiveModel(EvaluationModel.HAIKU);

        assertThat(result).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("setActiveModel() can switch from SONNET to HAIKU")
    void setActiveModel_switchesFromSonnetToHaiku() {
        EvaluationModel result = modelSelectionService.setActiveModel(EvaluationModel.HAIKU);

        verify(modelSelectionRepository).deleteAll();
        verify(modelSelectionRepository).save(any(ModelSelectionEntity.class));
        assertThat(result).isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("setActiveModel() can switch from HAIKU to SONNET")
    void setActiveModel_switchesFromHaikuToSonnet() {
        EvaluationModel result = modelSelectionService.setActiveModel(EvaluationModel.SONNET);

        verify(modelSelectionRepository).deleteAll();
        verify(modelSelectionRepository).save(any(ModelSelectionEntity.class));
        assertThat(result).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("getActiveModel() uses latest selection when multiple exist")
    void getActiveModel_multipleSelections_usesLatest() {
        LocalDateTime now = LocalDateTime.now();
        ModelSelectionEntity latest = ModelSelectionEntity.builder()
                .id(2L)
                .activeModel(EvaluationModel.SONNET)
                .updatedAt(now)
                .build();
        when(modelSelectionRepository.findFirstByOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(latest));

        EvaluationModel active = modelSelectionService.getActiveModel();

        assertThat(active).isEqualTo(EvaluationModel.SONNET);
    }
}
