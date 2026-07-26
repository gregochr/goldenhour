package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.BackfillStatus;
import com.gregochr.goldenhour.model.CloudVerificationReport;
import com.gregochr.goldenhour.service.CloudVerificationBackfillRunner;
import com.gregochr.goldenhour.service.CloudVerificationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    /** Default report window in days. */
    private static final int DEFAULT_WINDOW_DAYS = 180;

    private final CloudVerificationService verificationService;
    private final CloudVerificationBackfillRunner backfillRunner;

    /**
     * Constructs a {@code CloudVerificationController}.
     *
     * @param verificationService the service performing verification and reporting
     * @param backfillRunner      drives the background backfill
     */
    public CloudVerificationController(CloudVerificationService verificationService,
            CloudVerificationBackfillRunner backfillRunner) {
        this.verificationService = verificationService;
        this.backfillRunner = backfillRunner;
    }

    /**
     * Starts a background backfill of every unverified evaluation, and returns immediately.
     *
     * <p>Asynchronous by necessity: the backlog runs to tens of thousands of evaluations, far
     * beyond any reverse proxy's request timeout. A synchronous call returns a gateway error while
     * the work carries on server-side, which is worse than no feedback at all. Poll
     * {@link #backfillStatus()} for progress.
     *
     * <p>Returns {@code 409 Conflict} if a run is already in progress — two concurrent runs would
     * select overlapping candidates and collide on the unique evaluation id.
     *
     * @return {@code 202} with the starting status, or {@code 409} if already running
     */
    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BackfillStatus> backfill() {
        if (!backfillRunner.start()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(backfillRunner.status());
        }
        return ResponseEntity.accepted().body(backfillRunner.status());
    }

    /**
     * Returns the progress of the current or most recent backfill run.
     *
     * @return the status snapshot
     */
    @GetMapping("/backfill/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BackfillStatus> backfillStatus() {
        return ResponseEntity.ok(backfillRunner.status());
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
