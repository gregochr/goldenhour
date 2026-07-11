package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Deserialises the hourly sea-state response from the Open-Meteo Marine Weather API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenMeteoMarineResponse {

    /** Snapped marine grid latitude returned by Open-Meteo (differs from request for coastal points). */
    private Double latitude;

    /** Snapped marine grid longitude returned by Open-Meteo. */
    private Double longitude;

    private Hourly hourly;

    /**
     * Hourly sea-state variable arrays, each aligned with the {@code time} list.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hourly {

        private List<String> time;

        /** Significant wave height (mean of the highest third), in metres. */
        @JsonProperty("wave_height")
        private List<Double> waveHeight;

        /** Swell wave height (long-period waves from distant systems), in metres. */
        @JsonProperty("swell_wave_height")
        private List<Double> swellWaveHeight;

        /** Mean wave direction of origin, in degrees (meteorological convention). */
        @JsonProperty("wave_direction")
        private List<Integer> waveDirection;
    }
}
