package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A twilight visibility window for noctilucent clouds — the interval when the sun sits between
 * 6° and 16° below the horizon, so the mesosphere is still lit but the lower sky is dark enough
 * for NLC to show low on the horizon.
 *
 * <p>This is <strong>exact solar geometry</strong> (the same maths as golden/blue hour), not a
 * forecast: it says <em>when</em> to look, never that NLC will appear. There are two such windows
 * each night — one low in the NW after dusk, one low in the NE before dawn.
 *
 * @param start   local clock start time, {@code "HH:mm"} (Europe/London)
 * @param end     local clock end time, {@code "HH:mm"} (Europe/London)
 * @param azimuth compass direction to look, {@code "NW"} (evening) or {@code "NE"} (morning)
 */
public record NlcWindow(String start, String end, String azimuth) {

    /**
     * Canonical constructor with Jackson bindings so cached briefing JSON round-trips.
     *
     * @param start   local clock start time
     * @param end     local clock end time
     * @param azimuth compass look direction
     */
    @JsonCreator
    public NlcWindow(
            @JsonProperty("start") String start,
            @JsonProperty("end") String end,
            @JsonProperty("azimuth") String azimuth) {
        this.start = start;
        this.end = end;
        this.azimuth = azimuth;
    }
}
