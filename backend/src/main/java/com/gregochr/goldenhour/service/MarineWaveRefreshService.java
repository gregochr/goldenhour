package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fetches sea-state for coastal locations during the briefing cycle and UPSERTs it into
 * {@code marine_wave} (V123) — the shared carrier the coastal hot-topic pills read.
 *
 * <p>One Open-Meteo Marine call per coastal location (a small set) returns a 7-day hourly series;
 * from it the significant wave height is sampled at each day's sunrise and sunset (the shootable
 * moments), keyed {@code (location, date, event)} to match the survivor surface. The band the pills
 * show is derived from the stored Hs at render time, never persisted. HTTP happens outside any
 * database transaction; each row is saved on its own. Fetch failures and land-cell (no-wave) grid
 * points are logged and skipped, never fatal to the briefing.
 */
@Service
public class MarineWaveRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(MarineWaveRefreshService.class);

    private final MarineClient marineClient;
    private final MarineWaveRepository marineWaveRepository;
    private final SolarService solarService;
    private final JobRunService jobRunService;
    private final Clock clock;

    /**
     * Constructs a {@code MarineWaveRefreshService}.
     *
     * @param marineClient         the resilient Open-Meteo Marine wrapper + sea-state sampler
     * @param marineWaveRepository the shared sea-state carrier (V123)
     * @param solarService         resolves sunrise/sunset UTC times to sample at
     * @param jobRunService        logs the marine API calls for metrics/cost
     * @param clock                injectable clock for {@code evaluated_at = now()}
     */
    public MarineWaveRefreshService(MarineClient marineClient,
            MarineWaveRepository marineWaveRepository, SolarService solarService,
            JobRunService jobRunService, Clock clock) {
        this.marineClient = marineClient;
        this.marineWaveRepository = marineWaveRepository;
        this.solarService = solarService;
        this.jobRunService = jobRunService;
        this.clock = clock;
    }

    /**
     * Refreshes {@code marine_wave} for the coastal subset of the given colour locations across the
     * date window. Inland locations (no tide preference) are skipped.
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
            if (response == null) {
                continue;
            }
            for (LocalDate date : dates) {
                persisted += upsert(loc, date, TargetType.SUNRISE,
                        solarService.sunriseUtc(loc.getLat(), loc.getLon(), date), response, now);
                persisted += upsert(loc, date, TargetType.SUNSET,
                        solarService.sunsetUtc(loc.getLat(), loc.getLon(), date), response, now);
            }
        }
        LOG.info("Marine wave refresh: {} coastal locations, {} samples persisted",
                coastal.size(), persisted);
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
