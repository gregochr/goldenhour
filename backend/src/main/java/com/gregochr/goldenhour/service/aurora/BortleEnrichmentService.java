package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time enrichment service that populates the {@code bortle_class} column
 * on all un-enriched locations using the lightpollutionmap.info QueryRaster API.
 *
 * <p>The API allows 500 requests/day. With ~200 locations and a 500 ms inter-call
 * delay, a full run takes ~2 minutes and stays well within the daily limit.
 *
 * <p>Triggered via {@code POST /api/aurora/admin/enrich-bortle}. Safe to re-run —
 * only locations with a {@code null} Bortle class are processed.
 */
@Service
public class BortleEnrichmentService {

    private static final Logger LOG = LoggerFactory.getLogger(BortleEnrichmentService.class);

    /** Delay in milliseconds between API calls to respect the 500/day rate limit. */
    private static final long INTER_CALL_DELAY_MS = 500L;

    private final LocationRepository locationRepository;
    private final LightPollutionClient lightPollutionClient;

    /**
     * Constructs the service with repository and HTTP client dependencies.
     *
     * @param locationRepository   location data access
     * @param lightPollutionClient HTTP client for the light pollution API
     */
    public BortleEnrichmentService(LocationRepository locationRepository,
            LightPollutionClient lightPollutionClient) {
        this.locationRepository = locationRepository;
        this.lightPollutionClient = lightPollutionClient;
    }

    /**
     * Enriches all locations with a {@code null} Bortle class.
     *
     * <p>Fetches the sky brightness for each location, converts to Bortle class, and
     * saves the result. Locations where the API call fails are skipped and logged —
     * they remain with a {@code null} Bortle class and can be retried by calling
     * this method again.
     *
     * @param apiKey lightpollutionmap.info API key
     * @return a summary of results
     */
    public EnrichmentResult enrichAll(String apiKey) {
        List<LocationEntity> pending = locationRepository.findByBortleClassIsNull();
        LOG.info("Bortle enrichment starting: {} location(s) to process", pending.size());

        int enriched = 0;
        List<String> failed = new ArrayList<>();

        for (LocationEntity location : pending) {
            Integer bortleClass = lightPollutionClient.queryBortleClass(
                    location.getLat(), location.getLon(), apiKey);

            if (bortleClass != null) {
                location.setBortleClass(bortleClass);
                locationRepository.save(location);
                enriched++;
                LOG.debug("Enriched '{}': Bortle {}", location.getName(), bortleClass);
            } else {
                failed.add(location.getName());
                LOG.warn("Failed to enrich '{}' — will remain null", location.getName());
            }

            sleepBetweenCalls();
        }

        LOG.info("Bortle enrichment complete: {} enriched, {} failed", enriched, failed.size());
        return new EnrichmentResult(enriched, failed);
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
