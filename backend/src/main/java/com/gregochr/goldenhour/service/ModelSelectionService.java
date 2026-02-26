package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ModelSelectionEntity;
import com.gregochr.goldenhour.repository.ModelSelectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Manages the currently active evaluation model for forecast runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSelectionService {

    private final ModelSelectionRepository modelSelectionRepository;

    /**
     * Get the currently active evaluation model.
     * Defaults to HAIKU if no selection exists.
     *
     * @return the active evaluation model (HAIKU or SONNET)
     */
    @Transactional(readOnly = true)
    public EvaluationModel getActiveModel() {
        return modelSelectionRepository
                .findFirstByOrderByUpdatedAtDesc()
                .map(ModelSelectionEntity::getActiveModel)
                .orElse(EvaluationModel.HAIKU);
    }

    /**
     * Set the active evaluation model.
     * Updates the timestamp so getActiveModel() returns the latest.
     *
     * @param model the evaluation model to activate (HAIKU or SONNET)
     * @return the newly activated model
     */
    @Transactional
    public EvaluationModel setActiveModel(EvaluationModel model) {
        // Delete old selection(s) and create new one (singleton pattern)
        modelSelectionRepository.deleteAll();

        ModelSelectionEntity selection = ModelSelectionEntity.builder()
                .activeModel(model)
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        modelSelectionRepository.save(selection);
        log.info("Active evaluation model set to: {}", model);
        return model;
    }
}
