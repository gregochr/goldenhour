package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches sea-state for coastal locations during the briefing cycle and UPSERTs it into
 * {@code marine_wave} (V123) — the shared carrier the coastal hot-topic pills read.
 *
 * <p>Coastal locations are fetched 20 exact coordinates per Open-Meteo Marine request; each response
 * is a 7-day hourly series per location; from it the significant wave height is sampled at each day's
 * sunrise and sunset (the shootable moments), keyed {@code (location, date, event)} to match the slot
 * surface. The band the pills
 * show is derived from the stored Hs at render time, never persisted. HTTP happens outside any
 * database transaction; the existing rows are read in one query and the upserts saved together. A failed
 * chunk persists nothing for its locations. Fetch failures and land-cell (no-wave) grid
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

    private final MarineClient marineClient;
    private final MarineWaveRepository marineWaveRepository;
    private final SolarService solarService;
    private final TideExtremeRepository tideExtremeRepository;
    private final MarineHighWaterWriter highWaterWriter;
    private final JobRunService jobRunService;
    private final Clock clock;
    private final BigSeasProperties bigSeasProperties;

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
     * @param bigSeasProperties    supplies the high-water half-window
     */
    public MarineWaveRefreshService(MarineClient marineClient,
            MarineWaveRepository marineWaveRepository, SolarService solarService,
            TideExtremeRepository tideExtremeRepository, MarineHighWaterWriter highWaterWriter,
            JobRunService jobRunService, Clock clock,
            BigSeasProperties bigSeasProperties) {
        this.marineClient = marineClient;
        this.marineWaveRepository = marineWaveRepository;
        this.solarService = solarService;
        this.tideExtremeRepository = tideExtremeRepository;
        this.highWaterWriter = highWaterWriter;
        this.jobRunService = jobRunService;
        this.clock = clock;
        this.bigSeasProperties = bigSeasProperties;
    }

    /**
     * Refreshes the sea-state for the coastal subset of the given colour locations, reading high
     * waters live. Equivalent to {@link #refresh(List, List, Long, TideExtremeSnapshot)} with no
     * snapshot.
     *
     * @param colourLocations the briefing's colour locations (coastal ones are selected here)
     * @param dates           the briefing date window
     * @param jobRunId        the current briefing job run, for API-call logging
     */
    public void refresh(List<LocationEntity> colourLocations, List<LocalDate> dates, Long jobRunId) {
        refresh(colourLocations, dates, jobRunId, null);
    }

    /**
     * Refreshes {@code marine_wave} for the coastal subset of the given colour locations across the
     * date window, and {@code marine_high_water} for those marked as a coastal structure. Inland
     * locations (no tide preference) are skipped.
     *
     * <p>Coastal locations are fetched {@value MarineClient#BATCH_COORD_LIMIT} exact coordinates per
     * request. A failed request persists nothing for its locations, but their high-water refresh
     * still runs (and clears their stale rows). The existing {@code marine_wave} rows are read in
     * one query and the upserts are saved together.
     *
     * @param colourLocations the briefing's colour locations (coastal ones are selected here)
     * @param dates           the briefing date window
     * @param jobRunId        the current briefing job run, for API-call logging
     * @param tideSnapshot    the build's tide snapshot to read high waters from, or null to query live
     */
    public void refresh(List<LocationEntity> colourLocations, List<LocalDate> dates, Long jobRunId,
            TideExtremeSnapshot tideSnapshot) {
        List<LocationEntity> coastal = colourLocations.stream()
                .filter(l -> l.getTideType() != null && !l.getTideType().isEmpty())
                .toList();
        if (coastal.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        List<OpenMeteoMarineResponse> responses = fetchAll(coastal, jobRunId);
        int persisted = persistEvents(coastal, responses, dates, now);
        for (int i = 0; i < coastal.size(); i++) {
            LocationEntity loc = coastal.get(i);
            if (loc.getCoastalStructure() != null) {
                refreshHighWaters(loc, responses.get(i), dates, now, tideSnapshot);
            }
        }
        LOG.info("Marine wave refresh: {} coastal locations, {} samples persisted",
                coastal.size(), persisted);
    }

    /**
     * Fetches every coastal location in chunks. The result is aligned with {@code coastal}; a
     * location whose chunk failed (or whose element was absent) is null.
     */
    private List<OpenMeteoMarineResponse> fetchAll(List<LocationEntity> coastal, Long jobRunId) {
        List<OpenMeteoMarineResponse> responses = new ArrayList<>(Collections.nCopies(coastal.size(), null));
        int limit = MarineClient.BATCH_COORD_LIMIT;
        int totalChunks = (coastal.size() + limit - 1) / limit;
        for (int from = 0; from < coastal.size(); from += limit) {
            int to = Math.min(from + limit, coastal.size());
            List<LocationEntity> chunk = coastal.subList(from, to);
            List<OpenMeteoMarineResponse> fetched = fetchChunk(chunk, from / limit + 1, totalChunks, jobRunId);
            if (fetched != null) {
                for (int j = 0; j < fetched.size(); j++) {
                    responses.set(from + j, fetched.get(j));
                }
            }
        }
        return responses;
    }

    private List<OpenMeteoMarineResponse> fetchChunk(List<LocationEntity> chunk, int chunkNumber,
            int totalChunks, Long jobRunId) {
        long startMs = System.currentTimeMillis();
        String label = "briefing-marine(batch " + chunkNumber + "/" + totalChunks + ", "
                + chunk.size() + " locations)";
        try {
            List<OpenMeteoMarineResponse> fetched = marineClient.fetchMarineBatch(chunk.stream()
                    .map(l -> new MarineClient.Point(l.getLat(), l.getLon()))
                    .toList());
            jobRunService.logApiCall(jobRunId, ServiceName.OPEN_METEO_MARINE, "GET", label, null,
                    System.currentTimeMillis() - startMs, 200, null, true, null);
            return fetched;
        } catch (Exception e) {
            jobRunService.logApiCall(jobRunId, ServiceName.OPEN_METEO_MARINE, "GET", label, null,
                    System.currentTimeMillis() - startMs, null, null, false, e.getMessage());
            LOG.warn("Marine fetch failed for chunk {}/{} ({} locations): {}",
                    chunkNumber, totalChunks, chunk.size(), e.getMessage());
            return null;
        }
    }

    /** Key of a {@code marine_wave} row: the table's unique constraint. */
    private record WaveKey(Long locationId, LocalDate date, TargetType event) { }

    /**
     * The sunrise/sunset {@code marine_wave} upserts for every location with a response: one read
     * of the existing rows, then one {@code saveAll}. A sampling failure for one location is logged
     * and keeps that location's earlier rows; it neither skips the high-water refresh nor stops the
     * next location. A failed save is logged so the high-water refresh still runs.
     */
    private int persistEvents(List<LocationEntity> coastal, List<OpenMeteoMarineResponse> responses,
            List<LocalDate> dates, Instant now) {
        try {
            return persistEventsUnguarded(coastal, responses, dates, now);
        } catch (RuntimeException e) {
            LOG.warn("Marine event-grain persistence failed, high waters still refresh: {}", e.getMessage());
            return 0;
        }
    }

    private int persistEventsUnguarded(List<LocationEntity> coastal, List<OpenMeteoMarineResponse> responses,
            List<LocalDate> dates, Instant now) {
        List<Long> fetchedIds = new ArrayList<>();
        for (int i = 0; i < coastal.size(); i++) {
            if (responses.get(i) != null) {
                fetchedIds.add(coastal.get(i).getId());
            }
        }
        if (fetchedIds.isEmpty() || dates.isEmpty()) {
            return 0;
        }
        Map<WaveKey, MarineWaveEntity> existing = new HashMap<>();
        for (MarineWaveEntity row : marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(fetchedIds, dates)) {
            existing.put(new WaveKey(row.getLocation().getId(), row.getEvaluationDate(), row.getEventType()), row);
        }
        List<MarineWaveEntity> rows = new ArrayList<>();
        for (int i = 0; i < coastal.size(); i++) {
            if (responses.get(i) != null) {
                collectEvents(coastal.get(i), responses.get(i), dates, now, existing, rows);
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        marineWaveRepository.saveAll(rows);
        return rows.size();
    }

    private void collectEvents(LocationEntity loc, OpenMeteoMarineResponse response, List<LocalDate> dates,
            Instant now, Map<WaveKey, MarineWaveEntity> existing, List<MarineWaveEntity> out) {
        try {
            for (LocalDate date : dates) {
                collect(loc, date, TargetType.SUNRISE,
                        solarService.sunriseUtc(loc.getLat(), loc.getLon(), date), response, now, existing, out);
                collect(loc, date, TargetType.SUNSET,
                        solarService.sunsetUtc(loc.getLat(), loc.getLon(), date), response, now, existing, out);
            }
        } catch (RuntimeException e) {
            LOG.warn("Marine event-grain refresh failed for {}: {}", loc.getName(), e.getMessage());
        }
    }

    private void collect(LocationEntity loc, LocalDate date, TargetType event, LocalDateTime eventTimeUtc,
            OpenMeteoMarineResponse response, Instant now, Map<WaveKey, MarineWaveEntity> existing,
            List<MarineWaveEntity> out) {
        Optional<MarineWaveSample> sampleMaybe = marineClient.sampleAt(response, eventTimeUtc);
        if (sampleMaybe.isEmpty()) {
            return;
        }
        MarineWaveSample sample = sampleMaybe.get();
        MarineWaveEntity row = existing.getOrDefault(new WaveKey(loc.getId(), date, event), new MarineWaveEntity());
        row.setLocation(loc);
        row.setEvaluationDate(date);
        row.setEventType(event);
        row.setSignificantWaveHeightMetres(sample.significantWaveHeightMetres());
        row.setSwellWaveHeightMetres(sample.swellWaveHeightMetres());
        row.setWaveDirectionDegrees(sample.waveDirectionDegrees());
        row.setEvaluatedAt(now);
        out.add(row);
    }

    /**
     * Rebuilds a structure location's daylight high-water rows. A null response (failed fetch) or a
     * land cell yields no rows, but the writer still runs so stale rows are cleared, never kept.
     * Never fatal: a failure here is logged and the next location carries on.
     */
    private void refreshHighWaters(LocationEntity loc, OpenMeteoMarineResponse response,
            List<LocalDate> dates, Instant now, TideExtremeSnapshot tideSnapshot) {
        if (dates.isEmpty()) {
            return;
        }
        LocalDateTime from = ForecastHorizon.ukDayStartUtc(Collections.min(dates));
        LocalDateTime to = ForecastHorizon.ukDayStartUtc(Collections.max(dates).plusDays(1));
        try {
            List<MarineHighWaterEntity> rows = new ArrayList<>();
            if (response != null) {
                for (TideHighWater hw : highWaters(loc, from, to, tideSnapshot)) {
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

    /**
     * The location's high waters in {@code [from, to]}, ascending: from the build's snapshot when one
     * was passed (which itself falls back to a live read for a location or range it did not load),
     * else the live query.
     */
    private List<TideHighWater> highWaters(LocationEntity loc, LocalDateTime from, LocalDateTime to,
            TideExtremeSnapshot tideSnapshot) {
        if (tideSnapshot == null) {
            return tideExtremeRepository.findHighWatersInWindow(List.of(loc.getId()), from, to);
        }
        return tideSnapshot.between(tideExtremeRepository, loc.getId(), from, to).stream()
                .filter(t -> t.getType() == TideExtremeType.HIGH)
                .map(t -> new TideHighWater(t.getLocationId(), t.getEventTime(), t.getHeightMetres()))
                .toList();
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
        LocalDateTime windowStart = later(hwTime.minusMinutes(bigSeasProperties.getHalfWindowMinutes()),
                solarService.civilDawnUtc(loc.getLat(), loc.getLon(), date));
        LocalDateTime windowEnd = earlier(hwTime.plusMinutes(bigSeasProperties.getHalfWindowMinutes()),
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
}
