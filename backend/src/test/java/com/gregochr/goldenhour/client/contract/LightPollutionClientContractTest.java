package com.gregochr.goldenhour.client.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.config.AuroraProperties;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Outbound contract test for {@link LightPollutionClient} — the only client in the tree that builds
 * its URI by raw string concatenation into {@code URI.create}, with no {@code UriBuilder} and
 * therefore no percent-encoding of any component.
 *
 * <p><strong>The headline silent-wrong: coordinate order.</strong> The QueryRaster API takes
 * {@code qd=longitude,latitude} — longitude <em>first</em>. The production source asserts that
 * twice (class Javadoc and an inline comment) and nowhere in a test. Transpose them and a UK
 * location still returns a valid mcd/m² reading, for somewhere in the North Sea or the Atlantic —
 * i.e. a <em>dark</em> one. {@code mcdToSqm} and {@code sqmToBortle} then yield a perfectly
 * plausible Bortle 1–2, which is exactly the answer {@code BortleEnrichmentService} is hunting for.
 * On this endpoint the wrong answer is more believable than the right one, so nothing downstream
 * can detect it. Same failure shape as {@code 92cb95b5}.
 *
 * <p><strong>The dataset selector.</strong> {@code ql=sb_2025} chooses the sky-brightness dataset
 * vintage; any other layer returns a number in different units, and {@code mcdToSqm}'s hard-coded
 * {@code 108000000.0 / 0.171168465} constants are calibrated to {@code sb_2025} specifically.
 *
 * <p><strong>API key handling.</strong> This client sends a key. These tests assert only that a
 * non-empty key rides the {@code key} query parameter and that no {@code Authorization} header is
 * used instead — never the value. The key used throughout is an obvious placeholder.
 *
 * <p><strong>Fail-open caveat.</strong> {@code querySkyBrightness} swallows
 * {@code RestClientException} and returns null, so an unmatched URL surfaces as a null result
 * rather than a thrown assertion; every test ends with {@code server.verify()}.
 */
class LightPollutionClientContractTest {

    /** Obvious placeholder. Never a real key, and never asserted by value. */
    private static final String PLACEHOLDER_KEY = "placeholder-not-a-real-key";

    /** Durham, UK — signed and asymmetric, so a coordinate transposition cannot pass unnoticed. */
    private static final double LAT = 54.7753;
    private static final double LON = -1.5849;

    private MockRestServiceServer server;
    private LightPollutionClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        // An explicit AuroraProperties instance so the production LightPollutionConfig defaults are
        // what is asserted — the null-config branch would silently substitute the DEFAULT_ constants.
        this.client = new LightPollutionClient(builder.build(), new ObjectMapper(), new AuroraProperties());
    }

    @Test
    @DisplayName("qd sends longitude first, then latitude — a transposition returns a plausible dark sky")
    void queryPutsLongitudeBeforeLatitude() {
        // A raw substring, deliberately: queryParam("qd", ...) would also pass on a transposition,
        // and the ORDER of the two numbers is the entire assertion.
        server.expect(requestTo(containsString("qd=" + LON + "," + LAT)))
                .andExpect(requestTo(not(containsString("qd=" + LAT + "," + LON))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("0.0145", MediaType.TEXT_PLAIN));

        client.querySkyBrightness(LAT, LON, PLACEHOLDER_KEY);

        server.verify();
    }

    @Test
    @DisplayName("Query targets the queryraster endpoint with ql=sb_2025 and qt=point")
    void queryTargetsTheSkyBrightness2025Raster() {
        server.expect(requestTo(containsString("https://www.lightpollutionmap.info/api/queryraster?")))
                .andExpect(requestTo(containsString("ql=sb_2025")))
                .andExpect(requestTo(containsString("qt=point")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("0.0145", MediaType.TEXT_PLAIN));

        client.querySkyBrightness(LAT, LON, PLACEHOLDER_KEY);

        server.verify();
    }

    /**
     * Placement, not value: the key belongs in the {@code key} query parameter, and this API has no
     * bearer-token variant, so an {@code Authorization} header would simply be dropped and the
     * request rejected. The regex asserts the parameter is non-empty without pinning what it holds.
     */
    @Test
    @DisplayName("The API key rides the key query parameter, non-empty, and not an Authorization header")
    void apiKeyIsSentAsANonEmptyQueryParameter() {
        server.expect(requestTo(matchesRegex(".*[?&]key=[^&]+.*")))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("0.0145", MediaType.TEXT_PLAIN));

        client.querySkyBrightness(LAT, LON, PLACEHOLDER_KEY);

        server.verify();
    }

    @Test
    @DisplayName("A bare-number text/plain body converts to SQM and a Bortle class")
    void bareNumberResponseConvertsToBortle() {
        server.expect(requestTo(containsString("queryraster")))
                .andRespond(withSuccess("0.0145", MediaType.TEXT_PLAIN));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(LAT, LON, PLACEHOLDER_KEY);

        assertThat(result).isNotNull();
        assertThat(result.sqm()).isCloseTo(21.91, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.bortle()).isEqualTo(2);
        server.verify();
    }

    /**
     * The API serves JSON with a {@code text/plain} content type, and in the object form the client
     * must deserialise it by hand rather than through a message converter. Both wire shapes are in
     * production use, so both are pinned.
     */
    @Test
    @DisplayName("A JSON object body served as text/plain is deserialised to the same result")
    void jsonObjectResponseConvertsToTheSameResult() {
        server.expect(requestTo(containsString("queryraster")))
                .andRespond(withSuccess(readFixture("brightness-object-response.json"), MediaType.TEXT_PLAIN));

        LightPollutionClient.SkyBrightnessResult result =
                client.querySkyBrightness(LAT, LON, PLACEHOLDER_KEY);

        assertThat(result).isNotNull();
        assertThat(result.bortle()).isEqualTo(2);
        server.verify();
    }

    private static String readFixture(String name) {
        String path = "/contract/lightpollution/" + name;
        try (InputStream in = LightPollutionClientContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("Missing contract fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read contract fixture: " + path, e);
        }
    }
}
