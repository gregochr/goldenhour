package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 * @param type           topic type identifier, e.g. "BLUEBELL", "SPRING_TIDE", "AURORA"
 * @param label          short pill text, e.g. "Bluebell conditions"
 * @param detail         supporting detail, e.g. "Misty and still — Northumberland, Lake District"
 * @param date           which day this topic applies to
 * @param priority       lower = more important; used for ordering (ties broken by date)
 * @param filterAction   optional map filter key to apply on tap, e.g. "BLUEBELL"; may be null
 * @param regions        region names where conditions are best; may be empty
 * @param description    1–2 sentence explanation of the phenomenon for photographers; may be null
 * @param expandedDetail structured data for the expandable section of the pill; may be null
 */
public record HotTopic(
        String type,
        String label,
        String detail,
        LocalDate date,
        int priority,
        String filterAction,
        List<String> regions,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExpandedHotTopicDetail expandedDetail) implements Comparable<HotTopic> {

    /**
     * Explicit canonical constructor with Jackson annotations so that cached briefing
     * JSON written before the {@code description} or {@code expandedDetail} fields were
     * added deserialises correctly. When a field is absent from the JSON, Jackson passes
     * {@code null} and this constructor defaults it to {@code null}.
     */
    @JsonCreator
    public HotTopic(
            @JsonProperty("type") String type,
            @JsonProperty("label") String label,
            @JsonProperty("detail") String detail,
            @JsonProperty("date") LocalDate date,
            @JsonProperty("priority") int priority,
            @JsonProperty("filterAction") String filterAction,
            @JsonProperty("regions") List<String> regions,
            @JsonProperty("description") String description,
            @JsonProperty("expandedDetail") ExpandedHotTopicDetail expandedDetail) {
        this.type = type;
        this.label = label;
        this.detail = detail;
        this.date = date;
        this.priority = priority;
        this.filterAction = filterAction;
        this.regions = regions;
        this.description = description;
        this.expandedDetail = expandedDetail;
    }

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
