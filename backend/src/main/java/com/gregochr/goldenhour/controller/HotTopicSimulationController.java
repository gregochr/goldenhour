package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.HotTopicSimulationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Admin-only REST controller for the Hot Topics simulation feature.
 *
 * <p>Allows admins to enable/disable simulation mode and toggle individual
 * topic types on or off. When simulation is enabled, {@link
 * com.gregochr.goldenhour.service.HotTopicAggregator} returns simulated topics
 * instead of running real detectors — useful for UI testing and demos.
 */
@RestController
@RequestMapping("/api/admin/hot-topics/simulation")
@PreAuthorize("hasRole('ADMIN')")
public class HotTopicSimulationController {

    private final HotTopicSimulationService simulationService;

    /**
     * Constructs the controller with the simulation service.
     *
     * @param simulationService the in-memory simulation state service
     */
    public HotTopicSimulationController(HotTopicSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Returns the current simulation state: the master enabled flag plus all
     * simulatable types with their individual active flags.
     *
     * @return current {@link SimulationState}
     */
    @GetMapping
    public SimulationState getState() {
        return new SimulationState(
                simulationService.isEnabled(),
                simulationService.getAllTypes()
        );
    }

    /**
     * Toggles the master simulation on/off switch.
     *
     * @return updated {@link SimulationState} after the toggle
     */
    @PostMapping("/toggle")
    public SimulationState toggleEnabled() {
        simulationService.setEnabled(!simulationService.isEnabled());
        return getState();
    }

    /**
     * Toggles an individual topic type active/inactive.
     *
     * @param type topic type identifier, e.g. "BLUEBELL"
     * @return updated {@link SimulationState} after the toggle
     */
    @PostMapping("/type/{type}/toggle")
    public SimulationState toggleType(@PathVariable String type) {
        Set<String> current = simulationService.getActiveTypes();
        simulationService.setTypeActive(type, !current.contains(type));
        return getState();
    }

    /**
     * Snapshot of simulation state returned by all endpoints.
     *
     * @param enabled whether simulation is currently active
     * @param types   all simulatable types with their active flags
     */
    public record SimulationState(
            boolean enabled,
            List<HotTopicSimulationService.SimulatableType> types) { }
}
