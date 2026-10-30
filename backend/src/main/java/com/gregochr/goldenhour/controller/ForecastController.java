package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.LitePickShaper;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.PickScope;
import com.gregochr.goldenhour.service.PlanWindowProjector;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.util.Authorities;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * REST controller for forecast evaluation data.
 *
 * <p>Exposes endpoints to retrieve stored forecasts
 * and trigger the tide refresh and backfill. GET endpoints return {@link ForecastEvaluationDto}
 * with role-based shaping (LITE users receive neither the Fiery Sky and Golden Hour scores nor the
 * per-location written why; PRO/ADMIN receive the enhanced directional scores and prose).
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastController.class);


    private final ForecastEvaluationRepository repository;
    private final LocationService locationService;
    private final ScheduledForecastService scheduledForecastService;
    private final ForecastDtoMapper dtoMapper;
    private final EvaluationViewService evaluationViewService;
    private final RunProgressTracker progressTracker;
    private final Executor forecastExecutor;
    private final Clock clock;
    private final AsOfEventResolver asOfEventResolver;
    private final SolarEventFreshness solarEventFreshness;
    private final LitePickShaper litePickShaper;

    /**
     * Constructs a {@code ForecastController}.
     *
     * @param repository                the forecast evaluation repository
     * @param locationService           the service for persisted locations
     * @param scheduledForecastService  the scheduled forecast service (for tide refresh)
     * @param dtoMapper                 maps entities to role-aware DTOs
     * @param evaluationViewService     canonical merge layer for cached + forecast evaluations
     * @param progressTracker           tracks live run progress for SSE broadcasting
     * @param forecastExecutor          the executor used for async forecast runs
     * @param clock                     supplies "today" on the UK civil calendar, via
     *                                  {@link ForecastHorizon}
     * @param asOfEventResolver         what the reader had been shown for a passed slot at its event
     * @param solarEventFreshness       each place's own sunrise or sunset, for "has this slot passed"
     * @param litePickShaper            strips ratings outside a Lite reader's sub-region picks
     */
    public ForecastController(ForecastEvaluationRepository repository,
            LocationService locationService,
            ScheduledForecastService scheduledForecastService,
            ForecastDtoMapper dtoMapper, EvaluationViewService evaluationViewService,
            RunProgressTracker progressTracker, Executor forecastExecutor,
            Clock clock, AsOfEventResolver asOfEventResolver,
            SolarEventFreshness solarEventFreshness, LitePickShaper litePickShaper) {
        this.repository = repository;
        this.locationService = locationService;
        this.scheduledForecastService = scheduledForecastService;
        this.dtoMapper = dtoMapper;
        this.evaluationViewService = evaluationViewService;
        this.progressTracker = progressTracker;
        this.forecastExecutor = forecastExecutor;
        this.clock = clock;
        this.asOfEventResolver = asOfEventResolver;
        this.solarEventFreshness = solarEventFreshness;
        this.litePickShaper = litePickShaper;
    }

    /**
     * Returns stored forecast evaluations for all configured locations from
     * T-{@value ForecastHorizon#SERVE_PAST_DAYS} through
     * T+{@value RunType#FORECAST_HORIZON_DAYS}.
     *
     * <p>Merges two sources so the Map tab stays in sync with the Plan tab:
     * <ul>
     *   <li>Rich rows from {@code forecast_evaluation} (full atmospheric data) — preferred.</li>
     *   <li>Sparse rows from {@code cached_evaluation} (briefing/batch scores only) for
     *       {@code (location, date, type)} tuples that have no {@code forecast_evaluation}
     *       row yet — common while a batch is in-flight between submit and result-write.</li>
     * </ul>
     *
     * <p>Scores are role-aware: LITE users receive null potentials and a null {@code summary} on
     * every row, rich and sparse alike (the star rating is free); PRO/ADMIN users receive the
     * enhanced (directional) scores. Sparse rows carry a single set of scores for the roles that
     * get any.
     *
     * <p><b>A slot whose event has passed is served as of that event</b> — see
     * {@link #serveRange}; an unpassed slot (a future day, today's event before it happens) is served
     * exactly as described above.
     *
     * @param auth the current authentication context (injected by Spring Security)
     * @return evaluations: unpassed rows, then passed slots as of their event, then cached-only rows
     *         (no ordering is promised; the client groups by date and type)
     */
    @GetMapping
    public List<ForecastListDto> getForecasts(Authentication auth) {
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate from = today.minusDays(ForecastHorizon.SERVE_PAST_DAYS);
        LocalDate horizon = today.plusDays(RunType.FORECAST_HORIZON_DAYS);
        return serveRange(from, horizon, auth);
    }

    /** Message for a {@code ?date=} outside {@code [today - MAX_PAST_DAYS, today)}, or malformed. */
    static final String DATE_OUT_OF_RANGE = "date must be between T-"
            + ForecastHorizon.MAX_PAST_DAYS + " and yesterday";

    /** The two solar events a forecast slot is rated for. */
    private static final List<TargetType> SOLAR_EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    /**
     * Returns the stored forecast evaluations for ONE past day, for the Map tab's older-day fetch
     * (the "rate how it turned out" flow steps back beyond the days the list carries).
     *
     * <p>The day must lie in {@code [today - }{@value ForecastHorizon#MAX_PAST_DAYS}{@code , today)} on
     * the UK civil calendar — 400 {@code {"error": "date must be between T-4 and yesterday"}}
     * otherwise (a malformed date included), so it can never serve the future. It does admit days the
     * list also carries (T-1, T-2): it is the same {@link #serveRange}, so both answer a slot alike, and
     * a client simply never asks for a day it already holds. Every slot on a past day is served by the
     * same rule as the list's own past days: the forecast as of the slot's event.
     *
     * <p><b>Stored rows only.</b> Nothing here reaches {@code runForecast}, a batch or a model — the
     * controller's run-capable collaborators are never touched. The path is exactly {@code
     * /api/forecast}, so {@code HttpCachingConfig}'s ETag allow-list needs no change: the body is
     * the same shared forecast for every reader of the role, never personal.
     *
     * <p>Cost note: the sparse-cache read ({@code EvaluationViewService.loadCachedEvaluations})
     * loads every {@code cached_evaluation} row from the requested day forward and keeps one day's.
     * That over-read is bounded (regions × ~6 days × 2) and left as it is.
     *
     * @param date the day, ISO {@code yyyy-MM-dd}
     * @param auth the current authentication context
     * @return that day's evaluations
     * @throws IllegalArgumentException if the date is malformed or outside the permitted past window
     */
    @GetMapping(params = "date")
    public List<ForecastListDto> getForecastsForDate(@RequestParam("date") String date,
            Authentication auth) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(DATE_OUT_OF_RANGE);
        }
        LocalDate today = ForecastHorizon.today(clock);
        if (day.isBefore(today.minusDays(ForecastHorizon.MAX_PAST_DAYS)) || !day.isBefore(today)) {
            throw new IllegalArgumentException(DATE_OUT_OF_RANGE);
        }
        return serveRange(day, day, auth);
    }

    /**
     * Serves {@code [from, to]}: the list's body and the one-day read's body, one code path.
     *
     * <p>An <b>unpassed</b> slot is served as it always was: the latest rich row (retracted by the
     * slot's latest stability skip as of now) and the sparse cache rows for slots with no row.
     *
     * <p>⚠️ A <b>passed</b> slot — {@link PlanWindowProjector#hasPassed} on THIS place's own event,
     * {@code now} from the injected clock — is served <em>as of its event</em> through {@link
     * AsOfEventResolver}, the one resolution an outcome rating's provenance also uses, so the star a
     * past-window chip draws is the star the record captures (outcome-rating plan §2 #4, Codex
     * #1185 rounds 9–10). A batch that landed after the event, or a stability skip recorded after
     * it, can therefore no longer repaint a window the reader has already seen: the slot's latest
     * row and the post-event cache entry are never consulted for it.
     * <ul>
     *   <li>the resolver's {@code forecastRow} (available by the event, unretracted at it) is
     *       served, and for a PRO/ADMIN reader its {@code rating} is the resolver's captured {@code
     *       forecastRating} — always, so a canopy-bearing place whose composite star is not
     *       recoverable is served unscored (no star and no scores, the stand-down reason kept), never
     *       with the row's sky-only star. A LITE reader keeps the row exactly as the list has always
     *       served it to LITE: the row's own rating, without the potentials or the written why;</li>
     *   <li>a served result with no row behind it (the candidate was retracted or never scored) is
     *       served as a sparse row built from the result, rated {@code forecastRating};</li>
     *   <li>nothing available is served as no row at all — the place reads unscored on that window;</li>
     *   <li>every passed slot, nothing-available included, is marked covered so the sparse cache
     *       read cannot resurrect a post-event entry for it. A place with no event time is treated
     *       as unpassed.</li>
     * </ul>
     * The managed {@code forecastRow} is never mutated; the captured star overrides on the DTO.
     */
    private List<ForecastListDto> serveRange(LocalDate from, LocalDate to, Authentication auth) {
        boolean lite = isLiteUser(auth);

        List<LocationEntity> enabled = locationService.findAllEnabled();

        // 1. Rich rows from forecast_evaluation — the latest run per slot (dedup at source),
        //    fetched in one batched query for all enabled locations. Mapped to the slim list DTO:
        //    the heavy popup-only fields (summary text, atmospherics, tide/surge/inversion detail)
        //    are fetched lazily per location via GET /api/forecast/{id} when a popup opens.
        List<Long> locationIds = enabled.stream()
                .map(LocationEntity::getId)
                .toList();
        List<ForecastEvaluationEntity> entities = locationIds.isEmpty()
                ? List.of()
                : repository.findLatestRunPerSlotByLocationIds(locationIds, from, to);
        // A nightly Gate 4 stability skip writes no row to this table — the whole reason the slot
        // can go on reading as "the latest row" long after the pipeline declined to re-look at it.
        // This is the one serve path that reads forecast_evaluation directly rather than through
        // EvaluationViewService.mergeToView, so it has to apply the same retraction decision
        // itself, via the SAME method: this endpoint has no cached_evaluation lookup of its own, so
        // it calls isSlotRetracted with a null cached side, which correctly means "no live cache
        // evidence to fall back to" here — a slot is dropped when its only visible row is stale OR
        // says nothing at all while a skip stands against it, exactly as EvaluationViewService drops
        // it, so the two never disagree about the same slot. (This intentionally now drops an
        // otherwise-unscored row too, when a skip is recorded against it — the single case this
        // changes from a plain "row has nothing to say" filter: an ordinary, never-skipped unscored
        // row is completely unaffected, since isSlotRetracted returns false immediately whenever no
        // skip is recorded, which is true for the overwhelming majority of forecast_evaluation's
        // null-rating rows.)
        Map<String, Instant> stabilitySkips = evaluationViewService.loadStabilitySkips(from, to);

        // The passed slots are served as of their event instead: the latest row, retracted as of
        // now, is not what the reader saw at it.
        Map<String, PassedSlot> passed = resolvePassedSlots(enabled, from, to);
        List<ForecastEvaluationEntity> unpassed = entities.stream()
                .filter(e -> !passed.containsKey(slotKey(e.getLocationName(), e.getTargetDate(),
                        e.getTargetType())))
                .filter(e -> !EvaluationViewService.isSlotRetracted(null, null, e,
                        stabilitySkips.get(slotKey(e.getLocationName(), e.getTargetDate(),
                                e.getTargetType()))))
                .toList();

        // One mapper call over the unpassed rows then the passed slots' rows, so the mapper's
        // preloads (sea state) run once; `captured` lines up with the rows appended after the
        // unpassed ones.
        List<ForecastEvaluationEntity> served = new ArrayList<>(unpassed);
        List<Integer> captured = new ArrayList<>();
        List<ForecastListDto> sparsePassed = new ArrayList<>();
        for (PassedSlot slot : passed.values()) {
            AsOfEvent asOf = slot.asOf();
            if (asOf.forecastRow() != null) {
                served.add(asOf.forecastRow());
                captured.add(asOf.forecastRating());
            } else if (asOf.result() != null) {
                sparsePassed.add(dtoMapper.toSparseListDto(
                        viewOf(slot, asOf), slot.location(), lite));
            }
        }
        List<ForecastListDto> mapped = dtoMapper.toListDtoList(served, lite);
        List<ForecastListDto> dtos = new ArrayList<>(mapped);
        if (!lite) {
            // The captured star replaces the row's own on the DTO (the managed row is never touched).
            for (int i = 0; i < captured.size(); i++) {
                int at = unpassed.size() + i;
                ForecastListDto dto = dtos.get(at);
                Integer star = captured.get(i);
                // A star that cannot be recovered takes the row's scores with it (see unscored()).
                dtos.set(at, star == null && dto.rating() != null ? dto.unscored() : dto.withRating(star));
            }
        }
        dtos.addAll(sparsePassed);

        // 2. Cached-only rows — surfaced so the Map date strip shows future dates that the
        //    batch pipeline has scored but not yet persisted as full forecast_evaluation rows
        Set<String> covered = new HashSet<>(passed.keySet());
        for (var entity : unpassed) {
            covered.add(slotKey(entity.getLocationName(), entity.getTargetDate(), entity.getTargetType()));
        }
        Map<Long, LocationEntity> byId = enabled.stream()
                .collect(Collectors.toMap(LocationEntity::getId, l -> l));
        // Cached-only views (dates the batch pipeline scored but hasn't persisted as forecast rows).
        // getForecasts already has its own latest forecast rows, so this skips the per-location
        // forecast_evaluation re-query + merge that forDateRange would do — and reuses `enabled`.
        List<LocationEvaluationView> views = evaluationViewService.cachedOnlyViewsForDateRange(
                from, to, Set.of(TargetType.SUNRISE, TargetType.SUNSET), enabled,
                stabilitySkips);
        for (LocationEvaluationView view : views) {
            if (view.source() != LocationEvaluationView.Source.CACHED_EVALUATION) {
                continue;
            }
            if (covered.contains(slotKey(view.locationName(), view.date(), view.targetType()))) {
                continue;
            }
            LocationEntity loc = byId.get(view.locationId());
            if (loc == null) {
                continue;
            }
            dtos.add(dtoMapper.toSparseListDto(view, loc, lite));
        }
        // Enforcement (sub-regions-plan S3): after the role shaping above, a reader whose picks do not cover a
        // place gets that row gated, past days included; the body is then no-store (it reveals the picks).
        PickScope scope = litePickShaper.scopeFor(auth);
        if (!scope.shaped()) {
            return dtos;
        }
        LitePickShaper.markNoStore(scope);
        return dtos.stream().map(dto -> litePickShaper.shapeListRow(dto, scope)).toList();
    }

    /** A passed slot and what the reader had been shown for it at its event. */
    private record PassedSlot(LocationEntity location, LocalDate date, TargetType type, AsOfEvent asOf) {
    }

    private static String slotKey(String locationName, LocalDate date, TargetType type) {
        return locationName + "|" + date + "|" + type;
    }

    /**
     * Every slot in {@code [from, to]} whose own event has passed, resolved as of that event — one
     * resolver call per (date, type) over the passed places only, never per slot.
     *
     * @return slot key ({@code name|date|type}) to the slot and its as-of-event answer, in date,
     *         type then roster order
     */
    private Map<String, PassedSlot> resolvePassedSlots(List<LocationEntity> enabled, LocalDate from,
            LocalDate to) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate last = to.isBefore(today) ? to : today;
        Map<String, PassedSlot> out = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(last); date = date.plusDays(1)) {
            for (TargetType type : SOLAR_EVENTS) {
                Map<Long, Instant> events = new LinkedHashMap<>();
                List<LocationEntity> places = new ArrayList<>();
                for (LocationEntity location : enabled) {
                    LocalDateTime event = solarEventFreshness.eventTime(location, date, type);
                    if (PlanWindowProjector.hasPassed(event, now)) {
                        places.add(location);
                        events.put(location.getId(), event.toInstant(ZoneOffset.UTC));
                    }
                }
                if (places.isEmpty()) {
                    continue;
                }
                Map<Long, AsOfEvent> resolved = asOfEventResolver.resolveAll(places, date, type,
                        place -> events.get(place.getId()));
                for (LocationEntity place : places) {
                    out.put(slotKey(place.getName(), date, type), new PassedSlot(place, date, type,
                            resolved.getOrDefault(place.getId(), AsOfEvent.NONE)));
                }
            }
        }
        return out;
    }

    /**
     * Presents the served result of a passed slot with no row behind it as a sparse view, rated with
     * the resolver's captured star and aged by the forecast's own instant.
     */
    private static LocationEvaluationView viewOf(PassedSlot slot, AsOfEvent asOf) {
        BriefingEvaluationResult result = asOf.result();
        LocationEntity location = slot.location();
        return new LocationEvaluationView(location.getId(), location.getName(),
                location.getRegion() != null ? location.getRegion().getId() : null,
                location.getRegion() != null ? location.getRegion().getName() : null,
                slot.date(), slot.type(), LocationEvaluationView.Source.CACHED_EVALUATION,
                asOf.forecastRating(), result.summary(), result.fierySkyPotential(),
                result.goldenHourPotential(), result.triageReason(), result.triageMessage(), null,
                asOf.runAt(), null, null, null, null, null);
    }

    /**
     * Returns the full forecast evaluation for one persisted row, fetched lazily when a map popup
     * opens. This carries the heavy popup-only fields (the Claude {@code summary}, atmospherics,
     * directional cloud, tide/surge/inversion detail, bluebell, golden/blue-hour windows) that the
     * slim {@link #getForecasts} list omits.
     *
     * <p>Shaped for the role identically to the list: LITE users get null potentials, summary and
     * headline, PRO/ADMIN get the enhanced (directional) values — so this endpoint cannot be used to
     * bypass the freemium score gating.
     *
     * <p>With {@code photocast.sub-regions.enforce} on, an id whose location is outside a non-Pro reader's
     * sub-region picks is served the gated evaluation, never the full one (ids are enumerable): no star, no
     * potentials, no summary or headline, no bluebell score or summary, no stand-down reason, and
     * {@code gated: true}; the weather and tide facts stay. The response is sent {@code no-store}.
     *
     * @param id   the forecast_evaluation id (only persisted rich rows have one; sparse cached-only
     *             rows carry their summary inline in the list and never reach here)
     * @param auth the current authentication context
     * @return the full evaluation DTO
     * @throws NoSuchElementException if no evaluation exists with that id (mapped to HTTP 404)
     */
    @GetMapping("/{id:\\d+}")
    public ForecastEvaluationDto getForecastDetail(@PathVariable Long id, Authentication auth) {
        com.gregochr.goldenhour.entity.ForecastEvaluationEntity entity = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No forecast evaluation with id " + id));
        ForecastEvaluationDto dto = dtoMapper.toDto(entity, isLiteUser(auth));
        PickScope scope = litePickShaper.scopeFor(auth);
        if (!scope.shaped()) {
            return dto;
        }
        LitePickShaper.markNoStore(scope);
        Long locationId = entity.getLocation() == null ? null : entity.getLocation().getId();
        return litePickShaper.shapeDetail(dto, locationId, scope);
    }

    /**
     * Triggers a manual refresh of tide extreme data for all coastal locations.
     * Restricted to ADMIN only.
     *
     * <p>Delegates to {@link ScheduledForecastService#startTideRefresh}, which runs the same
     * refresh as the {@code tide_refresh} schedule on {@code forecastExecutor}: it
     * fetches the forward tide window of high/low extremes from WorldTides and stores them in the
     * {@code tide_extreme} table. The run is tracked as a TIDE {@code JobRunEntity}.
     *
     * <p>Refused with 409 while a tide refresh is already running from any route — the
     * schedule, the Scheduler screen's Run Now, or an earlier call here.
     *
     * @return 202 Accepted with status message, or 409 Conflict if a refresh is already running
     */
    @PostMapping("/run/tide")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> refreshTideData() {
        LOG.info("POST /api/forecast/run/tide triggered by admin");
        if (!scheduledForecastService.startTideRefresh(forecastExecutor)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "A tide refresh is already running", "runType", "TIDE"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "Tide refresh started", "runType", "TIDE"));
    }

    /**
     * Triggers a backfill of 12 months of historical tide data for all SEASCAPE locations.
     * Restricted to ADMIN only.
     *
     * <p>Fetches in 7-day chunks, skipping ranges where data already exists to avoid
     * duplicate WorldTides API charges. The run is tracked as a TIDE {@code JobRunEntity}.
     *
     * @return 202 Accepted with status message
     */
    @PostMapping("/run/tide/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> backfillTideData() {
        LOG.info("POST /api/forecast/run/tide/backfill triggered by admin");
        CompletableFuture.runAsync(() -> scheduledForecastService.backfillTideExtremes(), forecastExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "Tide backfill started (12 months, SEASCAPE locations)",
                        "runType", "TIDE"));
    }

    /**
     * SSE endpoint streaming live progress for a specific forecast run. ADMIN only.
     *
     * @param runId the job run ID
     * @return an SSE emitter streaming task-update, run-summary, and run-complete events
     */
    @GetMapping(value = "/run/{runId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter getRunProgress(@PathVariable long runId) {
        return progressTracker.subscribe(runId);
    }

    private boolean isLiteUser(Authentication auth) {
        // Fail closed: anything that is not positively PRO_USER or ADMIN (including no authentication
        // and a bare ROLE_USER) is the least-privileged tier.
        return !(Authorities.hasRole(auth, UserRole.PRO_USER) || Authorities.hasRole(auth, UserRole.ADMIN));
    }
}
