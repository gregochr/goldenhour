package com.gregochr.goldenhour.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The evening-date rule for a meteor shower's peak night, in one place.
 *
 * <p>A night runs 18:00 to 18:00 UK local time, and its date is the evening's. A maximum at 14:00 UT
 * on 14 Dec belongs to the night of 13 Dec, because the dark hours that follow the evening of the 13th
 * are the ones inside IMO's reliable window.
 */
public final class MeteorPeakNight {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** Hours a night's start (18:00) precedes the local midnight that follows it. */
    private static final int NIGHT_STARTS_HOURS_BEFORE_MIDNIGHT = 18;

    /** A best-hours start before this hour (UK local) marks a pre-dawn shower. */
    private static final String PRE_DAWN_BEFORE = "06";

    private static final Pattern START_HOUR = Pattern.compile("^([0-9]{2}):[0-9]{2}");

    private MeteorPeakNight() {
    }

    /**
     * The peak night for a maximum given as an instant.
     *
     * @param maxUtc the time of maximum
     * @return the evening date of the night containing it
     */
    public static LocalDate of(Instant maxUtc) {
        return maxUtc.atZone(LONDON).minusHours(NIGHT_STARTS_HOURS_BEFORE_MIDNIGHT).toLocalDate();
    }

    /**
     * The peak night for a maximum given as a date only. A pre-dawn shower (best hours starting between
     * 00:00 and 05:59) peaks in the small hours of the IMO date, so its night is the day before.
     *
     * @param imoDate   the date IMO gives
     * @param bestHours the shower's best hours, e.g. {@code "02:00–05:00"}
     * @return the evening date of the night of maximum
     */
    public static LocalDate ofDateOnly(LocalDate imoDate, String bestHours) {
        Matcher m = START_HOUR.matcher(bestHours);
        // Total by construction: two ASCII digits compare as text, so no parse can throw; no match means not pre-dawn.
        if (m.find() && m.group(1).compareTo(PRE_DAWN_BEFORE) < 0) {
            return imoDate.minusDays(1);
        }
        return imoDate;
    }
}
