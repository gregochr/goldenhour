package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V222 seed rows equal the in-code copy of the plan's table, and that the table is internally sound.
 */
class MeteorSeedConsistencyTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('(\\w{3})', (\\d{4}), DATE '([\\d-]+)', (?:NULL|TIMESTAMPTZ '([^']+)'), (\\d+)\\)");

    private static List<MeteorSeed.Row> parseMigration() throws IOException {
        String sql;
        try (InputStream in = MeteorSeedConsistencyTest.class.getResourceAsStream(
                "/db/migration/V222__meteor_shower_peak.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<MeteorSeed.Row> rows = new ArrayList<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) {
            Instant max = m.group(4) == null ? null
                    : Instant.parse(m.group(4).replace(' ', 'T').replace("+00", "Z"));
            rows.add(new MeteorSeed.Row(m.group(1), Integer.parseInt(m.group(2)), LocalDate.parse(m.group(3)),
                    max, Integer.parseInt(m.group(5))));
        }
        return rows;
    }

    @Test
    @DisplayName("the V222 INSERT rows equal the in-code copy of the plan table")
    void migrationSeed_equalsInCodeTable() throws IOException {
        assertThat(parseMigration()).containsExactlyElementsOf(MeteorSeed.ROWS);
    }

    @Test
    @DisplayName("24 rows: twelve showers in each of two years, every code one the code table knows")
    void seed_coversEveryShowerBothYears() {
        Set<String> known = MeteorHotTopicStrategy.SHOWERS.stream()
                .map(MeteorHotTopicStrategy.Shower::code).collect(Collectors.toSet());

        assertThat(MeteorSeed.ROWS).hasSize(24);
        for (int year : new int[] {2026, 2027}) {
            int y = year;
            assertThat(MeteorSeed.ROWS.stream().filter(r -> r.year() == y)
                    .map(MeteorSeed.Row::code).collect(Collectors.toSet())).isEqualTo(known);
        }
    }

    @Test
    @DisplayName("per year, all peak nights are distinct (the almanac entry id is built from them)")
    void seed_nightsDistinctPerYear() {
        for (int year : new int[] {2026, 2027}) {
            int y = year;
            List<LocalDate> nights = MeteorSeed.ROWS.stream().filter(r -> r.year() == y)
                    .map(MeteorSeed.Row::night).toList();
            assertThat(new HashSet<>(nights)).hasSameSizeAs(nights);
        }
    }

    @Test
    @DisplayName("shower codes are distinct and each is three upper-case letters")
    void showerCodes_distinct() {
        List<String> codes = MeteorHotTopicStrategy.SHOWERS.stream()
                .map(MeteorHotTopicStrategy.Shower::code).toList();

        assertThat(new HashSet<>(codes)).hasSameSizeAs(codes);
        assertThat(codes).allMatch(c -> c.matches("[A-Z]{3}"));
    }

    @Test
    @DisplayName("the Orionids fallback is the night of 20 Oct")
    void orionidsFallback_is20Oct() {
        assertThat(MeteorHotTopicStrategy.SHOWERS.stream().filter(s -> s.code().equals("ORI"))
                .findFirst().orElseThrow().peak()).isEqualTo(java.time.MonthDay.of(10, 20));
    }
}
