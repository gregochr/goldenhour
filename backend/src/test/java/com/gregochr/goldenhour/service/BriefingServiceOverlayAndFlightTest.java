package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import com.gregochr.goldenhour.service.evaluation.BriefingGlossService;
import com.gregochr.goldenhour.service.evaluation.BluebellGlossService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The live-overlay step of {@link BriefingService#getCachedBriefing()} (hot topics, aurora, the simulated
 * eclipse), the raw cache accessors, and what concurrent served-briefing callers see when the build they join
 * fails. The cache is filled the way production fills it after a restart: from the persisted payload.
 */
class BriefingServiceOverlayAndFlightTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** A clock the test can move, to roll the UK date over while a build is running. */
    private static final class MovableClock extends Clock {
        private volatile Instant now = NOW;

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private BriefingAuroraSummaryBuilder auroraSummaryBuilder;
    private HotTopicAggregator hotTopicAggregator;
    private EclipseSightAssembler eclipseSightAssembler;
    private ServedBriefingAssembler assembler;
    private DailyBriefingCacheRepository briefingCacheRepository;
    private BriefingService service;

    @BeforeEach
    void setUp() {
        auroraSummaryBuilder = mock(BriefingAuroraSummaryBuilder.class);
        hotTopicAggregator = mock(HotTopicAggregator.class);
        eclipseSightAssembler = mock(EclipseSightAssembler.class);
        assembler = mock(ServedBriefingAssembler.class);
        briefingCacheRepository = mock(DailyBriefingCacheRepository.class);
        service = new BriefingService(
                mock(LocationService.class), mock(BriefingWeatherLoader.class),
                mock(JobRunService.class), briefingCacheRepository, MAPPER,
                mock(BriefingHeadlineGenerator.class), mock(BriefingBestBetAdvisor.class),
                mock(BriefingGlossService.class), mock(BluebellGlossService.class),
                auroraSummaryBuilder, mock(BriefingHierarchyBuilder.class),
                mock(BriefingSlotBuilder.class), mock(ApplicationEventPublisher.class),
                hotTopicAggregator, mock(EvaluationViewService.class),
                mock(SeasonalWindow.class), mock(NlcClarityService.class),
                mock(MeteorClarityService.class), mock(SurgeCurveService.class), clock,
                mock(MarineWaveRefreshService.class), mock(BriefingRegionSnapshotService.class),
                assembler, mock(BriefingRegionEvaluationRollup.class), eclipseSightAssembler,
                new ServedBriefingEpoch());
    }

    private static HotTopic topic() {
        return new HotTopic("DUST", "Dust", "d", TODAY, 2, null, List.of(), null, null);
    }

    private static BriefingSlot slot(String name) {
        return new BriefingSlot(name, LocalDateTime.of(2026, 10, 28, 6, 30), Verdict.GO, null,
                BriefingSlot.TideInfo.NONE, List.of());
    }

    private static BriefingRegion region(BriefingSlot... slots) {
        return new BriefingRegion("Northumberland", Verdict.GO, "Clear.", List.of(), List.of(slots),
                null, null, null, null, null, null, DisplayVerdict.WORTH_IT, 0);
    }

    private static DailyBriefingResponse briefing(String headline, List<HotTopic> topics, List<BriefingDay> days) {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 28, 9, 0), headline, days,
                List.of(), null, null, false, "Opus", topics, List.of());
    }

    private void persisted(DailyBriefingResponse response) throws Exception {
        DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
        entity.setId(1);
        entity.setGeneratedAt(response.generatedAt());
        entity.setPayload(MAPPER.writeValueAsString(response));
        when(briefingCacheRepository.findById(1)).thenReturn(Optional.of(entity));
        service.loadPersistedBriefing();
    }

    private void liveTopics(List<HotTopic> topics) {
        when(hotTopicAggregator.getHotTopics(TODAY, PlanHorizon.lastPlanDate(TODAY))).thenReturn(topics);
    }

    // ── raw cache accessors ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the raw cache accessors report the persisted build's days and stamp, and null before any build")
    void rawAccessors_reflectTheCache() throws Exception {
        assertThat(service.getCachedDays()).isNull();
        assertThat(service.getCachedGeneratedAt()).isNull();

        BriefingDay day = new BriefingDay(TODAY, List.of());
        persisted(briefing("built", List.of(), List.of(day)));

        assertThat(service.getCachedDays()).hasSize(1);
        assertThat(service.getCachedDays().get(0).date()).isEqualTo(TODAY);
        assertThat(service.getCachedGeneratedAt()).isEqualTo(LocalDateTime.of(2026, 10, 28, 9, 0));
    }

    // ── live overlays ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a hot-topic aggregator that answers null counts as no topics: an unchanged briefing is served as is")
    void nullLiveTopics_areNoTopics_andTheCachedBriefingIsServedAsIs() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(null);

        DailyBriefingResponse served = service.getCachedBriefing();

        assertThat(served.headline()).isEqualTo("built");
        assertThat(served.hotTopics()).isEmpty();
    }

    @Test
    @DisplayName("live hot topics that differ from the cached ones replace them in the served briefing")
    void differingLiveTopics_replaceTheCachedOnes() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(List.of(topic()));

        DailyBriefingResponse served = service.getCachedBriefing();

        assertThat(served.hotTopics()).extracting(HotTopic::type).containsExactly("DUST");
        assertThat(served.headline()).isEqualTo("built");
    }

    @Test
    @DisplayName("a failure building the live aurora overlay still serves the cached briefing")
    void auroraOverlayFailure_servesTheCachedBriefing() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        when(auroraSummaryBuilder.buildAuroraTonightCached()).thenThrow(new IllegalStateException("aurora down"));

        DailyBriefingResponse served = service.getCachedBriefing();

        assertThat(served.headline()).isEqualTo("built");
        assertThat(served.hotTopics()).isEmpty();
    }

    @Test
    @DisplayName("an active lunar-eclipse simulation attaches the simulated sight to sight-less slots of the "
            + "matching window only")
    void simulatedEclipse_attachesToSightlessSlotsOfTheMatchingWindow() throws Exception {
        BriefingEventSummary sunrise = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region(slot("Bamburgh"))), List.of(slot("Orphan")));
        BriefingEventSummary sunset = new BriefingEventSummary(TargetType.SUNSET,
                List.of(region(slot("Craster"))), List.of());
        persisted(briefing("built", List.of(),
                List.of(new BriefingDay(TODAY, List.of(sunrise, sunset)))));
        liveTopics(List.of());
        BriefingSlot.EclipseSight sight = new BriefingSlot.EclipseSight("LUNAR_ECLIPSE", 8, 241, "WSW",
                LocalDateTime.of(2026, 10, 28, 5, 12), LocalDateTime.of(2026, 10, 28, 4, 33),
                LocalDateTime.of(2026, 10, 28, 6, 52), null, null, false, false, "DAWN", List.of());
        when(eclipseSightAssembler.isSimulationActiveForLunarEclipse()).thenReturn(true);
        when(eclipseSightAssembler.simulatedSightFor(TODAY, TargetType.SUNRISE)).thenReturn(sight);
        when(eclipseSightAssembler.simulatedSightFor(TODAY, TargetType.SUNSET)).thenReturn(null);

        DailyBriefingResponse served = service.getCachedBriefing();

        List<BriefingEventSummary> events = served.days().get(0).eventSummaries();
        assertThat(events.get(0).regions().get(0).slots().get(0).eclipse()).isNotNull();
        assertThat(events.get(0).regions().get(0).slots().get(0).eclipse().type()).isEqualTo("LUNAR_ECLIPSE");
        assertThat(events.get(0).unregioned().get(0).eclipse()).isNotNull();
        assertThat(events.get(1).regions().get(0).slots().get(0).eclipse()).isNull();
    }

    @Test
    @DisplayName("a slot that already carries a real sight keeps it when the simulation is active")
    void simulatedEclipse_neverOverwritesARealSight() throws Exception {
        BriefingSlot.EclipseSight real = new BriefingSlot.EclipseSight("LUNAR_ECLIPSE", 5, 250, "WSW",
                LocalDateTime.of(2026, 10, 28, 5, 0), LocalDateTime.of(2026, 10, 28, 4, 0),
                LocalDateTime.of(2026, 10, 28, 6, 0), null, null, true, false, null, List.of());
        BriefingSlot withReal = slot("Bamburgh").withEclipse(real);
        BriefingEventSummary sunrise = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region(withReal)), List.of());
        persisted(briefing("built", List.of(), List.of(new BriefingDay(TODAY, List.of(sunrise)))));
        liveTopics(List.of());
        BriefingSlot.EclipseSight simulated = new BriefingSlot.EclipseSight("LUNAR_ECLIPSE", 8, 241, "WSW",
                LocalDateTime.of(2026, 10, 28, 5, 12), LocalDateTime.of(2026, 10, 28, 4, 33),
                LocalDateTime.of(2026, 10, 28, 6, 52), null, null, false, false, "DAWN", List.of());
        when(eclipseSightAssembler.isSimulationActiveForLunarEclipse()).thenReturn(true);
        when(eclipseSightAssembler.simulatedSightFor(TODAY, TargetType.SUNRISE)).thenReturn(simulated);

        DailyBriefingResponse served = service.getCachedBriefing();

        BriefingSlot kept = served.days().get(0).eventSummaries().get(0).regions().get(0).slots().get(0);
        assertThat(kept.eclipse().moonAltAtMax()).isEqualTo(5);
    }

    // ── served-briefing builds that fail ─────────────────────────────────────────

    private DailyBriefingResponse servedSource(DailyBriefingResponse built) {
        return argThat(arg -> arg != null && built.headline().equals(arg.headline()));
    }

    private Throwable failureSeenBy(Throwable buildFailure) throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(assembler.assembleForPlan(servedSource(briefing("built", List.of(), List.of())), eq(0.0)))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    if (buildFailure instanceof Error error) {
                        throw error;
                    }
                    throw (RuntimeException) buildFailure;
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> joinerThread = new AtomicReference<>();
        try {
            Future<DailyBriefingResponse> builder = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<DailyBriefingResponse> joiner = pool.submit(() -> {
                joinerThread.set(Thread.currentThread());
                return service.getCachedBriefingForApi();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((joinerThread.get() == null || joinerThread.get().getState() != Thread.State.WAITING)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            release.countDown();

            Throwable builderSaw = null;
            try {
                builder.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                builderSaw = e.getCause();
            }
            assertThat(builderSaw).as("the builder sees the failure itself").isSameAs(buildFailure);
            try {
                joiner.get(10, TimeUnit.SECONDS);
                throw new AssertionError("the joiner should have failed");
            } catch (java.util.concurrent.ExecutionException e) {
                return e.getCause();
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a caller joining a build that fails with a RuntimeException sees that very exception")
    void joinerOfAFailedBuild_seesTheRuntimeException() throws Exception {
        IllegalStateException boom = new IllegalStateException("assembly exploded");

        Throwable seen = failureSeenBy(boom);

        assertThat(seen).isSameAs(boom);
    }

    @Test
    @DisplayName("a caller joining a build that fails with an Error sees a CompletionException wrapping it")
    void joinerOfAnErroredBuild_seesTheErrorWrapped() throws Exception {
        AssertionError boom = new AssertionError("assembly errored");

        Throwable seen = failureSeenBy(boom);

        assertThat(seen).isInstanceOf(CompletionException.class).hasCause(boom);
    }

    @Test
    @DisplayName("a failed build is not memoised: the next call builds again")
    void failedBuild_isNotMemoised() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(List.of());
        DailyBriefingResponse good = briefing("assembled", List.of(), List.of());
        when(assembler.assembleForPlan(servedSource(briefing("built", List.of(), List.of())), eq(0.0)))
                .thenThrow(new IllegalStateException("first build fails"))
                .thenReturn(good);

        assertThatThrownBy(() -> service.getCachedBriefingForApi()).isInstanceOf(IllegalStateException.class);
        assertThat(service.getCachedBriefingForApi()).isSameAs(good);
    }

    @Test
    @DisplayName("a caller whose cached briefing was rebuilt meanwhile does not wait on the build of the old one")
    void rebuiltBriefing_doesNotJoinTheOlderBuild() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(assembler.assembleForPlan(servedSource(briefing("built", List.of(), List.of())), eq(0.0)))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return briefing("old-assembly", List.of(), List.of());
                });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DailyBriefingResponse> older = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            persisted(briefing("rebuilt", List.of(), List.of()));
            when(assembler.assembleForPlan(servedSource(briefing("rebuilt", List.of(), List.of())), eq(0.0)))
                    .thenReturn(briefing("new-assembly", List.of(), List.of()));

            DailyBriefingResponse newer = service.getCachedBriefingForApi();

            assertThat(newer.headline()).isEqualTo("new-assembly");
            release.countDown();
            assertThat(older.get(10, TimeUnit.SECONDS).headline()).isEqualTo("old-assembly");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a caller on the next UK date does not wait on a build started the day before")
    void nextUkDate_doesNotJoinTheOlderBuild() throws Exception {
        persisted(briefing("built", List.of(), List.of()));
        liveTopics(List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(assembler.assembleForPlan(servedSource(briefing("built", List.of(), List.of())), eq(0.0)))
                .thenAnswer(inv -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                    }
                    return briefing("assembly-" + n, List.of(), List.of());
                });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DailyBriefingResponse> older = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            clock.set(NOW.plus(java.time.Duration.ofDays(1)));

            DailyBriefingResponse nextDay = service.getCachedBriefingForApi();

            assertThat(nextDay.headline()).isEqualTo("assembly-2");
            release.countDown();
            assertThat(older.get(10, TimeUnit.SECONDS).headline()).isEqualTo("assembly-1");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
