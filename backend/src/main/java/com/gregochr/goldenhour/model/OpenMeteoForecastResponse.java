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

        @JsonProperty("cloud_cover_250hPa")
        private List<Integer> cloudCover250hPa;

        @JsonProperty("cloud_cover_200hPa")
        private List<Integer> cloudCover200hPa;

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

        /** Temperature at 975 hPa (about 450 m). Read only by the inversion valley profile. */
        @JsonProperty("temperature_975hPa")
        private List<Double> temperature975hPa;

        /** Temperature at 950 hPa (about 680 m). Read only by the inversion valley profile. */
        @JsonProperty("temperature_950hPa")
        private List<Double> temperature950hPa;

        /** Temperature at 900 hPa (about 1,130 m). Read only by the inversion valley profile. */
        @JsonProperty("temperature_900hPa")
        private List<Double> temperature900hPa;

        /** Geopotential height of the 975 hPa level in metres. Read only by the inversion profile. */
        @JsonProperty("geopotential_height_975hPa")
        private List<Double> geopotentialHeight975hPa;

        /** Geopotential height of the 950 hPa level in metres. Read only by the inversion profile. */
        @JsonProperty("geopotential_height_950hPa")
        private List<Double> geopotentialHeight950hPa;

        /** Geopotential height of the 925 hPa level in metres. Read only by the inversion profile. */
        @JsonProperty("geopotential_height_925hPa")
        private List<Double> geopotentialHeight925hPa;

        /** Geopotential height of the 900 hPa level in metres. Read only by the inversion profile. */
        @JsonProperty("geopotential_height_900hPa")
        private List<Double> geopotentialHeight900hPa;

        /** Geopotential height of the 850 hPa level in metres. Read only by the inversion profile. */
        @JsonProperty("geopotential_height_850hPa")
        private List<Double> geopotentialHeight850hPa;

        /** Wind speed at 975 hPa in m/s (the request sets the unit). Read only by the inversion profile. */
        @JsonProperty("wind_speed_975hPa")
        private List<Double> windSpeed975hPa;

        /** Wind speed at 950 hPa in m/s. Read only by the inversion profile. */
        @JsonProperty("wind_speed_950hPa")
        private List<Double> windSpeed950hPa;

        /** Wind speed at 925 hPa in m/s. Read only by the inversion profile. */
        @JsonProperty("wind_speed_925hPa")
        private List<Double> windSpeed925hPa;

        /** Wind speed at 900 hPa in m/s. Read only by the inversion profile. */
        @JsonProperty("wind_speed_900hPa")
        private List<Double> windSpeed900hPa;

        /** Wind speed at 850 hPa in m/s. Read only by the inversion profile. */
        @JsonProperty("wind_speed_850hPa")
        private List<Double> windSpeed850hPa;
    }
}
