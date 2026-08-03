package com.gregochr.goldenhour.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The shared vocabulary every tide surface states its numbers in — clock times, metres, and the
 * offset between a tide and a solar event.
 *
 * <p><b>Why this is one class and not two private copies.</b> The tide-run pill and the Plan tab's
 * per-window tide row can appear on the same screen describing the same coastline, and they are
 * built by different classes. A second copy of {@code "%.1f m"} or of the {@code "1h43 before
 * sunset"} form would let one surface read {@code 4.9 m · 1h43} while the other reads
 * {@code 4.90m · 103 min}, which reads as a disagreement about the water rather than about the
 * formatting. Nothing here decides <em>what</em> to say — only how a number is spelt once it has
 * been decided.
 *
 * <p>All clock times are Europe/London local, already formatted, because every tide chart's axis is
 * a local day: converting on the client would put the timezone rule in two places.
 */
final class TideWording {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** Minutes in a day, for wrapping a clock reading that has run past midnight. */
    private static final int MINUTES_PER_DAY = 1440;

    /** Minutes in an hour, the threshold at which an offset stops being stated in bare minutes. */
    private static final int MINUTES_PER_HOUR = 60;

    /**
     * Below this, an offset is not worth stating as a duration — the water is simply on the event.
     *
     * <p>{@code "0m after sunrise"} is a null statement dressed as a measurement, and it fires on
     * exactly the day a reader most wants a straight answer.
     */
    private static final long COINCIDENT_ROUNDING_MINUTES = 5;

    /**
     * Below this many metres, a range anomaly is display noise rather than a signal.
     *
     * <p>Lives here because it decides whether a sentence is said at all, which is the same
     * judgement the coincident rounding above makes about an offset.
     */
    static final double MIN_ANOMALY_METRES = 0.05;

    private TideWording() {
    }

    /**
     * Formats minutes past local midnight as a 24-hour clock reading.
     *
     * @param minutes minutes past local midnight; wrapped, so a value outside the day still reads
     *                as a clock time rather than throwing
     * @return {@code "19:28"}
     */
    static String clock(int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(minutes, MINUTES_PER_DAY) * 60L).format(CLOCK);
    }

    /**
     * Formats a height or a range in metres to one decimal place.
     *
     * @param value the value in metres
     * @return {@code "4.9 m"}
     */
    static String metres(double value) {
        return String.format(Locale.UK, "%.1f m", value);
    }

    /**
     * States how far a tide sits from a solar event, in words.
     *
     * @param signedMinutes minutes from the solar event to the water — positive when the water
     *                      comes after the sun
     * @param solarWord     the event being measured against, {@code "sunrise"} or {@code "sunset"}
     * @return {@code "34m after sunrise"}, {@code "2h31 before sunset"}, or {@code "at sunrise"}
     *         when the water lands on the event
     */
    static String offsetPhrase(long signedMinutes, String solarWord) {
        long magnitude = Math.abs(signedMinutes);
        if (magnitude <= COINCIDENT_ROUNDING_MINUTES) {
            return "at " + solarWord;
        }
        String duration = magnitude < MINUTES_PER_HOUR
                ? magnitude + "m"
                : String.format(Locale.UK, "%dh%02d",
                        magnitude / MINUTES_PER_HOUR, magnitude % MINUTES_PER_HOUR);
        return duration + (signedMinutes >= 0 ? " after " : " before ") + solarWord;
    }
}
