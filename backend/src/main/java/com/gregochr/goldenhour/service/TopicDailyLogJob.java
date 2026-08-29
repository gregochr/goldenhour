package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.StormSurgeDetails;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TopicDailyLogEntity;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TopicDailyLogRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightly presence/intensity log for the Coming-up model's candidate topics (plan P7,
 * {@code docs/engineering/coming-up-plan.md} §10).
 *
 * <p>This job is deliberately invisible: it writes one {@link TopicDailyLogEntity} row per
 * candidate topic per region per night, and nothing yet reads the table. It exists so that a later
 * phase can replace today's config-fallback rarity and interim magnitude buckets (plan §2 D4) with
 * observed rates once enough nights have accrued, and so a future band-hysteresis rule has a
 * per-night prior band to read back.
 *
 * <p><b>Each topic reads from whichever store is least triage-biased for that topic</b> — the
 * "denominator lesson" from the plan's round-3 review (§14): a store that only holds survivors
 * (rows that passed triage) understates presence, because a day the condition fired but got
 * triaged out reads as an absence. Documented per topic below:
 * <ul>
 *   <li><b>DUST, STORM_SURGE</b> — {@code forecast_evaluation}, the complete population. Both
 *       columns are written from {@code AtmosphericData} during augmentation, before triage or any
 *       Claude call ({@code ForecastService.buildEntity}), so they land on every evaluated row
 *       regardless of outcome.</li>
 *   <li><b>INVERSION</b> — {@code forecast_score} (survivor-only; the best available). Unlike dust
 *       and surge, the persisted inversion score is Claude's own output ({@code InversionDetails}
 *       Javadoc: "Cloud inversion score returned by Claude"), so it is null on any row that never
 *       reached Claude — there is no unbiased population to read yet (plan §1/§7: "inversion rarity
 *       stays on the config fallback until P7's log exists"). This job is that log's first writer.</li>
 *   <li><b>SNOW</b> — {@code survivor_atmosphere} (survivor-only; the only source — the
 *       {@code forecast_evaluation} snow columns were dropped in V116). The plan's candidate list
 *       names one "SNOW" topic, but the codebase has two live snow strategies:
 *       {@link SnowFreshHotTopicStrategy} (lying-depth threshold, a plain column reading) and
 *       {@link SnowTopsHotTopicStrategy} (a derived, fell-summit condition gated on freezing level
 *       against a location's elevation). This job logs {@code SnowFreshHotTopicStrategy}'s reading:
 *       it is the direct presence/intensity pair the table's two columns were shaped for, where
 *       "tops" is a compositional variant of the same underlying reading rather than a second
 *       independent phenomenon. Revisit if a future phase wants fell-summit snow logged as its own
 *       topic type.</li>
 *   <li><b>SPRING_TIDE, KING_TIDE</b> — {@code tide_extreme} + each location's spring-tide height
 *       threshold ({@code TideService.getTideStats}), the same unbiased, ephemeris-and-measurement
 *       basis {@link TideSizeIndex} uses — not read through that class because it answers
 *       roster-wide, not per region. ⚠️ <b>The two-axis rule ({@code backend/AGENTS.md} §2, "never OR
 *       the height test into the king label"):</b> "which dates and how big" is the height test
 *       above; "what KIND of event" is the moon's alone, via
 *       {@link LunarPhaseService#nearestSyzygyIsPerigean}, asked once per date so the whole roster
 *       agrees on one label that day. A qualifying date logs {@code KING_TIDE} when its nearest
 *       syzygy is perigean and {@code SPRING_TIDE} otherwise — including a big spring that clears
 *       its port's P95 without being perigean, the exact case
 *       {@code KingTideHotTopicStrategy.isPerigeanSpring}'s own history warns against re-admitting.
 *       {@code TideStats.p95HighMetres} is therefore never read here.</li>
 *   <li><b>AURORA</b> — {@code aurora_forecast_result}, written only when an admin manually
 *       triggers a run. Most nights carry no row at all; this job writes nothing for a region with
 *       no row that night (unmeasured, not false) and durably archives whatever was captured before
 *       a later admin run can overwrite it. Intensity is always {@code null} — the stored Kp figure
 *       exists only on these sparse, admin-triggered nights, so treating it as a comparable
 *       magnitude series would overstate what is actually a gap-filled sample (plan §10: "aurora and
 *       NLC log present with null intensity").</li>
 *   <li><b>NLC</b> — no per-night observational signal exists anywhere in this codebase
 *       ({@code NlcClarityService} is a stateless, forward-looking, in-memory cache; the NLCNET
 *       sighting scrape is un-persisted and its free-text locations do not map to the region
 *       roster). Presence here is therefore the deterministic, unbiased NLC season boundary
 *       ({@code NlcClarityService.isNlcSeason}) — "conditions were seasonally possible", not "NLC
 *       was seen" — logged identically for every region since the season is not region-specific.
 *       Intensity is always {@code null}, for the same reason as AURORA.</li>
 * </ul>
 *
 * <p><b>{@code landed_on_window} is set only where it is cheap and honest.</b> For the four
 * survivor/evaluation-row topics it is {@code true} whenever the topic is present, because those
 * rows are always keyed to a {@code SUNRISE} or {@code SUNSET} slot — "satisfied by construction",
 * the same reasoning D5 uses for the standing-condition peak gate. For SPRING_TIDE/KING_TIDE it is
 * left {@code null}: a true tide-in-light answer needs the day's aligned high/low water
 * ({@code TideRunDay.alignedEvent}), which this job deliberately does not replicate — a recorded
 * gap for whoever next extends this job, in the spirit of plan §11.21. AURORA and NLC have no light
 * window to land on and are always {@code null}.
 *
 * <p>{@code band} is written by no phase yet; it stays {@code null} until a future hysteresis rule
 * needs a prior-band store to read.
 */
@Service
public class TopicDailyLogJob {

    private static final Logger LOG = LoggerFactory.getLogger(TopicDailyLogJob.class);

    /** Scheduler key; matches the {@code scheduler_job_config} row seeded by V151. */
    static final String JOB_KEY = "topic_daily_log";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    static final String TYPE_DUST = "DUST";
    static final String TYPE_INVERSION = "INVERSION";
    static final String TYPE_SPRING_TIDE = "SPRING_TIDE";
    static final String TYPE_KING_TIDE = "KING_TIDE";
    static final String TYPE_STORM_SURGE = "STORM_SURGE";
    static final String TYPE_SNOW = "SNOW";
    static final String TYPE_AURORA = "AURORA";
    static final String TYPE_NLC = "NLC";

    private static final String STORM_SURGE_HIGH = "HIGH";

    private static final List<TargetType> EVENT_TARGET_TYPES = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final ForecastScoreRepository forecastScoreRepository;
    private final SurvivorAtmosphereRepository survivorAtmosphereRepository;
    private final TideExtremeRepository tideExtremeRepository;
    private final TideService tideService;
    private final LunarPhaseService lunarPhaseService;
    private final LocationRepository locationRepository;
    private final AuroraForecastResultRepository auroraForecastResultRepository;
    private final NlcClarityService nlcClarityService;
    private final RegionService regionService;
    private final TopicDailyLogRepository topicDailyLogRepository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    /**
     * Constructs the job.
     *
     * @param forecastEvaluationRepository  the complete-population source for DUST and STORM_SURGE
     * @param forecastScoreRepository       the survivor-only source for INVERSION
     * @param survivorAtmosphereRepository  the survivor-only source for SNOW
     * @param tideExtremeRepository         stored tide extremes for SPRING_TIDE/KING_TIDE
     * @param tideService                   per-location spring-tide height threshold
     * @param lunarPhaseService             decides the SPRING_TIDE vs KING_TIDE label (never height)
     * @param locationRepository            the coastal roster for tide topics
     * @param auroraForecastResultRepository admin-triggered nightly aurora results
     * @param nlcClarityService             the NLC season boundary predicate
     * @param regionService                 the full region roster (for NLC's uniform sweep)
     * @param topicDailyLogRepository       the append-only sink this job writes
     * @param dynamicSchedulerService       the DB-backed scheduler this job registers with
     * @param clock                         clock used to resolve "yesterday" on the UK civil calendar
     */
    public TopicDailyLogJob(ForecastEvaluationRepository forecastEvaluationRepository,
            ForecastScoreRepository forecastScoreRepository,
            SurvivorAtmosphereRepository survivorAtmosphereRepository,
            TideExtremeRepository tideExtremeRepository,
            TideService tideService,
            LunarPhaseService lunarPhaseService,
            LocationRepository locationRepository,
            AuroraForecastResultRepository auroraForecastResultRepository,
            NlcClarityService nlcClarityService,
            RegionService regionService,
            TopicDailyLogRepository topicDailyLogRepository,
            DynamicSchedulerService dynamicSchedulerService,
            Clock clock) {
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.forecastScoreRepository = forecastScoreRepository;
        this.survivorAtmosphereRepository = survivorAtmosphereRepository;
        this.tideExtremeRepository = tideExtremeRepository;
        this.tideService = tideService;
        this.lunarPhaseService = lunarPhaseService;
        this.locationRepository = locationRepository;
        this.auroraForecastResultRepository = auroraForecastResultRepository;
        this.nlcClarityService = nlcClarityService;
        this.regionService = regionService;
        this.topicDailyLogRepository = topicDailyLogRepository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /** Registers the nightly log with the dynamic scheduler. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::runScheduled);
    }

    /**
     * Logs presence/intensity for every candidate topic, for yesterday on the UK civil calendar.
     *
     * <p>Each topic is evaluated independently and a failure is logged and stepped over rather than
     * aborting the run — one topic's read failing must not deny every other topic its night's row.
     */
    void runScheduled() {
        LocalDate yesterday = ForecastHorizon.today(clock).minusDays(1);
        logDustAndStormSurge(yesterday);
        logInversion(yesterday);
        logSnow(yesterday);
        logTides(yesterday);
        logAurora(yesterday);
        logNlc(yesterday);
        LOG.info("Topic daily log complete for {}", yesterday);
    }

    /**
     * DUST and STORM_SURGE share one query — both columns are written on the same
     * {@code forecast_evaluation} rows, so reading them together halves the roster sweep.
     */
    private void logDustAndStormSurge(LocalDate date) {
        try {
            List<ForecastEvaluationEntity> rows =
                    forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(date, EVENT_TARGET_TYPES);
            Map<Long, Reading> dustByRegion = new LinkedHashMap<>();
            Map<Long, Reading> surgeByRegion = new LinkedHashMap<>();

            for (ForecastEvaluationEntity row : rows) {
                RegionEntity region = regionOf(row.getLocation());
                if (region == null || region.getId() == null) {
                    continue;
                }

                Reading dust = dustByRegion.computeIfAbsent(region.getId(), id -> new Reading());
                if (DustHotTopicStrategy.isDustEnhanced(
                        row.getAerosolOpticalDepth(), row.getDust(), row.getPm25())) {
                    dust.present = true;
                    dust.landedOnWindow = Boolean.TRUE;
                }
                dust.maxIntensity = maxOf(dust.maxIntensity, row.getAerosolOpticalDepth());

                StormSurgeDetails surge = row.getSurge();
                if (surge != null) {
                    Reading surgeReading = surgeByRegion.computeIfAbsent(region.getId(), id -> new Reading());
                    if (STORM_SURGE_HIGH.equals(surge.getRiskLevel())) {
                        surgeReading.present = true;
                        surgeReading.landedOnWindow = Boolean.TRUE;
                    }
                    Double total = surge.getTotalMetres();
                    surgeReading.maxIntensity =
                            maxOf(surgeReading.maxIntensity, total == null ? null : BigDecimal.valueOf(total));
                }
            }

            persist(TYPE_DUST, date, dustByRegion);
            persist(TYPE_STORM_SURGE, date, surgeByRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: DUST/STORM_SURGE failed for {}: {}", date, e.getMessage());
        }
    }

    /**
     * Sunrise rows only — an inversion "sea of clouds" is a dawn phenomenon, and a SUNSET row's
     * score is physically meaningless (see {@link InversionHotTopicStrategy}'s Javadoc).
     */
    private void logInversion(LocalDate date) {
        try {
            List<ForecastScoreEntity> rows =
                    forecastScoreRepository.findComponentsByType(ForecastType.INVERSION.getId(), date, date);
            Map<Long, Reading> byRegion = new LinkedHashMap<>();

            for (ForecastScoreEntity row : rows) {
                if (row.getEventType() != TargetType.SUNRISE) {
                    continue;
                }
                RegionEntity region = regionOf(row.getLocation());
                if (region == null || region.getId() == null) {
                    continue;
                }
                Reading reading = byRegion.computeIfAbsent(region.getId(), id -> new Reading());
                Integer score = row.getScore();
                if (score != null && score >= InversionHotTopicStrategy.STRONG_SCORE_INCLUSIVE) {
                    reading.present = true;
                    reading.landedOnWindow = Boolean.TRUE;
                }
                if (score != null) {
                    reading.maxIntensity = maxOf(reading.maxIntensity, BigDecimal.valueOf(score));
                }
            }

            persist(TYPE_INVERSION, date, byRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: INVERSION failed for {}: {}", date, e.getMessage());
        }
    }

    private void logSnow(LocalDate date) {
        try {
            List<SurvivorAtmosphereEntity> rows = survivorAtmosphereRepository.findInDateRange(date, date);
            Map<Long, Reading> byRegion = new LinkedHashMap<>();

            for (SurvivorAtmosphereEntity row : rows) {
                RegionEntity region = regionOf(row.getLocation());
                if (region == null || region.getId() == null) {
                    continue;
                }
                Reading reading = byRegion.computeIfAbsent(region.getId(), id -> new Reading());
                Double depth = row.getSnowDepthMetres();
                if (SnowFreshHotTopicStrategy.isFreshSnow(depth)) {
                    reading.present = true;
                    reading.landedOnWindow = Boolean.TRUE;
                }
                if (depth != null) {
                    reading.maxIntensity = maxOf(reading.maxIntensity, BigDecimal.valueOf(depth));
                }
            }

            persist(TYPE_SNOW, date, byRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: SNOW failed for {}: {}", date, e.getMessage());
        }
    }

    /**
     * SPRING_TIDE and KING_TIDE, measured the same way {@link TideSizeIndex} measures them
     * roster-wide — the day's biggest high water at each coastal location against that location's
     * own spring/P95 thresholds — but grouped per region rather than unioned across the roster.
     */
    private void logTides(LocalDate date) {
        try {
            List<LocationEntity> coastal = locationRepository.findCoastalLocations();
            Map<Long, LocationEntity> locationById = new HashMap<>();
            for (LocationEntity location : coastal) {
                if (location.getId() != null) {
                    locationById.put(location.getId(), location);
                }
            }
            if (locationById.isEmpty()) {
                return;
            }

            LocalDateTime windowStart =
                    date.atStartOfDay(LONDON).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime windowEnd =
                    date.plusDays(1).atStartOfDay(LONDON).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            List<TideExtremeEntity> extremes = tideExtremeRepository
                    .findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                            locationById.keySet(), TideExtremeType.HIGH, windowStart, windowEnd);

            Map<Long, Double> dayMaxByLocation = new HashMap<>();
            for (TideExtremeEntity extreme : extremes) {
                if (extreme.getHeightMetres() == null || extreme.getEventTime() == null) {
                    continue;
                }
                LocalDate local = extreme.getEventTime().atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(LONDON).toLocalDate();
                if (!local.equals(date)) {
                    continue;
                }
                dayMaxByLocation.merge(extreme.getLocationId(), extreme.getHeightMetres().doubleValue(), Math::max);
            }

            // Which dates and how big is the height test (TideSizeIndex's own axis); what KIND of
            // event it is is the moon's alone, asked once per date so every location on the roster
            // agrees on the same label that day — never OR the height test into the king label
            // (backend/AGENTS.md §2). A date whose nearest syzygy is perigean logs KING_TIDE; every
            // other qualifying date logs SPRING_TIDE — including a big spring that clears its port's
            // P95 without being perigean (the exact case `KingTideHotTopicStrategy.isPerigeanSpring`
            // stopped accepting `heightAboveP95()` for). `p95HighMetres` is therefore not read here
            // at all: it answers a different, size-only question this job does not ask.
            boolean king = lunarPhaseService.nearestSyzygyIsPerigean(date);
            String labelType = king ? TYPE_KING_TIDE : TYPE_SPRING_TIDE;
            Map<Long, Reading> byRegion = new LinkedHashMap<>();

            for (Map.Entry<Long, Double> entry : dayMaxByLocation.entrySet()) {
                LocationEntity location = locationById.get(entry.getKey());
                RegionEntity region = regionOf(location);
                if (region == null || region.getId() == null) {
                    continue;
                }
                TideStats stats = tideService.getTideStats(entry.getKey()).orElse(null);
                if (stats == null || stats.springTideThreshold() == null) {
                    continue;
                }
                double high = entry.getValue();
                if (high <= stats.springTideThreshold().doubleValue()) {
                    continue;
                }
                Reading reading = byRegion.computeIfAbsent(region.getId(), id -> new Reading());
                reading.present = true;
                reading.maxIntensity = maxOf(reading.maxIntensity, BigDecimal.valueOf(high));
            }

            persist(labelType, date, byRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: SPRING_TIDE/KING_TIDE failed for {}: {}", date, e.getMessage());
        }
    }

    /**
     * Only regions with a stored result for the night get a row — most nights carry none at all
     * (the table is written only on a manual admin trigger), and "no row" must read as unmeasured,
     * never as a false presence.
     */
    private void logAurora(LocalDate date) {
        try {
            List<AuroraForecastResultEntity> rows =
                    auroraForecastResultRepository.findByForecastDateFetchingLocation(date);
            Map<Long, Reading> byRegion = new LinkedHashMap<>();

            for (AuroraForecastResultEntity row : rows) {
                RegionEntity region = regionOf(row.getLocation());
                if (region == null || region.getId() == null) {
                    continue;
                }
                Reading reading = byRegion.computeIfAbsent(region.getId(), id -> new Reading());
                if (isAlertWorthy(row.getAlertLevel())) {
                    reading.present = true;
                }
            }

            persist(TYPE_AURORA, date, byRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: AURORA failed for {}: {}", date, e.getMessage());
        }
    }

    private static boolean isAlertWorthy(String alertLevel) {
        if (alertLevel == null) {
            return false;
        }
        try {
            return AlertLevel.valueOf(alertLevel).isAlertWorthy();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Presence here means "yesterday fell within the NLC season window" — a deterministic,
     * unbiased fact available for any date — not an observed sighting; see the class Javadoc. The
     * season is not region-specific, so every region gets the same reading.
     */
    private void logNlc(LocalDate date) {
        try {
            boolean inSeason = nlcClarityService.isNlcSeason(date);
            Map<Long, Reading> byRegion = new LinkedHashMap<>();
            for (RegionEntity region : regionService.findAll()) {
                if (region.getId() == null) {
                    continue;
                }
                Reading reading = new Reading();
                reading.present = inSeason;
                byRegion.put(region.getId(), reading);
            }

            persist(TYPE_NLC, date, byRegion);
        } catch (Exception e) {
            LOG.warn("Topic daily log: NLC failed for {}: {}", date, e.getMessage());
        }
    }

    /**
     * Writes one row per region, skipping any key already stored.
     *
     * <p>The existence check and the insert are not one transaction, so a second job execution
     * racing this one (a scheduled fire overlapping an admin-triggered {@code triggerNow}, or a
     * restart mid-run) can still lose the check-then-act race and hit the table's unique
     * constraint. That failure is caught per row rather than left to propagate: an uncaught
     * {@link DataIntegrityViolationException} would abort this whole {@code Map}'s remaining
     * regions (and, for the combined DUST/STORM_SURGE call, silently drop STORM_SURGE too, since
     * it persists after DUST in the same try block) — for an append-only table with no backfill
     * path, that is a permanently missing night for every region after the one that collided.
     */
    private void persist(String topicType, LocalDate date, Map<Long, Reading> byRegion) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<Long, Reading> entry : byRegion.entrySet()) {
            Long regionId = entry.getKey();
            if (topicDailyLogRepository.existsByTopicTypeAndLogDateAndRegionId(topicType, date, regionId)) {
                continue;
            }
            Reading reading = entry.getValue();
            try {
                topicDailyLogRepository.save(TopicDailyLogEntity.builder()
                        .topicType(topicType)
                        .logDate(date)
                        .regionId(regionId)
                        .present(reading.present)
                        .intensity(reading.maxIntensity)
                        .landedOnWindow(reading.landedOnWindow)
                        .loggedAt(now)
                        .build());
            } catch (DataIntegrityViolationException e) {
                LOG.debug("Topic daily log: {} {} region {} already written by a concurrent run",
                        topicType, date, regionId);
            }
        }
    }

    private static RegionEntity regionOf(LocationEntity location) {
        return location == null ? null : location.getRegion();
    }

    private static BigDecimal maxOf(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : current.max(candidate);
    }

    /** Mutable per-region accumulator for one topic's night. */
    private static final class Reading {
        private boolean present;
        private BigDecimal maxIntensity;
        private Boolean landedOnWindow;
    }
}
