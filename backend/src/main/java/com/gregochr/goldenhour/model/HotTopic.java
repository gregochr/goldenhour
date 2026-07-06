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
 * @param eventType      photographic event this topic is about — {@code "SUNRISE"},
 *                       {@code "SUNSET"} or {@code "NIGHT"}; may be null when the topic has no
 *                       clear solar anchor
 * @param eventTime      local clock time of that event on {@code date}, 24-hour {@code "HH:mm"};
 *                       may be null when genuinely unknown
 * @param locationNames  the specific locations that made this topic fire (elevated spots for an
 *                       inversion, coastal spots for a tide, dark-sky spots for aurora/NLC, …), so
 *                       the map overlay can open to exactly those pins; may be null when unknown
 * @param eveningWindow  NLC twilight window low in the NW after dusk (sun 6–16° below the horizon);
 *                       null for non-NLC topics or when the geometry does not exist that night
 * @param morningWindow  NLC twilight window low in the NE before dawn; null as {@code eveningWindow}
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
        ExpandedHotTopicDetail expandedDetail,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String eventType,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String eventTime,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> locationNames,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        NlcWindow eveningWindow,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        NlcWindow morningWindow) implements Comparable<HotTopic> {

    /**
     * Explicit canonical constructor with Jackson annotations so that cached briefing
     * JSON written before the {@code description}, {@code expandedDetail}, {@code eventType},
     * {@code eventTime} or {@code locationNames} fields were added deserialises correctly. When a
     * field is absent from the JSON, Jackson passes {@code null} and this constructor defaults it
     * to {@code null}.
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
            @JsonProperty("expandedDetail") ExpandedHotTopicDetail expandedDetail,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("eventTime") String eventTime,
            @JsonProperty("locationNames") List<String> locationNames,
            @JsonProperty("eveningWindow") NlcWindow eveningWindow,
            @JsonProperty("morningWindow") NlcWindow morningWindow) {
        this.type = type;
        this.label = label;
        this.detail = detail;
        this.date = date;
        this.priority = priority;
        this.filterAction = filterAction;
        this.regions = regions;
        this.description = description;
        this.expandedDetail = expandedDetail;
        this.eventType = eventType;
        this.eventTime = eventTime;
        this.locationNames = locationNames;
        this.eveningWindow = eveningWindow;
        this.morningWindow = morningWindow;
    }

    /**
     * Back-compatible constructor for the many callers (and tests) that predate the
     * {@code eventType} / {@code eventTime} fields. Defaults both to {@code null}; the
     * central {@link com.gregochr.goldenhour.service.HotTopicEventEnricher} fills them in
     * afterwards.
     *
     * @param type           topic type identifier
     * @param label          short pill text
     * @param detail         supporting detail
     * @param date           which day this topic applies to
     * @param priority       lower = more important
     * @param filterAction   optional map filter key; may be null
     * @param regions        region names where conditions are best
     * @param description     explanation of the phenomenon; may be null
     * @param expandedDetail structured expandable data; may be null
     */
    public HotTopic(
            String type,
            String label,
            String detail,
            LocalDate date,
            int priority,
            String filterAction,
            List<String> regions,
            String description,
            ExpandedHotTopicDetail expandedDetail) {
        this(type, label, detail, date, priority, filterAction, regions, description,
                expandedDetail, null, null, null, null, null);
    }

    /**
     * Returns a copy of this topic with the photographic event type and local event time set,
     * preserving the qualifying locations.
     *
     * @param eventType {@code "SUNRISE"}, {@code "SUNSET"} or {@code "NIGHT"}
     * @param eventTime local {@code "HH:mm"} clock time on {@link #date()}, or null
     * @return a new {@link HotTopic} with the event fields populated
     */
    public HotTopic withEvent(String eventType, String eventTime) {
        return new HotTopic(type, label, detail, date, priority, filterAction, regions,
                description, expandedDetail, eventType, eventTime, locationNames,
                eveningWindow, morningWindow);
    }

    /**
     * Returns a copy of this topic carrying the specific locations that made it fire, so the map
     * overlay can open to exactly those pins.
     *
     * @param locationNames the qualifying location names for this topic's date
     * @return a new {@link HotTopic} with {@code locationNames} set
     */
    public HotTopic withLocations(List<String> locationNames) {
        return new HotTopic(type, label, detail, date, priority, filterAction, regions,
                description, expandedDetail, eventType, eventTime, locationNames,
                eveningWindow, morningWindow);
    }

    /**
     * Returns a copy of this topic carrying the two NLC twilight visibility windows.
     *
     * @param eveningWindow the after-dusk NW window, or null
     * @param morningWindow the before-dawn NE window, or null
     * @return a new {@link HotTopic} with the NLC windows set
     */
    public HotTopic withNlcWindows(NlcWindow eveningWindow, NlcWindow morningWindow) {
        return new HotTopic(type, label, detail, date, priority, filterAction, regions,
                description, expandedDetail, eventType, eventTime, locationNames,
                eveningWindow, morningWindow);
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
