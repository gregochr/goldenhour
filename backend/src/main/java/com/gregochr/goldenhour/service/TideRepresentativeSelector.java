package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Picks the one coastal location a multi-location tide claim is drawn for: the configured anchor
 * when it is present and drawable, otherwise the biggest single-day range in the set.
 *
 * <h2>Why one location at all</h2>
 *
 * <p>Alignment differs by ~20–30 minutes across a coastline, and both callers state offsets to the
 * minute — enough to flip the sign of the one claim a coastal photographer acts on. Choosing per
 * day, or per window, lets the curve jump coastlines mid-claim, so a reader comparing Tuesday to
 * Thursday compares two different places. One location, chosen once, and <b>named</b> to the reader.
 *
 * <h2>Why an anchor exists</h2>
 *
 * <p>Biggest-range is not a neutral rule on a roster that spans a coastline. Tidal range on this
 * coast grows southward toward the Humber approaches, so the maximum reliably lands at the southern
 * end — a 53-location set running Bamburgh to Bridlington anchored every spring run to a cove on
 * Flamborough Head, ~90 miles from the reader. Naming a place the reader cannot locate carries no
 * information: it makes the numbers read as arbitrary rather than as measurements of somewhere.
 * Range picks the most dramatic version of the event; the anchor picks the most legible one.
 *
 * <p>Matched on <b>name</b> rather than id because ids are environment-specific: the same anchor has
 * different ids in local H2 and production, so a configured id would be a foot-gun that silently
 * selects the wrong coast. The trade is that renaming a location in the Admin UI quietly disables
 * the preference — the fallback then applies, so the claim degrades rather than breaking.
 *
 * <h2>One instance per caller, never shared</h2>
 *
 * <p>This class holds <b>warn-once</b> state, so a misconfigured anchor logs one line rather than
 * one per request. Sharing a single instance between the tide run and the Plan tab's window rollup
 * would change that semantic: the warning would fire for whichever caller ran first and stay silent
 * for the other, which is the failure mode the warning exists to prevent. Each caller therefore
 * constructs its own, and this is deliberately <em>not</em> a Spring bean.
 *
 * <p>Both callers read the same {@code photocast.tide-run.preferred-anchor} property, so a
 * configured anchor makes them name the same place. With no anchor configured they select
 * independently over their own date spans and <em>may</em> name different places — the run's span is
 * its own three-to-five days, the rollup's is the briefing window, and unifying those would be
 * wrong in both directions. Configure the anchor if the two must agree.
 */
final class TideRepresentativeSelector {

    private static final Logger LOG = LoggerFactory.getLogger(TideRepresentativeSelector.class);

    /**
     * Everything that is not a letter or a digit, for comparing two spellings of one place name.
     *
     * <p>{@code St. Mary's Lighthouse} in the Admin UI against {@code St Mary's Lighthouse} in
     * configuration is one missing full stop, and under a plain {@code equalsIgnoreCase} that single
     * character silently disabled the whole feature: the run fell back to biggest-range and drew
     * itself at a cove 90 miles away, with nothing anywhere saying why. Punctuation and spacing are
     * exactly the part of a place name people disagree about — {@code St}/{@code St.},
     * {@code Marys}/{@code Mary's}, hyphen or space — and none of those disagreements mean a
     * different place.
     */
    private static final String NON_ALPHANUMERIC = "[^\\p{L}\\p{N}]";

    /**
     * Whether a location has a drawable day, and how big that day's range is.
     *
     * <p>Supplied by the caller rather than owned here, because "drawable" is the caller's own
     * definition — the tide run needs a chartable curve, the window rollup needs a sparkline plus a
     * derivable range — and a shared private rule would let a change to one silently re-select the
     * other's representative.
     */
    @FunctionalInterface
    interface DayRange {

        /**
         * The tidal range at one location on one local day.
         *
         * @param extremes that location's stored extremes across the whole span, or null when it
         *                 has none
         * @param date     the local day to measure
         * @return the day's range in metres, or empty when the day is not drawable
         */
        OptionalDouble rangeOn(List<TideExtremeEntity> extremes, LocalDate date);
    }

    /** The last unresolved anchor name warned about, so the WARN fires once rather than per call. */
    private final AtomicReference<String> warnedAnchor = new AtomicReference<>();

    /** Location name to draw for when present and drawable; blank means biggest range. */
    private final String preferredAnchor;

    /**
     * Constructs a selector for one caller.
     *
     * @param preferredAnchor location name to prefer when it is present and drawable; blank or null
     *                        restores pure biggest-range selection
     */
    TideRepresentativeSelector(String preferredAnchor) {
        this.preferredAnchor = preferredAnchor;
    }

    /**
     * Picks the location to draw for.
     *
     * @param coastalLocations the candidate coastal locations
     * @param byLocation       stored extremes keyed by location id
     * @param dates            the local dates in scope, ascending
     * @param dayRange         the caller's drawability and range probe
     * @return the chosen location, or null when nothing in the set is drawable
     */
    LocationEntity select(List<LocationEntity> coastalLocations,
            Map<Long, List<TideExtremeEntity>> byLocation, List<LocalDate> dates,
            DayRange dayRange) {
        LocationEntity anchor = findAnchor(coastalLocations, byLocation, dates, dayRange);
        if (anchor != null) {
            return anchor;
        }
        LocationEntity best = null;
        double bestRange = Double.NEGATIVE_INFINITY;
        for (LocationEntity location : coastalLocations.stream()
                .sorted(Comparator.comparing(LocationEntity::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            List<TideExtremeEntity> extremes = byLocation.get(location.getId());
            if (extremes == null) {
                continue;
            }
            for (LocalDate date : dates) {
                OptionalDouble range = dayRange.rangeOn(extremes, date);
                if (range.isPresent() && range.getAsDouble() > bestRange) {
                    bestRange = range.getAsDouble();
                    best = location;
                }
            }
        }
        return best;
    }

    /**
     * The configured anchor, when it is in this set and has at least one drawable day.
     *
     * <p>The anchor must still be <em>drawable</em> — matching by name is not enough, since a
     * location with no extremes for any date in scope would yield an anchored claim with no curve.
     * A set that does not reach the anchor (a Yorkshire-only topic, say) falls back silently rather
     * than claiming a coastline it never covered.
     */
    private LocationEntity findAnchor(List<LocationEntity> coastalLocations,
            Map<Long, List<TideExtremeEntity>> byLocation, List<LocalDate> dates,
            DayRange dayRange) {
        if (preferredAnchor == null || preferredAnchor.isBlank()) {
            return null;
        }
        String wanted = preferredAnchor.trim();

        // Exact first, always. A configuration that names a roster entry outright must never be
        // resolved by the tolerant pass below, so a deliberate exact name cannot be beaten by a
        // punctuation-equal neighbour.
        LocationEntity named = matching(coastalLocations,
                name -> name.equalsIgnoreCase(wanted));
        if (named == null) {
            String normalised = normalise(wanted);
            named = normalised.isEmpty() ? null
                    : matching(coastalLocations, name -> normalise(name).equals(normalised));
            if (named != null) {
                warnOnce(wanted, "resolved to '" + LogSanitizer.sanitize(named.getName())
                        + "' by ignoring punctuation and spacing. The claim still draws correctly;"
                        + " align photocast.tide-run.preferred-anchor with the location's name to"
                        + " silence this.");
            }
        }
        if (named == null) {
            warnOnce(wanted, "is not in this coastal roster of " + coastalLocations.size()
                    + " location(s); falling back to the biggest single-day range, which may be"
                    + " far from the reader. Check the name in Location Management.");
            return null;
        }

        List<TideExtremeEntity> extremes = byLocation.get(named.getId());
        for (LocalDate date : dates) {
            if (dayRange.rangeOn(extremes, date).isPresent()) {
                return named;
            }
        }
        // Named but undrawable: stop looking rather than matching a same-named location
        // elsewhere in the roster, and let the caller fall back to biggest range.
        warnOnce(wanted, "is in scope but has no day with both a high and a low water, so it"
                + " cannot be drawn; falling back to the biggest single-day range. This usually"
                + " means its tide extremes have not been refreshed for these dates.");
        return null;
    }

    /** First roster entry whose non-null name satisfies {@code test}, or null. */
    private static LocationEntity matching(List<LocationEntity> coastalLocations,
            Predicate<String> test) {
        for (LocationEntity location : coastalLocations) {
            if (location.getName() != null && test.test(location.getName())) {
                return location;
            }
        }
        return null;
    }

    /** A place name reduced to its letters and digits, lower-cased — see {@link #NON_ALPHANUMERIC}. */
    private static String normalise(String name) {
        return name.replaceAll(NON_ALPHANUMERIC, "").toLowerCase(Locale.UK);
    }

    /**
     * Logs an anchor problem once per distinct configured name.
     *
     * <p>The anchor's whole job is to keep the claim legible, so when it does not apply the reader
     * gets a place they cannot locate and no indication that anything went wrong. That silence is
     * the defect worth fixing, not the fallback itself — the fallback is correct behaviour for a
     * set the anchor genuinely does not reach. Both callers recompute on every serve, so an
     * unguarded warning here would write a line per request, and a message that noisy stops being
     * read.
     */
    private void warnOnce(String wanted, String problem) {
        if (!wanted.equals(warnedAnchor.getAndSet(wanted))) {
            LOG.warn("[TIDE] Configured anchor '{}' {}", LogSanitizer.sanitize(wanted), problem);
        }
    }
}
