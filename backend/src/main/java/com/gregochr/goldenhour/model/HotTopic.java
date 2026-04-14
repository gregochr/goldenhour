package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A photographic topic worth highlighting regardless of forecast score.
 * Hot Topics appear in a pill strip between the Best Bet cards and the
 * quality slider in the briefing planner.
 *
 * <p>Examples: bluebell conditions, spring tides, storm surge, aurora,
 * elevated dust, cloud inversion, supermoon, meteor showers.</p>
 *
 * @param type         topic type identifier, e.g. "BLUEBELL", "SPRING_TIDE", "AURORA"
 * @param label        short pill text, e.g. "Bluebell conditions"
 * @param detail       supporting detail, e.g. "Misty and still — Northumberland, Lake District"
 * @param date         which day this topic applies to
 * @param priority     lower = more important; used for ordering (ties broken by date)
 * @param filterAction optional map filter key to apply on tap, e.g. "BLUEBELL"; may be null
 * @param regions      region names where conditions are best; may be empty
 */
public record HotTopic(
        String type,
        String label,
        String detail,
        LocalDate date,
        int priority,
        String filterAction,
        List<String> regions) implements Comparable<HotTopic> {

    /**
     * Orders topics by priority ascending, then by date ascending.
     *
     * @param other the other topic to compare to
     * @return a negative integer, zero, or positive integer as this topic is
     *     less than, equal to, or greater than the other
     */
    @Override
    public int compareTo(HotTopic other) {
        int p = Integer.compare(this.priority, other.priority);
        return p != 0 ? p : this.date.compareTo(other.date);
    }
}
