package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Fetches tide extremes from the WorldTides vendor API and merges them into the
 * {@code tide_extreme} table.
 *
 * <p>This is the vendor-facing half of what used to be a single {@code TideService}: the HTTP
 * calls, API key handling, billed-call metrics, the windowed merge that preserves historical
 * data, the post-merge integrity check, and the 12-month backfill. {@link TideService} is the
 * façade every existing caller still uses — it delegates these methods here unchanged. See
 * {@code docs/engineering} (or the PR that introduced this split) for why: a WorldTides API
 * change has no business touching tide-classification tests, and a threshold change has no
 * business reopening vendor request/backfill code.
 *
 * <p>Reading stored extremes back out and classifying tide state at a solar event time —
 * {@code deriveTideData}, {@code calculateTideAligned}, {@code getTideStats} and friends —
 * stays on {@link TideService}. That is our own data, not the vendor's.
 */
@Service
public class WorldTidesIngestionService {

    /**
     * Logger category is deliberately {@link TideService}, not this class — several
     * {@code TideServiceTest} assertions attach a {@code ListAppender} to
     * {@code LoggerFactory.getLogger(TideService.class)} and inspect the WARN lines this class
     * emits. Log category is observable behaviour like anything else this split promises not to
     * change; giving it its own category is a follow-up, not part of a pure move.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TideService.class);

    private static final String WORLDTIDES_HOST = "www.worldtides.info";

    /**
     * How far ahead the "Coming up" almanac feed may state a tide height, range, clock time
     * or solar-alignment verdict. Beyond this the feed states the date and the run position
     * and nothing numeric — it never synthesises a figure.
     */
    private static final int ALMANAC_HORIZON_DAYS = 90;

    /**
     * Slack on top of {@link #ALMANAC_HORIZON_DAYS}, sized to the weekly refresh cadence so
     * coverage still reaches the full horizon on the last day before the next refresh. The
     * window is fetched on a Monday and decays a day at a time; without this the feed would
     * be short by six days every Sunday.
     */
    private static final int REFRESH_SLACK_DAYS = 7;

    /**
     * The WorldTides fetch window per location per weekly refresh, in seconds.
     *
     * <p>Derived from the two constants above rather than written as a literal, so the number
     * cannot drift from the reason it has that value.
     *
     * <p>⚠️ WorldTides bills {@code extremes} at one credit per seven days of data, not one
     * per request — so this constant sets the recurring cost, at roughly
     * {@code ceil(days / 7)} credits per coastal location per week. It does <em>not</em> set
     * the spring or king tide threshold: those are a climatology over past extremes only, and
     * {@code TideExtremeRepository.findHeightStatsByLocationIdAndTypeBefore} documents why
     * that separation is deliberate. Changing this value must not move a tide badge.
     */
    private static final long FETCH_LENGTH_SECONDS =
            (long) (ALMANAC_HORIZON_DAYS + REFRESH_SLACK_DAYS) * 24 * 3600;

    /**
     * How long a location may go without a full re-fetch of its forward window.
     *
     * <p>The weekly refresh normally extends only the tail, because tide predictions are
     * deterministic and re-buying stored days costs credits and row churn for an identical
     * result. The one thing a tail fetch can never pick up is an upstream revision to a station's
     * harmonic constants — rare, but "never" is a strong word — so the whole window is re-seeded
     * on this cadence as cheap insurance.
     *
     * <p>⚠️ Bounded above by {@link #ALMANAC_HORIZON_DAYS}, and not independently choosable. The
     * staleness clock reads the oldest {@code fetched_at} among rows still inside the forward
     * window, so its own evidence ages out of its own query: a stamp can only ever be as old as
     * the horizon. Set this above the horizon and the re-seed can never fire.
     */
    private static final int RESEED_INTERVAL_DAYS = 90;

    /**
     * Margin the tail fetch starts before the frontier extreme, so the frontier sits strictly
     * inside the requested span instead of exactly on its boundary.
     *
     * <p>On 2026-08-10 WorldTides did not return the extreme at exactly {@code start}, and the
     * inclusive delete had already removed it — see
     * {@code docs/engineering/tide-frontier-extreme-loss-plan.md}. A one-minute margin costs
     * nothing (the frontier is {@code MAX(event_time)}, so no other stored row can exist inside
     * it) and removes the reliance on WorldTides' boundary-inclusive behaviour, which production
     * has now shown false.
     */
    private static final long TAIL_OVERLAP_MINUTES = 1;

    /**
     * The span to ask WorldTides for, and why.
     *
     * @param from   first instant to fetch, inclusive — also the lower bound of the delete
     * @param to     last instant to fetch
     * @param reason what decided this window, for the log line
     */
    record FetchWindow(LocalDateTime from, LocalDateTime to, String reason) {

        long lengthSeconds() {
            return ChronoUnit.SECONDS.between(from, to);
        }
    }

    /** Number of days per backfill chunk. WorldTides charges per request, so 7 days is efficient. */
    private static final int BACKFILL_CHUNK_DAYS = 7;

    /** Decimal precision for stored tide heights. Mirrors {@code TideService.HEIGHT_SCALE}. */
    private static final int HEIGHT_SCALE = 3;

    private final RestClient restClient;
    private final TideExtremeRepository tideExtremeRepository;
    private final WorldTidesProperties worldTidesProperties;
    private final JobRunService jobRunService;

    /**
     * Constructs a {@code WorldTidesIngestionService}.
     *
     * @param restClient             shared RestClient for outbound HTTP calls
     * @param tideExtremeRepository  repository for persisted tide extremes
     * @param worldTidesProperties   WorldTides API configuration
     * @param jobRunService          service for recording API call metrics
     */
    public WorldTidesIngestionService(RestClient restClient, TideExtremeRepository tideExtremeRepository,
            WorldTidesProperties worldTidesProperties, JobRunService jobRunService) {
        this.restClient = restClient;
        this.tideExtremeRepository = tideExtremeRepository;
        this.worldTidesProperties = worldTidesProperties;
        this.jobRunService = jobRunService;
    }

    /**
     * Fetches the forward tide window ({@link #FETCH_LENGTH_SECONDS}) from WorldTides for a
     * coastal location and merges
     * them into the {@code tide_extreme} table, replacing only the overlapping window
     * while preserving historical data.
     *
     * <p>If the WorldTides API key is not configured, or if the API call fails or returns
     * a non-200 status, no rows are deleted or written — the existing data is preserved.
     *
     * @param location the coastal location to fetch tide data for
     */
    @Transactional
    public void fetchAndStoreTideExtremes(LocationEntity location) {
        fetchAndStoreTideExtremes(location, null);
    }

    /**
     * Fetches the forward tide window ({@link #FETCH_LENGTH_SECONDS}) from WorldTides for a
     * coastal location and merges
     * them into the {@code tide_extreme} table, replacing only the overlapping window
     * while preserving historical data. Optionally tracks the API call in a job run.
     *
     * <p>If the WorldTides API key is not configured, or if the API call fails or returns
     * a non-200 status, no rows are deleted or written — the existing data is preserved.
     *
     * @param location the coastal location to fetch tide data for
     * @param jobRun   the parent job run for metrics tracking, or {@code null}
     */
    @Transactional
    public void fetchAndStoreTideExtremes(LocationEntity location, JobRunEntity jobRun) {
        String apiKey = worldTidesProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("WorldTides API key not configured — skipping tide fetch for {}",
                    location.getName());
            return;
        }

        LocalDateTime startOfDay = LocalDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay();
        LocalDateTime horizon = startOfDay.plusSeconds(FETCH_LENGTH_SECONDS);

        FetchWindow window = resolveFetchWindow(location.getId(), startOfDay, horizon);
        if (window == null) {
            LOG.debug("Tide coverage for {} already reaches {} — no fetch needed",
                    location.getName(), horizon.toLocalDate());
            return;
        }

        long startEpoch = window.from().toEpochSecond(ZoneOffset.UTC);
        long fetchLengthSeconds = window.lengthSeconds();
        long callStartMs = System.currentTimeMillis();

        try {
            LOG.info("WorldTides ← {} ({}, {}) — {} to {} ({})",
                    location.getName(), location.getLat(), location.getLon(),
                    window.from().toLocalDate(), window.to().toLocalDate(), window.reason());
            String tideUrl = "https://" + WORLDTIDES_HOST + "/api/v3?extremes&lat=" + location.getLat()
                    + "&lon=" + location.getLon() + "&start=" + startEpoch
                    + "&length=" + fetchLengthSeconds + "&key=" + (apiKey.length() > 4
                    ? apiKey.substring(0, 4) + "***" : "***");

            WorldTidesResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host(WORLDTIDES_HOST).path("/api/v3")
                            .queryParam("extremes")
                            .queryParam("lat", location.getLat())
                            .queryParam("lon", location.getLon())
                            .queryParam("start", startEpoch)
                            .queryParam("length", fetchLengthSeconds)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(WorldTidesResponse.class);

            long durationMs = System.currentTimeMillis() - callStartMs;

            if (response == null || response.getStatus() != 200
                    || response.getExtremes() == null) {
                String errorMsg = "WorldTides returned status=" + (response != null ? response.getStatus() : "null");
                if (jobRun != null) {
                    jobRunService.logMeteredApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                            "GET", tideUrl, durationMs,
                            response != null ? response.getStatus() : null, false, errorMsg,
                            response != null ? response.getCallCount() : null);
                }
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

            // Delete only the span just fetched, preserving both historical data and the
            // forward days this fetch did not ask for. window.from() is never before
            // startOfDay, so backfilled history can never fall inside this range.
            tideExtremeRepository.deleteByLocationIdAndEventTimeBetween(
                    location.getId(), window.from(), window.to());
            tideExtremeRepository.saveAll(entities);

            checkTideIntegrity(location, window);

            if (jobRun != null) {
                // Priced from the credits WorldTides itself reports, not a flat per-call figure:
                // this endpoint bills one credit per seven days of data, so the cost moves with
                // FETCH_LENGTH_SECONDS and a fixed price cannot follow it.
                jobRunService.logMeteredApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                        "GET", tideUrl, durationMs, 200, true, null, response.getCallCount());
            }

            LOG.info("Stored {} tide extremes for {} ({} to {}, {} credit(s))",
                    entities.size(), location.getName(), window.from().toLocalDate(),
                    window.to().toLocalDate(),
                    response.getCallCount() != null ? response.getCallCount() : "?");

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - callStartMs;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (jobRun != null) {
                Integer statusCode = getStatusCode(e);
                jobRunService.logApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                        "GET", "https://" + WORLDTIDES_HOST + "/api/v3", null, durationMs,
                        statusCode, null, false, errorMsg);
            }
            LOG.warn("Failed to fetch tide extremes for {}: {}", location.getName(), e.getMessage());
        }
    }

    /**
     * Decides what span to fetch: the whole forward window, only the tail beyond what is stored,
     * or nothing at all.
     *
     * <p>Package-private so the decision can be tested without an HTTP call — it is the half of
     * the refresh that determines both the credit cost and what gets deleted.
     *
     * <p><strong>The tail starts {@value #TAIL_OVERLAP_MINUTES} minute(s) before the frontier
     * extreme.</strong> Production showed WorldTides does not reliably return an extreme at
     * exactly {@code start} — an extremum at the window's first instant has no left-hand sample
     * to be detected against, and/or recomputation can shift it a second earlier. The delete's
     * lower bound is still {@code window.from()} and still inclusive, so the frontier row is
     * still deleted; the margin only moves that bound to sit strictly below the frontier instead
     * of on top of it, so the frontier is now genuinely inside the requested span and comes back
     * in the response regardless of WorldTides' boundary semantics. It also still prevents a
     * {@code uq_tide_extreme} violation on re-insert. Note the frontier is a tide instant while the
     * horizon is midnight, so a weekly tail spans seven days plus up to one inter-extreme gap —
     * which is why it bills two credits rather than one.
     *
     * <p>⚠️ Every branch returns a window whose {@code from} is at or after {@code startOfDay}.
     * The delete is derived from it, and stored history sits strictly before that bound, so the
     * merge can only ever extend forwards. Widening this backwards would destroy the 12-month
     * backfill.
     *
     * @param locationId the location primary key
     * @param startOfDay start of today, UTC
     * @param windowEnd  the target horizon
     * @return the span to fetch, or {@code null} when coverage already reaches the horizon
     */
    FetchWindow resolveFetchWindow(Long locationId, LocalDateTime startOfDay,
            LocalDateTime windowEnd) {
        LocalDateTime frontier = tideExtremeRepository
                .findLatestEventTimeFrom(locationId, startOfDay);
        if (frontier == null) {
            // No forward data: a new coastal location, or one whose data has aged out. Same path
            // for both rather than two that can drift apart.
            return new FetchWindow(startOfDay, windowEnd, "seed — no forward data");
        }

        LocalDateTime oldestFetch = tideExtremeRepository
                .findOldestFetchedAtFrom(locationId, startOfDay);
        // The null arm is unreachable today — fetched_at is NOT NULL and both queries share a
        // predicate, so a non-null frontier guarantees a row here. Kept as the safe default if
        // that column ever becomes nullable: unknown age means re-seed, not skip.
        if (oldestFetch == null
                || oldestFetch.isBefore(startOfDay.minusDays(RESEED_INTERVAL_DAYS))) {
            return new FetchWindow(startOfDay, windowEnd, "re-seed — forward window is stale");
        }

        if (!frontier.isBefore(windowEnd)) {
            // Already at the horizon. Happens when the refresh runs twice in a day, and after a
            // re-seed. Returning null skips the API call entirely rather than buying a zero-day
            // window.
            return null;
        }
        LocalDateTime from = frontier.minusMinutes(TAIL_OVERLAP_MINUTES);
        if (from.isBefore(startOfDay)) {
            from = startOfDay;          // preserves the "never reaches backwards" invariant
        }
        return new FetchWindow(from, windowEnd, "tail — extending stored coverage");
    }

    /**
     * Reads back the just-merged span, including the seam with pre-existing rows, and logs a
     * WARN for every same-type adjacency found.
     *
     * <p>Called immediately after the delete+save so a hole left by the merge — such as the
     * frontier extreme {@link #TAIL_OVERLAP_MINUTES} was added to stop losing — is caught the
     * week it happens rather than sitting silent for months. Deliberately no auto-repair: this is
     * a smoke detector, not a sprinkler system.
     *
     * <p><strong>It catches its own failures rather than leaning on the caller's catch.</strong>
     * The enclosing block does more than swallow: it logs a <em>failed</em> WorldTides API call to
     * {@code job_run} metrics and skips the successful one, so a read-back that threw would report
     * a call that was made, billed and merged as an API failure, with no credit count. A
     * diagnostic must never be able to misreport the operation it is diagnosing.
     *
     * <p>What this fixes is the <em>misreport</em>, not durability. This method runs inside the
     * caller's transaction, so a read-back failure that also poisons that transaction still rolls
     * the merge and its success row back together — restoring the log row in that case needs a
     * {@code REQUIRES_NEW} boundary on the metrics write, which is a credit-handling change and a
     * separate piece of work.
     *
     * <p>The scan itself — same-type adjacency detection over a chronologically ordered list —
     * is a pure function that lives on {@link TideService} as {@code sameKindAdjacencies}, not
     * here: {@code TideServiceTest} exercises it directly against that class name.
     *
     * @param location the coastal location just fetched
     * @param window   the span just fetched and merged
     */
    private void checkTideIntegrity(LocationEntity location, FetchWindow window) {
        try {
            List<TideExtremeEntity> merged = tideExtremeRepository
                    .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                            location.getId(), window.from().minusDays(1), window.to());
            for (TideService.Adjacency adjacency : TideService.sameKindAdjacencies(merged)) {
                LOG.warn("Tide integrity: consecutive {} extremes at {} and {} for {} — an extreme "
                        + "is missing between them (window {} to {}, {})",
                        adjacency.first().getType(),
                        adjacency.first().getEventTime(), adjacency.second().getEventTime(),
                        location.getName(), window.from(), window.to(), window.reason());
            }
        } catch (RuntimeException e) {
            // Logged with the throwable: the merge itself has already succeeded and is unaffected,
            // so this line is the only trace that the check did not run.
            LOG.warn("Tide integrity check failed for {} — the merge itself succeeded",
                    location.getName(), e);
        }
    }

    /**
     * Backfills historical tide data for a location by fetching 7-day chunks going back
     * 12 months from today. Skips any chunk where data already exists in the database.
     *
     * <p>Uses the WorldTides {@code date} parameter (ISO date) and {@code days=7} rather
     * than epoch {@code start} + {@code length}, following the API's preferred date mode.
     *
     * @param location the coastal location to backfill
     * @param jobRun   the parent job run for metrics tracking, or {@code null}
     * @return the number of 7-day chunks actually fetched (skipped chunks not counted)
     */
    public int backfillTideExtremes(LocationEntity location, JobRunEntity jobRun) {
        String apiKey = worldTidesProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("WorldTides API key not configured — skipping backfill for {}",
                    location.getName());
            return 0;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate startDate = today.minusMonths(12);
        int chunksFetched = 0;

        for (LocalDate chunkStart = startDate; chunkStart.isBefore(today);
                chunkStart = chunkStart.plusDays(BACKFILL_CHUNK_DAYS)) {
            LocalDate chunkEnd = chunkStart.plusDays(BACKFILL_CHUNK_DAYS);
            LocalDateTime windowFrom = chunkStart.atStartOfDay();
            LocalDateTime windowTo = chunkEnd.atStartOfDay();

            if (tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                    location.getId(), windowFrom, windowTo)) {
                LOG.debug("Backfill skip {} {} — data exists", location.getName(), chunkStart);
                continue;
            }

            long callStartMs = System.currentTimeMillis();
            String maskedKey = apiKey.length() > 4
                    ? apiKey.substring(0, 4) + "***" : "***";
            String tideUrl = "https://" + WORLDTIDES_HOST + "/api/v3?extremes&date="
                    + chunkStart + "&lat=" + location.getLat() + "&lon=" + location.getLon()
                    + "&days=" + BACKFILL_CHUNK_DAYS + "&key=" + maskedKey;
            final String dateParam = chunkStart.toString();

            try {
                WorldTidesResponse response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https").host(WORLDTIDES_HOST).path("/api/v3")
                                .queryParam("extremes")
                                .queryParam("date", dateParam)
                                .queryParam("lat", location.getLat())
                                .queryParam("lon", location.getLon())
                                .queryParam("days", BACKFILL_CHUNK_DAYS)
                                .queryParam("key", apiKey)
                                .build())
                        .retrieve()
                        .body(WorldTidesResponse.class);

                long durationMs = System.currentTimeMillis() - callStartMs;

                if (response == null || response.getStatus() != 200
                        || response.getExtremes() == null) {
                    if (jobRun != null) {
                        jobRunService.logApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                                "GET", tideUrl, null, durationMs,
                                response != null ? response.getStatus() : null,
                                null, false, "Non-200 status on backfill");
                    }
                    LOG.warn("Backfill failed for {} at {} — status={}",
                            location.getName(), chunkStart,
                            response != null ? response.getStatus() : "null");
                    continue;
                }

                LocalDateTime fetchedAt = LocalDateTime.now(ZoneOffset.UTC);
                List<TideExtremeEntity> entities = response.getExtremes().stream()
                        .filter(e -> e.getType() != null
                                && ("High".equalsIgnoreCase(e.getType())
                                        || "Low".equalsIgnoreCase(e.getType())))
                        .map(e -> TideExtremeEntity.builder()
                                .locationId(location.getId())
                                .eventTime(Instant.ofEpochSecond(e.getDt())
                                        .atZone(ZoneOffset.UTC).toLocalDateTime())
                                .heightMetres(BigDecimal.valueOf(e.getHeight())
                                        .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP))
                                .type("High".equalsIgnoreCase(e.getType())
                                        ? TideExtremeType.HIGH : TideExtremeType.LOW)
                                .fetchedAt(fetchedAt)
                                .build())
                        .toList();

                tideExtremeRepository.saveAll(entities);
                chunksFetched++;

                if (jobRun != null) {
                    jobRunService.logApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                            "GET", tideUrl, null, durationMs, 200, null, true, null);
                }

                LOG.info("Backfill {} {} — {} extremes stored",
                        location.getName(), chunkStart, entities.size());

            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - callStartMs;
                if (jobRun != null) {
                    Integer statusCode = getStatusCode(e);
                    jobRunService.logApiCall(jobRun.getId(), ServiceName.WORLD_TIDES,
                            "GET", tideUrl, null, durationMs, statusCode, null, false,
                            e.getMessage());
                }
                LOG.warn("Backfill failed for {} at {}: {}",
                        location.getName(), chunkStart, e.getMessage());
            }
        }

        LOG.info("Backfill complete for {} — {} chunks fetched",
                location.getName(), chunksFetched);
        return chunksFetched;
    }

    /**
     * Extracts the HTTP status code from an exception, if available.
     *
     * @param e the exception
     * @return the HTTP status code, or null if not available
     */
    private Integer getStatusCode(Exception e) {
        if (e instanceof RestClientResponseException rex) {
            return rex.getStatusCode().value();
        }
        return null;
    }
}
