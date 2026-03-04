package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.OptimisationStrategyUpdateRequest;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OptimisationStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * API endpoints for per-run-type model selection, availability, and optimisation strategies.
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelsController {

    private final ModelSelectionService modelSelectionService;
    private final OptimisationStrategyService optimisationStrategyService;

    /**
     * Get available evaluation models, per-run-type active models, and optimisation strategies.
     *
     * @return response containing available models, configs, and optimisation strategies
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        EvaluationModel[] selectableModels = {
            EvaluationModel.HAIKU, EvaluationModel.SONNET, EvaluationModel.OPUS
        };
        List<Map<String, String>> availableWithVersions = Arrays.stream(selectableModels)
                .map(m -> Map.of("name", m.name(), "version", m.getVersion()))
                .toList();
        return ResponseEntity.ok(Map.of(
                "available", availableWithVersions,
                "configs", modelSelectionService.getAllConfigs(),
                "optimisationStrategies", optimisationStrategyService.getAllConfigs()
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

    /**
     * Toggle an optimisation strategy for a specific run type (ADMIN only).
     *
     * @param request the strategy update request
     * @return response with the updated strategy state
     */
    @PutMapping("/optimisation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateOptimisationStrategy(
            @RequestBody OptimisationStrategyUpdateRequest request) {
        var updated = optimisationStrategyService.updateStrategy(
                request.runType(), request.strategyType(),
                request.enabled(), request.paramValue());
        return ResponseEntity.ok(Map.of(
                "runType", updated.getRunType(),
                "strategyType", updated.getStrategyType(),
                "enabled", updated.isEnabled(),
                "paramValue", updated.getParamValue() != null ? updated.getParamValue() : ""
        ));
    }
}
