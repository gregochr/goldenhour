package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Today's light at the caller's home, as the masthead's light rule draws it.
 *
 * <p>The masthead band under the wordmark renders the day dawn-to-dusk as a horizontal gradient,
 * with a labelled row of clock times beneath it. Two things are needed for that, and they are
 * deliberately different shapes: four <em>display</em> times, already formatted; and a list of
 * gradient <em>stops</em> whose positions are computed from the same day's real solar times.
 *
 * <p><b>Positions are data; colours are not.</b> Every stop carries a {@link Stop#key()} naming the
 * solar event it marks and a position along the rule, and the client maps key → colour from a fixed
 * table. The split is what makes the rule genuinely narrow in winter and widen in summer while the
 * palette stays a design decision that never travels over the wire.
 *
 * <p><b>All clock times are formatted on the backend</b>, in {@code Europe/London}. The rule's axis
 * is a UK civil day; formatting on the client would put that timezone rule in a second place, which
 * is the same reason the tide run's chart formats its times here.
 *
 * @param label       the row's mandatory location label, e.g. {@code Home · NE66 1NG}. An unlabelled
 *                    gradient is a guess wearing data's clothes, so this is never null
 * @param shortLabel  the same label reduced to what fits a phone, e.g. {@code NE66 1NG}
 * @param civilDawn   start of the morning blue hour, {@code HH:mm}
 * @param sunrise     sunrise — the start of the morning golden hour, {@code HH:mm}
 * @param sunset      sunset — the end of the evening golden hour, {@code HH:mm}
 * @param civilDusk   end of the evening blue hour, {@code HH:mm}
 * @param stops       the gradient stops, in ascending position order
 */
public record TodaysLightResponse(
        String label,
        String shortLabel,
        String civilDawn,
        String sunrise,
        String sunset,
        String civilDusk,
        List<Stop> stops) {

    /**
     * Copy-on-construct, so a caller holding the list it passed in cannot mutate a served response.
     */
    public TodaysLightResponse {
        stops = List.copyOf(stops);
    }

    /**
     * One stop on the light rule.
     *
     * @param key      the solar event this stop marks — one of {@code NIGHT_START},
     *                 {@code NAUTICAL_DAWN}, {@code CIVIL_DAWN}, {@code SUNRISE},
     *                 {@code GOLDEN_MORNING_END}, {@code SOLAR_NOON},
     *                 {@code GOLDEN_EVENING_START}, {@code SUNSET}, {@code CIVIL_DUSK},
     *                 {@code NAUTICAL_DUSK}, {@code NIGHT_END}
     * @param position where it sits along the rule, 0–100, as a percentage of the local day
     */
    public record Stop(String key, double position) {
    }
}
