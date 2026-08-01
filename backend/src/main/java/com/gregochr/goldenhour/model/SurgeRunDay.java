package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One day's storm-surge curve for a storm-surge hot topic — the water pushed <em>above</em> the
 * predicted astronomical tide, hour by hour, at a single representative coastal location.
 *
 * <p><b>The zero is the predicted tide, not sea level.</b> Surge is a residual: every value here is
 * an elevation on top of whatever the almanac already says the water will do. That matters for what
 * the row may claim — the tide datum itself is undocumented in this system (nothing records whether
 * stored heights are chart datum or mean sea level, and no datum parameter is sent when they are
 * fetched), so no absolute water level can honestly be stated. {@link #datumNote} carries that
 * caveat as text rather than leaving the reader to assume.
 *
 * <p><b>The curve is a forecast, the tide is an almanac.</b> A spring tide's timing is fixed and
 * knowable; a surge is a weather prediction that may not happen. The two must never be drawn as
 * equally confident lines, which is why this record is a sibling of {@link TideRunDay} rather than a
 * mode of it.
 *
 * <p><b>{@link #verdict} is the entire accessible answer.</b> The chart is {@code aria-hidden} —
 * decorative to a screen reader — so this string must state the peak, when it lands, and how it sits
 * against high water, in words, and must never be hidden from one.
 *
 * <p><b>A null in {@link #surgeMetres} is a gap, not calm.</b> Hours the model could not supply are
 * absent rather than zero; a drawn path must break there rather than descending to a value nobody
 * forecast.
 *
 * @param locationName  the representative coastal location this curve is drawn for, named because
 *                      surge timing differs along a coastline the topic may span
 * @param surgeMetres   24 hourly values for the Europe/London local day, oldest first, metres above
 *                      the predicted tide; null where the hour could not be computed
 * @param axisLabels    backend-formatted hour labels for the chart's axis
 * @param peak          the day's largest surge, formatted (e.g. {@code "+0.72 m"})
 * @param peakTime      local {@code "HH:mm"} at which the peak lands
 * @param highWaterTime local {@code "HH:mm"} of the day's high water at this location, or null when
 *                      no high water could be derived
 * @param sunrise       local {@code "HH:mm"} sunrise, for the chart's solar rules
 * @param sunset        local {@code "HH:mm"} sunset, for the chart's solar rules
 * @param verdict       the plain-language call — the whole accessible answer
 * @param aligned       whether the peak surge lands near high water, the case worth going out for
 * @param datumNote     states what the curve's zero is, since no absolute level can be claimed
 * @param phrase        the editorial line for this event type
 */
public record SurgeRunDay(
        String locationName,
        List<Double> surgeMetres,
        List<String> axisLabels,
        String peak,
        String peakTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) String highWaterTime,
        String sunrise,
        String sunset,
        String verdict,
        boolean aligned,
        String datumNote,
        String phrase) {

    /**
     * Canonical constructor with Jackson bindings so cached briefing JSON round-trips.
     *
     * <p>The series is deliberately a {@link List} of boxed {@link Double}, never a {@code double[]}:
     * a record's {@code equals} compares array components by identity, and the briefing's
     * cache-equality shortcut would then be false on every request and rebuild the whole response.
     *
     * @param locationName  the representative coastal location
     * @param surgeMetres   24 hourly values, nulls permitted as gaps
     * @param axisLabels    backend-formatted axis labels
     * @param peak          the day's largest surge, formatted
     * @param peakTime      local clock time of the peak
     * @param highWaterTime local clock time of high water, or null
     * @param sunrise       local sunrise
     * @param sunset        local sunset
     * @param verdict       the plain-language call
     * @param aligned       whether the peak lands near high water
     * @param datumNote     what the curve's zero means
     * @param phrase        the editorial line
     */
    @JsonCreator
    public SurgeRunDay(
            @JsonProperty("locationName") String locationName,
            @JsonProperty("surgeMetres") List<Double> surgeMetres,
            @JsonProperty("axisLabels") List<String> axisLabels,
            @JsonProperty("peak") String peak,
            @JsonProperty("peakTime") String peakTime,
            @JsonProperty("highWaterTime") String highWaterTime,
            @JsonProperty("sunrise") String sunrise,
            @JsonProperty("sunset") String sunset,
            @JsonProperty("verdict") String verdict,
            @JsonProperty("aligned") boolean aligned,
            @JsonProperty("datumNote") String datumNote,
            @JsonProperty("phrase") String phrase) {
        this.locationName = locationName;
        this.surgeMetres = surgeMetres;
        this.axisLabels = axisLabels;
        this.peak = peak;
        this.peakTime = peakTime;
        this.highWaterTime = highWaterTime;
        this.sunrise = sunrise;
        this.sunset = sunset;
        this.verdict = verdict;
        this.aligned = aligned;
        this.datumNote = datumNote;
        this.phrase = phrase;
    }
}
