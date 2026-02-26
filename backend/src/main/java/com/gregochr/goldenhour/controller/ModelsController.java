package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.service.ModelSelectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API endpoints for model selection and availability.
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelsController {

    private final ModelSelectionService modelSelectionService;

    /**
     * Get available evaluation models and the currently active one.
     *
     * @return response containing available models and the active model
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        EvaluationModel activeModel = modelSelectionService.getActiveModel();
        // Only expose HAIKU and SONNET for user selection
        // WILDLIFE is handled automatically for pure-wildlife locations
        EvaluationModel[] selectableModels = { EvaluationModel.HAIKU, EvaluationModel.SONNET };
        return ResponseEntity.ok(Map.of(
                "available", selectableModels,
                "active", activeModel
        ));
    }

    /**
     * Set the active evaluation model (ADMIN only).
     *
     * @param request body containing "model" field with model name (HAIKU or SONNET)
     * @return response containing the newly activated model
     */
    @PutMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> setActiveModel(
            @RequestBody Map<String, String> request) {
        String modelName = request.get("model");
        EvaluationModel model = EvaluationModel.valueOf(modelName.toUpperCase());
        EvaluationModel active = modelSelectionService.setActiveModel(model);
        return ResponseEntity.ok(Map.of("active", active));
    }
}
