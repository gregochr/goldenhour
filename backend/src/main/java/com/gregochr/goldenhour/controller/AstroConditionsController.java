package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.model.AstroConditionsDto;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST controller for astro observing conditions.
 *
 * <p>All endpoints are available to every authenticated user (no role gate).
 * Astro conditions are template-scored (no Claude call) and provide nightly
 * observing quality assessments at dark-sky locations.
 */
@RestController
@RequestMapping("/api/astro")
public class AstroConditionsController {

    private final AstroConditionsRepository repository;

    /**
     * Constructs the controller.
     *
     * @param repository astro conditions data access
     */
    public AstroConditionsController(AstroConditionsRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns astro conditions for all scored locations on a given night.
     *
     * @param date the night to query (ISO format, e.g. 2026-04-01)
     * @return list of condition scores, one per location
     */
    @GetMapping("/conditions")
    public List<AstroConditionsDto> getConditions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.findByForecastDate(date).stream()
                .map(AstroConditionsController::toDto)
                .toList();
    }

    /**
     * Returns all dates that have stored astro condition results.
     *
     * @return distinct forecast dates in ascending order
     */
    @GetMapping("/conditions/available-dates")
    public List<String> getAvailableDates() {
        return repository.findDistinctForecastDates().stream()
                .map(LocalDate::toString)
                .toList();
    }

    private static AstroConditionsDto toDto(AstroConditionsEntity entity) {
        return new AstroConditionsDto(
                entity.getLocation().getId(),
                entity.getLocation().getName(),
                entity.getLocation().getLat(),
                entity.getLocation().getLon(),
                entity.getLocation().getBortleClass(),
                entity.getStars(),
                entity.getSummary(),
                entity.getCloudExplanation(),
                entity.getVisibilityExplanation(),
                entity.getMoonExplanation(),
                entity.getForecastDate(),
                entity.getMoonPhase(),
                entity.getMoonIlluminationPct()
        );
    }
}
