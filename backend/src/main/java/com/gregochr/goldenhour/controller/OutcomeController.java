package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.model.ActualOutcome;
import com.gregochr.goldenhour.service.OutcomeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST controller for recording actual observed sunrise/sunset outcomes.
 */
@RestController
@RequestMapping("/api/outcome")
public class OutcomeController {

    private final OutcomeService outcomeService;

    /**
     * Constructs an {@code OutcomeController}.
     *
     * @param outcomeService the service for persisting outcomes
     */
    public OutcomeController(OutcomeService outcomeService) {
        this.outcomeService = outcomeService;
    }

    /**
     * Returns recorded outcomes for a location within a date range.
     *
     * @param lat  latitude of the location
     * @param lon  longitude of the location
     * @param from start of the date range (inclusive), ISO format {@code yyyy-MM-dd}
     * @param to   end of the date range (inclusive), ISO format {@code yyyy-MM-dd}
     * @return outcomes ordered by outcome date ascending
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @GetMapping
    public List<ActualOutcomeEntity> getOutcomes(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return outcomeService.query(lat, lon, from, to);
    }

    /**
     * Records an observed sunrise or sunset outcome.
     *
     * @param outcome the outcome data from the client
     * @return the saved entity with its assigned database ID
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActualOutcomeEntity record(@RequestBody ActualOutcome outcome) {
        return outcomeService.record(outcome);
    }
}
