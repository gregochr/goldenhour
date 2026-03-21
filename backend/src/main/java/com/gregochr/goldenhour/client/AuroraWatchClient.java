package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Fetches and parses the current AuroraWatch UK status from the v0.2 XML API.
 *
 * <p>The client caches the last successful response until the {@code <expires>} datetime
 * in the XML, preventing redundant fetches. On network failure or malformed XML the
 * previous cached status is retained and a warning is logged.
 *
 * <p>XXE injection is prevented by disabling DOCTYPE declarations and external entity
 * resolution on the {@link DocumentBuilder}.
 */
@Component
public class AuroraWatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraWatchClient.class);

    private final RestClient restClient;
    private final AuroraProperties properties;

    /** Last successfully parsed status — {@code null} until the first successful fetch. */
    private volatile AuroraStatus cachedStatus;

    /**
     * Constructs the client with the shared {@link RestClient} and aurora configuration.
     *
     * @param restClient shared HTTP client
     * @param properties aurora configuration properties
     */
    public AuroraWatchClient(RestClient restClient, AuroraProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Returns the current AuroraWatch status, re-fetching from the API only if the
     * cached status has expired.
     *
     * <p>If the fetch or parse fails, the previous cached status is returned (or
     * {@code null} if no successful fetch has occurred yet).
     *
     * @return the current {@link AuroraStatus}, or {@code null} on first-call failure
     */
    public AuroraStatus fetchStatus() {
        AuroraStatus current = cachedStatus;
        if (current != null && ZonedDateTime.now().isBefore(current.expiresAt())) {
            return current;
        }
        try {
            String xml = restClient.get()
                    .uri(properties.getAurorawatchStatusUrl())
                    .retrieve()
                    .body(String.class);
            AuroraStatus parsed = parseXml(xml);
            cachedStatus = parsed;
            return parsed;
        } catch (Exception e) {
            LOG.warn("Failed to fetch AuroraWatch status (retaining cached): {}", e.getMessage());
            return cachedStatus;
        }
    }

    /**
     * Parses the AuroraWatch XML response into an {@link AuroraStatus}.
     *
     * <p>Finds all {@code <site_status>} elements and selects the one with the highest
     * severity {@code status_id}. The {@code <updated>} and {@code <expires>} datetimes
     * are read from the root document.
     *
     * @param xml raw XML response body
     * @return parsed {@link AuroraStatus}
     * @throws ParserConfigurationException if the XML parser cannot be configured
     * @throws SAXException                 if the XML is malformed
     * @throws IOException                  if the byte stream cannot be read
     */
    AuroraStatus parseXml(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Prevent XXE injection
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        ZonedDateTime updatedAt = parseFirstDatetime(doc, "updated");
        ZonedDateTime expiresAt = parseFirstDatetime(doc, "expires");

        // Find all site_status elements; pick the one with the highest severity.
        NodeList statusNodes = doc.getElementsByTagName("site_status");
        AlertLevel highestLevel = AlertLevel.GREEN;
        String station = "unknown";

        for (int i = 0; i < statusNodes.getLength(); i++) {
            Element el = (Element) statusNodes.item(i);
            String statusId = el.getAttribute("status_id");
            AlertLevel level = AlertLevel.fromStatusId(statusId);
            if (level.severity() > highestLevel.severity()) {
                highestLevel = level;
                station = el.getAttribute("station_name");
            }
            // Capture the first station name regardless, so we always have a non-empty value
            if (i == 0 && station.isBlank()) {
                station = el.getAttribute("station_name");
            }
        }

        if (statusNodes.getLength() == 0) {
            throw new SAXException("No <site_status> elements found in AuroraWatch XML");
        }

        return new AuroraStatus(highestLevel, updatedAt, station, expiresAt);
    }

    /**
     * Extracts the first {@code <datetime>} child text from the named element.
     *
     * @param doc         the parsed XML document
     * @param elementName the element name (e.g. {@code "updated"})
     * @return the parsed {@link ZonedDateTime}, or one hour from now as a safe fallback
     */
    private ZonedDateTime parseFirstDatetime(Document doc, String elementName) {
        NodeList nodes = doc.getElementsByTagName(elementName);
        if (nodes.getLength() == 0) {
            return ZonedDateTime.now().plusHours(1);
        }
        Element el = (Element) nodes.item(0);
        NodeList datetimeNodes = el.getElementsByTagName("datetime");
        if (datetimeNodes.getLength() == 0) {
            return ZonedDateTime.now().plusHours(1);
        }
        String raw = datetimeNodes.item(0).getTextContent().trim();
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            LOG.warn("Could not parse AuroraWatch datetime '{}': {}", raw, e.getMessage());
            return ZonedDateTime.now().plusHours(1);
        }
    }
}
