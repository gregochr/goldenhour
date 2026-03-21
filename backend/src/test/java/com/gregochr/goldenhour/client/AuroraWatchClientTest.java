package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraWatchClient} XML parsing.
 */
class AuroraWatchClientTest {

    private AuroraWatchClient client;

    @BeforeEach
    void setUp() {
        var properties = new AuroraProperties();
        // RestClient not needed for parsing tests — will mock for network tests
        client = new AuroraWatchClient(RestClient.create(), properties);
    }

    // -------------------------------------------------------------------------
    // XML parsing — happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Parses AMBER alert from sample XML file")
    void parseXml_amber() throws Exception {
        String xml = readTestXml("aurorawatch-amber.xml");

        AuroraStatus status = client.parseXml(xml);

        assertThat(status.level()).isEqualTo(AlertLevel.AMBER);
        assertThat(status.station()).isEqualTo("SAMNET/CRK2");
        assertThat(status.updatedAt()).isNotNull();
        assertThat(status.expiresAt()).isNotNull();
        assertThat(status.expiresAt()).isAfter(status.updatedAt());
    }

    @Test
    @DisplayName("Parses RED alert from sample XML file")
    void parseXml_red() throws Exception {
        String xml = readTestXml("aurorawatch-red.xml");

        AuroraStatus status = client.parseXml(xml);

        assertThat(status.level()).isEqualTo(AlertLevel.RED);
    }

    @Test
    @DisplayName("Parses GREEN status from sample XML file")
    void parseXml_green() throws Exception {
        String xml = readTestXml("aurorawatch-green.xml");

        AuroraStatus status = client.parseXml(xml);

        assertThat(status.level()).isEqualTo(AlertLevel.GREEN);
        assertThat(status.level().isAlertWorthy()).isFalse();
    }

    @Test
    @DisplayName("Multi-site XML: selects highest-severity site_status (AMBER from green/yellow/amber)")
    void parseXml_multiSite_selectsHighestSeverity() throws Exception {
        String xml = readTestXml("aurorawatch-multi-site.xml");

        AuroraStatus status = client.parseXml(xml);

        assertThat(status.level()).isEqualTo(AlertLevel.AMBER);
        assertThat(status.station()).isEqualTo("SAMNET/CRK2");
    }

    @Test
    @DisplayName("All four status levels parse correctly")
    void parseXml_allFourLevels() throws Exception {
        for (AlertLevel expected : AlertLevel.values()) {
            String xml = buildMinimalXml(expected.name().toLowerCase());
            AuroraStatus status = client.parseXml(xml);
            assertThat(status.level()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Parsed datetimes are offset-aware (not null)")
    void parseXml_datetimesAreOffsetAware() throws Exception {
        String xml = readTestXml("aurorawatch-amber.xml");

        AuroraStatus status = client.parseXml(xml);

        assertThat(status.updatedAt().getOffset()).isNotNull();
        assertThat(status.expiresAt().getOffset()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // XML parsing — error paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Malformed XML throws SAXException")
    void parseXml_malformedXml_throwsSax() {
        assertThatThrownBy(() -> client.parseXml("<not valid xml <<"))
                .isInstanceOf(SAXException.class);
    }

    @Test
    @DisplayName("XML with no site_status elements throws SAXException")
    void parseXml_noSiteStatus_throwsSax() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <current_status api_version="0.2">
                  <updated><datetime>2026-03-20T22:00:00+00:00</datetime></updated>
                  <expires><datetime>2026-03-20T22:10:00+00:00</datetime></expires>
                </current_status>
                """;
        assertThatThrownBy(() -> client.parseXml(xml))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("site_status");
    }

    @Test
    @DisplayName("Unknown status_id throws IllegalArgumentException")
    void parseXml_unknownStatusId_throwsIllegalArgument() {
        String xml = buildMinimalXml("ultraviolet");
        assertThatThrownBy(() -> client.parseXml(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ultraviolet");
    }

    @Test
    @DisplayName("XXE injection attempt is rejected")
    void parseXml_xxeInjection_isRejected() {
        String malicious = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <current_status api_version="0.2">
                  <updated><datetime>2026-03-20T22:00:00+00:00</datetime></updated>
                  <expires><datetime>2026-03-20T22:10:00+00:00</datetime></expires>
                  <site_status station_id="S" station_name="S" status_id="green">
                    <datetime>2026-03-20T22:00:00+00:00</datetime>
                  </site_status>
                </current_status>
                """;
        // Should throw because DOCTYPE is disabled
        assertThatThrownBy(() -> client.parseXml(malicious))
                .isInstanceOf(SAXException.class);
    }

    // -------------------------------------------------------------------------
    // Network failure caching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Network failure retains previously cached status")
    @SuppressWarnings("unchecked")
    void fetchStatus_networkFailure_retainsCached() throws Exception {
        // Build a client backed by a mocked RestClient that first succeeds then fails
        var mockRestClient = mock(RestClient.class);
        var mockReqSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var mockReqHeaders = mock(RestClient.RequestHeadersSpec.class);
        var mockRespSpec = mock(RestClient.ResponseSpec.class);

        var properties = new AuroraProperties();
        properties.setAurorawatchStatusUrl("http://test");

        var testClient = new AuroraWatchClient(mockRestClient, properties);

        String goodXml = readTestXml("aurorawatch-amber.xml");

        when(mockRestClient.get()).thenReturn(mockReqSpec);
        when(mockReqSpec.uri(any(String.class))).thenReturn(mockReqHeaders);
        when(mockReqHeaders.retrieve()).thenReturn(mockRespSpec);
        // First call succeeds
        when(mockRespSpec.body(String.class))
                .thenReturn(goodXml)
                .thenThrow(new RestClientException("network error"));

        AuroraStatus first = testClient.fetchStatus();
        assertThat(first).isNotNull();
        assertThat(first.level()).isEqualTo(AlertLevel.AMBER);

        // Force cache expiry and retry — should fail and return cached
        AuroraStatus second = testClient.fetchStatus();
        // Returns cached on second call (still within expiry window since expiry is 22:10 in the future)
        assertThat(second).isNotNull();
        assertThat(second.level()).isEqualTo(AlertLevel.AMBER);
    }

    @Test
    @DisplayName("fetchStatus returns null when no status has ever been fetched (first-call failure)")
    @SuppressWarnings("unchecked")
    void fetchStatus_firstCallFailure_returnsNull() {
        var mockRestClient = mock(RestClient.class);
        var mockReqSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var mockReqHeaders = mock(RestClient.RequestHeadersSpec.class);
        var mockRespSpec = mock(RestClient.ResponseSpec.class);

        var properties = new AuroraProperties();
        properties.setAurorawatchStatusUrl("http://test");

        var testClient = new AuroraWatchClient(mockRestClient, properties);

        when(mockRestClient.get()).thenReturn(mockReqSpec);
        when(mockReqSpec.uri(any(String.class))).thenReturn(mockReqHeaders);
        when(mockReqHeaders.retrieve()).thenReturn(mockRespSpec);
        when(mockRespSpec.body(String.class)).thenThrow(new RestClientException("network error"));

        AuroraStatus status = testClient.fetchStatus();
        assertThat(status).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String readTestXml(String filename) throws IOException {
        Path path = Path.of("src/test/resources", filename);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String buildMinimalXml(String statusId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <current_status api_version="0.2">
                  <updated><datetime>2026-03-20T22:00:00+00:00</datetime></updated>
                  <expires><datetime>2026-03-20T22:10:00+00:00</datetime></expires>
                  <site_status station_id="S" station_name="Test Station" status_id="%s">
                    <datetime>2026-03-20T22:00:00+00:00</datetime>
                  </site_status>
                </current_status>
                """.formatted(statusId);
    }
}
