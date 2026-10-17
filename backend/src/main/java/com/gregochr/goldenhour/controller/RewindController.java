package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.RewindEventService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only REST controller for the rewind feature: the recent solar events an admin can rewind
 * the app to.
 *
 * <p>The rewind itself is not a resource — it is the {@code X-Rewind-To} request header
 * ({@link com.gregochr.goldenhour.config.RewindFilter}) the client attaches to its ordinary GETs
 * once the admin has chosen a moment. No server-side state is set, so one admin's rewind can never
 * reach another user's page, and a reload ends it. This controller only supplies the menu.
 */
@RestController
@RequestMapping("/api/admin/rewind")
@PreAuthorize("hasRole('ADMIN')")
public class RewindController {

    private final RewindEventService rewindEventService;

    /**
     * Constructs the controller.
     *
     * @param rewindEventService the events service
     */
    public RewindController(RewindEventService rewindEventService) {
        this.rewindEventService = rewindEventService;
    }

    /**
     * The recent solar events, newest first, with the instant to rewind to for each.
     *
     * @return the events, the current instant and the cached briefing's build time
     */
    @GetMapping("/events")
    public RewindEventService.RewindEvents events() {
        return rewindEventService.events();
    }
}
