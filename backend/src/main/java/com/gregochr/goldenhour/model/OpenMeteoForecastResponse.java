package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Deserialises the hourly forecast response from the Open-Meteo Forecast API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenMeteoForecastResponse {

    /** Snapped grid latitude returned by Open-Meteo (~2 km resolution). */
    private Double latitude;

    /** Snapped grid longitude returned by Open-Meteo (~2 km resolution). */
    private Double longitude;

    private Hourly hourly;

    /**
     * Hourly weather variable arrays, each aligned with the {@code time} list.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hourly {

        private List<String> time;

        @JsonProperty("cloud_cover_low")
        private List<Integer> cloudCoverLow;

        @JsonProperty("cloud_cover_mid")
        private List<Integer> cloudCoverMid;

        @JsonProperty("cloud_cover_high")
        private List<Integer> cloudCoverHigh;

        private List<Double> visibility;

        @JsonProperty("wind_speed_10m")
        private List<Double> windSpeed10m;

        @JsonProperty("wind_direction_10m")
        private List<Integer> windDirection10m;

        private List<Double> precipitation;

        @JsonProperty("weather_code")
        private List<Integer> weatherCode;

        @JsonProperty("relative_humidity_2m")
        private List<Integer> relativeHumidity2m;

        @JsonProperty("surface_pressure")
        private List<Double> surfacePressure;

        @JsonProperty("shortwave_radiation")
        private List<Double> shortwaveRadiation;

        @JsonProperty("boundary_layer_height")
        private List<Double> boundaryLayerHeight;

        @JsonProperty("temperature_2m")
        private List<Double> temperature2m;

        @JsonProperty("apparent_temperature")
        private List<Double> apparentTemperature;

        @JsonProperty("precipitation_probability")
        private List<Integer> precipitationProbability;

        @JsonProperty("dew_point_2m")
        private List<Double> dewPoint2m;
    }
}
