package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunPhase;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time enrichment service that populates the {@code bortle_class} and
 * {@code sky_brightness_sqm} columns on all un-enriched locations using the
 * lightpollutionmap.info QueryRaster API.
 *
 * <p>The API allows 1000 requests/day (quota resets at GMT+1). With ~200 locations
 * and a 500 ms inter-call delay, a full run takes ~2 minutes and stays well
 * within the daily limit.
 *
 * <p>Triggered via {@code POST /api/aurora/admin/enrich-bortle}. Safe to re-run —
 * only locations with a {@code null} Bortle class are processed.
 */
@Service
public class BortleEnrichmentService {

    private static final Logger LOG = LoggerFactory.getLogger(BortleEnrichmentService.class);

    /** Delay in milliseconds between API calls to respect the 1000/day rate limit. */
    private static final long INTER_CALL_DELAY_MS = 500L;

    private final LocationRepository locationRepository;
    private final LightPollutionClient lightPollutionClient;
    private final JobRunService jobRunService;
    private final RunProgressTracker progressTracker;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Constructs the service with repository, HTTP client, and job run dependencies.
     *
     * @param locationRepository   location data access
     * @param lightPollutionClient HTTP client for the light pollution API
     * @param jobRunService        job run service for tracking the enrichment run
     * @param progressTracker      tracker for SSE progress broadcasting
     * @param eventPublisher       Spring event publisher for per-location state transitions
     */
    public BortleEnrichmentService(LocationRepository locationRepository,
            LightPollutionClient lightPollutionClient,
            JobRunService jobRunService,
            RunProgressTracker progressTracker,
            ApplicationEventPublisher eventPublisher) {
        this.locationRepository = locationRepository;
        this.lightPollutionClient = lightPollutionClient;
        this.jobRunService = jobRunService;
        this.progressTracker = progressTracker;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Enriches all locations with a {@code null} Bortle class and records the run in
     * the job run log.
     *
     * <p>Fetches the sky brightness for each location, converts to Bortle class, and
     * saves the result. Locations where the API call fails are skipped and logged —
     * they remain with a {@code null} Bortle class and can be retried by calling
     * this method again.
     *
     * @param apiKey lightpollutionmap.info API key
     * @param jobRun the job run entity to update on completion
     * @return a summary of results
     */
    public EnrichmentResult enrichAll(String apiKey, JobRunEntity jobRun) {
        List<LocationEntity> pending = locationRepository.findByBortleClassIsNull();
        LOG.info("Bortle enrichment starting: {} location(s) to process", pending.size());

        // Register all tasks as PENDING for SSE progress tracking
        List<String[]> taskDescriptors = pending.stream()
                .map(loc -> new String[]{taskKey(loc), loc.getName(), "–", "BORTLE"})
                .toList();
        progressTracker.initRun(jobRun.getId(), taskDescriptors);
        progressTracker.setPhase(jobRun.getId(), RunPhase.FULL_EVALUATION);

        int enriched = 0;
        List<String> failed = new ArrayList<>();

        for (LocationEntity location : pending) {
            publishEvent(jobRun.getId(), location, LocationTaskState.EVALUATING, null);

            String callUrl = String.format(
                    "https://www.lightpollutionmap.info/api/queryraster?ql=sb_2025&qt=point&qd=%f,%f",
                    location.getLon(), location.getLat());
            long callStart = System.currentTimeMillis();
            LightPollutionClient.SkyBrightnessResult brightness =
                    lightPollutionClient.querySkyBrightness(
                            location.getLat(), location.getLon(), apiKey);
            long callDurationMs = System.currentTimeMillis() - callStart;

            if (brightness != null) {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.LIGHT_POLLUTION,
                        "GET", callUrl, null, callDurationMs, 200, null, true, null);
                location.setSkyBrightnessSqm(brightness.sqm());
                location.setBortleClass(brightness.bortle());
                locationRepository.save(location);
                enriched++;
                publishEvent(jobRun.getId(), location, LocationTaskState.COMPLETE, null);
                LOG.debug("Enriched '{}': SQM {}, Bortle {}",
                        location.getName(), brightness.sqm(), brightness.bortle());
            } else {
                jobRunService.logApiCall(jobRun.getId(), ServiceName.LIGHT_POLLUTION,
                        "GET", callUrl, null, callDurationMs, null, null, false, "API returned no data");
                failed.add(location.getName());
                publishEvent(jobRun.getId(), location, LocationTaskState.FAILED, "API returned no data");
                LOG.warn("Failed to enrich '{}' — will remain null", location.getName());
            }

            sleepBetweenCalls();
        }

        LOG.info("Bortle enrichment complete: {} enriched, {} failed", enriched, failed.size());
        EnrichmentResult result = new EnrichmentResult(enriched, failed);
        jobRunService.completeRun(jobRun, enriched, failed.size());
        progressTracker.completeRun(jobRun.getId());
        return result;
    }

    private String taskKey(LocationEntity location) {
        return location.getName() + "|–|BORTLE";
    }

    private void publishEvent(long jobRunId, LocationEntity location,
            LocationTaskState state, String errorMessage) {
        String key = taskKey(location);
        eventPublisher.publishEvent(new LocationTaskEvent(
                this, jobRunId, key, location.getName(), "–", "BORTLE",
                state, errorMessage, null));
    }

    /**
     * Sleeps for {@link #INTER_CALL_DELAY_MS} milliseconds between API calls.
     *
     * <p>Thread interruption is propagated correctly.
     */
    private void sleepBetweenCalls() {
        try {
            Thread.sleep(INTER_CALL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Bortle enrichment interrupted");
        }
    }

    /**
     * Summary of a Bortle enrichment run.
     *
     * @param enriched number of locations successfully enriched
     * @param failed   names of locations that could not be enriched
     */
    public record EnrichmentResult(int enriched, List<String> failed) {
    }
}
