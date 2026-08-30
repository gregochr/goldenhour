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
 * <p>The question a tidal photographer actually asks is <em>does a water I can use land near the
 * light?</em> Tide clock times and solar clock times used to live in different parts of the UI,
 * leaving that as mental arithmetic. Everything here is stated against the day's own sunrise and
 * sunset so the answer is readable at a glance — {@link #verdict} states it in words,
 * {@link #aligned} flags it, and the extrema plus solar times let the client draw it.
 *
 * <p><b>Which water counts is not decided by the moon.</b> Every field that names a water names the
 * same one — the extremum nearest a solar event, high or low — so this record cannot describe two
 * different waters in two of its own fields. It could, and did.
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
 * @param highWater    the day's highest water, {@code "5.8 m"}. Populated when the water actually
 *                     clears the location's own spring-tide threshold — <b>a size test, not the
 *                     run's lunar label</b>. It was king-only, which was the same spring/king
 *                     conflation one level down: "king" says the moon was at perigee, and the moon
 *                     does not decide whether this port's water is remarkable today. Null when the
 *                     day is unremarkable there, or when the location has no threshold yet
 * @param highWaterAnomaly how far that high water clears the location's spring-tide threshold,
 *                     {@code "+0.4 m over spring"} — the thing that makes a tide king rather than
 *                     merely spring. Null when there is no threshold or the excess is display noise
 * @param highWaterRank where that high water sits against the highest ever recorded at the location,
 *                     {@code "0.4 m off the record"} or {@code "highest recorded here"} — how
 *                     <em>extraordinary</em> the tide is, which {@link #highWaterAnomaly} cannot say.
 *                     The spring threshold is defined as 125% of mean high water, so a
 *                     metres-over-the-mean figure would be the spring excess plus a per-location
 *                     constant — a restatement, not a second reading. Distance to the ceiling moves
 *                     independently of it. Null when the day does not clear the spring threshold,
 *                     or when the location's history is too short for its maximum to be a record
 * @param sunrise      the day's sunrise, {@code "05:10"}
 * @param sunset       the day's sunset, {@code "21:22"}
 * @param seas         significant wave height with its sea-state band, {@code "0.3 m · smooth"};
 *                     null when no marine sample covers this day
 * @param tides        every tide extreme in the local day, chronologically
 * @param verdict      the plain-language alignment call, {@code "LW 05:44 · 34m after sunrise"}.
 *                     This carries the meaning the chart draws — the chart is decorative to a
 *                     screen reader, so this string must never be hidden from one
 * @param roster       how much of the coastal roster shares this alignment, measured by the same
 *                     rule at each location's <em>own</em> sunrise and sunset. The chart is one
 *                     coastline and the headline is the roster, so both must name their scope: this
 *                     is what lets the pill say "at 47 of 61 coastal locations" instead of implying
 *                     all 61 from a single location's geometry. Null when nothing could be measured
 * @param aligned      true when a water of either kind falls within an hour of sunrise or sunset.
 *                     It used to mean "the useful extremum", where useful was
 *                     {@code king ? high : low}: a claim about what the reader came to shoot, made
 *                     by the moon, and wrong for the majority of a roster configured to shoot high
 *                     water. A spring tide's gift is a big <em>range</em>, which lifts high water as
 *                     far as it drops low water; which end is worth the drive is not the moon's call
 * @param bestAligned  true on the single day of the run whose water lands closest to the light —
 *                     the day the pill accents. Every aligned day keeps its verdict and its
 *                     editorial line; this only decides which one is emphasised.
 *                     <p>It exists because {@link #aligned} stopped being scarce. While alignment
 *                     meant "the low water, on a spring run", it fired on about a third of run-days
 *                     and never on all four of a run — so accenting every aligned day happened to
 *                     read as emphasis. Measured over a simulated year at the anchor, alignment on
 *                     either water fires on ~58%, and <b>28% of runs have all four days aligned</b>,
 *                     where the accent marks everything and therefore nothing.
 *                     <p><b>Unlike {@link #peak}, this is true on a one-day run.</b> {@code peak}
 *                     is a comparative badge and a lone day is trivially its own biggest, so
 *                     claiming it asserts a comparison never made. This is an emphasis marker, not
 *                     a claim: suppressing it on a one-day run would leave a genuinely aligned day
 *                     with no accent at all, which is a regression rather than a scruple
 * @param alignedEvent which solar event it aligned with, {@code "sunrise"} or {@code "sunset"};
 *                     null when {@link #aligned} is false. Carried rather than left to be parsed
 *                     out of {@link #verdict} because the hot-topic headline names the event, and
 *                     that sentence and this chart must not be able to disagree — they used to,
 *                     the headline being a count over a different table with a different window
 * @param alignmentPhrase the alignment clause on its own — {@code "HW 09:08 · 58m after sunrise"} —
 *                     or null when no water of this day lands in the light. <b>Non-null exactly
 *                     when {@link #alignedEvent} is</b>, because both name the same point, and the
 *                     two are built together from it so they cannot drift apart.
 *                     <p>Not a duplicate of {@link #verdict}. The verdict is the chart's whole
 *                     accessible answer and carries whatever else that day needs said — on the
 *                     run's biggest day it opens {@code "peak range · "} and drops the clock time to
 *                     fit a 224px column. A surface that states the range in its own chip and names
 *                     the biggest day in its own line, as the "Coming up" feed does, would print
 *                     that prefix as a third copy; and a clause labelled <em>alignment</em> that
 *                     opens by talking about range is the kind of sentence this project keeps having
 *                     to correct. So the clause exists separately rather than being recovered by
 *                     trimming the verdict's text, which would make the verdict's punctuation
 *                     load-bearing
 * @param sunriseWater the water nearest this day's own sunrise, stated neutrally —
 *                     {@code "high water 22m before sunrise"} — or null when the day carries no
 *                     extremes at all. Deliberately NOT gated on {@link #aligned}: it answers a
 *                     different question from {@link #alignmentPhrase}, which names the one water
 *                     that reached the light and is silent everywhere else. This one is asked of
 *                     <b>every</b> window on a tidal day, because "Spring tide" is a claim about the
 *                     day and a reader looking at the evening card is owed the evening's water.
 *                     <p>⚠️ Measured against <b>its own event</b>, never against the nearer of the
 *                     two. {@code TideRunBuilder}'s {@code signedGap} picks the nearer solar event
 *                     and is right for the alignment question; used here it would label the sunset
 *                     card's water {@code "before sunrise"} whenever that extremum happened to sit
 *                     closer to the morning
 * @param sunsetWater  the same question asked of this day's sunset; see {@link #sunriseWater}
 * @param peak         true on the run's biggest-range day
 * @param phrase       the editorial line for the water that reached the light, or null when none
 *                     did — the draw is only claimed when a water lands in usable light.
 *                     Keyed to the <b>water</b>, not to the run: {@code "low water bares the
 *                     foreground"} when the low is the one in the light, and a submerged-foreshore
 *                     line when the high is. Both sentences always described a water; they read as
 *                     run-type phrases only while the run type was picking the water
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
        @JsonInclude(JsonInclude.Include.NON_NULL) String highWaterRank,
        String sunrise,
        String sunset,
        @JsonInclude(JsonInclude.Include.NON_NULL) String seas,
        List<Extreme> tides,
        String verdict,
        boolean aligned,
        boolean bestAligned,
        @JsonInclude(JsonInclude.Include.NON_NULL) String alignedEvent,
        @JsonInclude(JsonInclude.Include.NON_NULL) String alignmentPhrase,
        @JsonInclude(JsonInclude.Include.NON_NULL) RosterAlignment roster,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sunriseWater,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sunsetWater,
        boolean peak,
        @JsonInclude(JsonInclude.Include.NON_NULL) String phrase) {

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
     * @param highWaterRank its rank in the location's observed history, or null
     * @param sunrise      the day's sunrise
     * @param sunset       the day's sunset
     * @param seas         wave height and sea-state band, or null
     * @param tides        every tide extreme in the local day
     * @param verdict      the plain-language alignment call
     * @param aligned      whether a water of either kind lands near a solar event
     * @param bestAligned  whether this is the run's closest-aligned day, the one that takes the
     *                     accent. Absent from legacy cached payloads, where it deserializes to
     *                     {@code false} — a run served from one shows no accent until the next
     *                     refresh, which is a degraded emphasis rather than a wrong claim
     * @param alignedEvent which solar event it aligned with, or null
     * @param alignmentPhrase the alignment clause alone, or null
     * @param roster       the roster-wide alignment tally, or null
     * @param sunriseWater the neutral state of the water at this day's sunrise, or null. Absent
     *                     from legacy cached payloads, where it deserializes to null and the badge
     *                     falls back to the topic's own detail line
     * @param sunsetWater  the same for this day's sunset, or null
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
            @JsonProperty("highWaterRank") String highWaterRank,
            @JsonProperty("sunrise") String sunrise,
            @JsonProperty("sunset") String sunset,
            @JsonProperty("seas") String seas,
            @JsonProperty("tides") List<Extreme> tides,
            @JsonProperty("verdict") String verdict,
            @JsonProperty("aligned") boolean aligned,
            @JsonProperty("bestAligned") boolean bestAligned,
            @JsonProperty("alignedEvent") String alignedEvent,
            @JsonProperty("alignmentPhrase") String alignmentPhrase,
            @JsonProperty("roster") RosterAlignment roster,
            @JsonProperty("sunriseWater") String sunriseWater,
            @JsonProperty("sunsetWater") String sunsetWater,
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
        this.highWaterRank = highWaterRank;
        this.sunrise = sunrise;
        this.sunset = sunset;
        this.seas = seas;
        this.tides = tides;
        this.verdict = verdict;
        this.aligned = aligned;
        this.bestAligned = bestAligned;
        this.alignedEvent = alignedEvent;
        this.alignmentPhrase = alignmentPhrase;
        this.roster = roster;
        this.sunriseWater = sunriseWater;
        this.sunsetWater = sunsetWater;
        this.peak = peak;
        this.phrase = phrase;
    }

    /**
     * How many coastal locations share this day's alignment, per solar event.
     *
     * <p>Measured by the <b>same rule</b> the representative's own row uses — the extremum nearest a
     * solar event, if it falls inside the alignment window — at each location's own sunrise and
     * sunset. Identical rules matter: it makes the representative a member of its own tally, so an
     * aligned chart can never sit above a count of zero.
     *
     * <p>Astronomical, never preference-weighted. Counting only locations whose stored
     * {@link com.gregochr.goldenhour.entity.TideType} matched would answer a different question from
     * the badge it sits under, and could not answer at all for a location with no preference set.
     *
     * @param sunriseAligned locations whose tide lands within the window of their own sunrise
     * @param sunsetAligned  locations whose tide lands within the window of their own sunset
     * @param measured       locations a tide could be derived for — the denominator. Not the size of
     *                       the coastal roster: a location with no stored extremes for the day
     *                       cannot be called aligned or unaligned, and counting it either way would
     *                       state something never measured
     */
    public record RosterAlignment(int sunriseAligned, int sunsetAligned, int measured) {

        /**
         * Locations aligned with the given event.
         *
         * @param event {@code "sunrise"} or {@code "sunset"}
         * @return the tally for that event
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public int alignedWith(String event) {
            return "sunrise".equals(event) ? sunriseAligned : sunsetAligned;
        }

        /**
         * Canonical constructor with Jackson bindings.
         *
         * @param sunriseAligned sunrise tally
         * @param sunsetAligned  sunset tally
         * @param measured       denominator
         */
        @JsonCreator
        public RosterAlignment(
                @JsonProperty("sunriseAligned") int sunriseAligned,
                @JsonProperty("sunsetAligned") int sunsetAligned,
                @JsonProperty("measured") int measured) {
            this.sunriseAligned = sunriseAligned;
            this.sunsetAligned = sunsetAligned;
            this.measured = measured;
        }
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
