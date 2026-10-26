package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.model.AstroConditionsDto;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.LitePickShaper;
import com.gregochr.goldenhour.service.PickScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
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
    private final AstroConditionsService service;
    private final LitePickShaper litePickShaper;

    /**
     * Constructs the controller.
     *
     * @param repository astro conditions data access
     * @param service    resolves the night window a row was scored over (stored, with a
     *                   legacy-row recompute fallback)
     * @param litePickShaper strips the stars and prose of locations outside a Lite reader's sub-region picks
     */
    public AstroConditionsController(AstroConditionsRepository repository,
            AstroConditionsService service, LitePickShaper litePickShaper) {
        this.repository = repository;
        this.service = service;
        this.litePickShaper = litePickShaper;
    }

    /**
     * Returns astro conditions for all scored locations on a given night.
     *
     * <p>With {@code photocast.sub-regions.enforce} on, a caller who is not positively PRO_USER or ADMIN
     * gets the stars, the summary and the three explanations of every location outside their sub-region
     * picks removed ({@code gated: true}) by {@link LitePickShaper} (docs/engineering/sub-regions-plan.md
     * §9 Q7), and the response is sent {@code Cache-Control: no-store}.
     *
     * @param date the night to query (ISO format, e.g. 2026-04-01)
     * @param auth the caller's authentication
     * @return list of condition scores, one per location
     */
    @GetMapping("/conditions")
    public List<AstroConditionsDto> getConditions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        PickScope scope = litePickShaper.scopeFor(auth);
        LitePickShaper.markNoStore(scope);
        return repository.findByForecastDate(date).stream()
                .map(this::toDto)
                .map(dto -> litePickShaper.shapeAstro(dto, scope))
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

    private AstroConditionsDto toDto(AstroConditionsEntity entity) {
        AstroConditionsService.NightWindow window = service.resolveNightWindow(entity);
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
                entity.getMoonIlluminationPct(),
                window.start(),
                window.end(),
                null
        );
    }
}
