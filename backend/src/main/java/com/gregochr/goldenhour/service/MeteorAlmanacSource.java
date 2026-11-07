package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every catalogued meteor shower peaking in the feed's range (major and minor).
 *
 * <p><strong>Why this exists when {@code MeteorHotTopicStrategy} already does.</strong> That
 * strategy is range-capable in the sense that it reads {@code fromDate} and {@code toDate} — but it
 * returns {@code List.of(buildTopic(best, bestPeak))}: the single earliest qualifying peak. Over a
 * four-day briefing window that is the same thing as "all of them". Over ninety days it means the
 * feed would show one shower and silently drop the other three. The same is true of
 * {@code SupermoonHotTopicStrategy}, which returns on its first match.
 *
 * <p>The shower table itself is <em>not</em> duplicated. {@link MeteorHotTopicStrategy#SHOWERS} is
 * the single source of truth for peak dates and catalogue rates, and this class reads it, which is
 * why both live in the same package. Copying it would have created two tables to keep in step, and
 * a divergence would show up as a feed disagreeing with the Plan tab about when the Perseids peak.
 *
 * <p><strong>The moon filter is deliberately not applied here.</strong> The strategy suppresses a
 * shower whose best hours are washed out by a bright moon (see {@link MeteorMoonGate}), which is
 * right for "is tonight worth going out" and wrong for an almanac: a washed-out Perseid peak is
 * still when the Perseids peak, and a reader planning three months out wants the date plus the
 * caveat, not silence. The moon's mean illumination over the best hours is reported in
 * {@code meta}, and {@code washedOut} is set from the same gate, so the caller can grey the row
 * rather than lose it.
 */
@Component
public class MeteorAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "meteor";

    private static final String DETAIL_TEMPLATE =
            "%s peaks, around %d meteors an hour at best under a dark sky, radiating from the %s."
                    + " Best watched %s.";

    /** For a radiant that stays low from the UK: the catalogue rate is not a fair guide. Kept short:
     * Ask caps the detail at 200 characters, the moonlit suffix included. */
    private static final String LOW_RADIANT_DETAIL_TEMPLATE =
            "%s peak. Catalogue rate is %d an hour, but the radiant stays low from the UK:"
                    + " expect nearer %d, from the %s. Best %s.";

    private static final String MOONLIT_SUFFIX =
            " The moon is %d%% lit, so only the brightest will show.";

    private static final String MOON_DOWN_SUFFIX =
            " The moon is %d%% lit but down during the best hours.";

    private static final String MOON_PARTIAL_SUFFIX = " The moon is %d%% lit and %s around %s.";

    private final MeteorMoonGate moonGate;

    /**
     * Constructs a {@code MeteorAlmanacSource}.
     *
     * @param moonGate decides whether a bright moon is up during the shower's best hours
     */
    public MeteorAlmanacSource(MeteorMoonGate moonGate) {
        this.moonGate = moonGate;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        // A 90-day window can straddle a year boundary, and the December/January showers are the
        // ones that would be lost if it were not enumerated this way.
        Set<Integer> years = new LinkedHashSet<>();
        years.add(from.getYear());
        years.add(to.getYear());

        List<AlmanacEvent> events = new ArrayList<>();
        for (MeteorHotTopicStrategy.Shower shower : MeteorHotTopicStrategy.SHOWERS) {
            for (int year : years) {
                LocalDate peak = shower.peak().atYear(year);
                if (peak.isBefore(from) || peak.isAfter(to)) {
                    continue;
                }
                events.add(toEvent(shower, peak));
            }
        }
        return events;
    }

    private AlmanacEvent toEvent(MeteorHotTopicStrategy.Shower shower, LocalDate peak) {
        // The flag is a moon-up decision (a bright moon up for most of the best hours), not a
        // threshold on the reported figure, so a row can read "50% lit" with no caveat when the
        // moon is down while the shower is best; the detail says why in that case.
        MeteorMoonGate.MoonOverShower moon = moonGate.assess(shower, peak);
        int illuminationPct = moon.illuminationPct();
        boolean washedOut = moon.washedOut();

        boolean lowRadiant = shower.ukRate() != null;
        String detail = lowRadiant
                ? LOW_RADIANT_DETAIL_TEMPLATE.formatted(shower.name(), shower.zhrPeak(),
                        shower.ukRate(), shower.radiantCompass(), shower.bestHours())
                : DETAIL_TEMPLATE.formatted(
                        shower.name(), shower.zhrPeak(), shower.radiantCompass(), shower.bestHours());
        detail += moonSuffix(moon);

        return AlmanacEvent.onDay(
                peak,
                AlmanacKind.ALMANAC,
                TYPE,
                shower.name(),
                detail,
                AlmanacEvent.metaOf(
                        "zhr", String.valueOf(shower.zhrPeak()),
                        "radiant", shower.radiantCompass(),
                        "bestHours", shower.bestHours(),
                        "moonIllumination", illuminationPct + "%",
                        "washedOut", washedOut ? "true" : null,
                        "ukRate", lowRadiant ? String.valueOf(shower.ukRate()) : null,
                        "lowRadiant", lowRadiant ? "true" : null,
                        "minor", shower.major() ? null : "true"),
                List.of());
    }

    private static String moonSuffix(MeteorMoonGate.MoonOverShower moon) {
        int pct = moon.illuminationPct();
        if (moon.washedOut()) {
            return MOONLIT_SUFFIX.formatted(pct);
        }
        if (pct < MeteorMoonGate.DARK_MOON_PCT) {
            return "";
        }
        if (moon.downThroughout()) {
            return MOON_DOWN_SUFFIX.formatted(pct);
        }
        if (moon.moonset().isPresent()) {
            return MOON_PARTIAL_SUFFIX.formatted(pct, "sets", moon.moonset().get());
        }
        if (moon.moonrise().isPresent()) {
            return MOON_PARTIAL_SUFFIX.formatted(pct, "rises", moon.moonrise().get());
        }
        return "";
    }
}
