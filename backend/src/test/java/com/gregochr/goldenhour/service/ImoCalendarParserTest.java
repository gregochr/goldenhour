package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.ImoCalendarParser.ParsedPeak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IMO calendar parser, driven by the real PDFBox text of the two committed calendars and by small synthetic
 * strings for the shapes the calendars do not contain.
 */
class ImoCalendarParserTest {

    @Test
    @DisplayName("the 2026 and 2027 PDFs, through the real extractor, parse to exactly the 24 V222 seed rows")
    void bothCalendarsReproduceTheSeed() {
        Map<Integer, String> texts = Map.of(2026, ImoCalendarFixtures.TEXT_2026, 2027, ImoCalendarFixtures.TEXT_2027);
        int checked = 0;
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(entry.getValue(), entry.getKey());
            assertThat(parsed).hasSize(12);
            for (MeteorSeed.Row seed : MeteorSeed.ROWS) {
                if (seed.year() != entry.getKey()) {
                    continue;
                }
                ParsedPeak peak = parsed.get(seed.code());
                assertThat(peak).as("%s %d", seed.code(), seed.year()).isNotNull();
                assertThat(peak.night()).as("night %s %d", seed.code(), seed.year()).isEqualTo(seed.night());
                assertThat(peak.zhr()).as("zhr %s %d", seed.code(), seed.year()).isEqualTo(seed.zhr());
                assertThat(peak.maxUtc()).as("max_utc %s %d", seed.code(), seed.year()).isEqualTo(seed.maxUtc());
                checked++;
            }
            assertThat(ImoCalendarParser.problems(parsed, entry.getKey())).isEmpty();
        }
        assertThat(checked).isEqualTo(24);
    }

    @Test
    @DisplayName("the Quadrantids' 2027 date is in 2027, and the 2027 '80+' strips to 80")
    void quadrantids2027() {
        ParsedPeak qua = ImoCalendarParser.parse(ImoCalendarFixtures.TEXT_2027, 2027).get("QUA");

        assertThat(qua.imoDate()).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(qua.zhr()).isEqualTo(80);
    }

    private static final String HEADER = """
            Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15
            """;

    @Test
    @DisplayName("the ORI/URS trap: the Ursids' time follows the Orionids' sentence and is not taken for ORI")
    void orionidsDoNotTakeTheUrsidsTime() {
        String text = """
                Orionids (008 ORI) Oct 02–Nov 07 Oct 21 208 ◦. 0 95◦ +16◦ 66 2.5 20
                Ursids (015 URS) Dec 17–Dec 26 Dec 22 270 ◦. 7 217◦ +76◦ 33 3.0 10
                The maxima of the Orionids (008 ORI) and Leonids (013 LEO) occur when the Moon is
                near its first quarter. There is only a waxing crescent while the Ursids (015 URS) reach their
                maximum on December 22, 22hUT, two days before Christmas.
                """;

        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(text, 2026);

        assertThat(parsed.get("ORI").maxUtc()).isNull();
        assertThat(parsed.get("URS").maxUtc()).isEqualTo(Instant.parse("2026-12-22T22:00:00Z"));
    }

    @Test
    @DisplayName("a Var rate keeps the catalogue ZHR; leading + and trailing + or (?) are dropped")
    void zhrForms() {
        String text = """
                Orionids (008 ORI) Oct 02–Nov 07 Oct 21 208 ◦. 0 95◦ +16◦ 66 2.5 Var
                Draconids (009 DRA) Oct 06–Oct 10 Oct 08 195 ◦. 4 262◦ +54◦ 20 2.6 5 (?)
                Quadrantids (010 QUA) Dec 28–Jan 12 Jan 04 283 ◦. 15 230◦ +49◦ 41 2.1 80+
                Geminids (004 GEM) Dec 04–Dec 20 Dec 14 262 ◦. 2 112◦ +33◦ 35 2.6 +150
                """;

        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(text, 2026);

        assertThat(parsed.get("ORI").zhr()).isEqualTo(20);
        assertThat(parsed.get("DRA").zhr()).isEqualTo(5);
        assertThat(parsed.get("QUA").zhr()).isEqualTo(80);
        assertThat(parsed.get("GEM").zhr()).isEqualTo(150);
    }

    @Test
    @DisplayName("a ZHR that is neither a number nor Var, an impossible date, or no row at all leaves the shower out")
    void unreadableRowsAreAbsent() {
        String text = """
                Orionids (008 ORI) Oct 02–Nov 07 Oct 21 208 ◦. 0 95◦ +16◦ 66 2.5 many
                Draconids (009 DRA) Oct 06–Oct 10 Feb 30 195 ◦. 4 262◦ +54◦ 20 2.6 5
                Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5
                """;

        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(text, 2026);

        assertThat(parsed).doesNotContainKeys("ORI", "DRA", "LEO", "GEM");
    }

    @Test
    @DisplayName("a section time ('Maximum: November 17, 23h 45m UT') is used, and a range takes its midpoint")
    void sectionTimeAndRange() {
        String text = """
                Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15
                Perseids (007 PER) Jul 17–Aug 24 Aug 12 140 ◦. 0 48◦ +58◦ 59 2.2 100
                Leonids (013 LEO)
                Active: November 6–30; Maximum: November 17, 23h 45m UT (λ⊙ = 235.27); ZHR ≈ 15
                Perseids (007 PER)
                Active: July 17–August 24; Maximum: August 13, 02h to 04h UT; ZHR = 100
                """;

        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(text, 2026);

        assertThat(parsed.get("LEO").maxUtc()).isEqualTo(Instant.parse("2026-11-17T23:45:00Z"));
        assertThat(parsed.get("PER").maxUtc()).isEqualTo(Instant.parse("2026-08-13T03:00:00Z"));
    }

    @Test
    @DisplayName("a time more than a day from the Table 5 date is ignored; one a day away is kept")
    void timeFilterIsPlusOrMinusOneDay() {
        String far = """
                Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15
                Leonids (013 LEO)
                Active: November 6–30; Maximum: November 20, 23h 45m UT; ZHR = 15
                """;
        String near = far.replace("November 20", "November 18");

        assertThat(ImoCalendarParser.parse(far, 2026).get("LEO").maxUtc()).isNull();
        assertThat(ImoCalendarParser.parse(far, 2026).get("LEO").night()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(ImoCalendarParser.parse(near, 2026).get("LEO").maxUtc())
                .isEqualTo(Instant.parse("2026-11-18T23:45:00Z"));
    }

    @Test
    @DisplayName("an unreal clock time (25h) is ignored and a range wrapping midnight takes the wrapped midpoint")
    void clockTimeEdges() {
        String bad = """
                Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15
                Leonids (013 LEO)
                Active: November 6–30; Maximum: November 17, 25h UT; ZHR = 15
                """;
        String wrapped = bad.replace("25h UT", "22h to 02h UT");
        String badEnd = bad.replace("25h UT", "22h to 26h UT");

        assertThat(ImoCalendarParser.parse(bad, 2026).get("LEO").maxUtc()).isNull();
        assertThat(ImoCalendarParser.parse(badEnd, 2026).get("LEO").maxUtc()).isNull();
        assertThat(ImoCalendarParser.parse(wrapped, 2026).get("LEO").maxUtc())
                .isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
    }

    @Test
    @DisplayName("the prose form 'peak on January 3 close to\\n21h UT' reads across the line break")
    void proseAcrossALineBreak() {
        String text = """
                Quadrantids (010 QUA) Dec 28–Jan 12 Jan 03 283 ◦. 15 230◦ +49◦ 41 2.1 80
                The first of the large annual showers, the Quadrantids (010 QUA), peak on January 3 close to
                21h UT. The Moon is no problem.
                """;

        assertThat(ImoCalendarParser.parse(text, 2026).get("QUA").maxUtc())
                .isEqualTo(Instant.parse("2026-01-03T21:00:00Z"));
    }

    @Test
    @DisplayName("a time in the next sentence is not taken for the shower named in the first")
    void proseIsScopedToTheSentence() {
        String text = """
                Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15
                The Leonids (013 LEO) are weak this year. Expect them on November 17, 23h UT from a dark site.
                """;

        assertThat(ImoCalendarParser.parse(text, 2026).get("LEO").maxUtc()).isNull();
    }

    @Test
    @DisplayName("validation passes on a clean parse of the real calendars")
    void validCalendarsHaveNoProblems() {
        assertThat(ImoCalendarParser.problems(
                ImoCalendarParser.parse(ImoCalendarFixtures.TEXT_2026, 2026), 2026)).isEmpty();
    }

    @Test
    @DisplayName("a removed major row, a date moved five days, a bad ZHR and a clashing night are all reported")
    void validationProblems() {
        String text = ImoCalendarFixtures.TEXT_2026;
        String noGeminids = text.replaceAll("\\(004 GEM\\)", "(004 XXX)");
        String movedLeonids = text.replaceFirst("(\\(013 LEO\\)\\s*Nov 06–Nov 30\\s+)Nov 17", "$1Nov 22");

        List<String> missing = ImoCalendarParser.problems(ImoCalendarParser.parse(noGeminids, 2026), 2026);
        List<String> moved = ImoCalendarParser.problems(ImoCalendarParser.parse(movedLeonids, 2026), 2026);

        assertThat(missing).anyMatch(p -> p.contains("GEM"));
        assertThat(moved).anyMatch(p -> p.contains("LEO") && p.contains("days from"));
    }

    @Test
    @DisplayName("a ZHR above 500 or below 1, and two showers on one night, are problems; a minor row may be absent")
    void zhrAndDistinctness() {
        Map<String, ParsedPeak> parsed = new java.util.LinkedHashMap<>(
                ImoCalendarParser.parse(ImoCalendarFixtures.TEXT_2026, 2026));
        ParsedPeak gem = parsed.get("GEM");
        parsed.put("GEM", new ParsedPeak(gem.imoDate(), gem.maxUtc(), gem.night(), 900));
        ParsedPeak lyr = parsed.get("LYR");
        parsed.put("LYR", new ParsedPeak(lyr.imoDate(), lyr.maxUtc(), lyr.night(), 0));
        ParsedPeak urs = parsed.get("URS");
        parsed.put("URS", new ParsedPeak(urs.imoDate(), urs.maxUtc(), gem.night(), urs.zhr()));
        parsed.remove("ETA");

        List<String> problems = ImoCalendarParser.problems(parsed, 2026);

        assertThat(problems).anyMatch(p -> p.startsWith("GEM ZHR 900"));
        assertThat(problems).anyMatch(p -> p.startsWith("LYR ZHR 0"));
        assertThat(problems).anyMatch(p -> p.startsWith("URS") && p.contains("shares its night"));
        assertThat(problems).noneMatch(p -> p.contains("ETA"));
    }

    @Test
    @DisplayName("an empty text parses to nothing, and then fails validation for every major")
    void emptyText() {
        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(HEADER.substring(0, 0), 2026);

        assertThat(parsed).isEmpty();
        assertThat(ImoCalendarParser.problems(parsed, 2026)).hasSize(6);
    }

    @Test
    @DisplayName("an overflowing ZHR leaves the row absent, so validation reports the shower")
    void overflowingZhrIsAbsent() {
        String text = ImoCalendarFixtures.TEXT_2026.replaceFirst(
                "(\\(013 LEO\\)\\s*Nov 06.Nov 30\\s+Nov 17[^\\n]*?)\\s\\S+\\s*\\n", "$1 99999999999\n");

        Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(text, 2026);

        assertThat(parsed).doesNotContainKey("LEO");
        assertThat(ImoCalendarParser.problems(parsed, 2026)).anyMatch(p -> p.contains("LEO"));
    }

    @Test
    @DisplayName("hour 25 and minute 75 in a section line give no time; a huge day is no date")
    void impossibleDigitsGiveNoTime() {
        String row = "Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 ◦. 27 152◦ +22◦ 71 2.5 15\n"
                + "Leonids (013 LEO)\nActive: November 6–30; Maximum: November 17, ";

        assertThat(ImoCalendarParser.parse(row + "25h UT\n", 2026).get("LEO").maxUtc()).isNull();
        assertThat(ImoCalendarParser.parse(row + "10h 75m UT\n", 2026).get("LEO").maxUtc()).isNull();
        assertThat(ImoCalendarParser.parse(row.replace("November 17,", "November 99999999999,") + "10h UT\n", 2026)
                .get("LEO").maxUtc()).isNull();
    }
}
