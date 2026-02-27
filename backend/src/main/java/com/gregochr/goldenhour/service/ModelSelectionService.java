package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelConfigType;
import com.gregochr.goldenhour.entity.ModelSelectionEntity;
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
 * Manages the active evaluation model for each run type (config type).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSelectionService {

    private final ModelSelectionRepository modelSelectionRepository;

    /**
     * Get the active model for a specific run type.
     * Defaults to HAIKU if no selection exists for that config type.
     *
     * @param configType which run type to look up
     * @return the active evaluation model for that run type
     */
    @Transactional(readOnly = true)
    public EvaluationModel getActiveModel(ModelConfigType configType) {
        return modelSelectionRepository
                .findByConfigType(configType)
                .map(ModelSelectionEntity::getActiveModel)
                .orElse(EvaluationModel.HAIKU);
    }

    /**
     * Get the active model using the default config type (SHORT_TERM).
     * Kept for backward compatibility with scheduled jobs.
     *
     * @return the active evaluation model for the SHORT_TERM config
     */
    @Transactional(readOnly = true)
    public EvaluationModel getActiveModel() {
        return getActiveModel(ModelConfigType.SHORT_TERM);
    }

    /**
     * Set the active model for a specific run type.
     *
     * @param configType which run type to update
     * @param model      the evaluation model to activate
     * @return the newly activated model
     */
    @Transactional
    public EvaluationModel setActiveModel(ModelConfigType configType, EvaluationModel model) {
        ModelSelectionEntity selection = modelSelectionRepository
                .findByConfigType(configType)
                .orElse(ModelSelectionEntity.builder().configType(configType).build());

        selection.setActiveModel(model);
        selection.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        modelSelectionRepository.save(selection);

        log.info("Active evaluation model for {} set to: {}", configType, model);
        return model;
    }

    /**
     * Set the active model using the default config type (SHORT_TERM).
     * Kept for backward compatibility.
     *
     * @param model the evaluation model to activate
     * @return the newly activated model
     */
    @Transactional
    public EvaluationModel setActiveModel(EvaluationModel model) {
        return setActiveModel(ModelConfigType.SHORT_TERM, model);
    }

    /**
     * Get all model configurations as a map from config type to active model.
     *
     * @return map of config type to active evaluation model
     */
    @Transactional(readOnly = true)
    public Map<ModelConfigType, EvaluationModel> getAllConfigs() {
        Map<ModelConfigType, EvaluationModel> configs = new EnumMap<>(ModelConfigType.class);
        for (ModelConfigType type : ModelConfigType.values()) {
            configs.put(type, getActiveModel(type));
        }
        return configs;
    }
}
