package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.service.CloudScoringRules;
import com.gregochr.goldenhour.service.NorthwardTransectSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triages aurora candidate locations by cloud cover, separating those worth
 * including in the Claude evaluation from those that should receive an automatic 1-star rating.
 *
 * <p>Cloud is sampled along a northward transect (50/100/150 km due north) via the shared
 * {@link NorthwardTransectSampler} — aurora appears low on the poleward horizon, so cloud between
 * the observer and the north matters more than cloud overhead. The triage rule is intentionally
 * lenient: <strong>reject only when every hour in the next {@value #TRIAGE_LOOKAHEAD_HOURS} hours
 * reaches the shared overcast boundary.</strong> If any single hour counts as clear (see
 * {@link CloudScoringRules#isClear}), the location passes — a gap in the clouds is all you need for
 * aurora photography.
 */
@Service
public class WeatherTriageService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherTriageService.class);

    /** Hours ahead to check for any viable aurora window. */
    static final int TRIAGE_LOOKAHEAD_HOURS = 6;

    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final NorthwardTransectSampler transectSampler;

    /**
     * Constructs the triage service.
     *
     * @param transectSampler shared northward-transect cloud sampler
     */
    public WeatherTriageService(NorthwardTransectSampler transectSampler) {
        this.transectSampler = transectSampler;
    }

    /**
     * Runs weather triage for a list of aurora candidate locations.
     *
     * <p>Samples the northward transect's total (low+mid+high, capped) cloud for the current hour
     * and the next {@value #TRIAGE_LOOKAHEAD_HOURS} hours. Separates the list into viable locations
     * (any hour below {@value CloudScoringRules#OVERCAST_PERCENT}%) and rejected locations
     * (completely overcast across the window).
     *
     * @param candidates locations that have already passed the Bortle filter
     * @return triage result with viable/rejected lists and cloud data per location
     */
    public TriageResult triage(List<LocationEntity> candidates) {
        List<String> hourKeys = buildWindowHourKeys();
        Map<LocationEntity, int[]> cloudByLocationHours = transectSampler.sample(
                candidates, hourKeys, NorthwardTransectSampler.LayerCombiner.SUM_CAPPED);

        List<LocationEntity> viable = new ArrayList<>();
        List<LocationEntity> rejected = new ArrayList<>();
        Map<LocationEntity, Integer> cloudByLocation = new HashMap<>();

        for (LocationEntity loc : candidates) {
            int[] hourly = cloudByLocationHours.getOrDefault(loc, defaultWindow());
            int avgCloud = averageCloud(hourly);
            cloudByLocation.put(loc, avgCloud);

            if (hasViableHour(hourly)) {
                viable.add(loc);
            } else {
                rejected.add(loc);
                LOG.debug("Triage rejected {} — overcast every hour (avg {}%)",
                        loc.getName(), avgCloud);
            }
        }

        LOG.info("Aurora weather triage: {}/{} locations viable", viable.size(), candidates.size());
        return new TriageResult(viable, rejected, cloudByLocation);
    }

    /**
     * Builds the UTC hour keys for the triage window: the current hour plus the next
     * {@value #TRIAGE_LOOKAHEAD_HOURS} hours.
     */
    private List<String> buildWindowHourKeys() {
        ZonedDateTime base = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        List<String> keys = new ArrayList<>(TRIAGE_LOOKAHEAD_HOURS + 1);
        for (int i = 0; i <= TRIAGE_LOOKAHEAD_HOURS; i++) {
            keys.add(base.plusHours(i).format(HOUR_FORMAT));
        }
        return keys;
    }

    /**
     * Returns true if any hour in the window is below the overcast threshold.
     *
     * @param hourlyCloud hourly cloud cover array
     * @return true if any hour is viable for aurora photography
     */
    private boolean hasViableHour(int[] hourlyCloud) {
        for (int cloud : hourlyCloud) {
            if (CloudScoringRules.isClear(cloud)) {
                return true;
            }
        }
        return false;
    }

    private int averageCloud(int[] hourlyCloud) {
        if (hourlyCloud.length == 0) {
            return CloudScoringRules.OVERCAST_PERCENT;
        }
        int sum = 0;
        for (int v : hourlyCloud) {
            sum += v;
        }
        return sum / hourlyCloud.length;
    }

    private int[] defaultWindow() {
        int[] arr = new int[TRIAGE_LOOKAHEAD_HOURS + 1];
        Arrays.fill(arr, CloudScoringRules.OVERCAST_PERCENT);
        return arr;
    }

    /**
     * Result of the weather triage pass.
     *
     * @param viable          locations that passed triage and should be sent to Claude
     * @param rejected         locations rejected (completely overcast) — auto-assign 1★
     * @param cloudByLocation  average transect cloud cover per location (0–100)
     */
    public record TriageResult(
            List<LocationEntity> viable,
            List<LocationEntity> rejected,
            Map<LocationEntity, Integer> cloudByLocation) {}

}
