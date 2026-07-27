package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link PostcodesIoClient} — the only client in the tree that uses the
 * {@code uri(String, Object...)} template overload with a path variable.
 *
 * <p><strong>Why this exists.</strong> {@code PostcodesIoClientTest} stubs through
 * {@code RestClientMocks}, which matches {@code uri(anyString(), any(Object[].class))} — both the
 * template and the interpolated value are discarded. Nothing today asserts that the postcode ever
 * reaches the URL, nor which endpoint it reaches.
 *
 * <p><strong>The silent-wrong mode.</strong> {@code /postcodes/{p}}, {@code /outcodes/{p}} and
 * {@code /postcodes/{p}/validate} are three real postcodes.io endpoints with overlapping response
 * shapes. {@code /outcodes} returns a <em>district centroid</em> — coordinates that are valid,
 * plausible, and up to ~10 km wrong. That feeds user home locations and therefore every drive time.
 * There are no units and no enums here, so the exact-URL assertion is the whole contract.
 *
 * <p><strong>Fail-open caveat.</strong> {@code lookup} wraps <em>every</em> exception into
 * {@code PostcodeLookupException}, so an unmatched-URL assertion error from
 * {@link MockRestServiceServer} is swallowed and resurfaces with the wrong cause. Each test
 * therefore ends with {@code server.verify()} rather than relying on the call to throw.
 */
class PostcodesIoClientContractTest {

    private MockRestServiceServer server;
    private PostcodesIoClient client;
    private String fixture;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new PostcodesIoClient(builder.build());
        this.fixture = readFixture("lookup-response.json");
    }

    @Test
    @DisplayName("lookup GETs /postcodes/{postcode} — not /outcodes, which returns a plausible wrong centroid")
    void lookupUsesThePostcodesEndpoint() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/DH13LE"))
                .andExpect(requestTo(not(startsWith("https://api.postcodes.io/outcodes"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        client.lookup("DH13LE");

        server.verify();
    }

    /**
     * Normalisation (whitespace stripped, upper-cased) happens before interpolation, so the same
     * canonical URL must come out of a messy user-typed postcode. This pins the normalisation and
     * the template together — the unit-level normalisation is invisible unless it reaches the wire.
     */
    @Test
    @DisplayName("A lower-cased, space-containing postcode normalises to the same canonical URL")
    void lookupNormalisesThePostcodeBeforeItReachesTheUrl() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/DH13LE"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        client.lookup("  dh1 3le  ");

        server.verify();
    }

    /**
     * The path variable is user-supplied. {@code uri(String, Object...)} encodes it, so a postcode
     * carrying a path separator or a query delimiter cannot escape its segment. Pinning this makes
     * any future "simplification" to string concatenation — the style
     * {@code LightPollutionClient} uses — a visible, deliberate change rather than a silent
     * removal of the protection.
     */
    @Test
    @DisplayName("A postcode carrying URL metacharacters is percent-encoded into its own path segment")
    void lookupEncodesTheUserSuppliedPathVariable() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/DH1%2F3LE%3FX%3D1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        client.lookup("dh1/3le ?x=1");

        server.verify();
    }

    @Test
    @DisplayName("A well-formed postcodes.io body maps latitude and longitude without transposition")
    void lookupResponseMapsCoordinatesInOrder() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/DH13LE"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        PostcodeLookupResult result = client.lookup("DH1 3LE");

        assertThat(result.postcode()).isEqualTo("DH1 3LE");
        // Signed and asymmetric on purpose: a lat/lon swap cannot satisfy both.
        assertThat(result.latitude()).isEqualTo(54.7679);
        assertThat(result.longitude()).isEqualTo(-1.5719);
        server.verify();
    }

    private static String readFixture(String name) {
        String path = "/contract/postcodesio/" + name;
        try (InputStream in = PostcodesIoClientContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
