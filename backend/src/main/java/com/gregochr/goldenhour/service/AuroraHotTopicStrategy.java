package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects aurora hot topics from cached NOAA data.
 *
 * <p>Emits a hot topic when the aurora alert level is MINOR or above (tonight),
 * and optionally when the cached 3-day Kp forecast shows Kp &ge; 4 for
 * tomorrow night. Makes no external API calls — reads only from
 * {@link AuroraStateCache} and the cached Kp forecast.
 */
@Component
public class AuroraHotTopicStrategy implements HotTopicStrategy {

    private static final String AURORA_DESCRIPTION =
            "The aurora borealis is occasionally visible from northern England when"
                    + " solar activity is high. Best seen from dark-sky locations with"
                    + " a clear northern horizon.";

    /** The italic "where to look" cue on the enriched aurora fact line. */
    private static final String AURORA_NOTE = "look due N, low";

    /** Minimum Kp forecast to emit a tomorrow-night topic. */
    private static final double TOMORROW_KP_THRESHOLD = 4.0;

    /** Start of dark hours (UTC) for filtering Kp forecast windows. */
    private static final int DARK_HOURS_START = 18;

    /** End of dark hours (UTC, exclusive) for filtering Kp forecast windows. */
    private static final int DARK_HOURS_END = 6;

    private final AuroraStateCache auroraStateCache;
    private final NoaaSwpcClient noaaSwpcClient;
    private final LocationRepository locationRepository;
    private final BriefingAuroraSummaryBuilder auroraSummaryBuilder;

    /**
     * Constructs an {@code AuroraHotTopicStrategy}.
     *
     * @param auroraStateCache      cache holding the current aurora alert state
     * @param noaaSwpcClient        client for cached Kp forecast data
     * @param locationRepository    repository for location lookups
     * @param auroraSummaryBuilder  builder for tonight's aurora summary (cloud-triaged counts)
     */
    public AuroraHotTopicStrategy(AuroraStateCache auroraStateCache,
            NoaaSwpcClient noaaSwpcClient,
            LocationRepository locationRepository,
            BriefingAuroraSummaryBuilder auroraSummaryBuilder) {
        this.auroraStateCache = auroraStateCache;
        this.noaaSwpcClient = noaaSwpcClient;
        this.locationRepository = locationRepository;
        this.auroraSummaryBuilder = auroraSummaryBuilder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits up to two topics: one for tonight (from live cache state) if the
     * alert level is MINOR or above, and one for tomorrow night if the cached
     * Kp forecast peaks at 4+.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<HotTopic> topics = new ArrayList<>();

        detectTonight(fromDate, topics);
        if (!fromDate.plusDays(1).isAfter(toDate)) {
            detectTomorrow(fromDate, topics);
        }

        return topics;
    }

    private void detectTonight(LocalDate fromDate, List<HotTopic> topics) {
        AlertLevel level = auroraStateCache.getCurrentLevel();
        if (level == null || level == AlertLevel.QUIET) {
            return;
        }

        Double kp = auroraStateCache.getLastTriggerKp();
        // Fetch the cached tonight summary once — it supplies both the clear-location count (fresh
        // Open-Meteo triage) and the moon illumination the fact line reads.
        AuroraTonightSummary tonight = auroraSummaryBuilder.buildAuroraTonightCached();
        // Read count + moon from the summary when present, else fall back to the state cache's
        // count (an if/else, not a mixed int/Integer ternary, which would unbox a null count → NPE).
        Integer clearCount;
        Double moonPct;
        if (tonight != null) {
            clearCount = tonight.clearLocationCount();
            moonPct = tonight.moonIlluminationPct();
        } else {
            clearCount = auroraStateCache.getClearLocationCount();
            moonPct = null;
        }

        int priority = (level == AlertLevel.STRONG || level == AlertLevel.MODERATE) ? 1 : 2;
        String detail = buildTonightDetail(kp, clearCount);
        List<String> regions = findAuroraRegions();

        HotTopic topic = new HotTopic(
                "AURORA",
                "Aurora possible",
                detail,
                fromDate,
                priority,
                null,
                regions,
                AURORA_DESCRIPTION,
                null);
        List<HotTopicFact> facts = buildAuroraFacts(kp, moonPct);
        if (!facts.isEmpty()) {
            topic = topic.withScience(facts, AURORA_NOTE);
        }
        topics.add(topic);
    }

    private void detectTomorrow(LocalDate fromDate, List<HotTopic> topics) {
        double tomorrowPeakKp = findTomorrowNightPeakKp(fromDate.plusDays(1));
        if (tomorrowPeakKp < TOMORROW_KP_THRESHOLD) {
            return;
        }

        AuroraTomorrowSummary tomorrow = auroraSummaryBuilder.buildAuroraTomorrowCached();
        Double moonPct = tomorrow != null ? tomorrow.moonIlluminationPct() : null;

        HotTopic topic = new HotTopic(
                "AURORA",
                "Aurora possible",
                String.format("Kp %.0f forecast tomorrow night — worth watching",
                        tomorrowPeakKp),
                fromDate.plusDays(1),
                3,
                null,
                findAuroraRegions(),
                AURORA_DESCRIPTION,
                null);
        List<HotTopicFact> facts = buildAuroraFacts(tomorrowPeakKp, moonPct);
        if (!facts.isEmpty()) {
            topic = topic.withScience(facts, AURORA_NOTE);
        }
        topics.add(topic);
    }

    /**
     * Builds the enriched aurora fact chips — the Kp + glow-latitude headline (only when a trigger
     * Kp is known, so the latitude is data-backed) and the optional moon-illumination chip. The
     * magnetic-midnight peak window is not computed anywhere and is deliberately omitted rather than
     * fabricated. Returns an empty list when neither figure is available (nothing rendered).
     *
     * @param kp      the Kp index, or null
     * @param moonPct lunar illumination percent (0–100), or null
     * @return the aurora fact chips (possibly empty)
     */
    private List<HotTopicFact> buildAuroraFacts(Double kp, Double moonPct) {
        List<HotTopicFact> facts = new ArrayList<>();
        if (kp != null) {
            double cap = noaaSwpcClient.getGlowLatitudeCap(kp);
            String value = cap > 0
                    ? String.format("Kp %.0f · glow reaches ~%.0f°N and north", kp, cap)
                    : String.format("Kp %.0f · glow across the whole UK", kp);
            facts.add(HotTopicFact.metric(null, value));
        }
        if (moonPct != null) {
            facts.add(HotTopicFact.context("moon " + Math.round(moonPct) + "%").asOptional());
        }
        return facts;
    }

    private String buildTonightDetail(Double kp, Integer clearCount) {
        StringBuilder sb = new StringBuilder();
        if (kp != null) {
            sb.append(String.format("Kp %.0f forecast tonight", kp));
        } else {
            sb.append("Elevated activity tonight");
        }
        if (clearCount != null && clearCount > 0) {
            sb.append(String.format(" — %d dark-sky locations with clear skies", clearCount));
        }
        return sb.toString();
    }

    private List<String> findAuroraRegions() {
        return locationRepository.findByBortleClassIsNotNullAndEnabledTrue().stream()
                .map(LocationEntity::getRegion)
                .filter(Objects::nonNull)
                .map(RegionEntity::getName)
                .distinct()
                .toList();
    }

    private double findTomorrowNightPeakKp(LocalDate tomorrow) {
        List<KpForecast> forecast = noaaSwpcClient.getCachedKpForecast();
        if (forecast == null || forecast.isEmpty()) {
            return 0.0;
        }
        return forecast.stream()
                .filter(kf -> isDuringDarkHours(kf, tomorrow))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
    }

    private boolean isDuringDarkHours(KpForecast kf, LocalDate night) {
        LocalDate forecastDate = kf.from().toLocalDate();
        int hour = kf.from().getHour();
        // Evening of the target night (18:00-23:59 UTC)
        // or early hours of the following morning (00:00-05:59 UTC)
        return (forecastDate.equals(night) && hour >= DARK_HOURS_START)
                || (forecastDate.equals(night.plusDays(1)) && hour < DARK_HOURS_END);
    }
}
