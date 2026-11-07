package com.gregochr.goldenhour.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An in-code copy of the 24 rows V222 seeds, from the plan's seed table (meteor-showers-plan Phase 2, with the
 * round-1 edits). {@code MeteorSeedConsistencyTest} proves the migration SQL equals it; the peak-night tests run
 * its timed rows through {@link MeteorPeakNight#of}.
 */
final class MeteorSeed {

    /**
     * One seeded row.
     *
     * @param code   the shower code
     * @param year   the IMO calendar year
     * @param night  the peak night (evening date)
     * @param maxUtc the maximum time, or null for a date only
     * @param zhr    the rate for that year
     */
    record Row(String code, int year, LocalDate night, Instant maxUtc, int zhr) { }

    /** The 24 seeded rows. */
    static final List<Row> ROWS = List.of(
            row("QUA", 2026, "2026-01-03", "2026-01-03T21:00:00Z", 80),
            row("LYR", 2026, "2026-04-22", "2026-04-22T19:40:00Z", 18),
            row("ETA", 2026, "2026-05-05", null, 50),
            row("SDA", 2026, "2026-07-30", null, 25),
            row("PER", 2026, "2026-08-12", "2026-08-13T03:00:00Z", 100),
            row("DRA", 2026, "2026-10-08", "2026-10-09T01:00:00Z", 5),
            row("ORI", 2026, "2026-10-20", null, 20),
            row("STA", 2026, "2026-11-05", null, 7),
            row("NTA", 2026, "2026-11-12", null, 5),
            row("LEO", 2026, "2026-11-17", "2026-11-17T23:45:00Z", 15),
            row("GEM", 2026, "2026-12-13", "2026-12-14T14:00:00Z", 150),
            row("URS", 2026, "2026-12-22", "2026-12-22T22:00:00Z", 10),
            row("QUA", 2027, "2027-01-03", "2027-01-04T03:25:00Z", 80),
            row("LYR", 2027, "2027-04-22", "2027-04-23T01:40:00Z", 18),
            row("ETA", 2027, "2027-05-05", "2027-05-06T09:00:00Z", 50),
            row("SDA", 2027, "2027-07-30", null, 25),
            row("PER", 2027, "2027-08-12", "2027-08-13T09:00:00Z", 110),
            row("DRA", 2027, "2027-10-08", "2027-10-09T07:00:00Z", 5),
            row("ORI", 2027, "2027-10-21", null, 20),
            row("STA", 2027, "2027-11-06", null, 7),
            row("NTA", 2027, "2027-11-13", null, 5),
            row("LEO", 2027, "2027-11-17", "2027-11-18T06:00:00Z", 15),
            row("GEM", 2027, "2027-12-14", "2027-12-14T20:00:00Z", 150),
            row("URS", 2027, "2027-12-22", "2027-12-23T04:00:00Z", 10));

    private MeteorSeed() {
    }

    private static Row row(String code, int year, String night, String maxUtc, int zhr) {
        return new Row(code, year, LocalDate.parse(night), maxUtc == null ? null : Instant.parse(maxUtc), zhr);
    }
}
