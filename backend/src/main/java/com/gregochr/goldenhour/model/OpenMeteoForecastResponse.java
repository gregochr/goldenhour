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

    /** Surface height in metres the response refers to (Open-Meteo's top-level {@code elevation}). */
    private Double elevation;

    private Hourly hourly;

    /**
     * Hourly weather variable arrays, each aligned with the {@code time} list.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hourly {

        private List<String> time;

        /** Total cloud cover, all layers, in percent. Read only by the valley-mist trend. */
        @JsonProperty("cloud_cover")
        private List<Integer> cloudCover;

        @JsonProperty("cloud_cover_low")
        private List<Integer> cloudCoverLow;

        @JsonProperty("cloud_cover_mid")
        private List<Integer> cloudCoverMid;

        @JsonProperty("cloud_cover_high")
        private List<Integer> cloudCoverHigh;

        @JsonProperty("cloud_cover_850hPa")
        private List<Integer> cloudCover850hPa;

        @JsonProperty("cloud_cover_700hPa")
        private List<Integer> cloudCover700hPa;

        @JsonProperty("cloud_cover_600hPa")
        private List<Integer> cloudCover600hPa;

        @JsonProperty("cloud_cover_500hPa")
        private List<Integer> cloudCover500hPa;

        @JsonProperty("cloud_cover_400hPa")
        private List<Integer> cloudCover400hPa;

        @JsonProperty("cloud_cover_300hPa")
        private List<Integer> cloudCover300hPa;

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

        @JsonProperty("pressure_msl")
        private List<Double> pressureMsl;

        @JsonProperty("wind_gusts_10m")
        private List<Double> windGusts10m;

        /** Snowfall in the preceding hour, in centimetres. */
        private List<Double> snowfall;

        @JsonProperty("snow_depth")
        private List<Double> snowDepth;

        @JsonProperty("freezing_level_height")
        private List<Double> freezingLevelHeight;

        /** Temperature at the 925 hPa pressure level (≈ 760 m) — the inversion-detection level. */
        @JsonProperty("temperature_925hPa")
        private List<Double> temperature925hPa;

        /** Temperature at the 850 hPa pressure level (≈ 1500 m) — deeper fallback level. */
        @JsonProperty("temperature_850hPa")
        private List<Double> temperature850hPa;
    }
}
