package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;

/**
 * Whether an admin simulation is switched on: the one definition Ask uses (plan §1 #21). While a
 * hot-topic or aurora simulation is active the served data is not real, so Ready precompute is
 * skipped and no typed answer is cached — both ask this and nothing else.
 */
final class AskSimulation {

    private AskSimulation() {
    }

    /**
     * Whether a simulation is active right now.
     *
     * @param hotTopics the hot-topic simulation switch (global)
     * @param aurora    the aurora state, whose simulated data marks an aurora simulation
     * @return true when either simulation is on
     */
    static boolean active(HotTopicSimulationService hotTopics, AuroraStateCache aurora) {
        return hotTopics.isEnabled() || aurora.getSimulatedData() != null;
    }
}
