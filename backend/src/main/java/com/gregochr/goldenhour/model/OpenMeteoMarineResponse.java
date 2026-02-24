package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Deserialised response from the Open-Meteo Marine API.
 *
 * <p>Only the hourly sea surface height timeseries is mapped; all other fields
 * returned by the API are ignored.
 */
public class OpenMeteoMarineResponse {

    private Hourly hourly;

    /**
     * Returns the hourly marine data block.
     *
     * @return hourly data, or {@code null} if absent
     */
    public Hourly getHourly() {
        return hourly;
    }

    /**
     * Sets the hourly marine data block.
     *
     * @param hourly hourly data from the API
     */
    public void setHourly(Hourly hourly) {
        this.hourly = hourly;
    }

    /**
     * Hourly timeseries data from the Marine API response.
     */
    public static class Hourly {

        private List<String> time;

        @JsonProperty("sea_surface_height_above_mean_sea_level")
        private List<Double> seaSurfaceHeight;

        /**
         * Returns the ISO-8601 timestamps for each hourly slot.
         *
         * @return list of timestamps, e.g. "2026-02-24T06:00"
         */
        public List<String> getTime() {
            return time;
        }

        /**
         * Sets the hourly timestamps.
         *
         * @param time list of ISO-8601 timestamp strings
         */
        public void setTime(List<String> time) {
            this.time = time;
        }

        /**
         * Returns the sea surface height above mean sea level in metres for each slot.
         *
         * @return list of heights in metres, may contain {@code null} entries
         */
        public List<Double> getSeaSurfaceHeight() {
            return seaSurfaceHeight;
        }

        /**
         * Sets the sea surface height values.
         *
         * @param seaSurfaceHeight list of heights in metres
         */
        public void setSeaSurfaceHeight(List<Double> seaSurfaceHeight) {
            this.seaSurfaceHeight = seaSurfaceHeight;
        }
    }
}
