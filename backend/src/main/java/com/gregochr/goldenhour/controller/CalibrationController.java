package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.CalibrationReport;
import com.gregochr.goldenhour.service.ForecastCalibrationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST endpoint reporting how well forecasts matched recorded outcomes.
 *
 * <p>Accessible only to ADMIN users. This is the gate for changes to the scoring rules: run it
 * over a fixed window before and after a prompt or sampling-geometry change and compare the
 * buckets. Every other harness in the project measures Claude against fixtures, expectations, or
 * other models; this one measures it against what a photographer actually saw.
 */
@RestController
@RequestMapping("/api/admin/calibration")
public class CalibrationController {

    /** Default window length in days when the caller supplies no dates. */
    private static final int DEFAULT_WINDOW_DAYS = 90;

    private final ForecastCalibrationService calibrationService;

    /**
     * Constructs a {@code CalibrationController}.
     *
     * @param calibrationService the service scoring forecasts against outcomes
     */
    public CalibrationController(ForecastCalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /**
     * Returns forecast accuracy over a window of recorded outcomes.
     *
     * <p>Defaults to the trailing 90 days, which is the widest window likely to hold enough
     * recorded outcomes to be meaningful. Both bounds are inclusive.
     *
     * @param from start of the window (optional; defaults to 90 days before {@code to})
     * @param to   end of the window (optional; defaults to today)
     * @return the calibration report
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CalibrationReport> getCalibration(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return ResponseEntity.ok(calibrationService.report(start, end));
    }
}
