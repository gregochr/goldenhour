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
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
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
 * the night after. Makes no external API calls — reads only from
 * {@link AuroraStateCache} and the cached Kp forecast.
 *
 * <p><b>"Tonight" is the poller's night, not the civil date.</b> The alert level this strategy
 * reads is set by {@code AuroraOrchestrator} for the dark window
 * {@code AuroraPollingJob.calculateTonightWindow(now)} names — before nautical dawn that is the
 * night still running, whose dusk fell on <em>yesterday's</em> date. The topic is dated from the
 * same rule, through {@link AuroraForecastRunService#currentNight()} (its twin), so that a
 * {@code NIGHT} topic lands on the windows of the night the alert is actually about: dated
 * yesterday, {@code PlanWindowProjector} buckets it onto this morning's sunrise. Dated from the
 * civil date, as it was until 2026-10-08, a pre-dawn alert sat on this evening's sunset — the
 * following night — while the banner, reading the same level, said "tonight" about the one ending
 * in an hour. The forecast topic names the night after the poller's, so that pre-dawn the coming
 * night is covered too; both details word the night relative to the civil {@code fromDate}
 * ("until dawn", "tonight", "tomorrow night"), never by which of the two topics emitted them.
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
    private final AuroraForecastRunService forecastRunService;

    /**
     * Constructs an {@code AuroraHotTopicStrategy}.
     *
     * @param auroraStateCache      cache holding the current aurora alert state
     * @param noaaSwpcClient        client for cached Kp forecast data
     * @param locationRepository    repository for location lookups
     * @param auroraSummaryBuilder  builder for tonight's aurora summary (cloud-triaged counts)
     * @param forecastRunService    names the night the alert level is about — the night in
     *                              progress before dawn, else the coming one — from the clock
     */
    public AuroraHotTopicStrategy(AuroraStateCache auroraStateCache,
            NoaaSwpcClient noaaSwpcClient,
            LocationRepository locationRepository,
            BriefingAuroraSummaryBuilder auroraSummaryBuilder,
            AuroraForecastRunService forecastRunService) {
        this.auroraStateCache = auroraStateCache;
        this.noaaSwpcClient = noaaSwpcClient;
        this.locationRepository = locationRepository;
        this.auroraSummaryBuilder = auroraSummaryBuilder;
        this.forecastRunService = forecastRunService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits up to two topics: one for the poller's current night (from live cache state) if
     * the alert level is MINOR or above, and one for the night after it if the cached Kp forecast
     * peaks at 4+. Both are dated by the night's dusk date, the convention every {@code NIGHT}
     * topic shares — so before dawn the first is dated yesterday, and is admitted because that
     * night's sunrise is {@code fromDate}'s own.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<HotTopic> topics = new ArrayList<>();

        LocalDate night = forecastRunService.currentNight().date();
        if (!night.isBefore(fromDate.minusDays(1)) && !night.isAfter(toDate)) {
            detectTonight(night, fromDate, topics);
        }
        LocalDate nextNight = night.plusDays(1);
        if (!nextNight.isBefore(fromDate) && !nextNight.isAfter(toDate)) {
            detectTomorrow(nextNight, fromDate, topics);
        }

        return topics;
    }

    /**
     * The words a detail line uses for a night, relative to the civil day the topics were asked
     * for: the night still running before dawn is "until dawn", today's is "tonight", and any
     * later one is "tomorrow night".
     *
     * @param night the date of the night's dusk
     * @param today the civil date the detection was asked for
     * @return the phrase naming that night
     */
    private static String nightPhrase(LocalDate night, LocalDate today) {
        if (night.isBefore(today)) {
            return "until dawn";
        }
        return night.equals(today) ? "tonight" : "tomorrow night";
    }

    private void detectTonight(LocalDate night, LocalDate today, List<HotTopic> topics) {
        // An admin's aurora simulation (POST /api/aurora/admin/simulate) is for admin UI testing
        // only. It must never reach a signed-in user's Plan cards as if it were a real alert, so
        // this returns before touching the level or trigger Kp the simulation injects.
        if (auroraStateCache.getSimulatedData() != null) {
            return;
        }
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
        Integer totalCount;
        Double moonPct;
        if (tonight != null) {
            clearCount = tonight.clearLocationCount();
            totalCount = tonight.totalDarkSkyCount();
            moonPct = tonight.moonIlluminationPct();
        } else {
            clearCount = auroraStateCache.getClearLocationCount();
            totalCount = auroraStateCache.getDarkSkyLocationCount();
            moonPct = null;
        }

        int priority = (level == AlertLevel.STRONG || level == AlertLevel.MODERATE) ? 1 : 2;
        String detail = buildTonightDetail(kp, clearCount, totalCount, nightPhrase(night, today));
        List<String> regions = findAuroraRegions();

        HotTopic topic = new HotTopic(
                "AURORA",
                "Aurora possible",
                detail,
                night,
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

    private void detectTomorrow(LocalDate nextNight, LocalDate today, List<HotTopic> topics) {
        double tomorrowPeakKp = findTomorrowNightPeakKp(nextNight);
        if (tomorrowPeakKp < TOMORROW_KP_THRESHOLD) {
            return;
        }

        AuroraTomorrowSummary tomorrow = auroraSummaryBuilder.buildAuroraTomorrowCached();
        Double moonPct = tomorrow != null ? tomorrow.moonIlluminationPct() : null;

        HotTopic topic = new HotTopic(
                "AURORA",
                "Aurora possible",
                String.format("Kp %.0f forecast %s — worth watching",
                        tomorrowPeakKp, nightPhrase(nextNight, today)),
                nextNight,
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

    private String buildTonightDetail(Double kp, Integer clearCount, Integer totalCount,
            String nightPhrase) {
        StringBuilder sb = new StringBuilder();
        if (kp != null) {
            sb.append(String.format("Kp %.0f forecast %s", kp, nightPhrase));
        } else {
            sb.append("Elevated activity ").append(nightPhrase);
        }
        if (clearCount != null && clearCount > 0) {
            // "clear at X of Y" when the dark-sky total is known and consistent, so the number reads
            // as how widespread the clear sky is; fall back to the bare clear count otherwise.
            if (totalCount != null && totalCount >= clearCount) {
                sb.append(String.format(
                        " — clear at %d of %d dark-sky locations", clearCount, totalCount));
            } else {
                sb.append(String.format(" — %d dark-sky locations with clear skies", clearCount));
            }
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
