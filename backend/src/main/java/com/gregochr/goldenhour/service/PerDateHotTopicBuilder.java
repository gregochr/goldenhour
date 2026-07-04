package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SurvivorSignals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shared mechanics for expanding a favourable multi-day run into one {@link HotTopic} per date.
 *
 * <p>Several survivor-signal detectors (inversion, dust, storm surge, snow-on-the-fells) previously
 * collapsed a run of good days into a single topic dated to the earliest day, with a spanning
 * "today and tomorrow" phrase in the detail. The chronological strip now shows all of one day's
 * opportunities together, so each day is its own card: same {@code type} and copy, a distinct
 * {@code date} (which drives the card's own event time via {@link HotTopicEventEnricher}), and only
 * that day's regions. The span is expressed by the presence of adjacent cards, not by prose.
 *
 * <p>The detection window ({@code fromDate}..{@code toDate}) already bounds the rows the caller
 * passes in, so the number of per-date cards is naturally capped to the days the plan grid renders.
 */
final class PerDateHotTopicBuilder {

    private PerDateHotTopicBuilder() {
    }

    /**
     * Expands filtered survivor-signal rows into one topic per distinct date, ascending, each
     * carrying only the regions favourable on that date.
     *
     * @param rows        the already-filtered rows for the phenomenon (each with a date + location)
     * @param type        the {@link HotTopic} type identifier, e.g. {@code "INVERSION"}
     * @param label       the pill label
     * @param detail      the single-day condition detail (no spanning day phrase)
     * @param priority    the topic priority
     * @param description the phenomenon explanation
     * @return one topic per date the phenomenon is favourable, ordered earliest first
     */
    static List<HotTopic> perDate(List<SurvivorSignals> rows, String type, String label,
            String detail, int priority, String description) {
        Map<LocalDate, Set<String>> regionsByDate = new TreeMap<>();
        Map<LocalDate, Set<String>> locationsByDate = new TreeMap<>();
        for (SurvivorSignals row : rows) {
            Set<String> regions = regionsByDate.computeIfAbsent(row.date(), d -> new LinkedHashSet<>());
            Set<String> locations = locationsByDate.computeIfAbsent(row.date(), d -> new LinkedHashSet<>());
            if (row.location() != null) {
                if (row.location().getRegion() != null) {
                    regions.add(row.location().getRegion().getName());
                }
                if (row.location().getName() != null) {
                    // The qualifying spots — the exact locations that made the topic fire that day.
                    locations.add(row.location().getName());
                }
            }
        }
        return regionsByDate.entrySet().stream()
                .map(entry -> new HotTopic(type, label, detail, entry.getKey(), priority, null,
                        new ArrayList<>(entry.getValue()), description, null)
                        .withLocations(new ArrayList<>(locationsByDate.get(entry.getKey()))))
                .toList();
    }
}
