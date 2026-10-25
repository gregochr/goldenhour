package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.ForecastDtoMapper;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.FailedSlotRetryService;
import com.gregochr.goldenhour.service.ForecastCommandExecutor;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import com.gregochr.goldenhour.service.ScheduledForecastService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ForecastController}'s passed-slot rule (outcome-rating plan §2 #4, O2): a slot whose own
 * event has passed is served <em>as of that event</em>, on the list's past days and on
 * {@code ?date=} alike, while an unpassed slot is served exactly as it always was.
 *
 * <p>A plain unit test with a fixed clock, as {@link ForecastWindowAnchorTest} is, because the
 * MockMvc tests share a context whose clock is the real one. {@link ServeShape} uses a mocked
 * resolver to pin WHICH slots reach it and what the controller does with the answer;
 * {@link WithTheRealResolver} wires the real {@link AsOfEventResolver} over mocked stores to pin
 * the rules a past-window chip depends on end to end (a late batch, a retraction, a canopy place).
 */
@ExtendWith(MockitoExtension.class)
class ForecastControllerAsOfEventTest {

    /** 13:00 on 15 October in Europe/London (BST); 12:00 UTC. */
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 14);

    /** Yesterday's sunset: long gone. */
    private static final LocalDateTime YESTERDAY_SUNSET = LocalDateTime.of(2026, 10, 14, 17, 0);
    private static final Instant YESTERDAY_SUNSET_AT = YESTERDAY_SUNSET.toInstant(ZoneOffset.UTC);
    /** Today's sunrise has passed (06:30Z against a 12:00Z now); today's sunset has not (17:00Z). */
    private static final LocalDateTime TODAY_SUNRISE = LocalDateTime.of(2026, 10, 15, 6, 30);
    private static final LocalDateTime TODAY_SUNSET = LocalDateTime.of(2026, 10, 15, 17, 0);

    private static final String REGION_NAME = "North Pennines";
    private final RegionEntity region = RegionEntity.builder().id(1L).name(REGION_NAME).build();

    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private LocationService locationService;
    @Mock
    private ForecastCommandFactory commandFactory;
    @Mock
    private ForecastCommandExecutor commandExecutor;
    @Mock
    private ScheduledForecastService scheduledForecastService;
    @Mock
    private ForecastDtoMapper dtoMapper;
    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private RunProgressTracker progressTracker;
    @Mock
    private FailedSlotRetryService retryService;
    @Mock
    private SolarEventFreshness solarEventFreshness;

    private final Authentication pro = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");
    private final Authentication lite = new TestingAuthenticationToken("u", "p", "ROLE_LITE_USER");
    private final Authentication bareUser = new TestingAuthenticationToken("u", "p", "ROLE_USER");

    private LocationEntity sky;
    private LocationEntity canopy;

    @BeforeEach
    void setUp() {
        sky = LocationEntity.builder().id(10L).name("Hartside").lat(54.7).lon(-2.4).region(region)
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        canopy = LocationEntity.builder().id(11L).name("Hexham Wood").lat(54.9).lon(-2.1).region(region)
                .locationType(Set.of(LocationType.WOODLAND)).build();
        // Every slot is unpassed (no event time) unless a test says otherwise; the catch-all is
        // lenient because it is only consulted when a roster is non-empty.
        lenient().when(solarEventFreshness.eventTime(any(), any(), any())).thenReturn(null);
    }

    private ForecastController controller(AsOfEventResolver resolver) {
        return new ForecastController(repository, locationService, commandFactory, commandExecutor,
                scheduledForecastService, dtoMapper, evaluationViewService, jobRunService,
                progressTracker, Runnable::run, NOW, retryService, resolver, solarEventFreshness);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private ForecastEvaluationEntity row(long id, LocationEntity location, LocalDate date, TargetType type,
            LocalDateTime runAt, Instant scoredAt, Integer rating) {
        return ForecastEvaluationEntity.builder().id(id).location(location).targetDate(date)
                .targetType(type).forecastRunAt(runAt).scoredAt(scoredAt).rating(rating).build();
    }

    private static ForecastListDto dtoOf(ForecastEvaluationEntity e) {
        return new ForecastListDto(e.getId(), e.getLocationName(), null, null, e.getTargetDate(),
                e.getTargetType(), e.getForecastRunAt(), null, null, e.getRating(), null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** The mapper maps each entity to a DTO carrying its own id and rating, in order. */
    private void mapperEchoesEntities() {
        when(dtoMapper.toListDtoList(anyList(), anyBoolean())).thenAnswer(call -> {
            List<ForecastEvaluationEntity> rows = call.getArgument(0);
            return rows.stream().map(ForecastControllerAsOfEventTest::dtoOf).toList();
        });
    }

    private void roster(LocationEntity... places) {
        when(locationService.findAllEnabled()).thenReturn(List.of(places));
    }

    private void latestRows(LocalDate from, LocalDate to, ForecastEvaluationEntity... rows) {
        when(repository.findLatestRunPerSlotByLocationIds(anyCollection(), eq(from), eq(to)))
                .thenReturn(List.of(rows));
    }

    private void eventAt(LocationEntity place, LocalDate date, TargetType type, LocalDateTime at) {
        when(solarEventFreshness.eventTime(place, date, type)).thenReturn(at);
    }

    private static BriefingEvaluationResult served(String name, Integer rating, Instant evaluatedAt,
            Instant submittedAt) {
        return new BriefingEvaluationResult(name, rating, 60, 55, "Served summary", null, null, "Headline",
                evaluatedAt, rating, false, false, submittedAt);
    }

    // ── which slots reach the resolver, and what the controller does with the answer ────

    @Nested
    @DisplayName("the serve shape, with a mocked resolver")
    class ServeShape {

        @Mock
        private AsOfEventResolver resolver;

        @Test
        @DisplayName("a place with no event time (the solar library returned null) is unpassed: served as "
                + "today, never asked of the resolver")
        void noEventTimeIsUnpassed() {
            roster(sky);
            ForecastEvaluationEntity latest = row(1L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 20, 0), Instant.parse("2026-10-14T20:10:00Z"), 5);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), latest);
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(1L);
            assertThat(out.get(0).rating()).as("the latest row, as ever").isEqualTo(5);
            verifyNoInteractions(resolver);
        }

        @Test
        @DisplayName("today: the elapsed sunrise goes to the resolver, the sunset before it happens is "
                + "served as today - the latest row, untouched")
        void todayIsSplitByEvent() {
            roster(sky);
            eventAt(sky, TODAY, TargetType.SUNRISE, TODAY_SUNRISE);
            eventAt(sky, TODAY, TargetType.SUNSET, TODAY_SUNSET);
            ForecastEvaluationEntity sunrise = row(1L, sky, TODAY, TargetType.SUNRISE,
                    LocalDateTime.of(2026, 10, 15, 1, 0), Instant.parse("2026-10-15T01:10:00Z"), 4);
            ForecastEvaluationEntity sunset = row(2L, sky, TODAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 15, 9, 0), Instant.parse("2026-10-15T09:10:00Z"), 2);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), sunrise, sunset);
            ForecastEvaluationEntity asOfRow = row(3L, sky, TODAY, TargetType.SUNRISE,
                    LocalDateTime.of(2026, 10, 15, 0, 30), Instant.parse("2026-10-15T00:40:00Z"), 3);
            when(resolver.resolveAll(anyCollection(), eq(TODAY), eq(TargetType.SUNRISE), any()))
                    .thenReturn(Map.of(10L, new AsOfEvent(null, 3L, Instant.parse("2026-10-15T00:30:00Z"),
                            null, 3, asOfRow)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id)
                    .as("the unpassed sunset as ever, then the sunrise as of its event - not the latest row")
                    .containsExactly(2L, 3L);
            verify(resolver, never()).resolveAll(anyCollection(), eq(TODAY), eq(TargetType.SUNSET), any());
        }

        @Test
        @DisplayName("one resolver call per (date, type), over the PASSED places only - not one per slot")
        void oneCallPerDateAndTypeOverPassedPlaces() {
            roster(sky, canopy);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            eventAt(sky, TODAY, TargetType.SUNRISE, TODAY_SUNRISE);
            // The wood's sunrise is (artificially) still ahead: only one of the two places has passed.
            eventAt(canopy, TODAY, TargetType.SUNRISE, TODAY_SUNSET);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), any(), any(), any())).thenReturn(Map.of());
            mapperEchoesEntities();

            controller(resolver).getForecasts(pro);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<LocationEntity>> places = ArgumentCaptor.forClass(Collection.class);
            verify(resolver, times(1)).resolveAll(places.capture(), eq(YESTERDAY), eq(TargetType.SUNSET), any());
            assertThat(places.getValue()).containsExactly(sky, canopy);
            verify(resolver, times(1)).resolveAll(places.capture(), eq(TODAY), eq(TargetType.SUNRISE), any());
            assertThat(places.getValue()).containsExactly(sky);
            verify(resolver, times(2)).resolveAll(anyCollection(), any(), any(), any());
        }

        @Test
        @DisplayName("the resolver is handed each place's OWN event instant")
        void eachPlaceGetsItsOwnEventInstant() {
            roster(sky, canopy);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET.minusMinutes(7));
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), any(), any(), any())).thenReturn(Map.of());
            mapperEchoesEntities();

            controller(resolver).getForecasts(pro);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.function.Function<LocationEntity, Instant>> instants =
                    ArgumentCaptor.forClass(java.util.function.Function.class);
            verify(resolver).resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), instants.capture());
            assertThat(instants.getValue().apply(sky)).isEqualTo(YESTERDAY_SUNSET_AT);
            assertThat(instants.getValue().apply(canopy)).isEqualTo(YESTERDAY_SUNSET_AT.minusSeconds(7 * 60));
        }

        @Test
        @DisplayName("nothing available by the event serves NO row: not the latest row, and not a post-event "
                + "cache entry - every passed slot is covered; an unpassed slot's sparse row still shows")
        void nothingAvailableIsServedAsNoRow() {
            roster(sky);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity latest = row(1L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 15, 0), Instant.parse("2026-10-15T03:00:00Z"), 5);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), latest);
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(10L, AsOfEvent.NONE));
            LocationEvaluationView postEvent = view(YESTERDAY, TargetType.SUNSET, 5);
            LocationEvaluationView future = view(TODAY.plusDays(2), TargetType.SUNRISE, 3);
            when(evaluationViewService.cachedOnlyViewsForDateRange(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(postEvent, future));
            ForecastListDto futureDto = dtoOf(row(9L, sky, TODAY.plusDays(2), TargetType.SUNRISE,
                    LocalDateTime.of(2026, 10, 15, 11, 0), null, 3));
            when(dtoMapper.toSparseListDto(future, sky, false)).thenReturn(futureDto);
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).containsExactly(futureDto);
            verify(dtoMapper, never()).toSparseListDto(postEvent, sky, false);
        }

        @Test
        @DisplayName("a served result with no row behind it is a sparse row rated the CAPTURED star, aged "
                + "by the forecast's own instant")
        void resultWithoutRowIsServedSparse() {
            roster(sky);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            BriefingEvaluationResult result = served("Hartside", 4, cycle.plusSeconds(300), cycle);
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(10L, new AsOfEvent(result, null, cycle, 77L, 4, null)));
            ForecastListDto sparse = dtoOf(row(5L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 13, 5), null, 4));
            when(dtoMapper.toSparseListDto(any(LocationEvaluationView.class), eq(sky), eq(false)))
                    .thenReturn(sparse);
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).containsExactly(sparse);
            ArgumentCaptor<LocationEvaluationView> view = ArgumentCaptor.forClass(LocationEvaluationView.class);
            verify(dtoMapper).toSparseListDto(view.capture(), eq(sky), eq(false));
            assertThat(view.getValue().rating()).isEqualTo(4);
            assertThat(view.getValue().evaluatedAt()).as("the forecast's own age").isEqualTo(cycle);
            assertThat(view.getValue().summary()).isEqualTo("Served summary");
            assertThat(view.getValue().fierySkyPotential()).isEqualTo(60);
            assertThat(view.getValue().goldenHourPotential()).isEqualTo(55);
            assertThat(view.getValue().regionName()).isEqualTo(REGION_NAME);
            assertThat(view.getValue().date()).isEqualTo(YESTERDAY);
            assertThat(view.getValue().targetType()).isEqualTo(TargetType.SUNSET);
        }

        @Test
        @DisplayName("PRO/ADMIN get the captured star as the rating, and the managed row is never mutated")
        void proGetsTheCapturedStarAndTheRowIsUntouched() {
            roster(canopy);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 12, 0), Instant.parse("2026-10-14T12:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(11L, new AsOfEvent(null, 7L, Instant.parse("2026-10-14T12:00:00Z"),
                            null, 5, skyRow)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).rating()).as("the captured composite").isEqualTo(5);
            assertThat(skyRow.getRating()).as("the managed entity is never mutated").isEqualTo(3);
        }

        @Test
        @DisplayName("an unrecoverable composite is served unscored - no star AND no scores, so a pin cannot "
                + "draw arcs over an unscored window - with the stand-down reason kept")
        void unrecoverableStarTakesTheScoresWithIt() {
            roster(canopy);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 12, 0), Instant.parse("2026-10-14T12:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(11L, new AsOfEvent(null, 7L, Instant.parse("2026-10-14T12:00:00Z"),
                            null, null, skyRow)));
            when(dtoMapper.toListDtoList(anyList(), anyBoolean())).thenReturn(List.of(
                    new ForecastListDto(7L, "Hexham Wood", null, null, YESTERDAY, TargetType.SUNSET, null,
                            null, null, 3, 61, 54, TriageReason.HIGH_CLOUD, null, null, null, null, null,
                            null, null, null)));

            ForecastListDto only = controller(resolver).getForecasts(pro).get(0);

            assertThat(only.rating()).isNull();
            assertThat(only.fierySkyPotential()).isNull();
            assertThat(only.goldenHourPotential()).isNull();
            assertThat(only.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        }

        @Test
        @DisplayName("an unpassed row and several passed rows in one response: each passed row gets ITS OWN "
                + "captured star, the unpassed row keeps its own, and unpassed rows come first")
        void capturedStarsPairWithTheirOwnRows() {
            roster(sky, canopy);
            eventAt(sky, TODAY, TargetType.SUNSET, TODAY_SUNSET);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity unpassedRow = row(1L, sky, TODAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 15, 9, 0), Instant.parse("2026-10-15T09:10:00Z"), 2);
            ForecastEvaluationEntity skyRow = row(2L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 9, 0), Instant.parse("2026-10-14T09:10:00Z"), 3);
            ForecastEvaluationEntity woodRow = row(3L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 9, 0), Instant.parse("2026-10-14T09:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), unpassedRow);
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(
                            10L, new AsOfEvent(null, 2L, Instant.parse("2026-10-14T09:00:00Z"), null, 4, skyRow),
                            11L, new AsOfEvent(null, 3L, Instant.parse("2026-10-14T09:00:00Z"), null, 5, woodRow)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(1L, 2L, 3L);
            assertThat(out).extracting(ForecastListDto::rating).containsExactly(2, 4, 5);
        }

        @Test
        @DisplayName("a slot served from a row or a result is covered too: a post-event cache entry for it "
                + "is never served alongside")
        void servedSlotsAreCoveredAgainstTheCache() {
            roster(sky, canopy);
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity skyRow = row(2L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 9, 0), Instant.parse("2026-10-14T09:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(
                            10L, new AsOfEvent(null, 2L, Instant.parse("2026-10-14T09:00:00Z"), null, 3, skyRow),
                            11L, new AsOfEvent(served("Hexham Wood", 4, cycle.plusSeconds(60), cycle), null,
                                    cycle, null, 4, null)));
            LocationEvaluationView skyCacheEntry = view(YESTERDAY, TargetType.SUNSET, 5);
            LocationEvaluationView woodCacheEntry = new LocationEvaluationView(11L, "Hexham Wood", 1L,
                    REGION_NAME, YESTERDAY, TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION,
                    5, "post-event", 60, 55, null, null, null, null, null, null, null, null, null);
            when(evaluationViewService.cachedOnlyViewsForDateRange(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(skyCacheEntry, woodCacheEntry));
            when(dtoMapper.toSparseListDto(any(LocationEvaluationView.class), eq(canopy), eq(false)))
                    .thenReturn(dtoOf(row(8L, canopy, YESTERDAY, TargetType.SUNSET,
                            LocalDateTime.of(2026, 10, 14, 13, 5), null, 4)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(2L, 8L);
            verify(dtoMapper, never()).toSparseListDto(skyCacheEntry, sky, false);
            verify(dtoMapper, never()).toSparseListDto(woodCacheEntry, canopy, false);
        }

        @Test
        @DisplayName("the afterglow boundary: an event 25 minutes ago has NOT passed, one 35 minutes ago HAS "
                + "- and 'now' is read in UTC, not on the London clock")
        void afterglowBoundary() {
            roster(sky, canopy);
            // now is 12:00Z. 11:35Z + 30 min is still ahead; 11:25Z + 30 min is behind. Read as a London
            // clock (13:00) the 11:35Z event would wrongly count as passed.
            eventAt(sky, TODAY, TargetType.SUNRISE, LocalDateTime.of(2026, 10, 15, 11, 35));
            eventAt(canopy, TODAY, TargetType.SUNRISE, LocalDateTime.of(2026, 10, 15, 11, 25));
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), eq(TODAY), eq(TargetType.SUNRISE), any()))
                    .thenReturn(Map.of());
            mapperEchoesEntities();

            controller(resolver).getForecasts(pro);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<LocationEntity>> places = ArgumentCaptor.forClass(Collection.class);
            verify(resolver).resolveAll(places.capture(), eq(TODAY), eq(TargetType.SUNRISE), any());
            assertThat(places.getValue()).containsExactly(canopy);
        }

        @Test
        @DisplayName("a LITE reader keeps the row's own basic rating for a passed slot")
        void liteKeepsTheRowsRating() {
            roster(canopy);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 12, 0), Instant.parse("2026-10-14T12:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(11L, new AsOfEvent(null, 7L, Instant.parse("2026-10-14T12:00:00Z"),
                            null, 5, skyRow)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(lite);

            assertThat(out.get(0).rating()).isEqualTo(3);
            verify(dtoMapper).toListDtoList(List.of(skyRow), true);
        }

        @Test
        @DisplayName("a reader with no PRO_USER or ADMIN role is served as LITE (fail closed), and a passed "
                + "sparse slot is built from the full view for the mapper to strip")
        void unrecognisedRoleFailsClosedOnPassedSlots() {
            roster(canopy);
            eventAt(canopy, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 12, 0), Instant.parse("2026-10-14T12:10:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            when(resolver.resolveAll(eq(List.of(canopy)), eq(YESTERDAY), eq(TargetType.SUNSET),
                    isA(Function.class)))
                    .thenReturn(Map.of(11L, new AsOfEvent(null, 7L, Instant.parse("2026-10-14T12:00:00Z"),
                            null, 5, skyRow)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(bareUser);

            assertThat(out.get(0).rating()).isEqualTo(3);
            verify(dtoMapper).toListDtoList(List.of(skyRow), true);
        }

        @Test
        @DisplayName("an unregioned place served from a result is a sparse row too (no region on the view), "
                + "and a LITE reader is served it the same way")
        void unregionedPlaceAndLiteReaderOnTheSparsePath() {
            LocationEntity unregioned = LocationEntity.builder().id(12L).name("Roadside").lat(54.6).lon(-1.9)
                    .locationType(Set.of(LocationType.LANDSCAPE)).build();
            roster(unregioned);
            eventAt(unregioned, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(Map.of(12L, new AsOfEvent(served("Roadside", 4, cycle.plusSeconds(60), cycle),
                            null, cycle, null, 4, null)));
            when(dtoMapper.toSparseListDto(any(LocationEvaluationView.class), eq(unregioned), eq(true)))
                    .thenReturn(dtoOf(row(5L, unregioned, YESTERDAY, TargetType.SUNSET,
                            LocalDateTime.of(2026, 10, 14, 13, 5), null, 4)));
            mapperEchoesEntities();

            List<ForecastListDto> out = controller(resolver).getForecasts(lite);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(5L);
            ArgumentCaptor<LocationEvaluationView> view = ArgumentCaptor.forClass(LocationEvaluationView.class);
            verify(dtoMapper).toSparseListDto(view.capture(), eq(unregioned), eq(true));
            assertThat(view.getValue().regionId()).isNull();
            assertThat(view.getValue().regionName()).isNull();
        }

        @Test
        @DisplayName("?date= serves the one day: the repository is read for exactly that day, passed slots "
                + "go through the resolver, and no run collaborator is ever touched")
        void dateReadServesOneDayAndRunsNothing() {
            roster(sky);
            eventAt(sky, YESTERDAY, TargetType.SUNRISE, LocalDateTime.of(2026, 10, 14, 6, 30));
            eventAt(sky, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            latestRows(YESTERDAY, YESTERDAY);
            when(resolver.resolveAll(anyCollection(), eq(YESTERDAY), any(), any())).thenReturn(Map.of());
            mapperEchoesEntities();

            controller(resolver).getForecastsForDate("2026-10-14", pro);

            verify(repository).findLatestRunPerSlotByLocationIds(anyCollection(), eq(YESTERDAY), eq(YESTERDAY));
            verify(resolver).resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNRISE), any());
            verify(resolver).resolveAll(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any());
            verifyNoInteractions(commandFactory, commandExecutor, scheduledForecastService, jobRunService,
                    progressTracker, retryService);
        }

        @Test
        @DisplayName("?date= admits T-1 and T-4 and refuses today, tomorrow, T-5 and anything malformed")
        void dateBounds() {
            roster();
            mapperEchoesEntities();

            ForecastController controller = controller(resolver);
            assertThat(controller.getForecastsForDate("2026-10-14", pro)).isEmpty();
            assertThat(controller.getForecastsForDate("2026-10-11", pro)).isEmpty();
            for (String refused : List.of("2026-10-15", "2026-10-16", "2026-10-10", "2026-10-1",
                    "tomorrow", "")) {
                assertThatThrownBy(() -> controller.getForecastsForDate(refused, pro))
                        .as(refused)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("date must be between T-4 and yesterday");
            }
        }

        private LocationEvaluationView view(LocalDate date, TargetType type, int rating) {
            return new LocationEvaluationView(10L, "Hartside", 1L, REGION_NAME, date, type,
                    LocationEvaluationView.Source.CACHED_EVALUATION, rating, "cache", 60, 55, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    // ── the rules a past-window chip depends on, with the real resolver over mocked stores ──────

    @Nested
    @DisplayName("the passed-slot rules, with the real resolver")
    class WithTheRealResolver {

        @Mock
        private BriefingEvaluationService briefingEvaluationService;
        @Mock
        private ForecastRunDispositionRepository dispositionRepository;
        @Mock
        private PipelineRunRepository pipelineRunRepository;

        private ForecastController realController() {
            // The controller's rich-row repository and the resolver's scored-row pool are one store.
            return controller(new AsOfEventResolver(evaluationViewService, briefingEvaluationService,
                    repository, dispositionRepository, pipelineRunRepository));
        }

        private void yesterdaySunsetPassedFor(LocationEntity... places) {
            for (LocationEntity place : places) {
                eventAt(place, YESTERDAY, TargetType.SUNSET, YESTERDAY_SUNSET);
            }
        }

        private void pool(ForecastEvaluationEntity... rows) {
            when(repository.findScoredByEventInstant(anyCollection(), eq(YESTERDAY), eq(TargetType.SUNSET), any()))
                    .thenReturn(List.of(rows));
        }

        private void liveResults(Map<String, BriefingEvaluationResult> byName) {
            when(evaluationViewService.getLiveScoresForEnrichmentBulk(YESTERDAY, YESTERDAY,
                    Set.of(TargetType.SUNSET)))
                    .thenReturn(Map.of(REGION_NAME + "|" + YESTERDAY + "|" + TargetType.SUNSET, byName));
        }

        @Test
        @DisplayName("a batch that landed AFTER the event cannot repaint the window: the latest row is not "
                + "served, the row scored by the event is")
        void lateLandingRowIsHidden() {
            roster(sky);
            yesterdaySunsetPassedFor(sky);
            ForecastEvaluationEntity lateLanding = row(99L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 16, 0), Instant.parse("2026-10-15T03:00:00Z"), 5);
            ForecastEvaluationEntity seenAtTheEvent = row(2L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 9, 0), Instant.parse("2026-10-14T09:20:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), lateLanding);
            pool(seenAtTheEvent);
            mapperEchoesEntities();

            List<ForecastListDto> out = realController().getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(2L);
            assertThat(out.get(0).rating()).isEqualTo(3);
        }

        @Test
        @DisplayName("a row retracted by a stability skip recorded before the event is hidden - the slot "
                + "reads unscored, and the post-event cache entry does not resurrect it")
        void rowRetractedBeforeTheEventIsHidden() {
            roster(sky);
            yesterdaySunsetPassedFor(sky);
            ForecastEvaluationEntity retracted = row(2L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 9, 0), Instant.parse("2026-10-14T09:20:00Z"), 4);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), retracted);
            pool(retracted);
            when(dispositionRepository.findStabilitySkipInstantsUpTo(anyCollection(), eq(YESTERDAY),
                    eq("SUNSET"), any()))
                    .thenReturn(java.util.Collections.singletonList(
                            new Object[] {"Hartside", Instant.parse("2026-10-14T13:00:00Z")}));
            when(evaluationViewService.cachedOnlyViewsForDateRange(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new LocationEvaluationView(10L, "Hartside", 1L, REGION_NAME,
                            YESTERDAY, TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION,
                            5, "post-event", 60, 55, null, null, null, null, null, null, null, null, null)));
            mapperEchoesEntities();

            List<ForecastListDto> out = realController().getForecasts(pro);

            assertThat(out).isEmpty();
            verify(dtoMapper, never()).toSparseListDto(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a canopy place's captured composite star is served over the sky row's own")
        void canopyCapturedStarIsServed() {
            roster(canopy);
            yesterdaySunsetPassedFor(canopy);
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 13, 5), Instant.parse("2026-10-14T13:30:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), skyRow);
            pool(skyRow);
            liveResults(Map.of("Hexham Wood", served("Hexham Wood", 5, cycle.plusSeconds(1500), cycle)));
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION_NAME, YESTERDAY, TargetType.SUNSET))
                    .thenReturn(java.util.Optional.of(Instant.parse("2026-10-14T13:31:00Z")));
            mapperEchoesEntities();

            List<ForecastListDto> out = realController().getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(7L);
            assertThat(out.get(0).rating()).as("the composite the reader saw, not the sky row's 3").isEqualTo(5);
            assertThat(skyRow.getRating()).isEqualTo(3);
        }

        @Test
        @DisplayName("a canopy place whose region entry was rewritten after the event is served UNSCORED, "
                + "never with the sky-only star")
        void canopyWithUnrecoverableCompositeIsServedUnscored() {
            roster(canopy);
            yesterdaySunsetPassedFor(canopy);
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            ForecastEvaluationEntity skyRow = row(7L, canopy, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 13, 5), Instant.parse("2026-10-14T13:30:00Z"), 3);
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5), skyRow);
            pool(skyRow);
            liveResults(Map.of("Hexham Wood", served("Hexham Wood", 5, cycle.plusSeconds(1500), cycle)));
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION_NAME, YESTERDAY, TargetType.SUNSET))
                    .thenReturn(java.util.Optional.of(YESTERDAY_SUNSET_AT.plusSeconds(3600)));
            mapperEchoesEntities();

            List<ForecastListDto> out = realController().getForecasts(pro);

            assertThat(out).extracting(ForecastListDto::id).containsExactly(7L);
            assertThat(out.get(0).rating()).isNull();
        }

        @Test
        @DisplayName("a sky place served from a result that has no scored row is a sparse row rated the "
                + "served star")
        void servedResultWithoutRow() {
            roster(sky);
            yesterdaySunsetPassedFor(sky);
            Instant cycle = Instant.parse("2026-10-14T13:05:00Z");
            latestRows(TODAY.minusDays(2), TODAY.plusDays(5));
            pool();
            liveResults(Map.of("Hartside", served("Hartside", 4, cycle.plusSeconds(1500), cycle)));
            ForecastListDto sparse = dtoOf(row(5L, sky, YESTERDAY, TargetType.SUNSET,
                    LocalDateTime.of(2026, 10, 14, 13, 5), null, 4));
            when(dtoMapper.toSparseListDto(any(LocationEvaluationView.class), eq(sky), eq(false)))
                    .thenReturn(sparse);
            mapperEchoesEntities();

            List<ForecastListDto> out = realController().getForecasts(pro);

            assertThat(out).containsExactly(sparse);
            ArgumentCaptor<LocationEvaluationView> view = ArgumentCaptor.forClass(LocationEvaluationView.class);
            verify(dtoMapper).toSparseListDto(view.capture(), eq(sky), eq(false));
            assertThat(view.getValue().rating()).isEqualTo(4);
            assertThat(view.getValue().evaluatedAt()).isEqualTo(cycle);
        }
    }
}
