package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WarningFeedParser} against the hand-written fixtures under {@code src/test/resources/warnings}.
 */
class WarningFeedParserTest {

    private final WarningFeedParser parser = new WarningFeedParser();

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/warnings/" + name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static WarningFeedParser.FeedEntry entry(String id, int version) {
        return new WarningFeedParser.FeedEntry(id, version,
                URI.create("https://example.test/nswws/warnings/" + id + "/" + version));
    }

    private static String atom(String entries) {
        return "<feed xmlns=\"http://www.w3.org/2005/Atom\">" + entries + "</feed>";
    }

    // ── Feed ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the fixture feed yields seven identified entries and one malformed one")
    void parsesFixtureFeed() {
        WarningFeedParser.FeedParse feed = parser.parseFeed(fixture("feed.atom.xml"));

        assertThat(feed.malformed()).isEqualTo(1);
        assertThat(feed.entries()).extracting(WarningFeedParser.FeedEntry::warningId)
                .containsExactly("W-YEL-WIND", "W-UPG", "W-UPG", "W-CAN", "W-SNOW", "W-UNK", "W-HOLE");
        assertThat(feed.entries()).extracting(WarningFeedParser.FeedEntry::warningVersion)
                .containsExactly(1, 1, 2, 2, 1, 1, 1);
        assertThat(feed.entries().get(0).detailUri())
                .isEqualTo(URI.create("https://example.test/nswws/warnings/W-YEL-WIND/1"));
    }

    @Test
    @DisplayName("a json link is preferred over an alternate link, which is preferred over any other")
    void linkPreference() {
        String xml = atom("<entry><id>x</id>"
                + "<link rel=\"self\" href=\"https://e.test/a/SELF/9\"/>"
                + "<link rel=\"alternate\" href=\"https://e.test/a/ALT/8\"/>"
                + "<link rel=\"related\" type=\"application/geo+json\" href=\"https://e.test/a/JSON/7\"/>"
                + "</entry>"
                + "<entry><id>y</id>"
                + "<link rel=\"self\" href=\"https://e.test/a/SELF/9\"/>"
                + "<link rel=\"alternate\" href=\"https://e.test/a/ALT/8\"/>"
                + "</entry>"
                + "<entry><id>z</id>"
                + "<link href=\"https://e.test/a/NOREL/6\"/>"
                + "</entry>"
                + "<entry><id>w</id>"
                + "<link rel=\"self\" href=\"https://e.test/a/ONLY/5\"/>"
                + "</entry>");

        List<WarningFeedParser.FeedEntry> entries = parser.parseFeed(xml).entries();

        assertThat(entries).extracting(WarningFeedParser.FeedEntry::warningId)
                .containsExactly("JSON", "ALT", "NOREL", "ONLY");
    }

    @Test
    @DisplayName("a blank href is ignored and the next link is used")
    void blankHrefIgnored() {
        String xml = atom("<entry><link href=\"\"/><link href=\"https://e.test/a/OK/3\"/></entry>");

        assertThat(parser.parseFeed(xml).entries()).extracting(WarningFeedParser.FeedEntry::warningVersion)
                .containsExactly(3);
    }

    @Test
    @DisplayName("when the link carries no version the entry id is tried; query strings and fragments are ignored")
    void idFallbackAndQueryStripped() {
        String xml = atom("<entry><id>https://e.test/ids/FROMID/4</id><link href=\"https://e.test/detail\"/></entry>"
                + "<entry><link href=\"https://e.test/a/Q/2?x=1#frag\"/></entry>");

        List<WarningFeedParser.FeedEntry> entries = parser.parseFeed(xml).entries();

        assertThat(entries).extracting(WarningFeedParser.FeedEntry::warningId).containsExactly("FROMID", "Q");
        assertThat(entries).extracting(WarningFeedParser.FeedEntry::warningVersion).containsExactly(4, 2);
    }

    @Test
    @DisplayName("entries with no link, no usable id, a non-integer version, one segment, a non-https or "
            + "unparseable link are skipped and counted")
    void malformedEntries() {
        String xml = atom("<entry><id>https://e.test/a/N/1</id></entry>"
                + "<entry><link href=\"https://e.test/a/N/notanumber\"/></entry>"
                + "<entry><link href=\"https://e.test/only\"/></entry>"
                + "<entry><link href=\"http://e.test/a/N/1\"/></entry>"
                + "<entry><link href=\"https://e.test/a b/N/1\"/></entry>"
                + "<entry><link href=\"https://e.test/a/N/12345678901\"/></entry>");

        WarningFeedParser.FeedParse feed = parser.parseFeed(xml);

        assertThat(feed.entries()).isEmpty();
        assertThat(feed.malformed()).isEqualTo(6);
    }

    @Test
    @DisplayName("a feed with no entries is an empty read, not a failure")
    void emptyFeed() {
        WarningFeedParser.FeedParse feed = parser.parseFeed(atom(""));

        assertThat(feed.entries()).isEmpty();
        assertThat(feed.malformed()).isZero();
    }

    @Test
    @DisplayName("well-formed XML whose root is not an Atom feed is refused, even if it contains entry elements")
    void nonAtomRootRefused() {
        assertThatThrownBy(() -> parser.parseFeed("<error>Service unavailable</error>"))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("not an Atom feed");
        assertThatThrownBy(() -> parser.parseFeed(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Maintenance</body></html>"))
                .isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed("<feed><entry/></feed>"))
                .isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed(
                "<rss xmlns=\"http://www.w3.org/2005/Atom\"><entry/></rss>"))
                .isInstanceOf(WarningFeedException.class);
    }

    @Test
    @DisplayName("text that is not XML, an XML doctype and null all fail the whole feed")
    void unreadableFeed() {
        assertThatThrownBy(() -> parser.parseFeed("<feed><entry></feed>")).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed("<!DOCTYPE feed [<!ENTITY x \"y\">]><feed/>"))
                .isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed(null)).isInstanceOf(WarningFeedException.class);
    }

    // ── Detail ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a Yellow wind detail maps every property and keeps the geometry")
    void yellowWind() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-YEL-WIND-1.json"), entry("W-YEL-WIND", 1));

        assertThat(row.getWarningId()).isEqualTo("W-YEL-WIND");
        assertThat(row.getWarningVersion()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getSourceStatus()).isEqualTo("NEW");
        assertThat(row.getWarningLevel()).isEqualTo("Yellow");
        assertThat(row.getWeatherTypes()).isEqualTo("[\"WIND\"]");
        assertThat(row.getLikelihood()).isEqualTo("Medium");
        assertThat(row.getImpact()).isEqualTo("Low");
        assertThat(row.getValidFrom()).isEqualTo(Instant.parse("2026-11-17T12:00:00Z"));
        assertThat(row.getValidTo()).isEqualTo(Instant.parse("2026-11-18T06:00:00Z"));
        assertThat(row.getIssuedAt()).isEqualTo(Instant.parse("2026-11-17T08:00:00Z"));
        assertThat(row.getModifiedAt()).isEqualTo(Instant.parse("2026-11-17T08:00:00Z"));
        assertThat(row.getHeadline()).isEqualTo("Yellow warning of wind");
        assertThat(row.getWhatToExpect()).contains("Some travel disruption");
        assertThat(row.getFurtherDetails()).isEqualTo("Strong winds across the north east.");
        assertThat(row.getAffectedAreas()).isEqualTo("[\"North East England\"]");
        assertThat(row.getSourceUrl()).isEqualTo("https://example.test/nswws/warnings/W-YEL-WIND/1");
        assertThat(row.getGeometry()).contains("MultiPolygon");
        assertThat(row.getSimplifiedGeometry()).contains("MultiPolygon");
        assertThat(row.isSimulated()).isFalse();
    }

    @Test
    @DisplayName("an Amber update is a live version at the higher level")
    void amberUpgrade() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-UPG-2.json"), entry("W-UPG", 2));

        assertThat(row.getWarningLevel()).isEqualTo("Amber");
        assertThat(row.getWarningVersion()).isEqualTo(2);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a cancellation is stored as CANCELLED and needs neither a level nor an end time")
    void cancelled() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-CAN-2.json"), entry("W-CAN", 2));

        assertThat(row.getStatus()).isEqualTo("CANCELLED");
        assertThat(row.getSourceStatus()).isEqualTo("CANCELLED");
        assertThat(row.getValidTo()).isNull();
        assertThat(row.getLikelihood()).isNull();
        assertThat(row.getWhatToExpect()).isNull();
    }

    @Test
    @DisplayName("a SNOW and ICE warning keeps both types in order")
    void snowAndIce() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-SNOW-1.json"), entry("W-SNOW", 1));

        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("SNOW", "ICE");
    }

    @Test
    @DisplayName("an unknown weather type is kept exactly as received")
    void unknownTypeKept() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-UNK-1.json"), entry("W-UNK", 1));

        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("Volcanic Ash");
        assertThat(WarningFeedParser.isKnownType("Volcanic Ash")).isFalse();
    }

    @Test
    @DisplayName("a polygon with a hole keeps both rings in the stored geometry")
    void polygonWithHole() {
        WeatherWarningVersionEntity row = parser.parseDetail(fixture("W-HOLE-1.json"), entry("W-HOLE", 1));

        var polygons = WarningGeometry.parse(row.getGeometry());
        assertThat(polygons.get(0)).hasSize(2);
    }

    @Test
    @DisplayName("a bare Feature (no FeatureCollection) is read, a string version equal to the entry's is accepted, "
            + "and a lone weather type string becomes a one-element array")
    void bareFeatureAndLooseTypes() {
        String detail = "{\"type\":\"Feature\",\"properties\":{\"warningId\":\"B\",\"warningVersion\":\"3\","
                + "\"warningLevel\":\"Red\",\"weatherType\":\"RAIN\",\"validToDate\":\"2026-11-18T06:00:00+00:00\"}}";

        WeatherWarningVersionEntity row = parser.parseDetail(detail, entry("B", 3));

        assertThat(row.getWeatherTypes()).isEqualTo("[\"RAIN\"]");
        assertThat(row.getGeometry()).isNull();
        assertThat(row.getSimplifiedGeometry()).isNull();
        assertThat(row.getValidTo()).isEqualTo(Instant.parse("2026-11-18T06:00:00Z"));
    }

    @Test
    @DisplayName("a detail with no id or version of its own is accepted for its entry")
    void detailWithoutOwnIds() {
        String detail = "{\"features\":[{\"properties\":{\"warningLevel\":\"Yellow\","
                + "\"validToDate\":\"2026-11-18T06:00:00Z\",\"warningStatus\":\"\",\"warningImpact\":null,"
                + "\"weatherType\":[null,\"WIND\",{\"x\":1}]}}]}";

        WeatherWarningVersionEntity row = parser.parseDetail(detail, entry("NOID", 1));

        assertThat(row.getWarningId()).isEqualTo("NOID");
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getSourceStatus()).isNull();
        assertThat(row.getImpact()).isNull();
        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("WIND");
    }

    @Test
    @DisplayName("a weather type that is neither an array nor a value is stored as null")
    void objectWeatherType() {
        String detail = "{\"properties\":{\"warningLevel\":\"Yellow\",\"validToDate\":\"2026-11-18T06:00:00Z\","
                + "\"weatherType\":{\"a\":1}}}";

        assertThat(parser.parseDetail(detail, entry("X", 1)).getWeatherTypes()).isNull();
    }

    @Test
    @DisplayName("a detail that disagrees with its entry on id or version is refused")
    void mismatchRefused() {
        assertThatThrownBy(() -> parser.parseDetail(fixture("W-UPG-2.json"), entry("W-OTHER", 2)))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("id disagrees");
        assertThatThrownBy(() -> parser.parseDetail(fixture("W-UPG-2.json"), entry("W-UPG", 5)))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("version disagrees");
    }

    @Test
    @DisplayName("a detail that is not a JSON object, not JSON, null or without properties is refused")
    void unreadableDetail() {
        WarningFeedParser.FeedEntry e = entry("A", 1);
        assertThatThrownBy(() -> parser.parseDetail("[1,2]", e)).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseDetail("{oops", e)).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseDetail(null, e)).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseDetail("{\"features\":[]}", e))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("no properties");
    }

    @Test
    @DisplayName("a live warning with no level, or with no end time, is refused; an unreadable date is refused")
    void liveWarningNeedsLevelAndEnd() {
        WarningFeedParser.FeedEntry e = entry("A", 1);
        assertThatThrownBy(() -> parser.parseDetail(
                "{\"properties\":{\"validToDate\":\"2026-11-18T06:00:00Z\"}}", e))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("level or an end time");
        assertThatThrownBy(() -> parser.parseDetail("{\"properties\":{\"warningLevel\":\"Red\"}}", e))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("level or an end time");
        assertThatThrownBy(() -> parser.parseDetail(
                "{\"properties\":{\"warningLevel\":\"Red\",\"validToDate\":\"tomorrow\"}}", e))
                .isInstanceOf(WarningFeedException.class).hasMessageContaining("validToDate");
    }

    @Test
    @DisplayName("a very long detail link is truncated to the column width")
    void longUrlTruncated() {
        String id = "L";
        WarningFeedParser.FeedEntry e = new WarningFeedParser.FeedEntry(id, 1,
                URI.create("https://example.test/" + "a".repeat(600) + "/L/1"));

        WeatherWarningVersionEntity row = parser.parseDetail(
                "{\"properties\":{\"warningLevel\":\"Red\",\"validToDate\":\"2026-11-18T06:00:00Z\"}}", e);

        assertThat(row.getSourceUrl()).hasSize(500);
    }

    // ── Types and arrays ────────────────────────────────────────────────────────

    @Test
    @DisplayName("known types are recognised whatever their case or punctuation; others, and null, are unknown")
    void knownTypes() {
        assertThat(WarningFeedParser.isKnownType("wind")).isTrue();
        assertThat(WarningFeedParser.isKnownType("Extreme heat")).isTrue();
        assertThat(WarningFeedParser.isKnownType(" thunderstorm ")).isTrue();
        assertThat(WarningFeedParser.isKnownType("Tornado")).isFalse();
        assertThat(WarningFeedParser.isKnownType(null)).isFalse();
    }

    @Test
    @DisplayName("stringList reads an array, and is empty for null, blank or unreadable text")
    void stringList() {
        assertThat(WarningFeedParser.stringList("[\"A\",\"B\"]")).containsExactly("A", "B");
        assertThat(WarningFeedParser.stringList(null)).isEmpty();
        assertThat(WarningFeedParser.stringList(" ")).isEmpty();
        assertThat(WarningFeedParser.stringList("[oops")).isEmpty();
    }
}
