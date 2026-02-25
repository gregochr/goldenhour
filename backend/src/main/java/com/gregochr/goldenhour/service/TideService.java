package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manages tide extremes for coastal locations using the WorldTides API.
 *
 * <p>On a weekly schedule, fetches 14 days of high and low tide times from WorldTides
 * and stores them in the {@code tide_extreme} table. At forecast evaluation time,
 * {@link #deriveTideData} looks up the stored extremes to classify the tide state
 * and find the next high and low tides — without calling the external API on every run.
 *
 * <p>HIGH: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a stored HIGH extreme.
 * LOW: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a stored LOW extreme.
 * MID: everything else — tide is neither fully in nor fully out.
 */
@Service
public class TideService {

    private static final Logger LOG = LoggerFactory.getLogger(TideService.class);

    private static final String WORLDTIDES_HOST = "www.worldtides.info";

    /** Seconds in 14 days: the WorldTides fetch window per location per weekly refresh. */
    private static final long FETCH_LENGTH_SECONDS = 14L * 24 * 3600;

    /** Days either side of the solar event time to query from the DB. */
    private static final long QUERY_WINDOW_DAYS = 2;

    /**
     * Minutes within which the solar event is classified as HIGH or LOW tide,
     * and within which the midpoint between consecutive extremes is considered mid-tide.
     */
    private static final long HIGH_LOW_THRESHOLD_MINUTES = 45;

    /** Decimal precision for stored tide heights. */
    private static final int HEIGHT_SCALE = 3;

    private final WebClient webClient;
    private final TideExtremeRepository tideExtremeRepository;
    private final WorldTidesProperties worldTidesProperties;

    /**
     * Constructs a {@code TideService}.
     *
     * @param webClient              shared WebClient for outbound HTTP calls
     * @param tideExtremeRepository  repository for persisted tide extremes
     * @param worldTidesProperties   WorldTides API configuration
     */
    public TideService(WebClient webClient, TideExtremeRepository tideExtremeRepository,
            WorldTidesProperties worldTidesProperties) {
        this.webClient = webClient;
        this.tideExtremeRepository = tideExtremeRepository;
        this.worldTidesProperties = worldTidesProperties;
    }

    /**
     * Fetches 14 days of tide extremes from WorldTides for a coastal location and replaces
     * any existing rows for that location in the {@code tide_extreme} table.
     *
     * <p>If the WorldTides API key is not configured, or if the API call fails or returns
     * a non-200 status, no rows are deleted or written — the existing data is preserved.
     *
     * @param location the coastal location to fetch tide data for
     */
    @Transactional
    public void fetchAndStoreTideExtremes(LocationEntity location) {
        String apiKey = worldTidesProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("WorldTides API key not configured — skipping tide fetch for {}",
                    location.getName());
            return;
        }

        LocalDateTime startOfDay = LocalDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay();
        long startEpoch = startOfDay.toEpochSecond(ZoneOffset.UTC);

        try {
            WorldTidesResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host(WORLDTIDES_HOST).path("/api/v3")
                            .queryParam("extremes")
                            .queryParam("lat", location.getLat())
                            .queryParam("lon", location.getLon())
                            .queryParam("start", startEpoch)
                            .queryParam("length", FETCH_LENGTH_SECONDS)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(WorldTidesResponse.class)
                    .block();

            if (response == null || response.getStatus() != 200
                    || response.getExtremes() == null) {
                LOG.warn("WorldTides returned no usable data for {} (status={})",
                        location.getName(), response != null ? response.getStatus() : "null");
                return;
            }

            LocalDateTime fetchedAt = LocalDateTime.now(ZoneOffset.UTC);
            List<TideExtremeEntity> entities = response.getExtremes().stream()
                    .filter(e -> e.getType() != null
                            && ("High".equalsIgnoreCase(e.getType())
                                    || "Low".equalsIgnoreCase(e.getType())))
                    .map(e -> TideExtremeEntity.builder()
                            .locationId(location.getId())
                            .eventTime(Instant.ofEpochSecond(e.getDt())
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDateTime())
                            .heightMetres(BigDecimal.valueOf(e.getHeight())
                                    .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP))
                            .type("High".equalsIgnoreCase(e.getType())
                                    ? TideExtremeType.HIGH : TideExtremeType.LOW)
                            .fetchedAt(fetchedAt)
                            .build())
                    .toList();

            tideExtremeRepository.deleteByLocationId(location.getId());
            tideExtremeRepository.saveAll(entities);
            LOG.info("Stored {} tide extremes for {} (T+0 to T+13)",
                    entities.size(), location.getName());

        } catch (Exception e) {
            LOG.warn("Failed to fetch tide extremes for {}: {}", location.getName(), e.getMessage());
        }
    }

    /**
     * Returns {@code true} if any tide extremes are stored for the given location.
     *
     * <p>Called at startup to decide whether a tide fetch is needed — avoids redundant
     * API calls when the {@code tide_extreme} table already has data for this location.
     *
     * @param locationId the location primary key
     * @return {@code true} if the {@code tide_extreme} table has at least one row for this location
     */
    public boolean hasStoredExtremes(Long locationId) {
        return tideExtremeRepository.existsByLocationId(locationId);
    }

    /**
     * Returns all tide extremes for a location on the given UTC calendar day, ordered chronologically.
     *
     * <p>Used by {@code TideController} to serve the daily tide schedule for a specific date.
     * The query window is midnight-to-midnight UTC, so extremes that straddle midnight
     * (e.g. a low tide at 01:15) appear on the day they actually occur.
     *
     * @param locationId the location primary key
     * @param date       the UTC calendar day to query
     * @return tide extremes for that day in chronological order; empty list if none stored
     */
    public List<TideExtremeEntity> getTidesForDate(Long locationId, java.time.LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay().minusNanos(1);
        return tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                locationId, from, to);
    }

    /**
     * Derives tide data for a coastal location at a solar event time using stored extremes.
     *
     * <p>Queries the {@code tide_extreme} table for extremes within
     * {@value #QUERY_WINDOW_DAYS} days either side of the event time.
     * Returns empty if no extremes are stored (e.g. weekly refresh not yet run).
     *
     * @param locationId the location primary key
     * @param eventTime  UTC time of sunrise or sunset
     * @return Optional containing TideData if extremes are available, empty otherwise
     */
    public Optional<TideData> deriveTideData(Long locationId, LocalDateTime eventTime) {
        List<TideExtremeEntity> extremes = tideExtremeRepository
                .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                        locationId,
                        eventTime.minusDays(QUERY_WINDOW_DAYS),
                        eventTime.plusDays(QUERY_WINDOW_DAYS));

        if (extremes.isEmpty()) {
            LOG.warn("No tide extremes in DB for locationId={} around {}", locationId, eventTime);
            return Optional.empty();
        }

        return Optional.of(buildTideData(extremes, eventTime));
    }

    /**
     * Computes whether the tide state aligns with the location's photographer preference.
     *
     * <p>For {@link TideType#MID_TIDE}, alignment requires the solar event to be within
     * {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of the midpoint between consecutive
     * HIGH and LOW extremes — not merely "not HIGH and not LOW".
     *
     * @param tideData          the tide data snapshot at the solar event time
     * @param locationTideTypes the location's acceptable tide states
     * @return true if the tide state matches any of the location's preferences
     */
    public boolean calculateTideAligned(TideData tideData, Set<TideType> locationTideTypes) {
        if (locationTideTypes.isEmpty()) {
            return false;
        }
        if (locationTideTypes.contains(TideType.ANY_TIDE)) {
            return true;
        }
        TideState tideState = tideData.tideState();
        return locationTideTypes.stream().anyMatch(pref -> switch (pref) {
            case HIGH_TIDE -> tideState == TideState.HIGH;
            case LOW_TIDE -> tideState == TideState.LOW;
            case MID_TIDE -> tideData.nearMidPoint();
            case ANY_TIDE -> true;
            case NOT_COASTAL -> false;
        });
    }

    /**
     * Builds a {@link TideData} snapshot from a list of stored tide extremes at an event time.
     *
     * <p>Package-private for unit testing.
     *
     * @param extremes  stored tide extremes in chronological order
     * @param eventTime UTC time of the solar event
     * @return tide data snapshot including state, mid-point proximity, and next high/low events
     */
    TideData buildTideData(List<TideExtremeEntity> extremes, LocalDateTime eventTime) {
        TideState state = classifyTideState(extremes, eventTime);
        boolean nearMid = isMidPointAligned(extremes, eventTime);

        TideExtremeEntity nextHigh = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.HIGH
                        && e.getEventTime().isAfter(eventTime))
                .findFirst()
                .orElse(null);

        TideExtremeEntity nextLow = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.LOW
                        && e.getEventTime().isAfter(eventTime))
                .findFirst()
                .orElse(null);

        return new TideData(
                state,
                nearMid,
                nextHigh != null ? nextHigh.getEventTime() : null,
                nextHigh != null ? nextHigh.getHeightMetres() : null,
                nextLow != null ? nextLow.getEventTime() : null,
                nextLow != null ? nextLow.getHeightMetres() : null);
    }

    /**
     * Classifies the tide state at {@code eventTime} given a list of stored extremes.
     *
     * <p>Package-private for unit testing.
     *
     * @param extremes  stored tide extremes around the event time
     * @param eventTime UTC time of the solar event
     * @return HIGH, LOW, or MID
     */
    TideState classifyTideState(List<TideExtremeEntity> extremes, LocalDateTime eventTime) {
        boolean nearHigh = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.HIGH)
                .anyMatch(e -> Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))
                        <= HIGH_LOW_THRESHOLD_MINUTES);
        if (nearHigh) {
            return TideState.HIGH;
        }

        boolean nearLow = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.LOW)
                .anyMatch(e -> Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))
                        <= HIGH_LOW_THRESHOLD_MINUTES);
        if (nearLow) {
            return TideState.LOW;
        }

        return TideState.MID;
    }

    /**
     * Returns {@code true} when the solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES}
     * minutes of the midpoint between any consecutive pair of tide extremes.
     *
     * <p>Consecutive pairs are taken from the chronologically sorted {@code extremes} list.
     * In practice extremes alternate HIGH-LOW-HIGH-LOW so each pair spans one tidal phase.
     *
     * @param extremes  stored tide extremes in chronological order
     * @param eventTime UTC time of the solar event
     * @return {@code true} if the event falls in a precise mid-tide window
     */
    private boolean isMidPointAligned(List<TideExtremeEntity> extremes, LocalDateTime eventTime) {
        for (int i = 0; i < extremes.size() - 1; i++) {
            LocalDateTime t1 = extremes.get(i).getEventTime();
            LocalDateTime t2 = extremes.get(i + 1).getEventTime();
            long halfSeconds = ChronoUnit.SECONDS.between(t1, t2) / 2;
            LocalDateTime midpoint = t1.plusSeconds(halfSeconds);
            if (Math.abs(ChronoUnit.MINUTES.between(midpoint, eventTime)) <= HIGH_LOW_THRESHOLD_MINUTES) {
                return true;
            }
        }
        return false;
    }
}
