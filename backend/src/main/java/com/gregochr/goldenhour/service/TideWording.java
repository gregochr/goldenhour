package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideType;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /**
     * Every tide clock time this vocabulary states is Europe/London local — the single zone every
     * caller of {@link #londonMinutesOfDay} converts through.
     */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
     * Minutes past Europe/London local midnight for a UTC instant — the one UTC→London conversion
     * every clock-time caller needs, previously duplicated three ways
     * ({@code BriefingSlotBuilder}, {@code TideRunBuilder.localMinutes},
     * {@code TideCurveCalculator.clockMinutesFrom}).
     *
     * @param utc the instant, UTC
     * @return minutes past local midnight, ready for {@link #clock}
     */
    static int londonMinutesOfDay(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON)
                .toLocalTime().toSecondOfDay() / 60;
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

    /**
     * States, in words, why a coastal slot was withheld from Claude on the tide.
     *
     * <p>Three clauses, the first two always, the third when there is a nearest extreme to name:
     * <pre>
     *   Tide not right at sunrise · needs low water, mid tide instead · HW 09:19 · 2h35 after sunrise
     * </pre>
     * "Needs" names the location's own {@code TideType} preference — the question the gate actually
     * asked — and "instead" names the state the deriver found. Both are said because the gate is a
     * <em>mismatch</em>, and a mismatch is unintelligible with only one side stated: "mid tide"
     * alone does not tell a reader whether that is a problem here.
     *
     * <p>Lives in this vocabulary because the third clause is
     * {@code BriefingSlot.TideInfo.nearestSolarOffsetPhrase} verbatim, and the same reader sees that
     * phrase on the tide chip one line up. No contractions and no anthropomorphism, deliberately:
     * every other served tide string is a terse declarative ("Tide mismatch", "low water bares the
     * foreground", "the tide matters here"), and a first cut's "Tide's not right … wants low water,
     * it's mid tide" was the only contracted copy on any served surface (adversarial review).
     *
     * @param wanted     the location's acceptable tide states; empty when it was never configured,
     *                   which drops the "wants" clause rather than inventing a preference
     * @param tideState  {@code "HIGH"}, {@code "MID"} or {@code "LOW"} — the state at the event
     * @param nearest    the already-formatted nearest-extreme phrase, or null to omit that clause
     * @param solarWord  {@code "sunrise"} or {@code "sunset"}
     * @return the gate sentence, never null
     */
    static String tideGatePhrase(Set<TideType> wanted, String tideState, String nearest,
            String solarWord) {
        StringBuilder sb = new StringBuilder("Tide not right at ").append(solarWord).append(" · ");
        List<String> wants = orderedWantWords(wanted);
        if (!wants.isEmpty()) {
            sb.append("needs ").append(joinOr(wants)).append(", ");
        }
        sb.append(stateWord(tideState));
        if (!wants.isEmpty()) {
            sb.append(" instead");
        }
        if (nearest != null && !nearest.isBlank()) {
            sb.append(" · ").append(nearest);
        }
        return sb.toString();
    }

    /**
     * States, in words, how the tide at a solar event compares with what a coastal location
     * wants — the map tab's tide-fit chip, callout and location-sheet block, both tiers.
     *
     * <p>Two forms, deliberately different in shape rather than one template with a blank. A
     * match names the nearest extreme — the SAME phrase {@code BriefingSlot.TideInfo
     * .nearestSolarOffsetPhrase} already states — because that water IS the story:
     * <pre>
     *   high water, falling · HW 19:52 · 36m before sunset · 3.9 m
     * </pre>
     * A miss does <b>not</b> repeat that offset: a gated card already carries it in {@link
     * #tideGatePhrase}'s own third clause, and printing the same offset twice on one card is the
     * fact CLAUDE.md's tide-window rule bans. It states the light's own clock time instead, and
     * "of X m" is the day's high water — the sampled series' own maximum, never the historical
     * average, so it answers "how far short" rather than "how unusual":
     * <pre>
     *   wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m
     * </pre>
     *
     * @param aligned                  whether the tide matches the location's preference — the
     *                                 tight alignment, {@code TideInfo.tideAligned}
     * @param wanted                   the location's acceptable tide states; empty omits the
     *                                 "wants" clause (the miss form only)
     * @param tideState                {@code "HIGH"}, {@code "MID"} or {@code "LOW"} — the state
     *                                 at the event
     * @param tideDirection            {@code "RISING"} or {@code "FALLING"}
     * @param nearestSolarOffsetPhrase the already-formatted nearest-extreme phrase (the match
     *                                 form only), or null to omit that clause
     * @param solarEventTime           UTC time of the solar event — the miss form's own clock
     * @param heightAtLight            the already-formatted height at the light, e.g. "2.6 m"
     * @param dayHighWaterMetres       the already-formatted day's high water — the series max,
     *                                 never the average (the miss form only)
     * @return the fit phrase, never null
     */
    static String tideFitPhrase(boolean aligned, Set<TideType> wanted, String tideState,
            String tideDirection, String nearestSolarOffsetPhrase, LocalDateTime solarEventTime,
            String heightAtLight, String dayHighWaterMetres) {
        String stateClause = stateWord(tideState) + ", " + directionWord(tideDirection);
        if (aligned) {
            StringBuilder sb = new StringBuilder(stateClause);
            if (nearestSolarOffsetPhrase != null && !nearestSolarOffsetPhrase.isBlank()) {
                sb.append(" · ").append(nearestSolarOffsetPhrase);
            }
            sb.append(" · ").append(heightAtLight);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        List<String> wants = orderedWantWords(wanted);
        if (!wants.isEmpty()) {
            sb.append("wants ").append(joinOr(wants)).append(" · ");
        }
        sb.append(stateClause).append(" at ")
                .append(clock(londonMinutesOfDay(solarEventTime)))
                .append(" · ").append(heightAtLight).append(" of ").append(dayHighWaterMetres);
        return sb.toString();
    }

    /** {@code HIGH → "high water"}, {@code LOW → "low water"}, {@code MID → "mid tide"}. */
    private static String stateWord(String state) {
        return switch (state == null ? "" : state) {
            case "HIGH" -> "high water";
            case "LOW" -> "low water";
            case "MID" -> "mid tide";
            default -> "an unknown tide";
        };
    }

    /**
     * {@code RISING → "rising"}, {@code FALLING → "falling"}, an unrecognised or null direction
     * (unreachable in production — {@code TideCurveCalculator.directionAt} only ever answers one
     * of the two) named as "moving" rather than printed raw or thrown on, the same fail-soft
     * convention {@link #stateWord}'s "an unknown tide" already uses.
     */
    private static String directionWord(String direction) {
        return switch (direction == null ? "" : direction) {
            case "RISING" -> "rising";
            case "FALLING" -> "falling";
            default -> "moving";
        };
    }

    /**
     * The location's wanted tide states, worded, HIGH/MID/LOW in a fixed order so two locations
     * with the same preferences read alike — shared by {@link #tideGatePhrase} and
     * {@link #tideFitPhrase}.
     *
     * <p>{@code EnumSet.copyOf} rejects an empty plain {@code Set}, and an unconfigured location
     * has one, so the set is copied into an {@code EnumSet} element by element rather than via
     * {@code copyOf}.
     */
    private static List<String> orderedWantWords(Set<TideType> wanted) {
        List<String> words = new ArrayList<>();
        EnumSet<TideType> ordered = EnumSet.noneOf(TideType.class);
        if (wanted != null) {
            ordered.addAll(wanted);
        }
        for (TideType type : ordered) {
            words.add(stateWord(type.name()));
        }
        return words;
    }

    /** {@code [a] → "a"}, {@code [a, b] → "a or b"}, {@code [a, b, c] → "a, b or c"}. */
    private static String joinOr(List<String> words) {
        if (words.size() == 1) {
            return words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1))
                + " or " + words.get(words.size() - 1);
    }
}
