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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.empty()); // defaults to HAIKU
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_GLOSS))
                .thenReturn(Optional.empty()); // defaults to HAIKU
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.AURORA_EVALUATION)
                        .activeModel(EvaluationModel.SONNET).build()));
        when(modelSelectionRepository.findByRunType(RunType.AURORA_GLOSS))
                .thenReturn(Optional.empty()); // defaults to HAIKU
        when(modelSelectionRepository.findByRunType(RunType.SCHEDULED_BATCH))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.SCHEDULED_BATCH)
                        .activeModel(EvaluationModel.SONNET).build()));

        Map<RunType, EvaluationModel> configs = modelSelectionService.getAllConfigs();

        assertThat(configs).hasSize(8);
        assertThat(configs.get(RunType.VERY_SHORT_TERM)).isEqualTo(EvaluationModel.OPUS);
        assertThat(configs.get(RunType.SHORT_TERM)).isEqualTo(EvaluationModel.SONNET);
        assertThat(configs.get(RunType.LONG_TERM)).isEqualTo(EvaluationModel.HAIKU);
        assertThat(configs.get(RunType.BRIEFING_BEST_BET)).isEqualTo(EvaluationModel.HAIKU);
        assertThat(configs.get(RunType.BRIEFING_GLOSS)).isEqualTo(EvaluationModel.HAIKU);
        assertThat(configs.get(RunType.AURORA_EVALUATION)).isEqualTo(EvaluationModel.SONNET);
        assertThat(configs.get(RunType.AURORA_GLOSS)).isEqualTo(EvaluationModel.HAIKU);
        assertThat(configs.get(RunType.SCHEDULED_BATCH)).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("getActiveModel(BRIEFING_BEST_BET) returns stored model")
    void getActiveModel_briefingBestBet_returnsStoredModel() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.BRIEFING_BEST_BET)
                        .activeModel(EvaluationModel.OPUS).build()));

        assertThat(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                .isEqualTo(EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("getActiveModel(AURORA_EVALUATION) returns stored model")
    void getActiveModel_auroraEvaluation_returnsStoredModel() {
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.AURORA_EVALUATION)
                        .activeModel(EvaluationModel.SONNET).build()));

        assertThat(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("getActiveModel(BRIEFING_BEST_BET) defaults to HAIKU when no selection")
    void getActiveModel_briefingBestBet_defaultsToHaiku() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.empty());

        assertThat(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                .isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("getActiveModel(AURORA_EVALUATION) defaults to HAIKU when no selection")
    void getActiveModel_auroraEvaluation_defaultsToHaiku() {
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.empty());

        assertThat(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .isEqualTo(EvaluationModel.HAIKU);
    }

    @Test
    @DisplayName("setActiveModel(BRIEFING_BEST_BET, SONNET) upserts correctly")
    void setActiveModel_briefingBestBet_upserts() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.empty());

        EvaluationModel result = modelSelectionService.setActiveModel(
                RunType.BRIEFING_BEST_BET, EvaluationModel.SONNET);

        assertThat(result).isEqualTo(EvaluationModel.SONNET);
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        assertThat(captor.getValue().getRunType()).isEqualTo(RunType.BRIEFING_BEST_BET);
        assertThat(captor.getValue().getActiveModel()).isEqualTo(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("setActiveModel(AURORA_EVALUATION, OPUS) upserts correctly")
    void setActiveModel_auroraEvaluation_upserts() {
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.empty());

        EvaluationModel result = modelSelectionService.setActiveModel(
                RunType.AURORA_EVALUATION, EvaluationModel.OPUS);

        assertThat(result).isEqualTo(EvaluationModel.OPUS);
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        assertThat(captor.getValue().getRunType()).isEqualTo(RunType.AURORA_EVALUATION);
        assertThat(captor.getValue().getActiveModel()).isEqualTo(EvaluationModel.OPUS);
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

    // ── Extended thinking flag ──

    @Test
    @DisplayName("isExtendedThinking returns false when no row exists")
    void isExtendedThinking_noRow_returnsFalse() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.empty());

        assertThat(modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)).isFalse();
    }

    @Test
    @DisplayName("isExtendedThinking returns false when row has flag off")
    void isExtendedThinking_flagOff_returnsFalse() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.BRIEFING_BEST_BET)
                        .activeModel(EvaluationModel.SONNET)
                        .extendedThinking(false)
                        .build()));

        assertThat(modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)).isFalse();
    }

    @Test
    @DisplayName("isExtendedThinking returns true when row has flag on")
    void isExtendedThinking_flagOn_returnsTrue() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.BRIEFING_BEST_BET)
                        .activeModel(EvaluationModel.SONNET)
                        .extendedThinking(true)
                        .build()));

        assertThat(modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)).isTrue();
    }

    @Test
    @DisplayName("setExtendedThinking(BRIEFING_BEST_BET, true) saves flag on existing row")
    void setExtendedThinking_existingRow_savesFlag() {
        ModelSelectionEntity existing = ModelSelectionEntity.builder()
                .id(1L)
                .runType(RunType.BRIEFING_BEST_BET)
                .activeModel(EvaluationModel.SONNET)
                .extendedThinking(false)
                .build();
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(existing));

        boolean result = modelSelectionService.setExtendedThinking(RunType.BRIEFING_BEST_BET, true);

        assertThat(result).isTrue();
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        ModelSelectionEntity saved = captor.getValue();
        assertThat(saved.isExtendedThinking()).isTrue();
        assertThat(saved.getRunType()).isEqualTo(RunType.BRIEFING_BEST_BET);
        assertThat(saved.getActiveModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(saved.getUpdatedAt())
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("setExtendedThinking creates new row with HAIKU default when none exists")
    void setExtendedThinking_noRow_createsWithHaikuDefault() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.empty());

        boolean result = modelSelectionService.setExtendedThinking(RunType.BRIEFING_BEST_BET, true);

        assertThat(result).isTrue();
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        ModelSelectionEntity saved = captor.getValue();
        assertThat(saved.isExtendedThinking()).isTrue();
        assertThat(saved.getActiveModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(saved.getRunType()).isEqualTo(RunType.BRIEFING_BEST_BET);
        assertThat(saved.getUpdatedAt())
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("setExtendedThinking(false) clears flag on existing row")
    void setExtendedThinking_existingRow_clearsFlag() {
        ModelSelectionEntity existing = ModelSelectionEntity.builder()
                .id(1L)
                .runType(RunType.BRIEFING_BEST_BET)
                .activeModel(EvaluationModel.OPUS)
                .extendedThinking(true)
                .build();
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(existing));

        boolean result = modelSelectionService.setExtendedThinking(RunType.BRIEFING_BEST_BET, false);

        assertThat(result).isFalse();
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        assertThat(captor.getValue().isExtendedThinking()).isFalse();
    }

    @Test
    @DisplayName("getAllExtendedThinkingConfigs returns false for all run types by default")
    void getAllExtendedThinkingConfigs_allFalseByDefault() {
        when(modelSelectionRepository.findByRunType(RunType.VERY_SHORT_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.LONG_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_GLOSS)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.AURORA_GLOSS)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.SCHEDULED_BATCH)).thenReturn(Optional.empty());

        var result = modelSelectionService.getAllExtendedThinkingConfigs();

        assertThat(result).hasSize(8);
        assertThat(result).allSatisfy((runType, flag) -> assertThat(flag).isFalse());
    }

    @Test
    @DisplayName("getAllExtendedThinkingConfigs returns true for BRIEFING_BEST_BET when set")
    void getAllExtendedThinkingConfigs_briefingEnabled_returnsTrue() {
        when(modelSelectionRepository.findByRunType(RunType.VERY_SHORT_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.SHORT_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.LONG_TERM)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.BRIEFING_BEST_BET)
                        .activeModel(EvaluationModel.SONNET)
                        .extendedThinking(true)
                        .build()));
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_GLOSS)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.AURORA_GLOSS)).thenReturn(Optional.empty());
        when(modelSelectionRepository.findByRunType(RunType.SCHEDULED_BATCH)).thenReturn(Optional.empty());

        var result = modelSelectionService.getAllExtendedThinkingConfigs();

        assertThat(result.get(RunType.BRIEFING_BEST_BET)).isTrue();
        assertThat(result.get(RunType.VERY_SHORT_TERM)).isFalse();
        assertThat(result.get(RunType.AURORA_EVALUATION)).isFalse();
    }

    @Test
    @DisplayName("setExtendedThinking works for AURORA_EVALUATION (not hardcoded to BRIEFING_BEST_BET)")
    void setExtendedThinking_auroraEvaluation_savesCorrectRunType() {
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.empty());

        boolean result = modelSelectionService.setExtendedThinking(RunType.AURORA_EVALUATION, true);

        assertThat(result).isTrue();
        ArgumentCaptor<ModelSelectionEntity> captor = ArgumentCaptor.forClass(ModelSelectionEntity.class);
        verify(modelSelectionRepository).save(captor.capture());
        assertThat(captor.getValue().getRunType()).isEqualTo(RunType.AURORA_EVALUATION);
        assertThat(captor.getValue().isExtendedThinking()).isTrue();
    }

    @Test
    @DisplayName("isExtendedThinking for two run types independently respects each row")
    void isExtendedThinking_twoRunTypesAreIndependent() {
        when(modelSelectionRepository.findByRunType(RunType.BRIEFING_BEST_BET))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.BRIEFING_BEST_BET)
                        .activeModel(EvaluationModel.SONNET)
                        .extendedThinking(true)
                        .build()));
        when(modelSelectionRepository.findByRunType(RunType.AURORA_EVALUATION))
                .thenReturn(Optional.of(ModelSelectionEntity.builder()
                        .runType(RunType.AURORA_EVALUATION)
                        .activeModel(EvaluationModel.HAIKU)
                        .extendedThinking(false)
                        .build()));

        assertThat(modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)).isTrue();
        assertThat(modelSelectionService.isExtendedThinking(RunType.AURORA_EVALUATION)).isFalse();
    }
}
