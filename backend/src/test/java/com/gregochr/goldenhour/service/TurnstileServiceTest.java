package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TurnstileService}.
 */
@ExtendWith(MockitoExtension.class)
class TurnstileServiceTest {

    @InjectMocks
    private TurnstileService turnstileService;

    @Mock
    private RestTemplate restTemplate;

    private void setSecretKey(String key) {
        ReflectionTestUtils.setField(turnstileService, "secretKey", key);
    }

    private void injectMockRestTemplate() {
        ReflectionTestUtils.setField(turnstileService, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("Returns true when secret key is not configured (development mode)")
    void verify_noSecretKey_returnsTrue() {
        setSecretKey("");
        assertThat(turnstileService.verify("any-token")).isTrue();
    }

    @Test
    @DisplayName("Returns true when secret key is null (development mode)")
    void verify_nullSecretKey_returnsTrue() {
        setSecretKey(null);
        assertThat(turnstileService.verify("any-token")).isTrue();
    }

    @Test
    @DisplayName("Returns false when token is null")
    void verify_nullToken_returnsFalse() {
        setSecretKey("secret");
        assertThat(turnstileService.verify(null)).isFalse();
    }

    @Test
    @DisplayName("Returns false when token is blank")
    void verify_blankToken_returnsFalse() {
        setSecretKey("secret");
        assertThat(turnstileService.verify("  ")).isFalse();
    }

    @Test
    @DisplayName("Returns true when Cloudflare responds with success")
    void verify_successResponse_returnsTrue() {
        setSecretKey("secret");
        injectMockRestTemplate();
        Map<String, Object> response = Map.of("success", true);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenReturn(response);

        assertThat(turnstileService.verify("valid-token")).isTrue();
    }

    @Test
    @DisplayName("Returns false when Cloudflare responds with failure")
    void verify_failureResponse_returnsFalse() {
        setSecretKey("secret");
        injectMockRestTemplate();
        Map<String, Object> response = Map.of("success", false, "error-codes", List.of("invalid-input-response"));
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenReturn(response);

        assertThat(turnstileService.verify("bad-token")).isFalse();
    }

    @Test
    @DisplayName("Returns false when Cloudflare returns null response")
    void verify_nullResponse_returnsFalse() {
        setSecretKey("secret");
        injectMockRestTemplate();
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenReturn(null);

        assertThat(turnstileService.verify("some-token")).isFalse();
    }

    @Test
    @DisplayName("Returns false when REST call throws an exception")
    void verify_exception_returnsFalse() {
        setSecretKey("secret");
        injectMockRestTemplate();
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        assertThat(turnstileService.verify("some-token")).isFalse();
    }
}
