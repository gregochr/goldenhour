package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.service.TravelDayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only REST endpoints for managing travel-day ranges that gate the
 * overnight forecast batch.
 */
@RestController
@RequestMapping("/api/admin/travel-days")
public class TravelDayController {

    private final TravelDayService travelDayService;

    /**
     * Constructs the controller.
     *
     * @param travelDayService travel-day management service
     */
    public TravelDayController(TravelDayService travelDayService) {
        this.travelDayService = travelDayService;
    }

    /**
     * Lists all travel ranges, soonest first.
     *
     * @return the ranges
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TravelDayResponse>> list() {
        return ResponseEntity.ok(travelDayService.list());
    }

    /**
     * Creates a new travel range.
     *
     * @param request the range to create
     * @return the persisted range
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TravelDayResponse> add(@RequestBody TravelDayRequest request) {
        TravelDayResponse created = travelDayService.add(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Deletes a travel range by id.
     *
     * @param id the range id
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        travelDayService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
