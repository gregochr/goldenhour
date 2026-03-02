package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TurnstileService}.
 */
@ExtendWith(MockitoExtension.class)
class TurnstileServiceTest {

    private void setSecretKey(TurnstileService service, String key) {
        ReflectionTestUtils.setField(service, "secretKey", key);
    }

    @Test
    @DisplayName("Returns true when secret key is not configured (development mode)")
    void verify_noSecretKey_returnsTrue() {
        TurnstileService service = new TurnstileService(mock(RestClient.class));
        setSecretKey(service, "");
        assertThat(service.verify("any-token")).isTrue();
    }

    @Test
    @DisplayName("Returns true when secret key is null (development mode)")
    void verify_nullSecretKey_returnsTrue() {
        TurnstileService service = new TurnstileService(mock(RestClient.class));
        setSecretKey(service, null);
        assertThat(service.verify("any-token")).isTrue();
    }

    @Test
    @DisplayName("Returns false when token is null")
    void verify_nullToken_returnsFalse() {
        TurnstileService service = new TurnstileService(mock(RestClient.class));
        setSecretKey(service, "secret");
        assertThat(service.verify(null)).isFalse();
    }

    @Test
    @DisplayName("Returns false when token is blank")
    void verify_blankToken_returnsFalse() {
        TurnstileService service = new TurnstileService(mock(RestClient.class));
        setSecretKey(service, "secret");
        assertThat(service.verify("  ")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Returns true when Cloudflare responds with success")
    void verify_successResponse_returnsTrue() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        Map<String, Object> response = Map.of("success", true);
        when(mockClient.post().uri(anyString()).body(any(Object.class))
                .retrieve().body(Map.class)).thenReturn(response);

        TurnstileService service = new TurnstileService(mockClient);
        setSecretKey(service, "secret");

        assertThat(service.verify("valid-token")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Returns false when Cloudflare responds with failure")
    void verify_failureResponse_returnsFalse() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        Map<String, Object> response = Map.of("success", false, "error-codes", List.of("invalid-input-response"));
        when(mockClient.post().uri(anyString()).body(any(Object.class))
                .retrieve().body(Map.class)).thenReturn(response);

        TurnstileService service = new TurnstileService(mockClient);
        setSecretKey(service, "secret");

        assertThat(service.verify("bad-token")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Returns false when Cloudflare returns null response")
    void verify_nullResponse_returnsFalse() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.post().uri(anyString()).body(any(Object.class))
                .retrieve().body(Map.class)).thenReturn(null);

        TurnstileService service = new TurnstileService(mockClient);
        setSecretKey(service, "secret");

        assertThat(service.verify("some-token")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Returns false when REST call throws an exception")
    void verify_exception_returnsFalse() {
        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.post().uri(anyString()).body(any(Object.class))
                .retrieve().body(Map.class))
                .thenThrow(new RuntimeException("Connection timeout"));

        TurnstileService service = new TurnstileService(mockClient);
        setSecretKey(service, "secret");

        assertThat(service.verify("some-token")).isFalse();
    }
}
