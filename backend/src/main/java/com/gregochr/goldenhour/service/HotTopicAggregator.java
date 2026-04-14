package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Collects hot topics from all registered {@link HotTopicDetector} beans,
 * sorts by priority, and returns the final list for the API response.
 *
 * <p>Spring auto-collects all {@link HotTopicDetector} implementations
 * via constructor injection. The aggregated list is sorted using the natural
 * ordering defined on {@link HotTopic} (priority ascending, date ascending).
 *
 * <p>When {@link HotTopicSimulationService} is enabled the real detectors are
 * bypassed and simulated topics are returned instead — for admin demos and UI testing.
 */
@Service
public class HotTopicAggregator {

    private final List<HotTopicDetector> detectors;
    private final HotTopicSimulationService simulationService;

    /**
     * Constructs a {@code HotTopicAggregator} with all registered detectors.
     *
     * @param detectors         all {@link HotTopicDetector} beans in the application context
     * @param simulationService admin simulation override service
     */
    public HotTopicAggregator(List<HotTopicDetector> detectors,
                               HotTopicSimulationService simulationService) {
        this.detectors = detectors;
        this.simulationService = simulationService;
    }

    /**
     * Returns hot topics for the given date range.
     *
     * <p>When simulation is active, returns simulated topics from
     * {@link HotTopicSimulationService} without invoking any real detectors.
     * Otherwise aggregates from all registered detectors sorted by priority.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return sorted list of hot topics; never null
     */
    public List<HotTopic> getHotTopics(LocalDate fromDate, LocalDate toDate) {
        if (simulationService.isEnabled()) {
            return simulationService.getSimulatedTopics(fromDate, toDate);
        }
        return detectors.stream()
                .flatMap(d -> d.detect(fromDate, toDate).stream())
                .sorted()
                .toList();
    }
}
