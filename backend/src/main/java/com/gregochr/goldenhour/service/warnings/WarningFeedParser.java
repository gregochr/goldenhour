package com.gregochr.goldenhour.service.warnings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one place that reads the Met Office NSWWS wire formats. If the real API differs from what is assumed below,
 * this is the only class that has to change.
 *
 * <h2>Assumptions about the feed (not verified against the live API; no key was available when this was written)</h2>
 * <ul>
 *   <li>The feed is Atom (RFC 4287): {@code <feed><entry>...</entry></feed>}, any namespace prefix.</li>
 *   <li>Each entry carries a {@code <link href="...">} to the warning's GeoJSON detail. A link whose
 *       {@code type} mentions {@code json} is preferred, then a {@code rel="alternate"} or rel-less link, then
 *       the first link with an href.</li>
 *   <li>The warning id and version are the last two path segments of that link (or, failing that, of the entry's
 *       {@code <id>}): {@code .../{warningId}/{warningVersion}}, the version being an integer.</li>
 *   <li>The detail is a GeoJSON {@code FeatureCollection} (or one bare {@code Feature}) whose first feature has a
 *       {@code MultiPolygon} (or {@code Polygon}) geometry and {@code properties} named {@code warningId},
 *       {@code warningVersion}, {@code warningStatus}, {@code warningLevel}, {@code warningLikelihood},
 *       {@code warningImpact}, {@code weatherType} (array), {@code validFromDate}, {@code validToDate},
 *       {@code issuedDate}, {@code modifiedDate}, {@code warningHeadline}, {@code whatToExpect} (array),
 *       {@code warningFurtherDetails} and {@code affectedAreas} (array). Dates are ISO-8601 with an offset or Z.</li>
 *   <li>Any status containing "cancel" (any case) is a cancellation; every other status (or none) is live.</li>
 *   <li>A live warning needs a level and a {@code validToDate}; a cancellation needs neither.</li>
 * </ul>
 * Weather types and levels are strings, never enums: unknown types are kept and counted ({@link #isKnownType}).
 */
@Component
public class WarningFeedParser {

    /** Weather types the app has a glyph for, in normalised form. Anything else is kept and counted as unknown. */
    static final Set<String> KNOWN_WEATHER_TYPES = Set.of(
            "WIND", "RAIN", "SNOW", "ICE", "THUNDERSTORM", "FOG", "EXTREME_HEAT", "LIGHTNING");

    /** The path segments a link needs to carry a warning id and a version. */
    private static final int ID_SEGMENTS = 2;

    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One entry read from the Atom feed.
     *
     * @param warningId      the warning id
     * @param warningVersion the version
     * @param detailUri      the https link to the GeoJSON detail
     */
    public record FeedEntry(String warningId, int warningVersion, URI detailUri) {
    }

    /**
     * The result of reading the feed.
     *
     * @param entries   the entries with a usable id, version and link
     * @param malformed how many entries had none of those and were skipped
     */
    public record FeedParse(List<FeedEntry> entries, int malformed) {
    }

    /**
     * Reads the Atom feed. An entry that cannot be identified is skipped and counted; a document that is not
     * parseable at all fails the whole read.
     *
     * @param atomXml the raw feed
     * @return the entries and the skipped count
     * @throws WarningFeedException when the feed is not well-formed XML
     */
    public FeedParse parseFeed(String atomXml) {
        Document document = readXml(atomXml);
        Element root = document.getDocumentElement();
        if (!ATOM_NAMESPACE.equals(root.getNamespaceURI()) || !"feed".equals(root.getLocalName())) {
            throw new WarningFeedException("Document root is not an Atom feed");
        }
        NodeList nodes = document.getElementsByTagNameNS("*", "entry");
        List<FeedEntry> entries = new ArrayList<>();
        int malformed = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            FeedEntry entry = readEntry((Element) nodes.item(i));
            if (entry == null) {
                malformed++;
            } else {
                entries.add(entry);
            }
        }
        return new FeedParse(entries, malformed);
    }

    /**
     * Reads one warning's GeoJSON detail into an (unsaved) entity. {@code receivedAt} and {@code simulated} are left
     * for the caller.
     *
     * @param geoJson the raw detail
     * @param entry   the feed entry it was fetched for; the detail's own id and version must agree with it
     * @return the parsed version
     * @throws WarningFeedException when the detail is malformed or disagrees with its entry
     */
    public WeatherWarningVersionEntity parseDetail(String geoJson, FeedEntry entry) {
        JsonNode root = readJson(geoJson);
        JsonNode feature = root.has("features") ? root.path("features").path(0) : root;
        JsonNode properties = feature.path("properties");
        if (!properties.isObject()) {
            throw new WarningFeedException("Warning detail has no properties");
        }
        String id = text(properties, "warningId");
        if (id != null && !id.equals(entry.warningId())) {
            throw new WarningFeedException("Warning detail id disagrees with its feed entry");
        }
        JsonNode version = properties.path("warningVersion");
        if (version.isValueNode() && !version.isNull()
                && !version.asText().trim().equals(Integer.toString(entry.warningVersion()))) {
            throw new WarningFeedException("Warning detail version disagrees with its feed entry");
        }
        WeatherWarningVersionEntity row = new WeatherWarningVersionEntity();
        row.setWarningId(entry.warningId());
        row.setWarningVersion(entry.warningVersion());
        String sourceStatus = text(properties, "warningStatus");
        row.setSourceStatus(sourceStatus);
        boolean cancelled = sourceStatus != null && sourceStatus.toLowerCase(Locale.ROOT).contains("cancel");
        row.setStatus(cancelled ? WeatherWarningVersionEntity.STATUS_CANCELLED
                : WeatherWarningVersionEntity.STATUS_ACTIVE);
        row.setWarningLevel(text(properties, "warningLevel"));
        row.setWeatherTypes(jsonArray(properties.path("weatherType")));
        row.setLikelihood(text(properties, "warningLikelihood"));
        row.setImpact(text(properties, "warningImpact"));
        row.setValidFrom(instant(properties, "validFromDate"));
        row.setValidTo(instant(properties, "validToDate"));
        row.setIssuedAt(instant(properties, "issuedDate"));
        row.setModifiedAt(instant(properties, "modifiedDate"));
        row.setHeadline(text(properties, "warningHeadline"));
        row.setWhatToExpect(jsonArray(properties.path("whatToExpect")));
        row.setFurtherDetails(text(properties, "warningFurtherDetails"));
        row.setAffectedAreas(jsonArray(properties.path("affectedAreas")));
        row.setSourceUrl(truncate(entry.detailUri().toString()));
        JsonNode geometry = feature.path("geometry");
        if (geometry.isObject()) {
            row.setGeometry(geometry.toString());
            row.setSimplifiedGeometry(WarningGeometry.simplify(geometry));
        }
        if (!cancelled && (row.getWarningLevel() == null || row.getValidTo() == null)) {
            throw new WarningFeedException("Live warning detail lacks a level or an end time");
        }
        return row;
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
     * Reads back stored JSON array text (weather types, what to expect, affected areas).
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

    private static FeedEntry readEntry(Element entry) {
        List<Element> links = children(entry, "link");
        Element link = preferredLink(links);
        if (link == null) {
            return null;
        }
        String href = link.getAttribute("href").trim();
        String[] key = idAndVersion(href);
        if (key == null) {
            key = idAndVersion(childText(entry, "id"));
        }
        if (key == null) {
            return null;
        }
        try {
            URI uri = URI.create(href);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return new FeedEntry(key[0], Integer.parseInt(key[1]), uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Element preferredLink(List<Element> links) {
        Element rel = null;
        Element any = null;
        for (Element link : links) {
            if (link.getAttribute("href").isBlank()) {
                continue;
            }
            if (link.getAttribute("type").toLowerCase(Locale.ROOT).contains("json")) {
                return link;
            }
            String relation = link.getAttribute("rel");
            if (rel == null && (relation.isEmpty() || "alternate".equals(relation))) {
                rel = link;
            }
            if (any == null) {
                any = link;
            }
        }
        return rel != null ? rel : any;
    }

    /** The last two non-empty path segments of a URL or id, the second being an integer; else null. */
    private static String[] idAndVersion(String value) {
        if (value == null) {
            return null;
        }
        String path = value.split("[?#]", 2)[0];
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        if (segments.size() < ID_SEGMENTS) {
            return null;
        }
        String version = segments.get(segments.size() - 1);
        String id = segments.get(segments.size() - ID_SEGMENTS);
        if (!version.matches("\\d{1,9}")) {
            return null;
        }
        return new String[] {id, version};
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
                throw new WarningFeedException("Warning detail is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new WarningFeedException("Warning detail is not valid JSON", e);
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

    private static String truncate(String url) {
        final int max = 500;
        return url.length() <= max ? url : url.substring(0, max);
    }
}
