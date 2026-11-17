package com.gregochr.goldenhour.service.warnings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one place that reads the Met Office NSWWS v1.1 wire formats, recorded from the real feed (see
 * {@code docs/engineering/weather-warnings-plan.md} section 11; fixtures under {@code test/resources/warnings/real}).
 *
 * <h2>The feed</h2>
 * <ul>
 *   <li>Atom (RFC 4287). Each {@code <entry>} is a change event: its {@code <id>} is {@code urn:uuid:<uuid>} (its
 *       {@code rel="alternate"} link ends in the same uuid), and the uuid names an {@code updated/{uuid}} document.
 *       The entry id is not a warning id.</li>
 *   <li>The feed-level {@code rel="related"} link is the {@code issued/{uuid}} snapshot of every currently valid
 *       warning. Its uuid is the feed's own id.</li>
 * </ul>
 *
 * <h2>The documents ({@code updated} and {@code issued})</h2>
 * A GeoJSON {@code FeatureCollection} of N warnings (N may be zero). Each feature carries a geometry and
 * {@code properties}: {@code warningId} (uuid), {@code warningVersion} (a decimal string such as "3.0"),
 * {@code warningStatus} (ISSUED, CANCELLED or EXPIRED), {@code warningLevel} (upper case), {@code warningLikelihood}
 * and {@code warningImpact} (integers), {@code weatherType} (array), {@code validFromDate}, {@code validToDate},
 * {@code issuedDate}, {@code modifiedDate}, {@code warningHeadline}, {@code whatToExpect} (array),
 * {@code whatShouldIDo}, {@code warningFurtherDetails}, {@code warningUpdateDescription} and {@code affectedAreas}
 * (objects with a {@code regionCode}). ISSUED is live; CANCELLED and EXPIRED both mean ended (stored as
 * CANCELLED, the source word kept). A feature that cannot be identified (no id, no version, an unknown status) is
 * skipped and counted, never guessed at.
 *
 * <p>Weather types and levels are strings, never enums: unknown types are kept and counted
 * ({@link #isKnownType}).
 */
@Component
public class WarningFeedParser {

    /** Weather types the app has a glyph for, in normalised form. Anything else is kept and counted as unknown. */
    static final Set<String> KNOWN_WEATHER_TYPES = Set.of(
            "WIND", "RAIN", "SNOW", "ICE", "THUNDERSTORM", "FOG", "EXTREME_HEAT", "LIGHTNING");

    private static final Logger LOG = LoggerFactory.getLogger(WarningFeedParser.class);

    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";

    private static final String STATUS_ISSUED = "ISSUED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private static final int VERSION_SCALE = 2;
    private static final int MAX_ID_LENGTH = 100;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_VERSION_INTEGER_DIGITS = 7;
    private static final int MAX_SMALL_INT = 99;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The result of reading the Atom feed.
     *
     * @param updateIds the change-event uuids of the entries, in feed order
     * @param issuedId  the uuid of the issued snapshot named by the feed's related link, or null when absent
     * @param malformed how many entries had no readable uuid and were skipped
     */
    public record FeedParse(List<String> updateIds, String issuedId, int malformed) {
    }

    /**
     * The warnings read from one {@code updated} or {@code issued} document.
     *
     * @param warnings  the parsed versions, unsaved ({@code receivedAt} and {@code simulated} are left to the caller)
     * @param malformed how many features could not be read and were skipped
     */
    public record WarningParse(List<WeatherWarningVersionEntity> warnings, int malformed) {
    }

    /**
     * Reads the Atom feed. An entry without a readable uuid is skipped and counted; a document that is not
     * parseable at all fails the whole read.
     *
     * @param atomXml the raw feed
     * @return the change-event uuids, the issued snapshot uuid and the skipped count
     * @throws WarningFeedException when the feed is not well-formed Atom XML
     */
    public FeedParse parseFeed(String atomXml) {
        Document document = readXml(atomXml);
        Element root = document.getDocumentElement();
        if (!ATOM_NAMESPACE.equals(root.getNamespaceURI()) || !"feed".equals(root.getLocalName())) {
            throw new WarningFeedException("Document root is not an Atom feed");
        }
        List<String> ids = new ArrayList<>();
        int malformed = 0;
        NodeList nodes = document.getElementsByTagNameNS("*", "entry");
        for (int i = 0; i < nodes.getLength(); i++) {
            String id = entryUuid((Element) nodes.item(i));
            if (id == null) {
                malformed++;
            } else {
                ids.add(id);
            }
        }
        return new FeedParse(ids, issuedUuid(root), malformed);
    }

    /**
     * Reads a GeoJSON {@code FeatureCollection} (an {@code updated} or {@code issued} document) into unsaved
     * entities. A single bare {@code Feature} is accepted too. A feature that cannot be identified is skipped and
     * counted.
     *
     * @param geoJson the raw document
     * @return the parsed versions and the skipped count
     * @throws WarningFeedException when the document is not a JSON object
     */
    public WarningParse parseWarnings(String geoJson) {
        JsonNode root = readJson(geoJson);
        List<WeatherWarningVersionEntity> rows = new ArrayList<>();
        int malformed = 0;
        List<JsonNode> features = new ArrayList<>();
        if (root.path("features").isArray()) {
            root.path("features").forEach(features::add);
        } else if (root.has("properties")) {
            features.add(root);
        }
        for (JsonNode feature : features) {
            try {
                rows.add(parseFeature(feature));
            } catch (WarningFeedException e) {
                LOG.warn("Skipping malformed warning feature: {}", e.getMessage());
                malformed++;
            }
        }
        return new WarningParse(rows, malformed);
    }

    /**
     * Whether a weather type is one the app has a glyph for.
     *
     * @param weatherType a type as received
     * @return true when known (case and punctuation ignored)
     */
    public static boolean isKnownType(String weatherType) {
        return weatherType != null && KNOWN_WEATHER_TYPES.contains(normaliseType(weatherType));
    }

    /**
     * Reads back stored JSON array text (weather types, what to expect, region codes).
     *
     * @param jsonArrayText a stored array column, possibly null or unreadable
     * @return the strings as received; empty when none can be read
     */
    public static List<String> stringList(String jsonArrayText) {
        List<String> types = new ArrayList<>();
        if (jsonArrayText == null || jsonArrayText.isBlank()) {
            return types;
        }
        try {
            for (JsonNode item : MAPPER.readTree(jsonArrayText)) {
                types.add(item.asText());
            }
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
        return types;
    }

    private static String normaliseType(String weatherType) {
        return weatherType.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static WeatherWarningVersionEntity parseFeature(JsonNode feature) {
        JsonNode properties = feature.path("properties");
        if (!properties.isObject()) {
            throw new WarningFeedException("Warning feature has no properties");
        }
        String id = text(properties, "warningId");
        if (id == null || id.length() > MAX_ID_LENGTH) {
            throw new WarningFeedException("Warning feature has no usable warningId");
        }
        WeatherWarningVersionEntity row = new WeatherWarningVersionEntity();
        row.setWarningId(id);
        row.setWarningVersion(version(text(properties, "warningVersion")));
        String sourceStatus = upper(text(properties, "warningStatus"));
        boolean live = STATUS_ISSUED.equals(sourceStatus);
        if (!live && !STATUS_CANCELLED.equals(sourceStatus) && !STATUS_EXPIRED.equals(sourceStatus)) {
            throw new WarningFeedException("Warning feature has an unknown status");
        }
        row.setSourceStatus(sourceStatus);
        row.setStatus(live ? WeatherWarningVersionEntity.STATUS_ACTIVE
                : WeatherWarningVersionEntity.STATUS_CANCELLED);
        row.setWarningLevel(upper(text(properties, "warningLevel")));
        row.setWeatherTypes(jsonArray(properties.path("weatherType")));
        row.setLikelihood(smallInt(properties, "warningLikelihood"));
        row.setImpact(smallInt(properties, "warningImpact"));
        row.setValidFrom(instant(properties, "validFromDate"));
        row.setValidTo(instant(properties, "validToDate"));
        row.setIssuedAt(instant(properties, "issuedDate"));
        row.setModifiedAt(instant(properties, "modifiedDate"));
        row.setHeadline(text(properties, "warningHeadline"));
        row.setWhatToExpect(jsonArray(properties.path("whatToExpect")));
        row.setWhatShouldIDo(text(properties, "whatShouldIDo"));
        row.setFurtherDetails(text(properties, "warningFurtherDetails"));
        row.setUpdateDescription(text(properties, "warningUpdateDescription"));
        row.setAffectedAreas(regionCodes(properties.path("affectedAreas")));
        JsonNode geometry = feature.path("geometry");
        if (geometry.isObject()) {
            row.setGeometry(geometry.toString());
            row.setSimplifiedGeometry(WarningGeometry.simplify(geometry));
        }
        if (live && (row.getWarningLevel() == null || row.getValidTo() == null)) {
            throw new WarningFeedException("Live warning lacks a level or an end time");
        }
        return row;
    }

    private static BigDecimal version(String value) {
        if (value == null) {
            throw new WarningFeedException("Warning feature has no usable warningVersion");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new WarningFeedException("Warning feature has an unreadable warningVersion", e);
        }
        if (parsed.signum() < 0 || parsed.scale() < 0 || parsed.scale() > VERSION_SCALE
                || parsed.precision() - parsed.scale() > MAX_VERSION_INTEGER_DIGITS) {
            throw new WarningFeedException("Warning feature has an out-of-range warningVersion");
        }
        return parsed.setScale(VERSION_SCALE);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static Integer smallInt(JsonNode properties, String field) {
        String value = text(properties, field);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 || parsed > MAX_SMALL_INT ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The region codes of the {@code affectedAreas} objects as JSON array text, without duplicates; null if none. */
    private static String regionCodes(JsonNode areas) {
        if (!areas.isArray()) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode area : areas) {
            String code = text(area, "regionCode");
            if (code != null) {
                codes.add(code);
            }
        }
        return MAPPER.valueToTree(new ArrayList<>(codes)).toString();
    }

    /** The uuid of an entry: from its {@code <id>} ({@code urn:uuid:...}), else its alternate link's last segment. */
    private static String entryUuid(Element entry) {
        String fromId = uuidIn(childText(entry, "id"));
        if (fromId != null) {
            return fromId;
        }
        for (Element link : children(entry, "link")) {
            String relation = link.getAttribute("rel");
            if (relation.isEmpty() || "alternate".equals(relation)) {
                String fromLink = uuidIn(link.getAttribute("href"));
                if (fromLink != null) {
                    return fromLink;
                }
            }
        }
        return null;
    }

    /** The issued snapshot's uuid: the feed-level related link's last segment, else the feed's own id. */
    private static String issuedUuid(Element feed) {
        for (Element link : children(feed, "link")) {
            if ("related".equals(link.getAttribute("rel"))) {
                String fromLink = uuidIn(link.getAttribute("href"));
                if (fromLink != null) {
                    return fromLink;
                }
            }
        }
        return uuidIn(childText(feed, "id"));
    }

    /** The last uuid-shaped segment of a URL or URN, lower-cased; null when there is none. */
    private static String uuidIn(String value) {
        if (value == null) {
            return null;
        }
        String found = null;
        for (String segment : value.split("[/:?#]")) {
            if (UUID_PATTERN.matcher(segment).matches()) {
                found = segment.toLowerCase(Locale.ROOT);
            }
        }
        return found;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && localName.equals(child.getLocalName())) {
                out.add(child);
            }
        }
        return out;
    }

    private static String childText(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    private static Document readXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml == null ? "" : xml)));
        } catch (Exception e) {
            throw new WarningFeedException("Feed is not well-formed Atom XML", e);
        }
    }

    private static JsonNode readJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json == null ? "" : json);
            if (root == null || !root.isObject()) {
                throw new WarningFeedException("Warning document is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new WarningFeedException("Warning document is not valid JSON", e);
        }
    }

    private static String text(JsonNode properties, String field) {
        JsonNode node = properties.path(field);
        if (!node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Instant instant(JsonNode properties, String field) {
        String value = text(properties, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new WarningFeedException("Unreadable date in " + field, e);
        }
    }

    /** An array (or a lone value) as JSON array text of strings; null when absent. */
    private static String jsonArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isValueNode() && !item.isNull()) {
                    values.add(item.asText());
                }
            }
        } else if (node.isValueNode() && !node.isNull()) {
            values.add(node.asText());
        } else {
            return null;
        }
        return MAPPER.valueToTree(values).toString();
    }
}
