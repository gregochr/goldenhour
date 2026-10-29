package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.service.evaluation.BluebellGlossService;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import com.gregochr.goldenhour.service.evaluation.BriefingGlossService;
import com.gregochr.goldenhour.util.Rewind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The served-briefing memo (performance plan P1b): its key (cache identity, UK civil date, serve
 * epoch, 30 s), the epoch-before-assembly rule, the Rewind bypass, and that the memo holds the
 * pre-shaping result. The assembler is a counting stub, so "assembled" is observable.
 */
class BriefingServiceServedMemoTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    private ServedBriefingEpoch epoch;
    private MutableClock clock;
    private ServedBriefingAssembler assembler;
    private AtomicInteger assemblies;
    private BriefingService service;

    @BeforeEach
    void setUp() {
        epoch = new ServedBriefingEpoch();
        clock = new MutableClock(T0);
        assembler = mock(ServedBriefingAssembler.class);
        assemblies = new AtomicInteger();
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            assemblies.incrementAndGet();
            return briefing("assembly-" + assemblies.get());
        });
        service = newService();
        setCache(briefing("built-1"));
    }

    @AfterEach
    void clearRewind() {
        Rewind.clear();
    }

    @Test
    @DisplayName("a second call within 30 s reuses the assembly: the assembler runs once")
    void hitWithin30Seconds_assemblesOnce() {
        DailyBriefingResponse first = service.getCachedBriefingForApi();
        clock.advance(Duration.ofSeconds(29));
        DailyBriefingResponse second = service.getCachedBriefingForApi();

        assertThat(second).isSameAs(first);
        assertThat(assemblies).hasValue(1);
    }

    @Test
    @DisplayName("a call 30 s or more after the build assembles again")
    void missAfter30Seconds_assemblesAgain() {
        service.getCachedBriefingForApi();
        clock.advance(Duration.ofSeconds(30));
        service.getCachedBriefingForApi();

        assertThat(assemblies).hasValue(2);
    }

    @Test
    @DisplayName("a clock that has gone backwards never counts as fresh")
    void clockGoingBackwards_misses() {
        service.getCachedBriefingForApi();
        clock.advance(Duration.ofSeconds(-5));
        service.getCachedBriefingForApi();

        assertThat(assemblies).hasValue(2);
    }

    @Test
    @DisplayName("a new cache object (a new build) misses")
    void newCacheObject_misses() {
        service.getCachedBriefingForApi();
        setCache(briefing("built-2"));
        service.getCachedBriefingForApi();

        assertThat(assemblies).hasValue(2);
    }

    @Test
    @DisplayName("the UK civil date rolling over misses even inside 30 s")
    void dateRoll_misses() {
        // 23:59:50 UTC on 28 Oct is 23:59:50 GMT (BST ended 25 Oct 2026); +20 s is 29 Oct.
        clock.set(Instant.parse("2026-10-28T23:59:50Z"));
        service.getCachedBriefingForApi();
        clock.advance(Duration.ofSeconds(20));
        service.getCachedBriefingForApi();

        assertThat(assemblies).hasValue(2);
    }

    @Test
    @DisplayName("a bumped epoch misses")
    void epochBump_misses() {
        service.getCachedBriefingForApi();
        epoch.bump();
        service.getCachedBriefingForApi();

        assertThat(assemblies).hasValue(2);
    }

    @Test
    @DisplayName("the epoch is read BEFORE assembly: a bump during assembly leaves the result stale-stamped")
    void bumpDuringAssembly_nextCallRebuilds() {
        AtomicInteger calls = new AtomicInteger();
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                epoch.bump();
            }
            return briefing("assembly-" + calls.get());
        });

        service.getCachedBriefingForApi();
        service.getCachedBriefingForApi();
        service.getCachedBriefingForApi();

        // 1st call: built across a write, stamped with the old epoch. 2nd: rebuilds (and is stamped
        // with the new epoch). 3rd: hits.
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("Rewind always assembles and never stores a memo")
    void rewindActive_alwaysAssembles() {
        Rewind.set(T0.minus(Duration.ofDays(2)));
        service.getCachedBriefingForApi();
        service.getCachedBriefingForApi();
        Rewind.clear();

        assertThat(assemblies).hasValue(2);
        // The live call afterwards is a first call, not a hit on a rewound result.
        service.getCachedBriefingForApi();
        assertThat(assemblies).hasValue(3);
    }

    @Test
    @DisplayName("a bump inside a transaction lands only on commit; a rollback never bumps")
    void bumpInTransaction_afterCommitOnly() {
        long before = epoch.current();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            epoch.bump();
            assertThat(epoch.current()).isEqualTo(before);
            // Rolled back: the synchronisations are discarded without afterCommit running.
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(epoch.current()).isEqualTo(before);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            epoch.bump();
            assertThat(epoch.current()).isEqualTo(before);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("with no transaction the bump is immediate")
    void bumpOutsideTransaction_immediate() {
        long before = epoch.current();
        epoch.bump();
        assertThat(epoch.current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("the unfiltered internal getCachedBriefing() is not memoised")
    void internalAccessor_notMemoised() {
        DailyBriefingResponse first = service.getCachedBriefing();
        setCache(briefing("built-2"));
        DailyBriefingResponse second = service.getCachedBriefing();

        assertThat(first.headline()).isEqualTo("built-1");
        assertThat(second.headline()).isEqualTo("built-2");
    }

    @Test
    @DisplayName("the memo holds the pre-shaping result: every caller within 30 s gets the one unshaped object")
    void memoIsPreShaping_sameObjectForEveryCaller() {
        // The controller shapes (BriefingRoleShaper, LitePickShaper) per request on whatever this
        // returns. Two callers within 30 s get the SAME unshaped object, so no shaping happened
        // upstream of the memo; roleShapingStaysPerRequest below drives the per-role output.
        DailyBriefingResponse forLite = service.getCachedBriefingForApi();
        DailyBriefingResponse forPro = service.getCachedBriefingForApi();

        assertThat(forPro).isSameAs(forLite);
        assertThat(forLite.allowedLocationIds()).isNull();
    }

    @Test
    @DisplayName("role shaping stays per request: a Lite and a Pro request within 30 s are shaped differently "
            + "from the one memoised result")
    void roleShapingStaysPerRequest() {
        com.gregochr.goldenhour.model.BestBet bet = new com.gregochr.goldenhour.model.BestBet(1,
                "Go west", "Clear", "Wed sunset", "Lake District",
                com.gregochr.goldenhour.model.Confidence.HIGH, null, "Wednesday", "SUNSET", "18:30",
                null, null);
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            assemblies.incrementAndGet();
            return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 28, 9, 0), "h", List.of(),
                    List.of(bet), null, null, false, false, 0, "Opus", List.of(), List.of(),
                    com.gregochr.goldenhour.model.BestBetStatus.SUCCESS_WITH_PICKS, true);
        });
        assemblies.set(0); // re-stubbing above ran the previous stub's answer once
        LitePickShaper litePickShaper = mock(LitePickShaper.class);
        when(litePickShaper.scopeFor(any())).thenReturn(PickScope.unrestricted());
        when(litePickShaper.shapeBriefing(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        com.gregochr.goldenhour.controller.BriefingController controller =
                new com.gregochr.goldenhour.controller.BriefingController(service,
                        mock(com.gregochr.goldenhour.service.BriefingModelTestService.class),
                        mock(BriefingDigestService.class), new BriefingRoleShaper(), litePickShaper);

        DailyBriefingResponse lite = controller.getBriefing(auth("ROLE_LITE_USER")).getBody();
        DailyBriefingResponse pro = controller.getBriefing(auth("ROLE_PRO_USER")).getBody();

        assertThat(assemblies).hasValue(1);
        assertThat(lite.bestBets()).isEmpty();
        assertThat(pro.bestBets()).hasSize(1);
    }

    private static org.springframework.security.core.Authentication auth(String role) {
        return new org.springframework.security.authentication.TestingAuthenticationToken(
                "user", "pw", role);
    }

    @Test
    @DisplayName("two concurrent misses for the same epoch share one assembly (single-flight)")
    void concurrentMissesSameEpoch_assembleOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return briefing("shared");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DailyBriefingResponse> first = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<DailyBriefingResponse> second = pool.submit(() -> service.getCachedBriefingForApi());
            Thread.sleep(200);
            release.countDown();

            assertThat(second.get(10, TimeUnit.SECONDS)).isSameAs(first.get(10, TimeUnit.SECONDS));
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a caller for a newer epoch is not blocked behind an older build, and the older result "
            + "never replaces the newer one")
    void newerEpochNotBlockedByOlderBuild() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return briefing("assembly-" + n);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DailyBriefingResponse> older = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            epoch.bump();

            DailyBriefingResponse newer = service.getCachedBriefingForApi();

            assertThat(newer.headline()).isEqualTo("assembly-2");
            release.countDown();
            older.get(10, TimeUnit.SECONDS);
            // The older build finished last but must not have replaced the newer memo: this is a hit.
            assertThat(service.getCachedBriefingForApi()).isSameAs(newer);
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a Rewind call never touches the memo or waits on a running build")
    void rewindNeverTouchesMemoOrLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(assembler.assembleForPlan(any(), anyDouble())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return briefing("assembly-" + n);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DailyBriefingResponse> live = pool.submit(() -> service.getCachedBriefingForApi());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            Rewind.set(T0.minus(Duration.ofDays(1)));
            DailyBriefingResponse rewound;
            try {
                rewound = service.getCachedBriefingForApi();
            } finally {
                Rewind.clear();
            }

            assertThat(rewound.headline()).isEqualTo("assembly-2");
            assertThat(ReflectionTestUtils.getField(service, "servedMemo")).isNull();
            release.countDown();
            live.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void setCache(DailyBriefingResponse response) {
        @SuppressWarnings("unchecked")
        AtomicReference<DailyBriefingResponse> ref =
                (AtomicReference<DailyBriefingResponse>) ReflectionTestUtils.getField(service, "cache");
        ref.set(response);
    }

    private static DailyBriefingResponse briefing(String headline) {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 28, 9, 0), headline, List.of(),
                List.of(), null, null, false, false, 0, "Opus", List.of(), List.of());
    }

    private BriefingService newService() {
        return new BriefingService(
                mock(LocationService.class), mock(BriefingWeatherLoader.class),
                mock(JobRunService.class), mock(DailyBriefingCacheRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                mock(BriefingHeadlineGenerator.class), mock(BriefingBestBetAdvisor.class),
                mock(BriefingGlossService.class), mock(BluebellGlossService.class),
                mock(BriefingAuroraSummaryBuilder.class), mock(BriefingHierarchyBuilder.class),
                mock(BriefingSlotBuilder.class), mock(ApplicationEventPublisher.class),
                mock(HotTopicAggregator.class), mock(EvaluationViewService.class),
                mock(SeasonalWindow.class), mock(NlcClarityService.class),
                mock(MeteorClarityService.class), mock(SurgeCurveService.class), clock,
                mock(MarineWaveRefreshService.class), mock(BriefingRegionSnapshotService.class),
                assembler, mock(BriefingRegionEvaluationRollup.class), mock(EclipseSightAssembler.class),
                epoch);
    }

    /** A settable clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
