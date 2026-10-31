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

import java.util.List;
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
     * Get available evaluation models, per-run-type active models, and extended-thinking flags (ADMIN only).
     *
     * @return response containing available models, configs, and extended-thinking config
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        List<Map<String, String>> availableWithVersions = EvaluationModel.selectable().stream()
                .map(m -> Map.of("name", m.name(), "version", m.getVersion()))
                .toList();
        return ResponseEntity.ok(Map.of(
                "available", availableWithVersions,
                "configs", modelSelectionService.getAllConfigs(),
                "extendedThinkingConfigs", modelSelectionService.getAllExtendedThinkingConfigs()
        ));
    }

    /**
     * Set the active evaluation model for a specific run type (ADMIN only).
     *
     * <p>Accepts either "runType" or legacy "configType" in the request body. A run type that is
     * not one of {@link ModelSelectionService#isConfigurable the configurable run types} is refused
     * with 400, so a retired run type cannot be given a model row through this endpoint.
     *
     * @param request body containing "runType" (or "configType") and "model" fields
     * @return response containing the updated run type and model
     * @throws IllegalArgumentException if the model or run type is unknown, the model is not
     *                                  {@link EvaluationModel#selectable() selectable}, or the run
     *                                  type is not configurable (mapped to 400)
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
        if (!model.isSelectable()) {
            throw new IllegalArgumentException("Model " + model + " is not selectable");
        }
        if (!ModelSelectionService.isConfigurable(runType)) {
            throw new IllegalArgumentException("Run type " + runType + " has no configurable model");
        }
        EvaluationModel active = modelSelectionService.setActiveModel(runType, model);
        return ResponseEntity.ok(Map.of("runType", runType, "active", active));
    }

    /**
     * Enable or disable extended thinking for a specific run type (ADMIN only).
     *
     * <p>Extended thinking adds a {@code ThinkingConfigAdaptive} block to the Claude API call,
     * allowing Claude to reason internally before responding. Currently surfaced in the UI
     * for {@code BRIEFING_BEST_BET} only; silently ignored for HAIKU (which does not
     * support extended thinking).
     *
     * @param request body containing {@code "runType"} and {@code "enabled"} (boolean) fields
     * @return response containing the updated {@code runType} and {@code extendedThinking} state
     */
    @PutMapping("/extended-thinking")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> setExtendedThinking(
            @RequestBody Map<String, Object> request) {
        String runTypeName = (String) request.get("runType");
        boolean enabled = Boolean.TRUE.equals(request.get("enabled"));
        RunType runType = RunType.valueOf(runTypeName.toUpperCase());
        boolean result = modelSelectionService.setExtendedThinking(runType, enabled);
        return ResponseEntity.ok(Map.of("runType", runType, "extendedThinking", result));
    }
}
