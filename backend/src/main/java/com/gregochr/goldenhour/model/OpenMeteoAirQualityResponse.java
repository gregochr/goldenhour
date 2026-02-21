package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Deserialises the hourly air quality response from the Open-Meteo Air Quality API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenMeteoAirQualityResponse {

    private Hourly hourly;

    /**
     * Hourly air quality variable arrays, each aligned with the {@code time} list.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hourly {

        private List<String> time;

        @JsonProperty("pm2_5")
        private List<Double> pm25;

        private List<Double> dust;

        @JsonProperty("aerosol_optical_depth")
        private List<Double> aerosolOpticalDepth;
    }
}
