package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.WaitlistEntryDto;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only endpoint for viewing waitlist email submissions.
 */
@RestController
@RequestMapping("/api/admin/waitlist")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class WaitlistAdminController {

    private final WaitlistEmailRepository waitlistEmailRepository;

    /**
     * Returns all waitlist entries ordered by submission time ascending (oldest first).
     *
     * @return 200 with a list of {@link WaitlistEntryDto}
     */
    @GetMapping
    public ResponseEntity<List<WaitlistEntryDto>> listWaitlistEntries() {
        List<WaitlistEntryDto> entries = waitlistEmailRepository.findAllByOrderBySubmittedAtAsc()
                .stream()
                .map(e -> new WaitlistEntryDto(e.getId(), e.getEmail(), e.getSubmittedAt()))
                .toList();
        return ResponseEntity.ok(entries);
    }
}
