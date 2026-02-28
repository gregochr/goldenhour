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
import java.util.EnumMap;
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
        RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM
    };

    private final ModelSelectionRepository modelSelectionRepository;

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
     * Get the active model using the default run type (SHORT_TERM).
     * Kept for backward compatibility with scheduled jobs.
     *
     * @return the active evaluation model for the SHORT_TERM config
     */
    @Transactional(readOnly = true)
    public EvaluationModel getActiveModel() {
        return getActiveModel(RunType.SHORT_TERM);
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
     * Set the active model using the default run type (SHORT_TERM).
     * Kept for backward compatibility.
     *
     * @param model the evaluation model to activate
     * @return the newly activated model
     */
    @Transactional
    public EvaluationModel setActiveModel(EvaluationModel model) {
        return setActiveModel(RunType.SHORT_TERM, model);
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
}
