package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of a night answer (ask-night plan §2.2, the thread): an opaque string the server writes
 * on a response that consulted or named a night, the client echoes on that exchange when it asks a
 * follow-up, and the server compares with the live forecast before it trusts the thread.
 *
 * <p>Why a night answer needs one. {@code AskThread.staleAgainst} compares only the briefing's
 * {@code generatedAt}, and the thread renders an earlier answer's summary to the model. A night
 * summary says things that move without the briefing moving: "active, Kp 6" after the aurora state
 * has gone quiet, a clear-sky claim after the afternoon astro refresh changed the cloud forecast
 * without moving the integer star. So the version carries what such a summary rests on: the astro
 * generation of each night the answer consulted (the night's {@code max(run_timestamp)}) and, when the
 * asker could see aurora figures and tonight was among those nights, the aurora alert level. An
 * exchange whose version is not the live one drops the whole thread, typed or Ready-origin alike.
 *
 * <p>The format is the server's own and the client never reads it: {@code n1;<date>=<millis|->;...}
 * with an optional trailing {@code ;a=<LEVEL|->}. {@link #isLive} re-derives a version from what the
 * string itself says it covers, so a client cannot make one live by editing it, and a string that is
 * not in the format is simply not live.
 */
final class AskNightVersion {

    /** The longest version the server accepts back, characters. */
    static final int MAX_LENGTH = 200;

    private static final String TAG = "n1";
    private static final String NONE = "-";
    private static final String AURORA_SEGMENT = ";a=";
    private static final Pattern FORMAT = Pattern.compile(
            "n1(?:;\\d{4}-\\d{2}-\\d{2}=(?:\\d{1,19}|-))+(?:;a=(?:[A-Z]{1,12}|-))?");
    private static final Pattern AURORA_PART = Pattern.compile(";a=(?:[A-Z]{1,12}|-)$");
    private static final Pattern DATE_PART = Pattern.compile(";(\\d{4}-\\d{2}-\\d{2})=");

    private AskNightVersion() {
    }

    /**
     * The version of an answer that consulted or named these nights.
     *
     * @param snapshot     the snapshot the answer was given against
     * @param dates        the dates of the nights the answer consulted or named
     * @param canSeeAurora whether the asker's role may see aurora figures: the aurora state is part of
     *                     the version only then, so a LITE asker's thread never resets for a state they
     *                     were never told
     * @return the version, or empty when no night was consulted or named
     */
    static Optional<String> of(AskSnapshot snapshot, Collection<LocalDate> dates, boolean canSeeAurora) {
        if (dates == null || dates.isEmpty()) {
            return Optional.empty();
        }
        TreeSet<LocalDate> sorted = new TreeSet<>(dates);
        boolean includeAurora = canSeeAurora && sorted.contains(snapshot.nights().tonight());
        return Optional.of(compose(snapshot, sorted, includeAurora));
    }

    /**
     * Whether an exchange's version is the live one.
     *
     * <p><b>The aurora part is compared only for an asker who may see aurora.</b> Whether the live aurora
     * level takes part would otherwise be decided by the {@code ;a=} segment the client sends, and a LITE
     * caller could append {@code ;a=STRONG} to its own version and watch whether the thread resets, learning
     * a PRO/ADMIN-only alert level. For an asker without aurora access the segment is therefore IGNORED: it is
     * never compared, so it can reveal nothing. It is ignored rather than refused, so that a user downgraded
     * mid-thread, whose earlier version carries one, is not turned away.
     *
     * @param version      the version the client echoed
     * @param snapshot     the live snapshot
     * @param canSeeAurora whether the asker's role may see aurora figures
     * @return true when the version is in the server's format and re-deriving it from the live snapshot,
     *         for the same nights and (for such an asker) the same aurora part, gives the identical string
     */
    static boolean isLive(String version, AskSnapshot snapshot, boolean canSeeAurora) {
        if (!wellFormed(version)) {
            return false;
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        Matcher matcher = DATE_PART.matcher(version);
        while (matcher.find()) {
            dates.add(LocalDate.parse(matcher.group(1)));
        }
        boolean aurora = canSeeAurora && version.contains(AURORA_SEGMENT);
        String comparable = canSeeAurora ? version : AURORA_PART.matcher(version).replaceFirst("");
        return compose(snapshot, dates, aurora).equals(comparable);
    }

    /**
     * Whether a string is in the version format and within the length bound: what the thread
     * validation checks before it lets one through (a date the format admits is not always a real one,
     * which {@link #isLive} would refuse by an exception, so the dates are parsed here too).
     *
     * @param version the string
     * @return true when it is well formed
     */
    static boolean wellFormed(String version) {
        if (version == null || version.length() > MAX_LENGTH || !FORMAT.matcher(version).matches()) {
            return false;
        }
        Matcher matcher = DATE_PART.matcher(version);
        while (matcher.find()) {
            try {
                LocalDate.parse(matcher.group(1));
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return true;
    }

    private static String compose(AskSnapshot snapshot, TreeSet<LocalDate> dates, boolean includeAurora) {
        StringBuilder version = new StringBuilder(TAG);
        for (LocalDate date : dates) {
            version.append(';').append(date).append('=').append(snapshot.nights().night(date, AskWindowKind.ASTRO)
                    .map(AskSnapshot.Night::generation)
                    .map(generation -> String.valueOf(generation.toEpochMilli()))
                    .orElse(NONE));
        }
        if (includeAurora) {
            AskSnapshot.AuroraState state = snapshot.nights().aurora();
            version.append(AURORA_SEGMENT).append(state == null ? NONE : state.level());
        }
        return version.toString();
    }
}
