package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WarningFeedParser} against the real NSWWS v1.1 samples and the synthetic fixtures derived from
 * them, under {@code src/test/resources/warnings}.
 */
class WarningFeedParserTest {

    static final String UUID_A = "11111111-1111-4111-8111-111111111111";
    static final String UUID_B = "22222222-2222-4222-8222-222222222222";
    static final String REAL_WARNING_ID = "401e8bda-a79f-4da4-aba7-73c323e7ac50";
    static final String REAL_UPDATE_ID = "2fec6291-2ef4-46b7-8e6b-9f944d9ea9a6";
    static final String REAL_ISSUED_ID = "e2865408-086c-4d1c-8ad8-45ab2d344099";

    private final WarningFeedParser parser = new WarningFeedParser();

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/warnings/" + name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String atom(String entries) {
        return "<feed xmlns=\"http://www.w3.org/2005/Atom\">" + entries + "</feed>";
    }

    // ── Feed ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the real feed yields one change-event uuid and the issued snapshot uuid")
    void parsesRealFeed() {
        WarningFeedParser.FeedParse feed = parser.parseFeed(fixture("real/feed.xml"));

        assertThat(feed.updateIds()).containsExactly(REAL_UPDATE_ID);
        assertThat(feed.issuedId()).isEqualTo(REAL_ISSUED_ID);
        assertThat(feed.malformed()).isZero();
    }

    @Test
    @DisplayName("a feed with several entries keeps feed order and counts an entry with no uuid as malformed")
    void parsesFeedWithBadEntry() {
        WarningFeedParser.FeedParse feed = parser.parseFeed(fixture("feed-three-entries.xml"));

        assertThat(feed.updateIds()).containsExactly(REAL_UPDATE_ID, "44444444-4444-4444-8444-444444444444");
        assertThat(feed.malformed()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry whose id is not a uuid falls back to its alternate link's last segment")
    void entryUuidFallsBackToLink() {
        String xml = atom("<entry><id>tag:example,2026:1</id><link rel=\"alternate\" "
                + "href=\"https://x.test/objects/updated/" + UUID_A.toUpperCase() + "/\"/></entry>"
                + "<entry><id>tag:example,2026:2</id><link rel=\"self\" href=\"https://x.test/" + UUID_B + "/\"/>"
                + "</entry>"
                + "<entry><id>tag:example,2026:3</id></entry>");

        WarningFeedParser.FeedParse feed = parser.parseFeed(xml);

        assertThat(feed.updateIds()).containsExactly(UUID_A);
        assertThat(feed.malformed()).isEqualTo(2);
    }

    @Test
    @DisplayName("a link with no rel counts as alternate")
    void relLessLinkIsAlternate() {
        String xml = atom("<entry><id>x</id><link href=\"https://x.test/objects/updated/" + UUID_B + "/\"/></entry>");

        assertThat(parser.parseFeed(xml).updateIds()).containsExactly(UUID_B);
    }

    @Test
    @DisplayName("the issued uuid falls back to the feed's own id when there is no related link")
    void issuedFallsBackToFeedId() {
        String xml = atom("<id>urn:uuid:" + UUID_A + "</id>");

        WarningFeedParser.FeedParse feed = parser.parseFeed(xml);

        assertThat(feed.issuedId()).isEqualTo(UUID_A);
        assertThat(feed.updateIds()).isEmpty();
    }

    @Test
    @DisplayName("a related link without a uuid is ignored; no id at all leaves the issued uuid null")
    void issuedNullWhenAbsent() {
        String xml = atom("<link rel=\"related\" href=\"https://x.test/objects/issued/latest/\"/>");

        assertThat(parser.parseFeed(xml).issuedId()).isNull();
    }

    @Test
    @DisplayName("a feed that is not XML, or not Atom, fails the whole read")
    void badFeed() {
        assertThatThrownBy(() -> parser.parseFeed("not xml")).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed(null)).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseFeed("<rss/>")).isInstanceOf(WarningFeedException.class)
                .hasMessageContaining("not an Atom feed");
        assertThatThrownBy(() -> parser.parseFeed("<feed xmlns=\"urn:other\"/>"))
                .isInstanceOf(WarningFeedException.class);
    }

    @Test
    @DisplayName("a DOCTYPE is refused (no XML external entities)")
    void doctypeRefused() {
        String xxe = "<!DOCTYPE feed [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>";

        assertThatThrownBy(() -> parser.parseFeed(xxe)).isInstanceOf(WarningFeedException.class);
    }

    // ── Documents ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the real EXPIRED snow warning: decimal version, integer likelihood and impact, region codes")
    void parsesRealExpiredWarning() {
        WarningFeedParser.WarningParse parse = parser.parseWarnings(fixture("real/updated-1.json"));

        assertThat(parse.malformed()).isZero();
        assertThat(parse.warnings()).hasSize(1);
        WeatherWarningVersionEntity row = parse.warnings().get(0);
        assertThat(row.getWarningId()).isEqualTo(REAL_WARNING_ID);
        assertThat(row.getWarningVersion()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(row.getSourceStatus()).isEqualTo("EXPIRED");
        assertThat(row.getStatus()).isEqualTo(WeatherWarningVersionEntity.STATUS_CANCELLED);
        assertThat(row.getWarningLevel()).isEqualTo("YELLOW");
        assertThat(row.getLikelihood()).isEqualTo(3);
        assertThat(row.getImpact()).isEqualTo(2);
        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("SNOW");
        assertThat(row.getValidFrom()).isEqualTo(Instant.parse("2026-11-17T00:00:00Z"));
        assertThat(row.getValidTo()).isEqualTo(Instant.parse("2026-11-17T11:00:00Z"));
        assertThat(row.getIssuedAt()).isEqualTo(Instant.parse("2026-11-15T17:15:42Z"));
        assertThat(row.getModifiedAt()).isEqualTo(Instant.parse("2026-11-16T09:24:37Z"));
        assertThat(row.getHeadline()).startsWith("Snow may cause travel disruption");
        assertThat(WarningFeedParser.stringList(row.getWhatToExpect())).hasSize(2);
        assertThat(row.getWhatShouldIDo()).startsWith("Snowy, wintry weather");
        assertThat(row.getFurtherDetails()).contains("A band of rain is expected");
        assertThat(row.getUpdateDescription()).startsWith("Update to trim southern");
        assertThat(WarningFeedParser.stringList(row.getAffectedAreas())).hasSize(7).contains("EM", "NE");
        assertThat(row.getGeometry()).contains("MultiPolygon");
        assertThat(row.getSimplifiedGeometry()).isNotBlank();
    }

    @Test
    @DisplayName("the real empty issued snapshot parses to nothing")
    void parsesEmptyIssued() {
        WarningFeedParser.WarningParse parse = parser.parseWarnings(fixture("real/issued.json"));

        assertThat(parse.warnings()).isEmpty();
        assertThat(parse.malformed()).isZero();
    }

    @Test
    @DisplayName("an ISSUED warning is live (ACTIVE) and keeps its source word")
    void issuedIsActive() {
        WarningFeedParser.WarningParse parse = parser.parseWarnings(fixture("issued-snapshot.json"));

        assertThat(parse.warnings()).hasSize(2);
        WeatherWarningVersionEntity first = parse.warnings().get(0);
        assertThat(first.getStatus()).isEqualTo(WeatherWarningVersionEntity.STATUS_ACTIVE);
        assertThat(first.getSourceStatus()).isEqualTo("ISSUED");
        assertThat(first.getWarningLevel()).isEqualTo("AMBER");
        assertThat(first.getUpdateDescription()).isNull();
        WeatherWarningVersionEntity second = parse.warnings().get(1);
        assertThat(second.getWarningVersion()).isEqualByComparingTo("2.0");
        assertThat(WarningFeedParser.stringList(second.getWeatherTypes())).containsExactly("WIND", "RAIN");
        assertThat(second.getUpdateDescription()).isEqualTo("Extended the area west.");
    }

    @Test
    @DisplayName("a CANCELLED warning is ended (CANCELLED) and keeps its source word")
    void cancelledIsEnded() {
        WeatherWarningVersionEntity row = parser.parseWarnings(fixture("updated-cancelled.json")).warnings().get(0);

        assertThat(row.getStatus()).isEqualTo(WeatherWarningVersionEntity.STATUS_CANCELLED);
        assertThat(row.getSourceStatus()).isEqualTo("CANCELLED");
        assertThat(row.getWarningVersion()).isEqualByComparingTo("3.0");
    }

    @Test
    @DisplayName("versions 9.0 and 10.0 are read as numbers, and malformed features are skipped and counted")
    void multiFeatureDocument() {
        WarningFeedParser.WarningParse parse = parser.parseWarnings(fixture("updated-multi.json"));

        assertThat(parse.warnings()).hasSize(3);
        assertThat(parse.malformed()).isEqualTo(3);
        BigDecimal nine = parse.warnings().get(0).getWarningVersion();
        BigDecimal ten = parse.warnings().get(1).getWarningVersion();
        assertThat(ten).isGreaterThan(nine);
        assertThat(parse.warnings().get(2).getWarningId()).isEqualTo(UUID_B);
    }

    @Test
    @DisplayName("a bare Feature (not wrapped in a FeatureCollection) is accepted")
    void bareFeature() {
        String feature = "{\"type\":\"Feature\",\"properties\":{\"warningId\":\"W1\",\"warningVersion\":\"1.0\","
                + "\"warningStatus\":\"ISSUED\",\"warningLevel\":\"red\",\"validToDate\":\"2026-12-02T12:00:00Z\"}}";

        WarningFeedParser.WarningParse parse = parser.parseWarnings(feature);

        assertThat(parse.warnings()).hasSize(1);
        assertThat(parse.warnings().get(0).getWarningLevel()).isEqualTo("RED");
        assertThat(parse.warnings().get(0).getGeometry()).isNull();
        assertThat(parse.warnings().get(0).getLikelihood()).isNull();
        assertThat(parse.warnings().get(0).getAffectedAreas()).isNull();
    }

    @Test
    @DisplayName("a document with neither features nor a feature parses to nothing")
    void noFeatures() {
        assertThat(parser.parseWarnings("{\"type\":\"Other\"}").warnings()).isEmpty();
    }

    @Test
    @DisplayName("numeric JSON values are accepted for the version, likelihood and impact")
    void numericValues() {
        String feature = "{\"properties\":{\"warningId\":\"W1\",\"warningVersion\":2.5,\"warningStatus\":\"issued\","
                + "\"warningLevel\":\"Amber\",\"validToDate\":\"2026-12-02T12:00:00Z\",\"warningLikelihood\":2,"
                + "\"warningImpact\":\"x\",\"weatherType\":\"WIND\",\"whatToExpect\":null}}";

        WeatherWarningVersionEntity row = parser.parseWarnings(feature).warnings().get(0);

        assertThat(row.getWarningVersion()).isEqualByComparingTo("2.5");
        assertThat(row.getLikelihood()).isEqualTo(2);
        assertThat(row.getImpact()).isNull();
        assertThat(WarningFeedParser.stringList(row.getWeatherTypes())).containsExactly("WIND");
        assertThat(row.getWhatToExpect()).isNull();
    }

    @Test
    @DisplayName("features that cannot be identified are skipped: no properties, long id, bad version, bad status")
    void unidentifiableFeatures() {
        String longId = "x".repeat(101);
        String doc = "{\"features\":[{},"
                + "{\"properties\":{\"warningId\":\"" + longId + "\",\"warningVersion\":\"1.0\"}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.234\",\"warningStatus\":\"ISSUED\"}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.0\"}}]}";

        WarningFeedParser.WarningParse parse = parser.parseWarnings(doc);

        assertThat(parse.warnings()).isEmpty();
        assertThat(parse.malformed()).isEqualTo(4);
    }

    @Test
    @DisplayName("an unparseable or out-of-range version skips the feature; out-of-range likelihood becomes null")
    void unreadableVersionsAndRanges() {
        String ok = "\"warningStatus\":\"ISSUED\",\"warningLevel\":\"RED\",\"validToDate\":\"2026-12-02T12:00:00Z\"";
        String doc = "{\"features\":["
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1..0\"," + ok + "}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"-1.0\"," + ok + "}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1e3\"," + ok + "}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"12345678.0\"," + ok + "}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.0\",\"warningLikelihood\":123,"
                + "\"warningImpact\":-1," + ok + "}}]}";

        WarningFeedParser.WarningParse parse = parser.parseWarnings(doc);

        assertThat(parse.malformed()).isEqualTo(4);
        assertThat(parse.warnings()).hasSize(1);
        assertThat(parse.warnings().get(0).getLikelihood()).isNull();
        assertThat(parse.warnings().get(0).getImpact()).isNull();
    }

    @Test
    @DisplayName("a live warning without a level or an end time is skipped; an ended one needs neither")
    void liveNeedsLevelAndEnd() {
        String live = "{\"features\":[{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.0\","
                + "\"warningStatus\":\"ISSUED\",\"validToDate\":\"2026-12-02T12:00:00Z\"}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.0\",\"warningStatus\":\"ISSUED\","
                + "\"warningLevel\":\"RED\"}},"
                + "{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"2.0\",\"warningStatus\":\"CANCELLED\"}}]}";

        WarningFeedParser.WarningParse parse = parser.parseWarnings(live);

        assertThat(parse.warnings()).hasSize(1);
        assertThat(parse.malformed()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unreadable date skips the feature")
    void badDate() {
        String doc = "{\"features\":[{\"properties\":{\"warningId\":\"W\",\"warningVersion\":\"1.0\","
                + "\"warningStatus\":\"ISSUED\",\"warningLevel\":\"RED\",\"validToDate\":\"tomorrow\"}}]}";

        assertThat(parser.parseWarnings(doc).malformed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document that is not JSON, or not an object, fails the whole read")
    void badDocument() {
        assertThatThrownBy(() -> parser.parseWarnings("{not json")).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseWarnings("[1,2]")).isInstanceOf(WarningFeedException.class);
        assertThatThrownBy(() -> parser.parseWarnings(null)).isInstanceOf(WarningFeedException.class);
    }

    // ── Types and lists ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("known weather types ignore case and punctuation; anything else is unknown")
    void knownTypes() {
        assertThat(WarningFeedParser.isKnownType("Extreme Heat")).isTrue();
        assertThat(WarningFeedParser.isKnownType("thunderstorm")).isTrue();
        assertThat(WarningFeedParser.isKnownType("MYSTERY_WEATHER")).isFalse();
        assertThat(WarningFeedParser.isKnownType(null)).isFalse();
    }

    @Test
    @DisplayName("stringList reads stored arrays and never throws on garbage")
    void stringList() {
        assertThat(WarningFeedParser.stringList("[\"A\",\"B\"]")).containsExactly("A", "B");
        assertThat(WarningFeedParser.stringList(null)).isEmpty();
        assertThat(WarningFeedParser.stringList("  ")).isEmpty();
        assertThat(WarningFeedParser.stringList("[oops")).isEqualTo(List.of());
    }
}
