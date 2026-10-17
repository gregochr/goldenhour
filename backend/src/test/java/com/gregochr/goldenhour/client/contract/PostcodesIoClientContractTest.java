package com.gregochr.goldenhour.client.contract;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodeLookupUnavailableException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link PostcodesIoClient} — the client in the tree that uses the
 * {@code uri(String, Object...)} template overload with a path variable ({@code lookup}) and with query
 * variables ({@code reverseLookup}).
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
 * <p><strong>Fail-open caveat.</strong> Both lookups catch <em>every</em> {@code Exception} and rethrow
 * a {@code PostcodeLookupException} or {@code PostcodeLookupUnavailableException}, so a failure that is an
 * exception (a wrongly-shaped body, say) resurfaces with the wrong cause. {@link MockRestServiceServer}'s
 * own unmatched-URL failure is an {@code AssertionError}, which that catch does not swallow, but a
 * stubbed request that is never made is only caught by {@code server.verify()}. Each test therefore ends
 * with {@code server.verify()} rather than relying on the call to throw.
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

    /**
     * The wire fields are snake_case ({@code admin_district}); the record components are camelCase.
     * Until R4a nothing bound them, so every place name was the bare postcode — a unit test that
     * builds the record directly (as {@code PostcodesIoClientTest} does) cannot see that.
     */
    @Test
    @DisplayName("lookup builds the place name from the wire's admin_district / parish, not the postcode")
    void lookupBuildsThePlaceNameFromTheWireFields() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/DH13LE"))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        PostcodeLookupResult result = client.lookup("DH1 3LE");

        assertThat(result.placeName()).isEqualTo("Durham, unparished area, County Durham");
        server.verify();
    }

    // ---- reverseLookup (login-registration plan §2 #5) ----

    /**
     * The exact URL is the whole contract. {@code lat}/{@code lon} swapped, a wrong path
     * ({@code /postcodes/lon/{lon}/lat/{lat}} is the older, deprecated form) or the API's default
     * 100 m radius would each return a plausible-looking answer — or none, for most rural homes — and
     * nothing else in the suite would notice.
     */
    @Test
    @DisplayName("reverseLookup GETs exactly /postcodes?lon=&lat=&limit=1&radius=2000 — longitude first "
            + "in the query, the 2000 m maximum radius")
    void reverseLookupSendsTheExactUrl() {
        server.expect(requestTo(
                        "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(readFixture("reverse-response.json"), MediaType.APPLICATION_JSON));

        PostcodeLookupResult result = client.reverseLookup(54.776, -1.575);

        assertThat(result.postcode()).isEqualTo("DH1 3LE");
        server.verify();
    }

    @Test
    @DisplayName("A reverse body maps the first result's latitude and longitude without transposition")
    void reverseLookupMapsCoordinatesInOrder() {
        server.expect(requestTo(
                        "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000"))
                .andRespond(withSuccess(readFixture("reverse-response.json"), MediaType.APPLICATION_JSON));

        PostcodeLookupResult result = client.reverseLookup(54.776, -1.575);

        // Signed and asymmetric on purpose: a lat/lon swap cannot satisfy both.
        assertThat(result.latitude()).isEqualTo(54.7679);
        assertThat(result.longitude()).isEqualTo(-1.5719);
        server.verify();
    }

    @Test
    @DisplayName("The place name is built from the response's snake_case admin_district / parish "
            + "fields, so a reverse hit never falls back to the bare postcode")
    void reverseLookupBuildsThePlaceNameFromTheWireFields() {
        server.expect(requestTo(
                        "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000"))
                .andRespond(withSuccess(readFixture("reverse-response.json"), MediaType.APPLICATION_JSON));

        PostcodeLookupResult result = client.reverseLookup(54.776, -1.575);

        assertThat(result.placeName()).isEqualTo("Durham, unparished area, County Durham");
        server.verify();
    }

    @Test
    @DisplayName("A real 404 from the upstream is 'not found'; a real 500, 429 and a dropped connection "
            + "are 'unavailable' — classified from the status, not guessed")
    void upstreamStatusesAreClassified() {
        String url = "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url)).andRespond(request -> {
            throw new IOException("Connection reset by peer: internal.example:5432");
        });

        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessage("No UK postcode found for that location");
        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupUnavailableException.class)
                .hasMessage("Postcode lookup is unavailable right now");
        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupUnavailableException.class);
        // Neither the I/O error's text nor the upstream's reaches the exception.
        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupUnavailableException.class)
                .hasMessageNotContaining("internal.example");
        server.verify();
    }

    @Test
    @DisplayName("An upstream 400 (its answer for a malformed postcode or coordinates) is 'not found' on "
            + "both lookups, never 'try again later'")
    void upstream400IsNotFound() {
        server.expect(requestTo("https://api.postcodes.io/postcodes/NOTAPOSTCODE"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(
                        "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.lookup("NOTAPOSTCODE"))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessage("Invalid postcode");
        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupException.class)
                .hasMessage("No UK postcode found for that location");
        server.verify();
    }

    @Test
    @DisplayName("A 200 with a null or empty result is 'not found', not 'unavailable'")
    void emptyReverseResultIsNotFound() {
        String url = "https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000";
        server.expect(requestTo(url)).andRespond(
                withSuccess("{\"status\":200,\"result\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(
                withSuccess("{\"status\":200,\"result\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupException.class);
        assertThatThrownBy(() -> client.reverseLookup(54.776, -1.575))
                .isInstanceOf(PostcodeLookupException.class);
        server.verify();
    }

    @Test
    @DisplayName("A coordinate near zero is sent as plain decimal text, never scientific notation")
    void reverseLookupNeverSendsScientificNotation() {
        server.expect(requestTo(
                        "https://api.postcodes.io/postcodes?lon=0.00001&lat=0.00002&limit=1&radius=2000"))
                .andRespond(withSuccess(readFixture("reverse-response.json"), MediaType.APPLICATION_JSON));

        client.reverseLookup(0.00002, 0.00001);

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
