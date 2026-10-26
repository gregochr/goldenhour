package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.ClientAddress;
import com.gregochr.goldenhour.service.WaitlistService;
import com.gregochr.goldenhour.service.WaitlistService.JoinOutcome;
import com.gregochr.goldenhour.util.EmailValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Accepts waitlist email submissions when early-access registration is full.
 *
 * <p>Thin HTTP layer: shape validation here; the CAPTCHA, the attempt bounds and the write live in
 * {@link WaitlistService}.
 */
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    /**
     * Submits an email address to the waitlist.
     *
     * <p>Silently succeeds if the email is already on the waitlist (idempotent), including when a concurrent
     * identical submission wins the race.
     *
     * @param body    map containing {@code email} and {@code turnstileToken}
     * @param request the request, for the caller's address
     * @return 200 with confirmation message, 400 if the email is missing/invalid or the CAPTCHA fails, or 429
     *         when an attempt bound is reached
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitWaitlist(@RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String email = body.get("email");
        if (email == null || !EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A valid email address is required"));
        }

        JoinOutcome outcome = waitlistService.join(email.trim().toLowerCase(), body.get("turnstileToken"),
                ClientAddress.of(request));
        return switch (outcome) {
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", AuthController.TOO_MANY_ATTEMPTS));
            case CAPTCHA_FAILED -> ResponseEntity.badRequest()
                    .body(Map.of("error", "CAPTCHA verification failed. Please try again."));
            case JOINED -> ResponseEntity.ok(Map.of("message", "You're on the list"));
        };
    }
}
