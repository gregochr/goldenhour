package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson POJO for the WorldTides v3 {@code /api/v3?extremes} response.
 *
 * <p>Maps the fields used by {@code TideService} plus the provenance fields (atlas, station,
 * request and response point, datum). Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldTidesResponse {

    private int status;
    private List<Extreme> extremes;
    private Integer callCount;
    private String atlas;
    private String station;
    private Double requestLat;
    private Double requestLon;
    private Double responseLat;
    private Double responseLon;
    private String responseDatum;

    /**
     * Returns the HTTP status code from the WorldTides API.
     *
     * @return HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * Sets the HTTP status code from the WorldTides API.
     *
     * @param status HTTP status code
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * Returns the number of credits WorldTides billed for this request.
     *
     * <p>The provider's own figure, so it needs no local model of the pricing rules — which
     * matters because credits scale with the size of the window requested ("a seven-day high/low
     * tide prediction costs one credit for a location"), not with the number of requests. A flat
     * per-call price cannot track that, and was wrong by roughly 6x before the fetch horizon
     * moved from 14 days to 97.
     *
     * <p>Nullable: absent on an error response, and on any response from before this field was
     * mapped. Callers must fall back rather than assume zero — zero credits would read as a free
     * call.
     *
     * @return credits billed, or null if the response did not report it
     */
    public Integer getCallCount() {
        return callCount;
    }

    /**
     * Sets the number of credits billed for this request.
     *
     * @param callCount credits billed
     */
    public void setCallCount(Integer callCount) {
        this.callCount = callCount;
    }

    /**
     * Returns the tidal atlas that produced the answer (for example {@code FES2022}).
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public String getAtlas() {
        return atlas;
    }

    /**
     * Sets the tidal atlas that produced the answer (for example {@code FES2022}).
     *
     * @param atlas the value
     */
    public void setAtlas(String atlas) {
        this.atlas = atlas;
    }

    /**
     * Returns the station the answer came from, or null when it came from a model.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public String getStation() {
        return station;
    }

    /**
     * Sets the station the answer came from, or null when it came from a model.
     *
     * @param station the value
     */
    public void setStation(String station) {
        this.station = station;
    }

    /**
     * Returns the latitude WorldTides says it was asked about.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public Double getRequestLat() {
        return requestLat;
    }

    /**
     * Sets the latitude WorldTides says it was asked about.
     *
     * @param requestLat the value
     */
    public void setRequestLat(Double requestLat) {
        this.requestLat = requestLat;
    }

    /**
     * Returns the longitude WorldTides says it was asked about.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public Double getRequestLon() {
        return requestLon;
    }

    /**
     * Sets the longitude WorldTides says it was asked about.
     *
     * @param requestLon the value
     */
    public void setRequestLon(Double requestLon) {
        this.requestLon = requestLon;
    }

    /**
     * Returns the latitude WorldTides actually answered for.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public Double getResponseLat() {
        return responseLat;
    }

    /**
     * Sets the latitude WorldTides actually answered for.
     *
     * @param responseLat the value
     */
    public void setResponseLat(Double responseLat) {
        this.responseLat = responseLat;
    }

    /**
     * Returns the longitude WorldTides actually answered for.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public Double getResponseLon() {
        return responseLon;
    }

    /**
     * Sets the longitude WorldTides actually answered for.
     *
     * @param responseLon the value
     */
    public void setResponseLon(Double responseLon) {
        this.responseLon = responseLon;
    }

    /**
     * Returns the datum the heights are relative to.
     *
     * <p>Provenance only; nullable, absent on an error response.
     *
     * @return the value, or null if the response did not report it
     */
    public String getResponseDatum() {
        return responseDatum;
    }

    /**
     * Sets the datum the heights are relative to.
     *
     * @param responseDatum the value
     */
    public void setResponseDatum(String responseDatum) {
        this.responseDatum = responseDatum;
    }

    /**
     * Returns the list of tide extreme events in the response.
     *
     * @return list of extremes, or null if not present
     */
    public List<Extreme> getExtremes() {
        return extremes;
    }

    /**
     * Sets the list of tide extreme events.
     *
     * @param extremes list of extremes from the API
     */
    public void setExtremes(List<Extreme> extremes) {
        this.extremes = extremes;
    }

    /**
     * A single tide extreme event returned by the WorldTides API.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extreme {

        /** Unix epoch seconds for the tide extreme. */
        private long dt;

        /** Tide height in metres (may be negative at low tide). */
        private double height;

        /** Extreme type: "High" or "Low". */
        private String type;

        /**
         * Returns the Unix epoch timestamp (seconds) of the tide extreme.
         *
         * @return Unix epoch seconds
         */
        public long getDt() {
            return dt;
        }

        /**
         * Sets the Unix epoch timestamp.
         *
         * @param dt Unix epoch seconds
         */
        public void setDt(long dt) {
            this.dt = dt;
        }

        /**
         * Returns the tide height in metres.
         *
         * @return height in metres (may be negative)
         */
        public double getHeight() {
            return height;
        }

        /**
         * Sets the tide height in metres.
         *
         * @param height height in metres
         */
        public void setHeight(double height) {
            this.height = height;
        }

        /**
         * Returns the extreme type as reported by WorldTides: "High" or "Low".
         *
         * @return "High" or "Low"
         */
        public String getType() {
            return type;
        }

        /**
         * Sets the extreme type.
         *
         * @param type "High" or "Low"
         */
        public void setType(String type) {
            this.type = type;
        }
    }
}
