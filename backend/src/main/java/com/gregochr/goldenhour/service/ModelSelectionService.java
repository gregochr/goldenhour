package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelSelectionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the active evaluation model for each forecast run type.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSelectionService {

    /** The run types that support model configuration. */
    private static final RunType[] CONFIGURABLE_RUN_TYPES = {
        RunType.BRIEFING_BEST_BET, RunType.BRIEFING_GLOSS,
        RunType.AURORA_EVALUATION, RunType.AURORA_GLOSS, RunType.BLUEBELL_GLOSS,
        RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM,
        RunType.ASK, RunType.ASK_READY
    };

    /**
     * The models Ask's engine runs on: it forces {@code tool_choice} on its last turn, which Sonnet 5.5
     * (adaptive thinking it cannot switch off) rejects, and Opus is not priced for it.
     */
    private static final List<EvaluationModel> ASK_MODELS =
            List.of(EvaluationModel.HAIKU, EvaluationModel.HAIKU_55, EvaluationModel.SONNET);

    private final ModelSelectionRepository modelSelectionRepository;

    /**
     * Whether a run type is one the Run Config screen manages ({@code CONFIGURABLE_RUN_TYPES}).
     *
     * @param runType the run type to test
     * @return {@code true} when a model can be chosen for it through the API
     */
    public static boolean isConfigurable(RunType runType) {
        return Arrays.asList(CONFIGURABLE_RUN_TYPES).contains(runType);
    }

    /**
     * The run types the Run Config screen manages.
     *
     * @return the configurable run types, in screen order
     */
    public static List<RunType> configurableRunTypes() {
        return List.of(CONFIGURABLE_RUN_TYPES);
    }

    /**
     * The models an admin may choose for a run type: the engine's own set for Ask, every
     * {@link EvaluationModel#selectable() selectable} model for the others.
     *
     * @param runType the run type
     * @return the allowed models in declaration order; never null
     */
    public static List<EvaluationModel> allowedModels(RunType runType) {
        if (runType == RunType.ASK || runType == RunType.ASK_READY) {
            return ASK_MODELS;
        }
        return EvaluationModel.selectable();
    }

    /**
     * Get the active model for a specific run type.
     * Defaults to HAIKU if no selection exists for that run type.
     *
     * @param runType which run type to look up
     * @return the active evaluation model for that run type
     */
    @Transactional(readOnly = true)
    public EvaluationModel getActiveModel(RunType runType) {
        return modelSelectionRepository
                .findByRunType(runType)
                .map(ModelSelectionEntity::getActiveModel)
                .orElse(EvaluationModel.HAIKU);
    }

    /**
     * Set the active model for a specific run type.
     *
     * @param runType which run type to update
     * @param model   the evaluation model to activate
     * @return the newly activated model
     */
    @Transactional
    public EvaluationModel setActiveModel(RunType runType, EvaluationModel model) {
        ModelSelectionEntity selection = modelSelectionRepository
                .findByRunType(runType)
                .orElse(ModelSelectionEntity.builder().runType(runType).build());

        selection.setActiveModel(model);
        selection.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        modelSelectionRepository.save(selection);

        log.info("Active evaluation model for {} set to: {}", runType, model);
        return model;
    }

    /**
     * Get all model configurations as a map from run type to active model.
     *
     * @return map of configurable run type to active evaluation model
     */
    @Transactional(readOnly = true)
    public Map<RunType, EvaluationModel> getAllConfigs() {
        Map<RunType, EvaluationModel> configs = new EnumMap<>(RunType.class);
        for (RunType type : CONFIGURABLE_RUN_TYPES) {
            configs.put(type, getActiveModel(type));
        }
        return configs;
    }

    /**
     * Return whether extended thinking is enabled for a specific run type.
     * Defaults to {@code false} if no row exists.
     *
     * @param runType which run type to look up
     * @return {@code true} when extended thinking is enabled for that run type
     */
    @Transactional(readOnly = true)
    public boolean isExtendedThinking(RunType runType) {
        return modelSelectionRepository
                .findByRunType(runType)
                .map(ModelSelectionEntity::isExtendedThinking)
                .orElse(false);
    }

    /**
     * Enable or disable extended thinking for a specific run type.
     * Creates the row (defaulting to HAIKU) if it does not yet exist.
     *
     * @param runType which run type to update
     * @param enabled whether to enable extended thinking
     * @return the new extended-thinking state
     */
    @Transactional
    public boolean setExtendedThinking(RunType runType, boolean enabled) {
        ModelSelectionEntity selection = modelSelectionRepository
                .findByRunType(runType)
                .orElse(ModelSelectionEntity.builder()
                        .runType(runType)
                        .activeModel(EvaluationModel.HAIKU)
                        .build());
        selection.setExtendedThinking(enabled);
        selection.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        modelSelectionRepository.save(selection);
        log.info("Extended thinking for {} set to: {}", runType, enabled);
        return enabled;
    }

    /**
     * Get extended-thinking flags for all configurable run types.
     *
     * @return map of configurable run type to extended-thinking enabled flag
     */
    @Transactional(readOnly = true)
    public Map<RunType, Boolean> getAllExtendedThinkingConfigs() {
        Map<RunType, Boolean> configs = new EnumMap<>(RunType.class);
        for (RunType type : CONFIGURABLE_RUN_TYPES) {
            configs.put(type, isExtendedThinking(type));
        }
        return configs;
    }
}
