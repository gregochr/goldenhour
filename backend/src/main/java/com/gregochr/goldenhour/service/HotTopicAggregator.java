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
 */
@Service
public class HotTopicAggregator {

    private final List<HotTopicDetector> detectors;

    /**
     * Constructs a {@code HotTopicAggregator} with all registered detectors.
     *
     * @param detectors all {@link HotTopicDetector} beans in the application context
     */
    public HotTopicAggregator(List<HotTopicDetector> detectors) {
        this.detectors = detectors;
    }

    /**
     * Returns hot topics for the given date range, aggregated from all detectors.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return sorted list of hot topics from all detectors; never null
     */
    public List<HotTopic> getHotTopics(LocalDate fromDate, LocalDate toDate) {
        return detectors.stream()
                .flatMap(d -> d.detect(fromDate, toDate).stream())
                .sorted()
                .toList();
    }
}
