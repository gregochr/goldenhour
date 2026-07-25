package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.CloudVerificationReport;
import com.gregochr.goldenhour.service.CloudVerificationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * REST endpoints for verifying past forecasts' cloud claims against reanalysis.
 *
 * <p>Accessible only to ADMIN users. This is the validation route that does not depend on anyone
 * recording an outcome: it checks the <em>cloud</em> the forecast predicted against the cloud that
 * was actually analysed, so it works retroactively over evaluations already in the database.
 */
@RestController
@RequestMapping("/api/admin/cloud-verification")
public class CloudVerificationController {

    /** Default evaluations verified per backfill call, bounding one pass's archive traffic. */
    private static final int DEFAULT_BACKFILL_LIMIT = 200;

    /** Default report window in days. */
    private static final int DEFAULT_WINDOW_DAYS = 180;

    private final CloudVerificationService verificationService;

    /**
     * Constructs a {@code CloudVerificationController}.
     *
     * @param verificationService the service performing verification and reporting
     */
    public CloudVerificationController(CloudVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Verifies a bounded batch of unverified evaluations against the archive.
     *
     * <p>Resumable — call repeatedly to work through the backlog. Each call verifies the oldest
     * unverified evaluations first.
     *
     * @param limit maximum evaluations to verify in this pass
     * @return the number verified
     */
    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Integer>> backfill(
            @RequestParam(required = false) Integer limit) {
        int verified = verificationService.backfill(
                limit != null ? limit : DEFAULT_BACKFILL_LIMIT);
        return ResponseEntity.ok(Map.of("verified", verified));
    }

    /**
     * Returns forecast-vs-observed cloud accuracy over a window of target dates.
     *
     * @param from start of the window (optional; defaults to 180 days before {@code to})
     * @param to   end of the window (optional; defaults to today)
     * @return the verification report
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CloudVerificationReport> getReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return ResponseEntity.ok(verificationService.report(start, end));
    }
}
