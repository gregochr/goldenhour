package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import com.gregochr.goldenhour.util.EmailValidator;
import com.gregochr.goldenhour.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Accepts waitlist email submissions when early-access registration is full.
 */
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private static final Logger LOG = LoggerFactory.getLogger(WaitlistController.class);

    private final WaitlistEmailRepository waitlistEmailRepository;

    /**
     * Submits an email address to the waitlist.
     *
     * <p>Silently succeeds if the email is already on the waitlist (idempotent).
     *
     * @param body map containing {@code email}
     * @return 200 with confirmation message, or 400 if the email is missing/invalid
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitWaitlist(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || !EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A valid email address is required"));
        }

        String trimmed = email.trim().toLowerCase();
        if (!waitlistEmailRepository.existsByEmail(trimmed)) {
            WaitlistEmailEntity entry = WaitlistEmailEntity.builder()
                    .email(trimmed)
                    .submittedAt(LocalDateTime.now())
                    .build();
            waitlistEmailRepository.save(entry);
            LOG.info("Waitlist signup: {}", LogSanitizer.sanitize(trimmed));
        }

        return ResponseEntity.ok(Map.of("message", "You're on the list"));
    }
}
