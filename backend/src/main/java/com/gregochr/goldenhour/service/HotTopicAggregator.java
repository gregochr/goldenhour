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
 *
 * <p>⚠️ Until 2026-09-29 this class opened a shared {@code SlotSignalReader.withStabilityWindow}
 * around the whole strategies pass, so a nightly Gate 4 stability skip could retract a
 * {@code forecast_score} component before a strategy ever saw it. The owner's two-question rule
 * removed that: hot topics answer "what is happening", not "is it worth going", so a stability skip
 * or a triage stand-down — both decisions about the RATING — must never silence a topic. See
 * {@code SlotSignalReader}'s own class javadoc. This class therefore has no dependency on the
 * stability-skip machinery at all any more.
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
        if (simulationService.isEnabled()) {
            return eventEnricher.enrich(simulationService.getSimulatedTopics(fromDate, toDate));
        }
        List<HotTopic> topics = strategies.stream()
                .flatMap(s -> s.detect(fromDate, toDate).stream())
                .sorted()
                .toList();
        // Enriched BEFORE the travel filter: the filter reads the dates a topic covers, and a
        // NIGHT anchor is what the enricher adds.
        return eventEnricher.enrich(topics).stream()
                .filter(topic -> actionableOutsideTravel(topic, fromDate))
                .toList();
    }

    /**
     * Whether the operator can act on this topic on some day it covers — not away on every one.
     *
     * <p>Suppresses topics the operator cannot act on ("Spring tide today", "Aurora tomorrow night"
     * are noise when in London). The test is over the dates the topic COVERS that are still ahead,
     * never its date alone: a {@code NIGHT} topic dated yesterday — the aurora alert for the night
     * running before dawn — is actionable on today's sunrise, so a travel day yesterday must not
     * silence it, and a travel day today must, whatever yesterday was. A NIGHT topic dated a travel
     * day today still reaches tomorrow's sunrise card when tomorrow is not one. Dates already behind
     * {@code fromDate} are not consulted (nothing can be acted on there); an undated topic is kept.
     */
    private boolean actionableOutsideTravel(HotTopic topic, LocalDate fromDate) {
        List<LocalDate> covered = topic.coveredDates();
        if (covered.isEmpty()) {
            return true;
        }
        List<LocalDate> ahead = covered.stream().filter(d -> !d.isBefore(fromDate)).toList();
        List<LocalDate> actionable = ahead.isEmpty() ? covered : ahead;
        return actionable.stream().anyMatch(d -> !travelDayService.isTravelDay(d));
    }
}
