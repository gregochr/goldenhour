package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
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
 * API endpoints for per-run-type model selection and availability.
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelsController {

    private final ModelSelectionService modelSelectionService;

    /**
     * Get available evaluation models and the active model for each run type.
     *
     * @return response containing available models and per-run-type active models
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        EvaluationModel[] selectableModels = {
            EvaluationModel.HAIKU, EvaluationModel.SONNET, EvaluationModel.OPUS
        };
        return ResponseEntity.ok(Map.of(
                "available", selectableModels,
                "configs", modelSelectionService.getAllConfigs()
        ));
    }

    /**
     * Set the active evaluation model for a specific run type (ADMIN only).
     *
     * <p>Accepts either "runType" or legacy "configType" in the request body.
     *
     * @param request body containing "runType" (or "configType") and "model" fields
     * @return response containing the updated run type and model
     */
    @PutMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> setActiveModel(
            @RequestBody Map<String, String> request) {
        String modelName = request.get("model");
        // Accept both "runType" and legacy "configType"
        String runTypeName = request.getOrDefault("runType",
                request.get("configType"));
        EvaluationModel model = EvaluationModel.valueOf(modelName.toUpperCase());
        RunType runType = RunType.valueOf(runTypeName.toUpperCase());
        EvaluationModel active = modelSelectionService.setActiveModel(runType, model);
        return ResponseEntity.ok(Map.of("runType", runType, "active", active));
    }
}
