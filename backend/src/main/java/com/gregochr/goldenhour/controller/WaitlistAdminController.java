package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.WaitlistEntryDto;
import com.gregochr.goldenhour.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for viewing, inviting and removing waitlist entries.
 */
@RestController
@RequestMapping("/api/admin/waitlist")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class WaitlistAdminController {

    private final WaitlistService waitlistService;

    /**
     * Returns all waitlist entries ordered by submission time ascending (oldest first).
     *
     * @return 200 with a list of {@link WaitlistEntryDto}
     */
    @GetMapping
    public ResponseEntity<List<WaitlistEntryDto>> listWaitlistEntries() {
        return ResponseEntity.ok(waitlistService.list());
    }

    /**
     * Invites a waitlisted address to register past the early-access cap. Re-inviting replaces the token.
     *
     * @param id the waitlist row
     * @return 200 when the invitation was sent, 400 for an address registration would reject, 404 for no such
     *         row, 503 when no mail sender is configured
     */
    @PostMapping("/{id}/invite")
    public ResponseEntity<Map<String, String>> invite(@PathVariable Long id) {
        return switch (waitlistService.invite(id)) {
            case SENT -> ResponseEntity.ok(Map.of("message", "Invitation sent"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No such waitlist entry"));
            case NON_ASCII -> ResponseEntity.badRequest().body(Map.of("error",
                    "This address contains non-ASCII characters, which registration does not accept"));
            case MAIL_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Email is not configured, so no invitation can be sent"));
        };
    }

    /**
     * Removes a waitlist entry.
     *
     * @param id the waitlist row
     * @return 204 when removed, 404 when there is no such row
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        return waitlistService.remove(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
