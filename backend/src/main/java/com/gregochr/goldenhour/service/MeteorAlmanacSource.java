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
 * Every major meteor shower peaking in the feed's range.
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
 * shower whose peak falls under a bright moon, which is right for "is tonight worth going out" and
 * wrong for an almanac: a washed-out Perseid peak is still when the Perseids peak, and a reader
 * planning three months out wants the date plus the caveat, not silence. The illumination is
 * reported in {@code meta} instead, so the caller can grey the row rather than lose it.
 */
@Component
public class MeteorAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "meteor";

    /** Illumination fraction at or above which the moon meaningfully washes a shower out. */
    private static final double WASHED_OUT_ILLUMINATION = 0.5;

    private static final String DETAIL_TEMPLATE =
            "%s peaks, around %d meteors an hour at best under a dark sky, radiating from the %s."
                    + " Best watched %s.";

    private static final String MOONLIT_SUFFIX =
            " The moon is %d%% lit that night, so expect only the brightest to show.";

    private final LunarPhaseService lunarPhaseService;

    /**
     * Constructs a {@code MeteorAlmanacSource}.
     *
     * @param lunarPhaseService supplies the peak night's lunar illumination
     */
    public MeteorAlmanacSource(LunarPhaseService lunarPhaseService) {
        this.lunarPhaseService = lunarPhaseService;
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
        double illumination = lunarPhaseService.getIlluminationFraction(peak);
        int illuminationPct = (int) Math.round(illumination * 100);

        String detail = DETAIL_TEMPLATE.formatted(
                shower.name(), shower.zhrPeak(), shower.radiantCompass(), shower.bestHours());
        if (illumination >= WASHED_OUT_ILLUMINATION) {
            detail += MOONLIT_SUFFIX.formatted(illuminationPct);
        }

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
                        "washedOut", illumination >= WASHED_OUT_ILLUMINATION ? "true" : null),
                List.of());
    }
}
