package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient;
import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient.GaugeReading;
import com.gregochr.goldenhour.config.TideGaugeProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideGaugeCheckEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideGaugeCheckRepository;
import com.gregochr.goldenhour.repository.TideHighWater;
import com.gregochr.goldenhour.service.TideGaugeMath.ObservedHigh;
import com.gregochr.goldenhour.service.TideGaugeMath.PairedHigh;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Weekly check of WorldTides' predicted high waters against measured water at Environment Agency
 * tide gauges (tide-source-integrity-plan P4).
 *
 * <p>It cannot catch a model change at a location with no gauge, and it sees an error only after the
 * event has passed. Its job is to show the model's accuracy where there is ground truth and to catch
 * drift there. It runs once at the end of the tide refresh and never fails it: every gauge is checked
 * independently and every failure is logged and skipped.
 *
 * <p>Contains Environment Agency data licensed under the Open Government Licence v3.0. The readings
 * are not stored; only the per-gauge medians are.
 */
@Service
public class TideGaugeCheckService {

    /** Attribution carried by the run summary and by every alert (Open Government Licence v3.0). */
    public static final String OGL_ATTRIBUTION =
            "Contains Environment Agency data licensed under the Open Government Licence v3.0.";

    private static final Logger LOG = LoggerFactory.getLogger(TideGaugeCheckService.class);

    private static final int WINDOW_DAYS = 7;

    /** Stored predictions are read this far beyond the readings so edge high waters can still pair. */
    private static final long PAIRING_MARGIN_HOURS = 3;

    private final EnvironmentAgencyGaugeClient gaugeClient;
    private final TideGaugeProperties properties;
    private final LocationRepository locationRepository;
    private final TideExtremeRepository tideExtremeRepository;
    private final TideGaugeCheckRepository checkRepository;
    private final AdminAlertService adminAlertService;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param gaugeClient           Environment Agency readings client
     * @param properties            gauge mappings and thresholds
     * @param locationRepository    resolves a mapped location name to a location
     * @param tideExtremeRepository stored predicted tide extremes
     * @param checkRepository       stored check results
     * @param adminAlertService     alerts admins when a gauge becomes newly flagged
     * @param clock                 injected clock
     */
    public TideGaugeCheckService(EnvironmentAgencyGaugeClient gaugeClient, TideGaugeProperties properties,
            LocationRepository locationRepository, TideExtremeRepository tideExtremeRepository,
            TideGaugeCheckRepository checkRepository, AdminAlertService adminAlertService, Clock clock) {
        this.gaugeClient = gaugeClient;
        this.properties = properties;
        this.locationRepository = locationRepository;
        this.tideExtremeRepository = tideExtremeRepository;
        this.checkRepository = checkRepository;
        this.adminAlertService = adminAlertService;
        this.clock = clock;
    }

    /**
     * Checks every configured gauge and records one row for each that has enough paired high waters.
     * Never throws: a failure for one gauge is logged and the others still run.
     *
     * @return the rows recorded this run (empty when disabled or nothing could be checked)
     */
    public List<TideGaugeCheckEntity> runChecks() {
        List<TideGaugeCheckEntity> recorded = new ArrayList<>();
        if (!properties.isEnabled()) {
            LOG.debug("Tide gauge check disabled — skipping");
            return recorded;
        }
        for (TideGaugeProperties.Measure measure : properties.getMeasures()) {
            try {
                checkOne(measure).ifPresent(recorded::add);
            } catch (Exception e) {
                LOG.warn("Tide gauge check failed for {} ({}): {}",
                        LogSanitizer.sanitize(measure.getLocationName()),
                        LogSanitizer.sanitize(measure.getMeasureId()),
                        LogSanitizer.sanitize(e.getMessage()));
            }
        }
        LOG.info("Tide gauge check complete — {} of {} gauge(s) recorded. {}",
                recorded.size(), properties.getMeasures().size(), OGL_ATTRIBUTION);
        return recorded;
    }

    private Optional<TideGaugeCheckEntity> checkOne(TideGaugeProperties.Measure measure) {
        Optional<LocationEntity> location = locationRepository.findByName(measure.getLocationName());
        if (location.isEmpty()) {
            LOG.warn("Tide gauge check: no location named '{}' for gauge {} — skipped",
                    LogSanitizer.sanitize(measure.getLocationName()),
                    LogSanitizer.sanitize(measure.getMeasureId()));
            return Optional.empty();
        }
        Instant now = clock.instant();
        LocalDate endDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<GaugeReading> readings =
                gaugeClient.fetchReadings(measure.getMeasureId(), endDate.minusDays(WINDOW_DAYS), endDate);
        if (readings.isEmpty()) {
            LOG.warn("Tide gauge check: no readings for {} — skipped", LogSanitizer.sanitize(measure.getMeasureId()));
            return Optional.empty();
        }
        LocalDateTime windowFrom = LocalDateTime.ofInstant(readings.get(0).time(), ZoneOffset.UTC);
        LocalDateTime windowTo = LocalDateTime.ofInstant(readings.get(readings.size() - 1).time(), ZoneOffset.UTC);

        List<ObservedHigh> observed = TideGaugeMath.extractHighWaters(readings);
        List<TideHighWater> predicted = tideExtremeRepository.findHighWatersInWindow(
                List.of(location.get().getId()),
                windowFrom.minusHours(PAIRING_MARGIN_HOURS), windowTo.plusHours(PAIRING_MARGIN_HOURS));
        List<PairedHigh> pairs = TideGaugeMath.pair(observed, predicted);
        if (pairs.size() < properties.getMinPairs()) {
            LOG.info("Tide gauge check: {} has {} paired high water(s), fewer than {} — skipped",
                    LogSanitizer.sanitize(measure.getLocationName()), pairs.size(), properties.getMinPairs());
            return Optional.empty();
        }

        double medianTiming = TideGaugeMath.median(pairs.stream().map(PairedHigh::timingErrorMinutes).toList());
        double medianHeight = TideGaugeMath.median(pairs.stream().map(PairedHigh::heightErrorMetres).toList());

        List<TideGaugeCheckEntity> priors = checkRepository.findByLocationIdAndGaugeMeasureIdOrderByRunAtDesc(
                location.get().getId(), measure.getMeasureId(),
                PageRequest.of(0, Math.max(1, properties.getTrailingRuns())));
        List<Double> priorMedians = priors.stream().map(TideGaugeCheckEntity::getMedianTimingErrorMinutes).toList();
        boolean flagged = isFlagged(medianTiming, priorMedians, properties.getTrailingRuns(),
                properties.getFlagMinutes());
        boolean newlyFlagged = flagged && (priors.isEmpty() || !priors.get(0).isFlagged());

        TideGaugeCheckEntity row = checkRepository.save(TideGaugeCheckEntity.builder()
                .gaugeMeasureId(measure.getMeasureId())
                .locationId(location.get().getId())
                .runAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .windowFrom(windowFrom)
                .windowTo(windowTo)
                .pairedHighs(pairs.size())
                .medianTimingErrorMinutes(medianTiming)
                .medianHeightErrorMetres(medianHeight)
                .flagged(flagged)
                .build());
        LOG.info("Tide gauge check: {} — {} pairs, median timing error {} min, median height error {} m{}",
                LogSanitizer.sanitize(measure.getLocationName()), pairs.size(), oneDecimal(medianTiming),
                twoDecimals(medianHeight), flagged ? " (FLAGGED)" : "");
        if (newlyFlagged) {
            adminAlertService.sendTideGaugeDriftAlert(measure.getLocationName(), driftDetail(measure,
                    medianTiming, TideGaugeMath.median(priorMedians), pairs.size()));
        }
        return Optional.of(row);
    }

    /**
     * The flag rule: this run's median timing error has moved more than {@code flagMinutes} from the
     * median of the previous runs' medians. There is no flag if {@code trailingRuns} is below 1, or
     * until {@code trailingRuns} earlier runs
     * exist.
     *
     * @param current       this run's median timing error, minutes
     * @param priorMedians  the previous runs' median timing errors, newest first
     * @param trailingRuns  how many previous runs are required and used
     * @param flagMinutes   the movement threshold, minutes
     * @return whether the gauge is flagged
     */
    static boolean isFlagged(double current, List<Double> priorMedians, int trailingRuns, double flagMinutes) {
        if (trailingRuns < 1 || priorMedians.size() < trailingRuns) {
            return false;
        }
        return Math.abs(current - TideGaugeMath.median(priorMedians)) > flagMinutes;
    }

    private String driftDetail(TideGaugeProperties.Measure measure, double current, double baseline, int pairs) {
        return "WorldTides' predicted high-water times at " + measure.getLocationName()
                + " have moved against the Environment Agency gauge " + measure.getMeasureId() + ".\n\n"
                + "Median timing error (predicted minus measured) this week: " + oneDecimal(current)
                + " min, against " + oneDecimal(baseline) + " min over the previous " + properties.getTrailingRuns()
                + " weekly checks (flag threshold " + oneDecimal(properties.getFlagMinutes()) + " min, "
                + pairs + " paired high waters).\n\n"
                + "A gauge sees only its own harbour, so this is a signal to look at the model's cell at "
                + "this location, not proof of a fault elsewhere.\n\n" + OGL_ATTRIBUTION + "\n";
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String twoDecimals(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
