package com.gregochr.goldenhour.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Test support for stubbing Spring {@link RestClient} fluent chains without the per-file
 * boilerplate of hand-mocking {@code get()/post() → uri(...) → retrieve() → body(...)}.
 *
 * <p>Each method takes a plain {@code mock(RestClient.class)} and stubs the whole chain in one
 * call, so a test only has to express the canned response (or the throwable) it cares about.
 *
 * <h2>Strictness</h2>
 * The intermediate fluent specs — {@code get()/post()}, every {@code uri(...)} overload,
 * {@code headers()/contentType()/header()/body(payload)} and {@code retrieve()} — are stubbed
 * {@link org.mockito.Mockito#lenient() leniently}. This is deliberate: a single helper call has to
 * cover whichever {@code uri(...)} overload the class under test happens to invoke, so the other
 * overloads are stubbed-but-unused and would otherwise trip {@code STRICT_STUBS}. This is purely
 * structural plumbing leniency — it never relaxes a stub that carries a meaningful input, so it is
 * distinct from the {@code lenient()} smell called out in {@code test-improvement-standards.md}
 * (which targets behavioural stubs that mask incorrect arguments). The one meaningful stub — the
 * terminal {@code body(...)}/{@code toBodilessEntity()} on the {@code ResponseSpec} — is left
 * strict, so a test that stubs a response the SUT never reads still fails.
 *
 * <p>Because {@code uri(...)} is matched with {@code any(...)}, these helpers do NOT exercise
 * URL-building. Tests that need to assert the exact request (URI, query params, body) must use
 * {@code MockRestServiceServer} instead — this helper is for tests that only consume a response.
 */
public final class RestClientMocks {

    private RestClientMocks() {
    }

    /**
     * Stubs {@code client.get()...retrieve().body(bodyType)} to return {@code body}.
     *
     * @param client   the mocked {@link RestClient}
     * @param bodyType the response body type the class under test requests
     * @param body     the canned body to return (may be {@code null})
     * @param <T>      the response body type
     */
    public static <T> void stubGet(RestClient client, Class<T> bodyType, T body) {
        when(getResponseSpec(client).body(bodyType)).thenReturn(body);
    }

    /**
     * Stubs {@code client.get()...retrieve().body(bodyType)} to return the given bodies in
     * sequence, one per successive GET (for classes that issue several GETs against one client).
     *
     * @param client   the mocked {@link RestClient}
     * @param bodyType the response body type the class under test requests
     * @param first    the first canned body to return
     * @param rest     subsequent canned bodies, returned in order
     * @param <T>      the response body type
     */
    @SafeVarargs
    public static <T> void stubGetSequence(RestClient client, Class<T> bodyType, T first, T... rest) {
        var stubbing = when(getResponseSpec(client).body(bodyType)).thenReturn(first);
        for (T next : rest) {
            stubbing = stubbing.thenReturn(next);
        }
    }

    /**
     * Stubs {@code client.get()...retrieve().body(bodyType)} to throw {@code error}.
     *
     * @param client   the mocked {@link RestClient}
     * @param bodyType the response body type the class under test requests
     * @param error    the throwable to raise when the body is read
     */
    public static void stubGetThrows(RestClient client, Class<?> bodyType, Throwable error) {
        when(getResponseSpec(client).body(bodyType)).thenThrow(error);
    }

    /**
     * Stubs {@code client.get()...retrieve().toBodilessEntity()} to return {@code response}.
     *
     * @param client   the mocked {@link RestClient}
     * @param response the canned bodiless response entity
     */
    public static void stubGetBodilessEntity(RestClient client, ResponseEntity<Void> response) {
        when(getResponseSpec(client).toBodilessEntity()).thenReturn(response);
    }

    /**
     * Stubs {@code client.get()...retrieve().toBodilessEntity()} to throw {@code error}.
     *
     * @param client the mocked {@link RestClient}
     * @param error  the throwable to raise when the response is read
     */
    public static void stubGetBodilessEntityThrows(RestClient client, Throwable error) {
        when(getResponseSpec(client).toBodilessEntity()).thenThrow(error);
    }

    /**
     * Stubs {@code client.post()...retrieve().body(bodyType)} to return {@code body}.
     *
     * @param client   the mocked {@link RestClient}
     * @param bodyType the response body type the class under test requests
     * @param body     the canned body to return (may be {@code null})
     * @param <T>      the response body type
     */
    public static <T> void stubPost(RestClient client, Class<T> bodyType, T body) {
        when(postResponseSpec(client).body(bodyType)).thenReturn(body);
    }

    /**
     * Stubs {@code client.post()...retrieve().body(bodyType)} to throw {@code error}.
     *
     * @param client   the mocked {@link RestClient}
     * @param bodyType the response body type the class under test requests
     * @param error    the throwable to raise when the body is read
     */
    public static void stubPostThrows(RestClient client, Class<?> bodyType, Throwable error) {
        when(postResponseSpec(client).body(bodyType)).thenThrow(error);
    }

    /**
     * Builds the leniently-stubbed GET plumbing and returns its terminal {@link RestClient.ResponseSpec}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestClient.ResponseSpec getResponseSpec(RestClient client) {
        RestClient.RequestHeadersUriSpec spec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        lenient().doReturn(spec).when(client).get();
        stubAllUriOverloads(spec);
        lenient().doReturn(spec).when(spec).headers(any());
        lenient().doReturn(responseSpec).when(spec).retrieve();
        return responseSpec;
    }

    /**
     * Builds the leniently-stubbed POST plumbing and returns its terminal {@link RestClient.ResponseSpec}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestClient.ResponseSpec postResponseSpec(RestClient client) {
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        lenient().doReturn(spec).when(client).post();
        stubAllUriOverloads(spec);
        lenient().doReturn(spec).when(spec).contentType(any());
        lenient().doReturn(spec).when(spec).header(anyString(), any(String[].class));
        // any(Object.class) — not bare any() — pins the body(Object) overload; bare any() binds
        // the more-specific body(StreamingHttpOutputMessage.Body) overload and misses the call.
        lenient().doReturn(spec).when(spec).body(any(Object.class));
        lenient().doReturn(responseSpec).when(spec).retrieve();
        return responseSpec;
    }

    /**
     * Stubs all five {@code UriSpec.uri(...)} overloads on the given spec to return the spec itself,
     * so the funnel transparently covers whichever overload the class under test invokes:
     * {@code uri(String, Object...)}, {@code uri(String, Map)}, {@code uri(String, Function)},
     * {@code uri(URI)}, and {@code uri(Function)}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubAllUriOverloads(RestClient.UriSpec spec) {
        lenient().doReturn(spec).when(spec).uri(anyString());
        lenient().doReturn(spec).when(spec).uri(anyString(), any(Object[].class));
        lenient().doReturn(spec).when(spec).uri(anyString(), any(Map.class));
        lenient().doReturn(spec).when(spec).uri(anyString(), any(Function.class));
        lenient().doReturn(spec).when(spec).uri(any(URI.class));
        lenient().doReturn(spec).when(spec).uri(any(Function.class));
    }
}
