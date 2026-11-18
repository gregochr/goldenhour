package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingRegion;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;

/** Met Office warning facts for the Ask tests (weather-warnings-plan.md section 14, W6). */
final class AskWarningFixtures {

    static final String URL = "https://www.metoffice.gov.uk/public/weather/warnings/";
    static final String ATTRIBUTION = "Met Office Weather Warnings © Crown Copyright 2026";
    static final Instant FROM = Instant.parse("2026-10-05T06:00:00Z");
    static final Instant TO = Instant.parse("2026-10-06T18:00:00Z");

    private AskWarningFixtures() {
    }

    /** An Amber snow warning over the given locations, with the Met Office's own two expectation lines. */
    static AskSnapshot.Warning warning(String id, String level, List<String> types, Long... locationIds) {
        return new AskSnapshot.Warning(id, "2.0", level, types, FROM, TO, "Snow, heavy at times",
                List.of("Snow showers will bring accumulations of 5 to 10 cm.", "Travel disruption is likely."),
                false, Set.of(locationIds));
    }

    /** The warnings in force: enabled and fresh. */
    static AskSnapshot.Warnings served(AskSnapshot.Warning... warnings) {
        return new AskSnapshot.Warnings(true, false, List.of(warnings), URL, ATTRIBUTION);
    }

    /** The warnings in force when the feed may be out of date. */
    static AskSnapshot.Warnings servedStale(AskSnapshot.Warning... warnings) {
        return new AskSnapshot.Warnings(true, true, List.of(warnings), URL, ATTRIBUTION);
    }

    /** Two regions: Coast (Whitby 1, Staithes 2) and Dales (Hawes 3), one sunset window today. */
    static AskSnapshot twoRegions() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Whitby", 4),
                AskFixtures.slot(2L, "Staithes", 3));
        BriefingRegion dales = AskFixtures.region("Dales", true, AskFixtures.slot(3L, "Hawes", 4));
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, coast, dales)), List.of()));
    }
}
