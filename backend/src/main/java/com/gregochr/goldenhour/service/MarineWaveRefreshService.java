package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.MarineWavePeak;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideHighWater;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches sea-state for coastal locations during the briefing cycle and UPSERTs it into
 * {@code marine_wave} (V123) — the shared carrier the coastal hot-topic pills read.
 *
 * <p>One Open-Meteo Marine call per coastal location (a small set) returns a 7-day hourly series;
 * from it the significant wave height is sampled at each day's sunrise and sunset (the shootable
 * moments), keyed {@code (location, date, event)} to match the slot surface. The band the pills
 * show is derived from the stored Hs at render time, never persisted. HTTP happens outside any
 * database transaction; each row is saved on its own. Fetch failures and land-cell (no-wave) grid
 * points are logged and skipped, never fatal to the briefing.
 *
 * <p>For locations marked as a coastal structure (V188) the same fetched response is also sampled at
 * every daylight high water into {@code marine_high_water} (big seas). That is a separate grain
 * from the sunrise/sunset rows, which are unchanged. A failed or land-cell fetch clears the
 * location's high-water rows rather than leaving last cycle's waves to be served as this cycle's.
 *
 * <p>Note on the V123 wording: {@code marine_wave} is sampled at sunrise and sunset, not "at the
 * aligned high-water time" as V123's comments say; see V188's header.
 */
@Service
public class MarineWaveRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(MarineWaveRefreshService.class);

    /** Half-width of the window sampled around a high water (B2 moves this onto configuration). */
    private static final long HALF_WINDOW_MINUTES = 90;

    private final MarineClient marineClient;
    private final MarineWaveRepository marineWaveRepository;
    private final SolarService solarService;
    private final TideExtremeRepository tideExtremeRepository;
    private final MarineHighWaterWriter highWaterWriter;
    private final JobRunService jobRunService;
    private final Clock clock;

    /**
     * Constructs a {@code MarineWaveRefreshService}.
     *
     * @param marineClient          the resilient Open-Meteo Marine wrapper + sea-state sampler
     * @param marineWaveRepository  the shared sea-state carrier (V123)
     * @param solarService          resolves sunrise/sunset UTC times to sample at
     * @param tideExtremeRepository stored tide extremes, read for the daylight high waters
     * @param highWaterWriter       replaces a structure location's high-water rows in one transaction
     * @param jobRunService         logs the marine API calls for metrics/cost
     * @param clock                 injectable clock for {@code evaluated_at = now()}
     */
    public MarineWaveRefreshService(MarineClient marineClient,
            MarineWaveRepository marineWaveRepository, SolarService solarService,
            TideExtremeRepository tideExtremeRepository, MarineHighWaterWriter highWaterWriter,
            JobRunService jobRunService, Clock clock) {
        this.marineClient = marineClient;
        this.marineWaveRepository = marineWaveRepository;
        this.solarService = solarService;
        this.tideExtremeRepository = tideExtremeRepository;
        this.highWaterWriter = highWaterWriter;
        this.jobRunService = jobRunService;
        this.clock = clock;
    }

    /**
     * Refreshes {@code marine_wave} for the coastal subset of the given colour locations across the
     * date window, and {@code marine_high_water} for those marked as a coastal structure. Inland
     * locations (no tide preference) are skipped.
     *
     * @param colourLocations the briefing's colour locations (coastal ones are selected here)
     * @param dates           the briefing date window
     * @param jobRunId        the current briefing job run, for API-call logging
     */
    public void refresh(List<LocationEntity> colourLocations, List<LocalDate> dates, Long jobRunId) {
        List<LocationEntity> coastal = colourLocations.stream()
                .filter(l -> l.getTideType() != null && !l.getTideType().isEmpty())
                .toList();
        if (coastal.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        int persisted = 0;
        for (LocationEntity loc : coastal) {
            OpenMeteoMarineResponse response = fetch(loc, jobRunId);
            if (response != null) {
                persisted += refreshEvents(loc, response, dates, now);
            }
            if (loc.getCoastalStructure() != null) {
                refreshHighWaters(loc, response, dates, now);
            }
        }
        LOG.info("Marine wave refresh: {} coastal locations, {} samples persisted",
                coastal.size(), persisted);
    }

    /**
     * The sunrise/sunset {@code marine_wave} upserts for one location. Isolated so a failure here is
     * logged and neither skips the location's high-water refresh nor stops the next location.
     */
    private int refreshEvents(LocationEntity loc, OpenMeteoMarineResponse response,
            List<LocalDate> dates, Instant now) {
        int persisted = 0;
        try {
            for (LocalDate date : dates) {
                persisted += upsert(loc, date, TargetType.SUNRISE,
                        solarService.sunriseUtc(loc.getLat(), loc.getLon(), date), response, now);
                persisted += upsert(loc, date, TargetType.SUNSET,
                        solarService.sunsetUtc(loc.getLat(), loc.getLon(), date), response, now);
            }
        } catch (RuntimeException e) {
            LOG.warn("Marine event-grain refresh failed for {}: {}", loc.getName(), e.getMessage());
        }
        return persisted;
    }

    /**
     * Rebuilds a structure location's daylight high-water rows. A null response (failed fetch) or a
     * land cell yields no rows, but the writer still runs so stale rows are cleared, never kept.
     * Never fatal: a failure here is logged and the next location carries on.
     */
    private void refreshHighWaters(LocationEntity loc, OpenMeteoMarineResponse response,
            List<LocalDate> dates, Instant now) {
        if (dates.isEmpty()) {
            return;
        }
        LocalDateTime from = ForecastHorizon.ukDayStartUtc(Collections.min(dates));
        LocalDateTime to = ForecastHorizon.ukDayStartUtc(Collections.max(dates).plusDays(1));
        try {
            List<MarineHighWaterEntity> rows = new ArrayList<>();
            if (response != null) {
                for (TideHighWater hw : tideExtremeRepository.findHighWatersInWindow(
                        List.of(loc.getId()), from, to)) {
                    highWaterRow(loc, response, dates, hw, now).ifPresent(rows::add);
                }
            }
            highWaterWriter.replace(loc.getId(), from, to, rows);
            LOG.info("Big-seas samples: {} {} daylight HWs", loc.getName(), rows.size());
        } catch (RuntimeException e) {
            LOG.warn("Big-seas high-water refresh failed for {}: {}", loc.getName(), e.getMessage());
            clearAfterFailure(loc, from, to);
        }
    }

    /** Best effort: a failed cycle must never leave last cycle's rows to be served as this cycle's. */
    private void clearAfterFailure(LocationEntity loc, LocalDateTime from, LocalDateTime to) {
        try {
            highWaterWriter.replace(loc.getId(), from, to, List.of());
        } catch (RuntimeException e) {
            LOG.warn("Big-seas high-water clear also failed for {}: {}", loc.getName(), e.getMessage());
        }
    }

    private Optional<MarineHighWaterEntity> highWaterRow(LocationEntity loc, OpenMeteoMarineResponse response,
            List<LocalDate> dates, TideHighWater hw, Instant now) {
        LocalDateTime hwTime = hw.eventTime();
        LocalDate date = ForecastHorizon.civilDate(hwTime.toInstant(ZoneOffset.UTC));
        if (!dates.contains(date)) {
            return Optional.empty();
        }
        LocalDateTime windowStart = later(hwTime.minusMinutes(HALF_WINDOW_MINUTES),
                solarService.civilDawnUtc(loc.getLat(), loc.getLon(), date));
        LocalDateTime windowEnd = earlier(hwTime.plusMinutes(HALF_WINDOW_MINUTES),
                solarService.civilDuskUtc(loc.getLat(), loc.getLon(), date));
        if (!windowStart.isBefore(windowEnd)) {
            return Optional.empty();
        }
        Optional<MarineWavePeak> peakMaybe = marineClient.peakInWindow(response, windowStart, windowEnd);
        if (peakMaybe.isEmpty()) {
            return Optional.empty();
        }
        MarineWavePeak peak = peakMaybe.get();
        return Optional.of(MarineHighWaterEntity.builder()
                .location(loc)
                .hwTime(hwTime)
                .hwHeightMetres(hw.heightMetres())
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .peakTime(peak.peakTime())
                .waveHeightMetres(peak.waveHeight())
                .wavePeriodSeconds(peak.period())
                .swellWaveHeightMetres(peak.swellHeight())
                .swellWavePeriodSeconds(peak.swellPeriod())
                .waveDirectionDegrees(peak.direction())
                .evaluatedAt(now)
                .build());
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private OpenMeteoMarineResponse fetch(LocationEntity loc, Long jobRunId) {
        long startMs = System.currentTimeMillis();
        try {
            OpenMeteoMarineResponse response = marineClient.fetchMarine(loc.getLat(), loc.getLon());
            jobRunService.logApiCall(jobRunId, ServiceName.OPEN_METEO_MARINE, "GET",
                    "briefing-marine(" + loc.getName() + ")", null,
                    System.currentTimeMillis() - startMs, 200, null, true, null);
            return response;
        } catch (Exception e) {
            jobRunService.logApiCall(jobRunId, ServiceName.OPEN_METEO_MARINE, "GET",
                    "briefing-marine(" + loc.getName() + ")", null,
                    System.currentTimeMillis() - startMs, null, null, false, e.getMessage());
            LOG.warn("Marine fetch failed for {}: {}", loc.getName(), e.getMessage());
            return null;
        }
    }

    private int upsert(LocationEntity loc, LocalDate date, TargetType event,
            LocalDateTime eventTimeUtc, OpenMeteoMarineResponse response, Instant now) {
        Optional<MarineWaveSample> sampleMaybe = marineClient.sampleAt(response, eventTimeUtc);
        if (sampleMaybe.isEmpty()) {
            return 0;
        }
        MarineWaveSample sample = sampleMaybe.get();
        MarineWaveEntity row = marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(loc.getId(), date, event)
                .orElseGet(MarineWaveEntity::new);
        row.setLocation(loc);
        row.setEvaluationDate(date);
        row.setEventType(event);
        row.setSignificantWaveHeightMetres(sample.significantWaveHeightMetres());
        row.setSwellWaveHeightMetres(sample.swellWaveHeightMetres());
        row.setWaveDirectionDegrees(sample.waveDirectionDegrees());
        row.setEvaluatedAt(now);
        marineWaveRepository.save(row);
        return 1;
    }
}
