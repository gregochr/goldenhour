package com.gregochr.goldenhour.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Verifies Cloudflare Turnstile tokens via the siteverify API.
 */
@Service
public class TurnstileService {

    private static final Logger LOG = LoggerFactory.getLogger(TurnstileService.class);
    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Value("${turnstile.secret-key:}")
    private String secretKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Verifies a Turnstile response token with Cloudflare.
     *
     * @param token the client-side Turnstile response token
     * @return true if the token is valid, false otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean verify(String token) {
        if (secretKey == null || secretKey.isBlank()) {
            LOG.warn("Turnstile secret key not configured — skipping verification");
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }

        try {
            Map<String, String> request = Map.of(
                    "secret", secretKey,
                    "response", token
            );
            Map<String, Object> response = restTemplate.postForObject(VERIFY_URL, request, Map.class);
            if (response == null) {
                LOG.warn("Turnstile verification returned null response");
                return false;
            }

            boolean success = Boolean.TRUE.equals(response.get("success"));
            if (!success) {
                List<String> errors = (List<String>) response.get("error-codes");
                LOG.warn("Turnstile verification failed: {}", errors);
            }
            return success;
        } catch (Exception ex) {
            LOG.error("Turnstile verification error", ex);
            return false;
        }
    }
}
