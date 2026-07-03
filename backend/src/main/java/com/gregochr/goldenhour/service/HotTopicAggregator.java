package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Collects hot topics from all registered strategies, deduplicates,
 * sorts by priority, and returns the final list for the API response.
 *
 * <p>Spring auto-collects all {@link HotTopicStrategy} implementations
 * via constructor injection. The aggregated list is sorted using the natural
 * ordering defined on {@link HotTopic} (priority ascending, date ascending).
 *
 * <p>When {@link HotTopicSimulationService} is enabled the real strategies are
 * bypassed and simulated topics are returned instead — for admin demos and UI testing.
 */
@Service
public class HotTopicAggregator {

    private final List<HotTopicStrategy> strategies;
    private final HotTopicSimulationService simulationService;
    private final TravelDayService travelDayService;
    private final HotTopicEventEnricher eventEnricher;

    /**
     * Constructs a {@code HotTopicAggregator} with all registered strategies.
     *
     * @param strategies        all {@link HotTopicStrategy} beans in the application context
     * @param simulationService admin simulation override service
     * @param travelDayService  suppresses topics dated on a travel day (operator is away)
     * @param eventEnricher     fills in each topic's photographic event type and time
     */
    public HotTopicAggregator(List<HotTopicStrategy> strategies,
                               HotTopicSimulationService simulationService,
                               TravelDayService travelDayService,
                               HotTopicEventEnricher eventEnricher) {
        this.strategies = strategies;
        this.simulationService = simulationService;
        this.travelDayService = travelDayService;
        this.eventEnricher = eventEnricher;
    }

    /**
     * Returns hot topics for the given date range.
     *
     * <p>When simulation is active, returns simulated topics from
     * {@link HotTopicSimulationService} without invoking any real strategies.
     * Otherwise aggregates from all registered strategies sorted by priority.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return sorted list of hot topics; never null
     */
    public List<HotTopic> getHotTopics(LocalDate fromDate, LocalDate toDate) {
        List<HotTopic> topics;
        if (simulationService.isEnabled()) {
            topics = simulationService.getSimulatedTopics(fromDate, toDate);
        } else {
            topics = strategies.stream()
                    .flatMap(s -> s.detect(fromDate, toDate).stream())
                    // Suppress topics dated on a travel day — the operator is away and can't act on
                    // them ("Spring tide today", "Aurora tomorrow night" are noise when in London).
                    .filter(topic -> topic.date() == null || !travelDayService.isTravelDay(topic.date()))
                    .sorted()
                    .toList();
        }
        return eventEnricher.enrich(topics);
    }
}
