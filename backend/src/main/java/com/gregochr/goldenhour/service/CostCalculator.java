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
     * <p>For Anthropic calls, the cost depends on the evaluation model (Haiku, Sonnet, or Opus).
     * For other services, the cost is fixed per service.
     *
     * @param service the external service (ANTHROPIC, WORLD_TIDES, OPEN_METEO_*, etc.)
     * @param model   the evaluation model for Anthropic calls (HAIKU, SONNET, or OPUS), or null
     * @return cost in pence (e.g., 75 for Opus, 13 for Sonnet, 5 for Haiku, 0 for OpenMeteo)
     */
    public int calculateCost(ServiceName service, EvaluationModel model) {
        return switch (service) {
            case ANTHROPIC -> {
                if (model == null) {
                    yield costProperties.getAnthropicSonnetPence();
                }
                yield switch (model) {
                        case HAIKU -> costProperties.getAnthropicHaikuPence();
                        case OPUS -> costProperties.getAnthropicOpusPence();
                        default -> costProperties.getAnthropicSonnetPence();
                    };
            }
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
