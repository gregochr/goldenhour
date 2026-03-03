package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;

/**
 * Request payload for updating an optimisation strategy toggle.
 *
 * @param runType      the run type to update
 * @param strategyType the strategy to toggle
 * @param enabled      whether the strategy should be enabled
 * @param paramValue   optional integer parameter (e.g. min rating threshold), nullable
 */
public record OptimisationStrategyUpdateRequest(
        RunType runType,
        OptimisationStrategyType strategyType,
        boolean enabled,
        Integer paramValue
) {
}
