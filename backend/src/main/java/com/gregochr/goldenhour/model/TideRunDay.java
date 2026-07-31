package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One day of a multi-day tidal run — the data behind a tide pill's 24-hour chart.
 *
 * <p>A spring or king tide is not a single event but a <em>run</em> of three to five days either
 * side of syzygy. The pill strip stays one card per day in chronological order (Hot Topics' ordering
 * spine is time, and a multi-row card for a four-day event breaks that flow for every topic around
 * it), so the run is carried <em>on</em> each day rather than as a separate object: {@link #dayNumber}
 * and {@link #dayCount} let the pill render a {@code SPRING RUN 2/4} chip that ties the days back
 * together without reordering anything.
 *
 * <p>The question a tidal photographer actually asks is <em>does low water land near sunrise?</em>
 * Tide clock times and solar clock times used to live in different parts of the UI, leaving that
 * as mental arithmetic. Everything here is stated against the day's own sunrise and sunset so the
 * answer is readable at a glance — {@link #verdict} states it in words, {@link #aligned} flags it,
 * and the extrema plus solar times let the client draw it.
 *
 * <p><b>All clock times are Europe/London local {@code "HH:mm"}</b>, already formatted: the chart's
 * geometry is a local-day 24-hour axis, so converting on the client would put the timezone rule in
 * two places. Metres are formatted strings for the same reason — the client is a render layer.
 *
 * <p><b>One location, not a region.</b> The run is drawn for a single representative coastal
 * location ({@link #locationName}), named in the card's footer because alignment genuinely differs
 * by ~20 minutes across a coastline the topic may cover. Naming it is the honest form of a caveat.
 *
 * @param runLabel     the run's chip text, {@code "SPRING RUN"} or {@code "KING RUN"}
 * @param dayNumber    this day's 1-based position in the run
 * @param dayCount     how many days the run spans
 * @param dayLabel     short day label, {@code "TUE 28"}
 * @param locationName the coastal location the curve, heights and times are drawn for
 * @param range        the day's tidal range, {@code "3.6 m"}
 * @param rangeAnomaly signed range anomaly against the location's mean, {@code "+0.4"}; null when
 *                     no historical baseline exists, or when the difference is below display noise
 * @param highWater    the day's highest water, {@code "5.8 m"} — populated for KING runs only,
 *                     because a king tide's defining number is how high the water gets, not how far
 *                     it swings. Null on a spring run, where the range is the story
 * @param highWaterAnomaly how far that high water clears the location's spring-tide threshold,
 *                     {@code "+0.4 m over spring"} — the thing that makes a tide king rather than
 *                     merely spring. Null when there is no threshold or the excess is display noise
 * @param sunrise      the day's sunrise, {@code "05:10"}
 * @param sunset       the day's sunset, {@code "21:22"}
 * @param seas         significant wave height with its sea-state band, {@code "0.3 m · smooth"};
 *                     null when no marine sample covers this day
 * @param tides        every tide extreme in the local day, chronologically
 * @param verdict      the plain-language alignment call, {@code "LW 05:44 · 34m after sunrise"}.
 *                     This carries the meaning the chart draws — the chart is decorative to a
 *                     screen reader, so this string must never be hidden from one
 * @param aligned      true when the useful extremum falls within an hour of sunrise or sunset
 * @param peak         true on the run's biggest-range day
 * @param phrase       the editorial line for this event type, {@code "low water bares the
 *                     foreground"} — a king run's draw is highest water, not exposed foreground,
 *                     so the wording differs
 */
public record TideRunDay(
        String runLabel,
        int dayNumber,
        int dayCount,
        String dayLabel,
        String locationName,
        String range,
        @JsonInclude(JsonInclude.Include.NON_NULL) String rangeAnomaly,
        @JsonInclude(JsonInclude.Include.NON_NULL) String highWater,
        @JsonInclude(JsonInclude.Include.NON_NULL) String highWaterAnomaly,
        String sunrise,
        String sunset,
        @JsonInclude(JsonInclude.Include.NON_NULL) String seas,
        List<Extreme> tides,
        String verdict,
        boolean aligned,
        boolean peak,
        String phrase) {

    /**
     * Canonical constructor with Jackson bindings so cached briefing JSON round-trips.
     *
     * @param runLabel     the run's chip text
     * @param dayNumber    1-based position in the run
     * @param dayCount     days in the run
     * @param dayLabel     short day label
     * @param locationName the representative coastal location
     * @param range        the day's tidal range
     * @param rangeAnomaly signed anomaly against the mean range, or null
     * @param highWater    the day's highest water (king runs only), or null
     * @param highWaterAnomaly its excess over the spring threshold, or null
     * @param sunrise      the day's sunrise
     * @param sunset       the day's sunset
     * @param seas         wave height and sea-state band, or null
     * @param tides        every tide extreme in the local day
     * @param verdict      the plain-language alignment call
     * @param aligned      whether the useful extremum lands near a solar event
     * @param peak         whether this is the run's biggest-range day
     * @param phrase       the editorial line for this event type
     */
    @JsonCreator
    public TideRunDay(
            @JsonProperty("runLabel") String runLabel,
            @JsonProperty("dayNumber") int dayNumber,
            @JsonProperty("dayCount") int dayCount,
            @JsonProperty("dayLabel") String dayLabel,
            @JsonProperty("locationName") String locationName,
            @JsonProperty("range") String range,
            @JsonProperty("rangeAnomaly") String rangeAnomaly,
            @JsonProperty("highWater") String highWater,
            @JsonProperty("highWaterAnomaly") String highWaterAnomaly,
            @JsonProperty("sunrise") String sunrise,
            @JsonProperty("sunset") String sunset,
            @JsonProperty("seas") String seas,
            @JsonProperty("tides") List<Extreme> tides,
            @JsonProperty("verdict") String verdict,
            @JsonProperty("aligned") boolean aligned,
            @JsonProperty("peak") boolean peak,
            @JsonProperty("phrase") String phrase) {
        this.runLabel = runLabel;
        this.dayNumber = dayNumber;
        this.dayCount = dayCount;
        this.dayLabel = dayLabel;
        this.locationName = locationName;
        this.range = range;
        this.rangeAnomaly = rangeAnomaly;
        this.highWater = highWater;
        this.highWaterAnomaly = highWaterAnomaly;
        this.sunrise = sunrise;
        this.sunset = sunset;
        this.seas = seas;
        this.tides = tides;
        this.verdict = verdict;
        this.aligned = aligned;
        this.peak = peak;
        this.phrase = phrase;
    }

    /**
     * A single tide extreme in the local day.
     *
     * @param type {@code "H"} for high water, {@code "L"} for low water
     * @param time local clock time, {@code "HH:mm"} (Europe/London)
     */
    public record Extreme(String type, String time) {

        /**
         * Canonical constructor with Jackson bindings.
         *
         * @param type {@code "H"} or {@code "L"}
         * @param time local clock time
         */
        @JsonCreator
        public Extreme(@JsonProperty("type") String type, @JsonProperty("time") String time) {
            this.type = type;
            this.time = time;
        }
    }
}
