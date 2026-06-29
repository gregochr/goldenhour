package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.service.TravelDayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only travel-day endpoint for any authenticated user.
 *
 * <p>The briefing and map views use this to render a "forecast not executed —
 * travel day" overlay on dates the operator is away. Management (create/delete)
 * stays ADMIN-only under {@link TravelDayController} at {@code /api/admin/travel-days};
 * this companion exposes just the ranges so the UI can derive per-date status
 * without needing the admin role.
 */
@RestController
@RequestMapping("/api/travel-days")
public class TravelDayQueryController {

    private final TravelDayService travelDayService;

    /**
     * Constructs the controller.
     *
     * @param travelDayService travel-day query service
     */
    public TravelDayQueryController(TravelDayService travelDayService) {
        this.travelDayService = travelDayService;
    }

    /**
     * Lists all travel ranges, soonest first. Requires authentication (any role).
     *
     * @return the ranges
     */
    @GetMapping
    public ResponseEntity<List<TravelDayResponse>> list() {
        return ResponseEntity.ok(travelDayService.list());
    }
}
