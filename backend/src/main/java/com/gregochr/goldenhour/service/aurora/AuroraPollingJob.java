package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.AuroraWatchClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatus;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.SolarCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Scheduled job that polls AuroraWatch UK and drives the aurora notification lifecycle.
 *
 * <p>Runs every 5 minutes (configurable via {@code aurora.poll-interval-minutes}).
 * Uses a fixed-delay schedule to avoid overlap if a run takes longer than expected.
 *
 * <p>The polling sequence:
 * <ol>
 *   <li>Skip if the sun is above nautical twilight (−12°) at a representative UK location —
 *       aurora is not visible until it is properly dark.</li>
 *   <li>Fetch the current AuroraWatch status.</li>
 *   <li>Evaluate via {@link AuroraStateCache} to obtain an {@link AuroraStateCache.Action}.</li>
 *   <li>On {@link AuroraStateCache.Action#NOTIFY} — score all Bortle-eligible locations and
 *       cache the results.</li>
 *   <li>On {@link AuroraStateCache.Action#CLEAR} — cached scores are cleared by the state
 *       machine during {@code evaluate()}.</li>
 *   <li>On {@link AuroraStateCache.Action#SUPPRESS} or {@link AuroraStateCache.Action#NONE}
 *       — do nothing.</li>
 * </ol>
 */
@Component
public class AuroraPollingJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraPollingJob.class);

    /**
     * Durham, UK — representative UK reference latitude for the nautical twilight check.
     * Aurora is not visible until the sun is below −12° altitude at northern UK latitudes.
     */
    private static final double DURHAM_LAT = 54.776;

    /** Durham longitude. */
    private static final double DURHAM_LON = -1.575;

    /**
     * Buffer in minutes added before civil dawn and after civil dusk to approximate
     * nautical twilight (−12°). Civil twilight is at −6°; the gap to −12° is ~30–40 min
     * at northern UK latitudes across all seasons.
     */
    private static final int NAUTICAL_BUFFER_MINUTES = 35;

    private final AuroraWatchClient auroraWatchClient;
    private final AuroraStateCache stateCache;
    private final AuroraScorer scorer;
    private final AuroraTransectFetcher transectFetcher;
    private final LocationRepository locationRepository;
    private final AuroraProperties properties;
    private final SolarCalculator solarCalculator;

    /**
     * Constructs the polling job with all required dependencies.
     *
     * @param auroraWatchClient  AuroraWatch HTTP client
     * @param stateCache         aurora state machine
     * @param scorer             location scorer
     * @param transectFetcher    northward cloud-cover fetcher
     * @param locationRepository location data access
     * @param properties         aurora configuration
     * @param solarCalculator    solar-utils calculator for twilight check
     */
    public AuroraPollingJob(AuroraWatchClient auroraWatchClient,
            AuroraStateCache stateCache,
            AuroraScorer scorer,
            AuroraTransectFetcher transectFetcher,
            LocationRepository locationRepository,
            AuroraProperties properties,
            SolarCalculator solarCalculator) {
        this.auroraWatchClient = auroraWatchClient;
        this.stateCache = stateCache;
        this.scorer = scorer;
        this.transectFetcher = transectFetcher;
        this.locationRepository = locationRepository;
        this.properties = properties;
        this.solarCalculator = solarCalculator;
    }

    /**
     * Executes one aurora polling cycle.
     *
     * <p>The initial 60-second delay prevents hitting AuroraWatch immediately on startup.
     * The fixed-delay schedule ensures the next poll does not begin until this one finishes.
     */
    @Scheduled(fixedDelayString = "PT${aurora.poll-interval-minutes:5}M",
               initialDelayString = "PT1M")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        executePoll();
    }

    /**
     * Core polling logic, extracted for unit-testability without triggering the scheduler.
     */
    void executePoll() {
        if (isDaylight()) {
            LOG.debug("Aurora poll skipped — above nautical twilight");
            return;
        }

        AuroraStatus status = auroraWatchClient.fetchStatus();
        if (status == null) {
            LOG.warn("Aurora poll skipped — no status available (first fetch may have failed)");
            return;
        }

        AlertLevel incoming = status.level();
        AuroraStateCache.Evaluation eval = stateCache.evaluate(incoming);
        LOG.info("Aurora poll: level={} action={}", incoming, eval.action());

        if (eval.action() == AuroraStateCache.Action.NOTIFY) {
            scoreAndCache(incoming, status);
        } else if (eval.action() == AuroraStateCache.Action.CLEAR) {
            LOG.info("Aurora event ended — cached scores cleared");
        }
    }

    /**
     * Scores all Bortle-eligible locations and stores the results in the state cache.
     *
     * @param level  the current alert level determining the Bortle threshold
     * @param status the AuroraWatch status (used for logging)
     */
    private void scoreAndCache(AlertLevel level, AuroraStatus status) {
        int threshold = (level == AlertLevel.RED)
                ? properties.getBortleThreshold().getRed()
                : properties.getBortleThreshold().getAmber();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);

        if (candidates.isEmpty()) {
            LOG.info("Aurora NOTIFY ({}): no Bortle-eligible locations (threshold={})",
                    level, threshold);
            stateCache.updateScores(List.of());
            return;
        }

        LOG.info("Aurora NOTIFY ({}): scoring {} location(s) (Bortle ≤ {}, station={})",
                level, candidates.size(), threshold, status.station());

        Map<String, Integer> cloudData = transectFetcher.fetchTransectCloud(candidates);
        List<AuroraForecastScore> scores = scorer.score(level, candidates, cloudData);
        stateCache.updateScores(scores);

        LOG.info("Aurora scoring complete: {} location(s) scored, highest={}", scores.size(),
                scores.stream().mapToInt(AuroraForecastScore::stars).max().orElse(0));
    }

    /**
     * Returns {@code true} if it is currently too bright for aurora at Durham, UK.
     *
     * <p>Uses civil twilight (−6°) times from {@link SolarCalculator} and adds a
     * {@value #NAUTICAL_BUFFER_MINUTES}-minute buffer on each side to approximate
     * nautical twilight (−12°). Aurora requires proper darkness.
     *
     * @return {@code true} if too bright for aurora visibility
     */
    boolean isDaylight() {
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime dawn = solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, today, utc);
        LocalDateTime dusk = solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, today, utc);
        LocalDateTime now = LocalDateTime.now(utc);
        return now.isAfter(dawn.minusMinutes(NAUTICAL_BUFFER_MINUTES))
                && now.isBefore(dusk.plusMinutes(NAUTICAL_BUFFER_MINUTES));
    }
}
