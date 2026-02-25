package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.CostProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ServiceName;
import org.springframework.stereotype.Service;

/**
 * Calculates operational costs for external API calls.
 *
 * <p>Used by {@code JobRunService} to track the cost of each API call and aggregate
 * total costs per job run for monitoring and reporting.
 */
@Service
public class CostCalculator {

    private final CostProperties costProperties;

    /**
     * Constructs a {@code CostCalculator}.
     *
     * @param costProperties cost configuration from {@code application.yml}
     */
    public CostCalculator(CostProperties costProperties) {
        this.costProperties = costProperties;
    }

    /**
     * Calculates the cost of a single API call in pence.
     *
     * <p>For Anthropic calls, the cost depends on the evaluation model (Haiku vs Sonnet).
     * For other services, the cost is fixed per service.
     *
     * @param service the external service (ANTHROPIC, WORLD_TIDES, OPEN_METEO_*, etc.)
     * @param model   the evaluation model for Anthropic calls (HAIKU or SONNET), or null for non-Anthropic
     * @return cost in pence (e.g., 130 for Sonnet, 20 for WorldTides, 0 for OpenMeteo)
     */
    public int calculateCost(ServiceName service, EvaluationModel model) {
        return switch (service) {
            case ANTHROPIC -> model == EvaluationModel.HAIKU
                    ? costProperties.getAnthropicHaikuPence()
                    : costProperties.getAnthropicSonnetPence();
            case WORLD_TIDES -> costProperties.getWorldTidesPence();
            case OPEN_METEO_FORECAST, OPEN_METEO_AIR_QUALITY -> costProperties.getOpenMeteoPence();
        };
    }

    /**
     * Calculates the cost of a single API call without an evaluation model (non-Anthropic).
     *
     * @param service the external service
     * @return cost in pence
     */
    public int calculateCost(ServiceName service) {
        return calculateCost(service, null);
    }
}
