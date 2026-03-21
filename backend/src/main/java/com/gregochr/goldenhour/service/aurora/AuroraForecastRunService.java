package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.MetOfficeSpaceWeatherScraper;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastPreview;
import com.gregochr.goldenhour.model.AuroraForecastResultDto;
import com.gregochr.goldenhour.model.AuroraForecastRunRequest;
import com.gregochr.goldenhour.model.AuroraForecastRunResponse;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.SolarCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates manual aurora forecast runs for user-selected nights.
 *
 * <p>Unlike {@link AuroraOrchestrator} (which is driven by live NOAA alerts), this service
 * runs on demand from the Admin UI. It supports tonight, T+1, and T+2 by computing the
 * correct dark window for each requested date, fetching Kp forecasts, running weather triage
 * (for tonight only), calling Claude once per viable night, and storing results to the database.
 *
 * <p>Stored results persist across restarts and are independent of the live alert state machine.
 * They power the Aurora map mode for any date on the date strip.
 */
@Service
public class AuroraForecastRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraForecastRunService.class);

    /** Durham, UK — representative UK reference latitude for twilight calculations. */
    static final double DURHAM_LAT = 54.776;

    /** Durham longitude. */
    static final double DURHAM_LON = -1.575;

    /** Buffer in minutes applied to civil twilight to approximate nautical twilight (−12°). */
    static final int NAUTICAL_BUFFER_MINUTES = 35;

    /** Number of nights shown in the preview (tonight, T+1, T+2). */
    private static final int PREVIEW_NIGHTS = 3;

    /** Approximate cost per Claude call used for the estimated-cost display. */
    private static final double HAIKU_COST_USD = 0.01;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    private final NoaaSwpcClient noaaClient;
    private final MetOfficeSpaceWeatherScraper metOfficeScraper;
    private final WeatherTriageService weatherTriage;
    private final ClaudeAuroraInterpreter claudeInterpreter;
    private final LocationRepository locationRepository;
    private final AuroraForecastResultRepository resultRepository;
    private final AuroraProperties properties;
    private final SolarCalculator solarCalculator;

    /**
     * Constructs the service with all required dependencies.
     *
     * @param noaaClient         NOAA SWPC client for Kp forecast data
     * @param metOfficeScraper   Met Office space weather scraper
     * @param weatherTriage      cloud cover triage service (used for tonight only)
     * @param claudeInterpreter  Claude aurora scoring service
     * @param locationRepository location data access
     * @param resultRepository   aurora result persistence
     * @param properties         aurora configuration
     * @param solarCalculator    solar-utils twilight calculator
     */
    public AuroraForecastRunService(NoaaSwpcClient noaaClient,
            MetOfficeSpaceWeatherScraper metOfficeScraper,
            WeatherTriageService weatherTriage,
            ClaudeAuroraInterpreter claudeInterpreter,
            LocationRepository locationRepository,
            AuroraForecastResultRepository resultRepository,
            AuroraProperties properties,
            SolarCalculator solarCalculator) {
        this.noaaClient = noaaClient;
        this.metOfficeScraper = metOfficeScraper;
        this.weatherTriage = weatherTriage;
        this.claudeInterpreter = claudeInterpreter;
        this.locationRepository = locationRepository;
        this.resultRepository = resultRepository;
        this.properties = properties;
        this.solarCalculator = solarCalculator;
    }

    /**
     * Returns a 3-night Kp preview (tonight, T+1, T+2) for the night selector popup.
     *
     * <p>This is cheap to produce — it reads cached NOAA forecast data and queries the
     * locations table. No Claude API calls are made.
     *
     * @return preview of the next three nights
     */
    public AuroraForecastPreview getPreview() {
        List<KpForecast> kpForecast = noaaClient.fetchKpForecast();
        int moderateThreshold = properties.getBortleThreshold().getModerate();
        int eligibleCount = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(moderateThreshold).size();

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        List<AuroraForecastPreview.NightPreview> nights = new ArrayList<>();

        for (int i = 0; i < PREVIEW_NIGHTS; i++) {
            LocalDate date = today.plusDays(i);
            TonightWindow window = computeWindowForDate(date);
            double maxKp = maxKpInWindow(kpForecast, window);
            AlertLevel level = AlertLevel.fromKp(maxKp);
            String gScale = gScaleFromKp(maxKp);
            boolean recommended = maxKp >= properties.getTriggers().getKpThreshold();
            String label = buildDateLabel(date, i);
            String summary = buildKpSummary(maxKp, kpForecast, window, level);
            nights.add(new AuroraForecastPreview.NightPreview(
                    date, label, maxKp, gScale, recommended, summary, eligibleCount));
        }

        return new AuroraForecastPreview(nights);
    }

    /**
     * Runs aurora forecasts for the user-selected nights, storing results to the database.
     *
     * <p>For each night:
     * <ol>
     *   <li>Computes the dark window (nautical dusk → nautical dawn).</li>
     *   <li>Finds the max Kp forecast for that window.</li>
     *   <li>Queries Bortle-eligible locations.</li>
     *   <li>Runs weather triage (tonight only; future dates use default cloud estimates).</li>
     *   <li>Calls Claude once for all viable locations.</li>
     *   <li>Persists all results (Claude-scored and triaged) keyed by date and location.</li>
     * </ol>
     *
     * <p>Existing results for the requested dates are deleted before new results are inserted,
     * so re-running a night always produces fresh data.
     *
     * @param request the nights to forecast
     * @return per-night outcomes and cost summary
     */
    @Transactional
    public AuroraForecastRunResponse runForecast(AuroraForecastRunRequest request) {
        List<LocalDate> nights = request.nights();
        if (nights == null || nights.isEmpty()) {
            return new AuroraForecastRunResponse(List.of(), 0, "~$0.00");
        }

        // Delete any prior results for these dates so re-runs replace old data cleanly
        resultRepository.deleteByForecastDateIn(nights);

        SpaceWeatherData spaceWeather = noaaClient.fetchAll();
        String metOfficeText = metOfficeScraper.getForecastText();
        List<KpForecast> kpForecast = spaceWeather.kpForecast();
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        List<AuroraForecastRunResponse.NightResult> results = new ArrayList<>();
        int totalClaudeCalls = 0;
        Instant now = Instant.now();

        for (LocalDate date : nights) {
            TonightWindow window = computeWindowForDate(date);
            double maxKp = maxKpInWindow(kpForecast, window);

            if (maxKp < 1.0) {
                LOG.info("Aurora forecast {}: Kp={} — no significant activity", date, maxKp);
                results.add(new AuroraForecastRunResponse.NightResult(
                        date, "no_activity", 0, 0, maxKp, "No significant geomagnetic activity"));
                continue;
            }

            AlertLevel level = AlertLevel.fromKp(maxKp);
            int bortleThreshold = (level == AlertLevel.STRONG)
                    ? properties.getBortleThreshold().getStrong()
                    : properties.getBortleThreshold().getModerate();

            List<LocationEntity> candidates = locationRepository
                    .findByBortleClassLessThanEqualAndEnabledTrue(bortleThreshold);

            if (candidates.isEmpty()) {
                LOG.info("Aurora forecast {}: no Bortle-eligible locations (threshold={})",
                        date, bortleThreshold);
                results.add(new AuroraForecastRunResponse.NightResult(
                        date, "no_eligible_locations", 0, 0, maxKp,
                        "No dark-sky locations available (Bortle threshold = " + bortleThreshold + ")"));
                continue;
            }

            // Weather triage — accurate for tonight; future dates use optimistic defaults
            WeatherTriageService.TriageResult triage = date.equals(today)
                    ? weatherTriage.triage(candidates)
                    : buildFutureNightTriage(candidates);

            // Persist triage-rejected locations as 1★ template results
            for (LocationEntity rejected : triage.rejected()) {
                int cloud = triage.cloudByLocation().getOrDefault(rejected, 100);
                AuroraForecastResultEntity entity = AuroraForecastResultEntity.builder()
                        .location(rejected)
                        .forecastDate(date)
                        .runTimestamp(now)
                        .stars(1)
                        .summary("Overcast — aurora obscured by cloud")
                        .factors(null)
                        .triaged(true)
                        .triageReason("Cloud cover " + cloud + "% — no clear window in the aurora hours")
                        .source("triage_template")
                        .alertLevel(level.name())
                        .maxKp(maxKp)
                        .build();
                resultRepository.save(entity);
            }

            // Claude call for viable locations
            List<AuroraForecastScore> claudeScores = List.of();
            if (!triage.viable().isEmpty()) {
                LOG.info("Aurora forecast {}: Claude call for {} location(s) — level={}, Kp={}",
                        date, triage.viable().size(), level, maxKp);
                claudeScores = claudeInterpreter.interpret(
                        level, triage.viable(), triage.cloudByLocation(),
                        spaceWeather, metOfficeText, TriggerType.FORECAST_LOOKAHEAD, window);
                totalClaudeCalls++;

                for (AuroraForecastScore score : claudeScores) {
                    AuroraForecastResultEntity entity = AuroraForecastResultEntity.builder()
                            .location(score.location())
                            .forecastDate(date)
                            .runTimestamp(now)
                            .stars(score.stars())
                            .summary(score.summary())
                            .factors(score.detail())
                            .triaged(false)
                            .triageReason(null)
                            .source("claude")
                            .alertLevel(level.name())
                            .maxKp(maxKp)
                            .build();
                    resultRepository.save(entity);
                }
            }

            String nightStatus = triage.viable().isEmpty() ? "all_triaged" : "scored";
            String nightSummary = buildNightSummary(claudeScores, triage.rejected().size());
            results.add(new AuroraForecastRunResponse.NightResult(
                    date, nightStatus,
                    claudeScores.size(), triage.rejected().size(),
                    maxKp, nightSummary));

            LOG.info("Aurora forecast {}: {} Claude-scored, {} triaged, status={}",
                    date, claudeScores.size(), triage.rejected().size(), nightStatus);
        }

        String estimatedCost = String.format("~$%.2f", totalClaudeCalls * HAIKU_COST_USD);
        return new AuroraForecastRunResponse(results, totalClaudeCalls, estimatedCost);
    }

    /**
     * Returns all stored aurora forecast results for the given night.
     *
     * @param date the night to retrieve
     * @return list of DTOs, one per location scored or triaged
     */
    public List<AuroraForecastResultDto> getResultsForDate(LocalDate date) {
        return resultRepository.findByForecastDate(date).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Returns all distinct dates for which aurora forecast results exist.
     *
     * @return sorted list of ISO date strings
     */
    public List<String> getAvailableDates() {
        return resultRepository.findDistinctForecastDates().stream()
                .map(LocalDate::toString)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the dark window (nautical dusk → nautical dawn) for the given date.
     *
     * <p>Nautical twilight (−12°) is approximated by applying a
     * {@value #NAUTICAL_BUFFER_MINUTES}-minute buffer to civil twilight (−6°).
     *
     * @param date the date whose evening dark period to compute
     * @return the dark window for that night
     */
    TonightWindow computeWindowForDate(LocalDate date) {
        ZoneId utc = ZoneId.of("UTC");
        LocalDateTime dusk = solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, date, utc)
                .plusMinutes(NAUTICAL_BUFFER_MINUTES);
        LocalDateTime dawn = solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, date.plusDays(1), utc)
                .minusMinutes(NAUTICAL_BUFFER_MINUTES);
        return new TonightWindow(dusk.atZone(utc), dawn.atZone(utc));
    }

    /**
     * Returns the maximum Kp value from forecast windows that overlap the dark window.
     *
     * @param forecast   NOAA Kp forecast (3-day horizon)
     * @param window     the night's dark period
     * @return max forecast Kp, or 0.0 if no overlapping windows
     */
    double maxKpInWindow(List<KpForecast> forecast, TonightWindow window) {
        return forecast.stream()
                .filter(f -> window.overlaps(f.from(), f.to()))
                .mapToDouble(KpForecast::kp)
                .max()
                .orElse(0.0);
    }

    /**
     * For future nights (T+1, T+2) where real-time weather triage is unavailable,
     * passes all candidates as viable with a 50% cloud estimate.
     *
     * <p>Cloud coverage is accurately known only for the next few hours; for tomorrow and
     * beyond we defer to Claude's judgement based on the Kp forecast.
     *
     * @param candidates Bortle-eligible locations
     * @return triage result with all candidates as viable
     */
    private WeatherTriageService.TriageResult buildFutureNightTriage(
            List<LocationEntity> candidates) {
        Map<LocationEntity, Integer> cloudByLocation = candidates.stream()
                .collect(Collectors.toMap(loc -> loc, loc -> 50));
        return new WeatherTriageService.TriageResult(candidates, List.of(), cloudByLocation);
    }

    /**
     * Maps a NOAA Kp value to the NOAA G-scale label.
     *
     * @param kp the Kp index (0–9)
     * @return G-scale string ("G1"–"G5") or null if below the G1 threshold
     */
    String gScaleFromKp(double kp) {
        if (kp < 5.0) {
            return null;
        }
        if (kp < 6.0) {
            return "G1";
        }
        if (kp < 7.0) {
            return "G2";
        }
        if (kp < 8.0) {
            return "G3";
        }
        if (kp < 9.0) {
            return "G4";
        }
        return "G5";
    }

    /**
     * Builds a human-readable date label for the night selector popup.
     *
     * @param date   the calendar date
     * @param offset days from today (0 = tonight, 1 = tomorrow, 2+  = day name)
     * @return formatted label
     */
    String buildDateLabel(LocalDate date, int offset) {
        String dayName = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.UK);
        String monthName = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.UK);
        String formatted = dayName + " " + date.getDayOfMonth() + " " + monthName;
        return switch (offset) {
            case 0 -> "Tonight — " + formatted;
            case 1 -> "Tomorrow — " + formatted;
            default -> formatted;
        };
    }

    /**
     * Builds the one-line Kp summary shown in the night selector.
     *
     * @param maxKp     highest forecast Kp for the window
     * @param forecast  full Kp forecast list
     * @param window    the night's dark period
     * @param level     derived alert level
     * @return formatted summary
     */
    String buildKpSummary(double maxKp, List<KpForecast> forecast, TonightWindow window,
            AlertLevel level) {
        if (maxKp < 1.0 || level == AlertLevel.QUIET) {
            return String.format("Quiet — Kp %.0f", maxKp);
        }
        if (maxKp < properties.getTriggers().getKpThreshold()) {
            return String.format("Minor activity — Kp %.0f", maxKp);
        }

        // Find the window that contains the peak Kp
        KpForecast peak = forecast.stream()
                .filter(f -> window.overlaps(f.from(), f.to()))
                .max(Comparator.comparingDouble(KpForecast::kp))
                .orElse(null);

        if (peak != null) {
            return String.format("Kp %.0f expected %s–%s",
                    maxKp,
                    TIME_FMT.format(peak.from()),
                    TIME_FMT.format(peak.to()));
        }

        return String.format("Kp %.0f expected tonight", maxKp);
    }

    /**
     * Builds the night summary line from Claude scores and triage outcomes.
     *
     * @param claudeScores list of Claude-scored results
     * @param triagedCount number of triaged (overcast-rejected) locations
     * @return summary string
     */
    private String buildNightSummary(List<AuroraForecastScore> claudeScores, int triagedCount) {
        if (claudeScores.isEmpty()) {
            return triagedCount + " location(s) overcast — no clear skies";
        }
        return claudeScores.stream()
                .max(Comparator.comparingInt(AuroraForecastScore::stars))
                .map(best -> best.location().getName() + " "
                        + "★".repeat(best.stars()) + "☆".repeat(5 - best.stars()))
                .orElse("Conditions assessed");
    }

    /**
     * Converts an entity to a DTO for the map view API response.
     *
     * @param entity the stored aurora result
     * @return the DTO with all location fields inlined
     */
    private AuroraForecastResultDto toDto(AuroraForecastResultEntity entity) {
        LocationEntity loc = entity.getLocation();
        return new AuroraForecastResultDto(
                loc.getId(),
                loc.getName(),
                loc.getLat(),
                loc.getLon(),
                loc.getBortleClass(),
                entity.getStars(),
                entity.getSummary(),
                entity.getFactors(),
                entity.isTriaged(),
                entity.getTriageReason(),
                entity.getAlertLevel(),
                entity.getMaxKp() != null ? entity.getMaxKp() : 0.0);
    }
}
