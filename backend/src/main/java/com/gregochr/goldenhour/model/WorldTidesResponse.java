package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson POJO for the WorldTides v3 {@code /api/v3?extremes} response.
 *
 * <p>Maps only the fields used by {@code TideService}. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldTidesResponse {

    private int status;
    private List<Extreme> extremes;
    private Integer callCount;

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
