package com.gregochr.goldenhour.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Replays {@link PointSnowpack} over the evidence fixture (snow-topics-truth-plan §3.1 and S2): Open-Meteo
 * {@code best_match} with {@code past_days=7}, fetched 2026-11-24, 336 hourly values for nine points, slots at
 * 08:00 UTC on 24 to 30 November.
 *
 * <p><b>The fixture carries no wind or shortwave.</b> The replay supplies 0 m/s and 0 W/m2 for both, which leaves
 * every melt hour at the base 3 mm/C/day: a lower bound on melt, so the packs below are upper bounds on what the
 * full inputs would give. The values pinned here are the ones this code computes; they are recorded in the plan's
 * §3.1 "S2 replay" note. Helvellyn's fixture point is at 855 m (the plan's quoted 35 to 49 mm came from a replay
 * run at the summit's 950 m), so its pack here is a little thinner.
 */
class PointSnowpackFixtureReplayTest {

    private static final String FIXTURE = "/snow/2026-11-24-best-match-past7.json";
    private static final int FIRST_DAY = 24;
    private static final int LAST_DAY = 30;
    private static final double TOLERANCE = 0.05;
    private static final int WIND_FREE = 0;

    /** point name to slot day to result (08:00 UTC). */
    private static final Map<String, Map<Integer, PointSnowpack.Result>> REPLAY = new LinkedHashMap<>();

    @BeforeAll
    static void replay() throws IOException {
        JsonNode root;
        try (InputStream in = PointSnowpackFixtureReplayTest.class.getResourceAsStream(FIXTURE)) {
            root = new ObjectMapper().readTree(in);
        }
        for (JsonNode point : root.get("points")) {
            JsonNode hourly = point.get("hourly");
            JsonNode times = hourly.get("time");
            List<PointSnowpack.Hour> series = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                series.add(new PointSnowpack.Hour(hourly.get("precipitation").get(i).asDouble(),
                        hourly.get("temperature_2m").get(i).asDouble(),
                        hourly.get("relative_humidity_2m").get(i).asDouble(), (double) WIND_FREE,
                        (double) WIND_FREE));
            }
            Map<Integer, PointSnowpack.Result> byDay = new LinkedHashMap<>();
            for (int day = FIRST_DAY; day <= LAST_DAY; day++) {
                int index = -1;
                for (int i = 0; i < times.size(); i++) {
                    if (times.get(i).asText().equals("2026-11-" + day + "T08:00")) {
                        index = i;
                    }
                }
                byDay.put(day, PointSnowpack.compute(series, index).orElseThrow());
            }
            REPLAY.put(point.get("name").asText(), byDay);
        }
    }

    private static double water(String point, int day) {
        return REPLAY.get(point).get(day).snowWaterMm();
    }

    @Test
    @DisplayName("all nine points replay, every slot with its lookback covered")
    void nineCoveredPoints() {
        assertThat(REPLAY).containsOnlyKeys("Rydal Water", "Grasmere", "Scafell Pike", "Helvellyn", "Windy Gyle",
                "Harbottle Hills", "High Force", "Durham", "Tynemouth");
        assertThat(REPLAY.values()).allSatisfy(byDay -> assertThat(byDay).hasSize(LAST_DAY - FIRST_DAY + 1));
    }

    @Test
    @DisplayName("Durham and Tynemouth never hold snow; Rydal Water and Grasmere none all week (grid depth said 2 cm)")
    void lowGroundHoldsNone() {
        for (String point : List.of("Durham", "Tynemouth", "Rydal Water", "Grasmere")) {
            for (int day = FIRST_DAY; day <= LAST_DAY; day++) {
                assertThat(water(point, day)).as(point + " on " + day).isZero();
                assertThat(REPLAY.get(point).get(day).snowfall24hCm()).as(point + " fresh on " + day).isZero();
            }
        }
    }

    @Test
    @DisplayName("on the 28th Windy Gyle holds 5.0 mm, Harbottle Hills 8.7 and High Force 7.5: UKV's own 2 cm")
    void uplandPlacesOnThe28th() {
        assertThat(water("Windy Gyle", 28)).isCloseTo(5.0, within(TOLERANCE))
                .isGreaterThanOrEqualTo(PointSnowpack.LYING_MM);
        assertThat(water("Harbottle Hills", 28)).isCloseTo(8.7, within(TOLERANCE))
                .isGreaterThanOrEqualTo(PointSnowpack.LYING_MM);
        assertThat(water("High Force", 28)).isCloseTo(7.5, within(TOLERANCE))
                .isGreaterThanOrEqualTo(PointSnowpack.LYING_MM);
    }

    @Test
    @DisplayName("the 1.0 C settling gate keeps Windy Gyle and Harbottle Hills below lying on the 25th")
    void settlingGateHoldsTheUplandPlacesOnThe25th() {
        assertThat(water("Windy Gyle", 25)).isCloseTo(2.9, within(TOLERANCE)).isLessThan(PointSnowpack.LYING_MM);
        assertThat(water("Harbottle Hills", 25)).isLessThan(PointSnowpack.LYING_MM);
        assertThat(water("High Force", 25)).isLessThan(PointSnowpack.LYING_MM);
    }

    @Test
    @DisplayName("Scafell Pike holds 23 to 56 mm all week, white at every slot")
    void scafellPikeIsWhiteAllWeek() {
        double min = Double.MAX_VALUE;
        double max = 0;
        for (int day = FIRST_DAY; day <= LAST_DAY; day++) {
            double water = water("Scafell Pike", day);
            assertThat(water).as("Scafell Pike on " + day).isGreaterThanOrEqualTo(PointSnowpack.SUMMIT_WHITE_MM);
            min = Math.min(min, water);
            max = Math.max(max, water);
        }
        assertThat(min).isCloseTo(23.1, within(TOLERANCE));
        assertThat(max).isCloseTo(56.1, within(TOLERANCE));
    }

    @Test
    @DisplayName("Helvellyn (fixture point at 855 m) holds 33 to 38 mm, with 21.6 cm fresh on the 25th")
    void helvellynIsWhiteAllWeek() {
        for (int day = FIRST_DAY; day <= LAST_DAY; day++) {
            assertThat(water("Helvellyn", day)).as("Helvellyn on " + day)
                    .isBetween(33.0, 38.0).isGreaterThanOrEqualTo(PointSnowpack.SUMMIT_WHITE_MM);
        }
        assertThat(REPLAY.get("Helvellyn").get(25).snowfall24hCm()).isCloseTo(21.6, within(TOLERANCE));
    }
}
