package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.util.RegionGroupingUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the day → event summary → region hierarchy for the daily briefing.
 */
@Component
public class BriefingHierarchyBuilder {

    private final BriefingVerdictEvaluator verdictEvaluator;

    /**
     * Constructs a {@code BriefingHierarchyBuilder}.
     *
     * @param verdictEvaluator evaluator for region-level verdict rollup, summaries and tide highlights
     */
    public BriefingHierarchyBuilder(BriefingVerdictEvaluator verdictEvaluator) {
        this.verdictEvaluator = verdictEvaluator;
    }

    /**
     * Groups slots into the day → event summary → region hierarchy.
     *
     * @param allSlots   all briefing slots across all locations, dates, and event types
     * @param locations  the source locations (for region lookup)
     * @param dates      the dates covered
     * @return structured briefing days
     */
    public List<BriefingDay> buildDays(List<BriefingSlot> allSlots,
            List<LocationEntity> locations, List<LocalDate> dates) {
        // Build location-to-region map
        Map<String, String> locationToRegion = new LinkedHashMap<>();
        for (LocationEntity loc : locations) {
            String regionName = loc.getRegion() != null ? loc.getRegion().getName() : null;
            locationToRegion.put(loc.getName(), regionName);
        }

        List<BriefingDay> days = new ArrayList<>();
        for (LocalDate date : dates) {
            List<BriefingEventSummary> eventSummaries = new ArrayList<>();
            for (TargetType eventType : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                List<BriefingSlot> eventSlots = allSlots.stream()
                        .filter(s -> s.solarEventTime().toLocalDate().equals(date))
                        .filter(s -> isEventType(s, eventType))
                        .toList();

                BriefingEventSummary summary = buildEventSummary(eventType, eventSlots,
                        locationToRegion);
                eventSummaries.add(summary);
            }
            days.add(new BriefingDay(date, eventSummaries));
        }
        return days;
    }

    /**
     * Classifies a slot as sunrise or sunset based on its event time relative to solar noon.
     * Slots with event times before noon are sunrise; after noon are sunset.
     *
     * @param slot      the briefing slot
     * @param eventType the target event type to match
     * @return true if the slot matches the event type
     */
    boolean isEventType(BriefingSlot slot, TargetType eventType) {
        int hour = slot.solarEventTime().getHour();
        return eventType == TargetType.SUNRISE ? hour < 12 : hour >= 12;
    }

    /**
     * Builds an event summary (sunrise or sunset) from the slots, grouping by region.
     *
     * @param eventType        the solar event type
     * @param slots            slots for this date and event type
     * @param locationToRegion map of location name to region name (null for unregioned)
     * @return the event summary with region rollups
     */
    BriefingEventSummary buildEventSummary(TargetType eventType, List<BriefingSlot> slots,
            Map<String, String> locationToRegion) {
        RegionGroupingUtils.GroupResult<BriefingSlot> grouped =
                RegionGroupingUtils.groupByRegion(slots,
                        slot -> locationToRegion.get(slot.locationName()));

        List<BriefingRegion> regions = new ArrayList<>();
        for (Map.Entry<String, List<BriefingSlot>> entry : grouped.grouped().entrySet()) {
            regions.add(buildRegion(entry.getKey(), entry.getValue()));
        }

        return new BriefingEventSummary(eventType, regions, grouped.unregioned());
    }

    /**
     * Builds a region rollup from its child slots.
     *
     * @param regionName the region display name
     * @param slots      the location slots within this region
     * @return the region rollup with verdict, summary, and tide highlights
     */
    BriefingRegion buildRegion(String regionName, List<BriefingSlot> slots) {
        Verdict verdict = verdictEvaluator.rollUpVerdict(slots);
        List<String> tideHighlights = verdictEvaluator.buildTideHighlights(slots);
        String summary = verdictEvaluator.buildRegionSummary(verdict, slots, tideHighlights);

        // Representative comfort: average of GO slots, falling back to all slots
        List<BriefingSlot> repSlots = slots.stream()
                .filter(s -> s.verdict() == Verdict.GO)
                .toList();
        if (repSlots.isEmpty()) {
            repSlots = slots;
        }

        double rawTemp = repSlots.stream()
                .filter(s -> s.weather().temperatureCelsius() != null)
                .mapToDouble(s -> s.weather().temperatureCelsius())
                .average().orElse(Double.NaN);
        double rawApparent = repSlots.stream()
                .filter(s -> s.weather().apparentTemperatureCelsius() != null)
                .mapToDouble(s -> s.weather().apparentTemperatureCelsius())
                .average().orElse(Double.NaN);
        double rawWind = repSlots.stream()
                .mapToDouble(s -> s.weather().windSpeedMs().doubleValue())
                .average().orElse(Double.NaN);

        // Weather code: code of the median-temperature slot
        List<BriefingSlot> withCode = repSlots.stream()
                .filter(s -> s.weather().temperatureCelsius() != null
                        && s.weather().weatherCode() != null)
                .sorted(Comparator.comparingDouble(s -> s.weather().temperatureCelsius()))
                .toList();
        Integer medianWeatherCode = withCode.isEmpty() ? null
                : withCode.get(withCode.size() / 2).weather().weatherCode();

        return new BriefingRegion(regionName, verdict, summary, tideHighlights, slots,
                Double.isNaN(rawTemp) ? null : rawTemp,
                Double.isNaN(rawApparent) ? null : rawApparent,
                Double.isNaN(rawWind) ? null : rawWind,
                medianWeatherCode, null, null);
    }
}
