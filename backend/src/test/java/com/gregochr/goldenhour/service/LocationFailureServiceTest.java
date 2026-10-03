package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.model.CyclePlaceEvidence;
import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.service.notification.AdminAlertService.DisabledLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationFailureService}, all driven through {@code settleCycle} with a
 * stubbed resolver: the like-evidence counting rule, the threshold, the per-cycle cap, idempotence
 * and the after-commit alert, on literal evidence and a fixed clock.
 */
@ExtendWith(MockitoExtension.class)
class LocationFailureServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 2, 3, 0);
    private static final Instant TRIGGER = Instant.parse("2026-10-02T01:00:00Z");
    private static final long RUN_ID = 300L;
    private static final String FULL_MODE_LOG =
            "Pipeline run 300 (triggered 2026-10-02T01:00:00Z): location failure settle mode FULL: "
                    + "settled at its own tail, newer than every settled cycle";
    private static final String COLLECTION_REASON =
            "Auto-disabled after 3 consecutive failed scheduled runs "
                    + "(last 2026-10-02: data could not be collected).";
    private static final String EVALUATION_REASON =
            "Auto-disabled after 3 consecutive failed scheduled runs "
                    + "(last 2026-10-02: the Claude evaluation request failed).";

    @Mock
    private CycleLocationOutcomeResolver resolver;

    @Mock
    private AdminAlertService adminAlertService;

    @Mock
    private com.gregochr.goldenhour.repository.ForecastBatchRepository forecastBatchRepository;

    /** Records transaction outcomes and can be told to fail its commit. */
    private static final class FakeTransactionManager implements PlatformTransactionManager {
        private final java.util.concurrent.atomic.AtomicInteger commits =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger rollbacks =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean failCommit;

        @Override
        public TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("commit failed");
            }
            commits.incrementAndGet();
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    private final FakeTransactionManager transactionManager = new FakeTransactionManager();

    /**
     * A repository whose counter methods behave like the database (increment, then read back), so
     * the tests can state the count a place had before the cycle and assert on what is written.
     * Everything else is an ordinary Mockito mock: stubs and verifications work as usual.
     */
    private LocationRepository locationRepository;

    /**
     * A pipeline-run repository whose claim and newest-settled behave like the database: a claim
     * succeeds once per run, and the newest settled trigger is the maximum over claimed runs.
     */
    private PipelineRunRepository pipelineRunRepository;

    /** Claimed runs and their trigger times; the fake's stand-in for {@code failures_settled_at}. */
    private final Map<Long, Instant> settledRuns = new java.util.concurrent.ConcurrentHashMap<>();

    /** Trigger time of every run {@link #run} has built, by id. */
    private final Map<Long, Instant> knownTriggers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Stored consecutive failure counts, by location id; seeded by {@link #place}. */
    private final Map<Long, Integer> storedCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private LocationFailureService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        locationRepository = Mockito.mock(LocationRepository.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "recordFailure" -> {
                    storedCounts.merge((Long) invocation.getArgument(0), 1, Integer::sum);
                    return 1;
                }
                case "resetFailureCounts" -> {
                    int reset = 0;
                    for (Object id : (java.util.Collection<?>) invocation.getArgument(0)) {
                        if (storedCounts.getOrDefault((Long) id, 0) > 0) {
                            storedCounts.put((Long) id, 0);
                            reset++;
                        }
                    }
                    return reset;
                }
                case "findConsecutiveFailuresById" -> {
                    return storedCounts.get((Long) invocation.getArgument(0));
                }
                default -> {
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
                }
            }
        });
        pipelineRunRepository = Mockito.mock(PipelineRunRepository.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "claimFailureSettle" -> {
                    Long id = invocation.getArgument(0);
                    return settledRuns.putIfAbsent(id, knownTriggers.getOrDefault(id, TRIGGER))
                            == null ? 1 : 0;
                }
                case "findNewestSettledTriggerTime" -> {
                    return settledRuns.values().stream().max(Instant::compareTo).orElse(null);
                }
                default -> {
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
                }
            }
        });
        service = new LocationFailureService(resolver, locationRepository, adminAlertService,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager, pipelineRunRepository,
                forecastBatchRepository);
        serviceLogger = (Logger) LoggerFactory.getLogger(LocationFailureService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private LocationEntity place(long id, String name, Integer failures, boolean enabled) {
        storedCounts.put(id, failures == null ? 0 : failures);
        return LocationEntity.builder().id(id).name(name).lat(54.0).lon(-1.0)
                .enabled(enabled).consecutiveFailures(failures).build();
    }

    private PipelineRunEntity run(long id, CycleType type) {
        return run(id, type, TRIGGER);
    }

    private PipelineRunEntity run(long id, CycleType type, Instant trigger) {
        PipelineRunEntity run = new PipelineRunEntity(type, trigger);
        run.setId(id);
        knownTriggers.put(id, trigger);
        return run;
    }

    private static Map<Long, CyclePlaceEvidence> cycle() {
        return new LinkedHashMap<>();
    }

    /** Ids {@code first..last} inclusive, all with the same evidence, appended in order. */
    private static void put(Map<Long, CyclePlaceEvidence> evidence, long first, long last,
            CyclePlaceEvidence value) {
        for (long id = first; id <= last; id++) {
            evidence.put(id, value);
        }
    }

    private static List<Long> ids(long first, long last) {
        List<Long> ids = new ArrayList<>();
        for (long id = first; id <= last; id++) {
            ids.add(id);
        }
        return ids;
    }

    private void settle(Map<Long, CyclePlaceEvidence> evidence) {
        settle(evidence, CycleType.NIGHTLY);
    }

    private void settle(Map<Long, CyclePlaceEvidence> evidence, CycleType type) {
        when(resolver.resolve(RUN_ID)).thenReturn(evidence);
        service.settleCycle(run(RUN_ID, type));
    }

    private List<String> messages(Level level) {
        return logAppender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---- the like-evidence rule, sky lane ----

    @Test
    @DisplayName("1 sky failure among 10 sky results counts: the failed place goes 0 -> 1 with the "
            + "clock's time, and the 9 that scored are reset")
    void oneSkyFailureOfTen_counts() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verify(locationRepository).recordFailure(10L, NOW_UTC);
        verify(locationRepository).findConsecutiveFailuresById(10L);
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
        assertThat(messages(Level.INFO)).containsExactly(
                FULL_MODE_LOG,
                "Pipeline run 300: location 'Bamburgh' failed this cycle (EVALUATION), "
                        + "consecutive failures now 1");
    }

    @Test
    @DisplayName("6 sky failures among 10 sky results count for none: for each, only 4 of the 9 "
            + "other sky results succeeded. The 4 that scored are still reset")
    void sixSkyFailuresOfTen_countForNone() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 4, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 5, 10, CyclePlaceEvidence.failedIn(Lane.SKY));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 4));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
        assertThat(messages(Level.WARN)).containsExactly(
                "Pipeline run 300: 6 of 6 failed place(s) not counted, too few comparable places "
                        + "got through (same result lane for a Claude failure, same collection "
                        + "step for a collection error), treated as systemic");
    }

    @Test
    @DisplayName("exactly half of the other sky results succeeding counts (the rule is 'at least "
            + "half'): 5 succeeded and 6 failed leaves 5 of the 10 others for each failed place")
    void exactlyHalfOfTheOthers_counts() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 5, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 6, 11, CyclePlaceEvidence.failedIn(Lane.SKY));
        List<LocationEntity> failing = new ArrayList<>();
        for (long id = 6; id <= 11; id++) {
            failing.add(place(id, "P" + id, 0, true));
        }
        when(locationRepository.findAllById(ids(6, 11))).thenReturn(failing);

        settle(evidence);

        for (long id = 6; id <= 11; id++) {
            verify(locationRepository).recordFailure(id, NOW_UTC);
        }
    }

    @Test
    @DisplayName("one more failure than half counts for none: 4 succeeded and 7 failed leaves 4 of "
            + "the 10 others for each failed place")
    void justUnderHalfOfTheOthers_countsForNone() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 4, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 5, 11, CyclePlaceEvidence.failedIn(Lane.SKY));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 4));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a cycle that fails everything counts for nobody: 510 sky failures, none succeeded")
    void everythingFailed_countsForNobody() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 510, CyclePlaceEvidence.failedIn(Lane.SKY));

        settle(evidence);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a lone failed place with no other like place offers no evidence the pipeline "
            + "was working, so it is not counted")
    void loneFailedPlace_notCounted() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        evidence.put(1L, CyclePlaceEvidence.failedIn(Lane.SKY));
        evidence.put(2L, CyclePlaceEvidence.nothing());

        settle(evidence);

        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("a sky failure among 10 sky results of which 5 succeeded counts")
    void skyFailure_fiveOfTenSucceeded_counts() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 5, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 6, 10, CyclePlaceEvidence.failedIn(Lane.SKY));
        List<LocationEntity> failing = new ArrayList<>();
        for (long id = 6; id <= 10; id++) {
            failing.add(place(id, "P" + id, 0, true));
        }
        when(locationRepository.findAllById(ids(6, 10))).thenReturn(failing);

        settle(evidence);

        verify(locationRepository).recordFailure(6L, NOW_UTC);
    }

    @Test
    @DisplayName("the same sky failures where the 5 'successes' are triage-only places do not "
            + "count: triaged places never touched Claude, so they are no evidence about Claude")
    void skyFailure_successesAreTriageOnly_doesNotCount() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 5, CyclePlaceEvidence.triagedOnly());
        put(evidence, 6, 10, CyclePlaceEvidence.failedIn(Lane.SKY));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 5));
        verifyNoMoreInteractions(locationRepository);
    }

    // ---- a fault confined to one lane is never read as a good night ----

    @Test
    @DisplayName("a woodland parser regression (every wd- result fails) while 200 sky places "
            + "score and 50 are triaged counts nobody: woodland places are judged against woodland")
    void woodlandRegression_countsNobody() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 200, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 201, 250, CyclePlaceEvidence.triagedOnly());
        put(evidence, 251, 255, CyclePlaceEvidence.failedIn(Lane.WOODLAND));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 250));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("one lane's bucket expiring entirely (every bluebell result errored) counts "
            + "nobody, whatever the sky lane did")
    void oneLaneExpiresEntirely_countsNobody() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 100, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 101, 108, CyclePlaceEvidence.failedIn(Lane.BLUEBELL));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 100));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a woodland failure counts when most other woodland results succeeded")
    void woodlandFailure_mostWoodlandSucceeded_counts() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.WOODLAND));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.WOODLAND));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Wallington Woods", 0, true)));

        settle(evidence);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
    }

    @Test
    @DisplayName("a place failing in two lanes counts once, when either lane qualifies")
    void failureInTwoLanes_countsOnce() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 10, 14, CyclePlaceEvidence.failedIn(Lane.WOODLAND));
        evidence.put(15L, CyclePlaceEvidence.failedIn(Lane.SKY, Lane.WOODLAND));
        when(locationRepository.findAllById(List.of(15L)))
                .thenReturn(List.of(place(15L, "Alnwick", 1, true)));

        settle(evidence);

        verify(locationRepository, times(1)).recordFailure(15L, NOW_UTC);
    }

    // ---- collection errors ----

    @Test
    @DisplayName("a collection error among 10 places whose collection ran counts, and the stored "
            + "reason says 'data could not be collected', not that weather failed")
    void collectionError_counts_withNeutralWording() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.triagedOnly());
        evidence.put(10L, CyclePlaceEvidence.collectionError());
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, COLLECTION_REASON)).thenReturn(1);

        settle(evidence);

        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, COLLECTION_REASON);
        assertThat(messages(Level.INFO)).containsExactly(
                FULL_MODE_LOG,
                "Pipeline run 300: location 'Bamburgh' failed this cycle (COLLECTION), "
                        + "consecutive failures now 3");
    }

    @Test
    @DisplayName("collection errors for most places (6 of 10) count for none: the collection step "
            + "itself was failing, which is systemic")
    void mostCollectionsFailed_countsNobody() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 4, CyclePlaceEvidence.triagedOnly());
        put(evidence, 5, 10, CyclePlaceEvidence.collectionError());

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 4));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a place whose collection errored and whose sky result failed, in a cycle where "
            + "collection was mostly failing but the sky lane was healthy, counts as an "
            + "evaluation failure")
    void collectionPopulationBad_butSkyLaneGood_countsAsEvaluation() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 20, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 21, 50, CyclePlaceEvidence.collectionError());
        evidence.put(51L, new CyclePlaceEvidence(false, true, java.util.Set.of(),
                java.util.Set.of(Lane.SKY)));
        when(locationRepository.findAllById(List.of(51L)))
                .thenReturn(List.of(place(51L, "Alnwick", 2, true)));
        when(locationRepository.autoDisable(51L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);

        settle(evidence);

        verify(locationRepository).autoDisable(51L, 3, NOW_UTC, EVALUATION_REASON);
    }

    // ---- reset, untouched ----

    @Test
    @DisplayName("a place that got through (scored or triaged) is reset through the repository's "
            + "column-scoped reset")
    void gotThrough_resetsCounter() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        evidence.put(7L, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(8L, CyclePlaceEvidence.triagedOnly());

        settle(evidence);

        verify(locationRepository).resetFailureCounts(List.of(7L, 8L));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a place with a failure in one lane and a success in another got through, so it "
            + "is reset, not counted")
    void failureInOneLaneSuccessInAnother_gotThrough() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        evidence.put(7L, new CyclePlaceEvidence(false, false, java.util.Set.of(Lane.SKY),
                java.util.Set.of(Lane.BLUEBELL)));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(List.of(7L));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a place with nothing recorded this cycle is neither counted nor reset")
    void nothingRecorded_keepsItsCount() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        evidence.put(1L, CyclePlaceEvidence.nothing());
        evidence.put(2L, CyclePlaceEvidence.nothing());

        settle(evidence);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    // ---- threshold, cap, alert ----

    @Test
    @DisplayName("a place reaching 3 in an INTRADAY cycle is disabled with the literal reason and "
            + "the clock's time, and the alert carries that cycle's own type and trigger time")
    void reachingThree_disables_alertCarriesCycleTypeAndTriggerTime() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);

        settle(evidence, CycleType.INTRADAY);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON);
        verify(adminAlertService).sendLocationsAutoDisabledAlert(RUN_ID, CycleType.INTRADAY,
                TRIGGER, List.of(new DisabledLocation("Bamburgh", EVALUATION_REASON)));
        assertThat(messages(Level.WARN)).containsExactly(
                "Pipeline run 300: location 'Bamburgh' AUTO-DISABLED: " + EVALUATION_REASON);
    }

    @Test
    @DisplayName("a place reaching 2 is counted but not disabled and nobody is told")
    void reachingTwo_notDisabled() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 1, true)));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verify(locationRepository).recordFailure(10L, NOW_UTC);
        verify(locationRepository).findConsecutiveFailuresById(10L);
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("exactly 5 places reaching 3 in one cycle (the cap itself) are all disabled, "
            + "listed in name order whatever order the evidence arrived in")
    void fivePlacesReachingThree_allDisabled_alertListSortedByName() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 20, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 21, 25, CyclePlaceEvidence.failedIn(Lane.SKY));
        // Names deliberately out of id order, so only a real sort can produce the expected list.
        String[] names = {"Zeta", "Alpha", "Mid", "Beta", "Omega"};
        List<LocationEntity> failing = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = 21 + i;
            failing.add(place(id, names[i], 2, true));
            when(locationRepository.autoDisable(id, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);
        }
        when(locationRepository.findAllById(ids(21, 25))).thenReturn(failing);

        settle(evidence);

        verify(adminAlertService).sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY,
                TRIGGER, List.of(
                        new DisabledLocation("Alpha", EVALUATION_REASON),
                        new DisabledLocation("Beta", EVALUATION_REASON),
                        new DisabledLocation("Mid", EVALUATION_REASON),
                        new DisabledLocation("Omega", EVALUATION_REASON),
                        new DisabledLocation("Zeta", EVALUATION_REASON)));
        verifyNoMoreInteractions(adminAlertService);
    }

    @Test
    @DisplayName("6 places reaching 3 in one cycle exceed the cap: none is disabled, an ERROR is "
            + "logged and the admin gets the cap alert with the cycle's type and trigger time, "
            + "while the counters still advance")
    void sixPlacesReachingThree_noneDisabled_capAlertSent() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 20, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 21, 26, CyclePlaceEvidence.failedIn(Lane.SKY));
        List<LocationEntity> failing = new ArrayList<>();
        for (long id = 21; id <= 26; id++) {
            failing.add(place(id, "P" + id, 2, true));
        }
        when(locationRepository.findAllById(ids(21, 26))).thenReturn(failing);

        settle(evidence, CycleType.INTRADAY);

        verify(locationRepository).resetFailureCounts(ids(1, 20));
        verify(locationRepository).findAllById(ids(21, 26));
        for (long id = 21; id <= 26; id++) {
            verify(locationRepository).recordFailure(id, NOW_UTC);
            verify(locationRepository).findConsecutiveFailuresById(id);
        }
        verifyNoMoreInteractions(locationRepository);
        verify(adminAlertService).sendLocationDisableCapAlert(RUN_ID, CycleType.INTRADAY, TRIGGER,
                List.of("P21", "P22", "P23", "P24", "P25", "P26"), 5);
        verifyNoMoreInteractions(adminAlertService);
        assertThat(messages(Level.ERROR)).containsExactly(
                "Pipeline run 300: 6 places reached 3 consecutive failed cycles in one cycle, more "
                        + "than the cap of 5, something systemic is wrong, NO place disabled: "
                        + "P21, P22, P23, P24, P25, P26");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("an autoDisable that updates no row (an admin disabled the place between the "
            + "read and the update) sends no alert and logs no disable")
    void autoDisableUpdatesNothing_noAlert() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(0);

        settle(evidence);

        verifyNoInteractions(adminAlertService);
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("a place an admin disabled mid-cycle is skipped, not counted and not disabled")
    void alreadyDisabledPlace_skipped() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, false)));

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a failed place the repository no longer returns (deleted mid-cycle) is skipped")
    void failedPlaceMissingFromRepository_skipped() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L))).thenReturn(List.of());

        settle(evidence);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a null stored counter counts as zero")
    void nullCounter_countsFromZero() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", null, true)));

        settle(evidence);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
    }

    @Test
    @DisplayName("an alert dispatch that throws is swallowed: the place stays disabled")
    void alertThrows_placeStaysDisabled() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);
        doThrow(new IllegalStateException("mail down")).when(adminAlertService)
                .sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY, TRIGGER,
                        List.of(new DisabledLocation("Bamburgh", EVALUATION_REASON)));

        settle(evidence);

        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("mail down"));
    }

    // ---- the alert goes out only after the transaction commits ----

    private Map<Long, CyclePlaceEvidence> oneDisableEvidence() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);
        return evidence;
    }

    @Test
    @DisplayName("the disable alert goes out only after the settle transaction has committed")
    void alertSentOnlyAfterCommit() {
        java.util.concurrent.atomic.AtomicInteger commitsWhenSent =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        doAnswer(invocation -> {
            commitsWhenSent.set(transactionManager.commits.get());
            return null;
        }).when(adminAlertService).sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY,
                TRIGGER, List.of(new DisabledLocation("Bamburgh", EVALUATION_REASON)));

        settle(oneDisableEvidence());

        assertThat(commitsWhenSent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transaction that fails to commit propagates the failure and sends no alert")
    void commitFailure_sendsNoAlert() {
        transactionManager.failCommit = true;

        assertThatThrownBy(() -> settle(oneDisableEvidence()))
                .isInstanceOf(TransactionSystemException.class);

        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("the cap alert is likewise sent only after the commit, and not when it fails")
    void capAlert_afterCommitOnly() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 20, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 21, 26, CyclePlaceEvidence.failedIn(Lane.SKY));
        List<LocationEntity> failing = new ArrayList<>();
        for (long id = 21; id <= 26; id++) {
            failing.add(place(id, "P" + id, 2, true));
        }
        when(locationRepository.findAllById(ids(21, 26))).thenReturn(failing);
        transactionManager.failCommit = true;

        assertThatThrownBy(() -> settle(evidence)).isInstanceOf(TransactionSystemException.class);

        verifyNoInteractions(adminAlertService);
    }

    // ---- serialised and ordered ----

    @Test
    @DisplayName("two settles started together from two threads run strictly one after the other: "
            + "the second does not begin resolving until the first has finished, and a place "
            + "that failed in both ends on exactly 2")
    void concurrentSettles_runOneAfterTheOther() throws Exception {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.CountDownLatch aInside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseA = new java.util.concurrent.CountDownLatch(1);
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));
        when(resolver.resolve(RUN_ID)).thenAnswer(invocation -> {
            events.add("A resolve start");
            aInside.countDown();
            releaseA.await();
            events.add("A resolve end");
            return evidence;
        });
        when(resolver.resolve(RUN_ID + 1)).thenAnswer(invocation -> {
            events.add("B resolve start");
            return evidence;
        });
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread first = new Thread(() -> settleRecording(
                run(RUN_ID, CycleType.NIGHTLY, TRIGGER), failure));
        Thread second = new Thread(() -> settleRecording(
                run(RUN_ID + 1, CycleType.NIGHTLY, TRIGGER.plusSeconds(60)), failure));

        first.start();
        assertThat(aInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        second.start();
        // No sleeping: spin until the second thread is parked on the settle lock (or, if the
        // service were unlocked, has run straight through), then let the first finish.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.WAITING
                && second.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        releaseA.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(failure.get()).isNull();
        assertThat(events).containsExactly("A resolve start", "A resolve end", "B resolve start");
        assertThat(storedCounts.get(10L)).isEqualTo(2);
    }

    private void settleRecording(PipelineRunEntity run,
            java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
        try {
            service.settleCycle(run);
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    @Test
    @DisplayName("a newer cycle settled first, then an older one: the older is settled RESETS_ONLY, "
            + "not refused: its success still resets a counter, its failure counts nothing, and the "
            + "mode and the reason are logged at INFO")
    void newerThenOlder_olderSettledResetsOnly() {
        Instant older = TRIGGER;
        Instant newer = TRIGGER.plusSeconds(3600);
        storedCounts.put(1L, 2);
        storedCounts.put(2L, 2);
        Map<Long, CyclePlaceEvidence> newerCycle = cycle();
        newerCycle.put(1L, CyclePlaceEvidence.scoredIn(Lane.SKY));
        when(resolver.resolve(RUN_ID + 1)).thenReturn(newerCycle);
        // The older cycle: place 1 and 3..11 scored, place 2 failed. Settled FULL, place 2 would
        // count (10 of the 10 others got through); RESETS_ONLY must not count it.
        Map<Long, CyclePlaceEvidence> olderCycle = cycle();
        put(olderCycle, 3, 11, CyclePlaceEvidence.scoredIn(Lane.SKY));
        olderCycle.put(1L, CyclePlaceEvidence.scoredIn(Lane.SKY));
        olderCycle.put(2L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(resolver.resolve(RUN_ID)).thenReturn(olderCycle);

        service.settleCycle(run(RUN_ID + 1, CycleType.NIGHTLY, newer));
        logAppender.list.clear();
        service.settleCycle(run(RUN_ID, CycleType.INTRADAY, older));

        verify(resolver, times(1)).resolve(RUN_ID);
        verify(locationRepository, never()).recordFailure(2L, NOW_UTC);
        verify(locationRepository, never()).findAllById(List.of(2L));
        assertThat(storedCounts.get(1L)).isZero();
        assertThat(storedCounts.get(2L)).isEqualTo(2);
        assertThat(messages(Level.INFO)).containsExactly(
                "Pipeline run 300 (triggered 2026-10-02T01:00:00Z): location failure settle mode "
                        + "RESETS_ONLY: a newer cycle (triggered 2026-10-02T02:00:00Z) has already "
                        + "been settled, and counting an older cycle after it could restart a "
                        + "streak that cycle's success had broken");
        assertThat(settledRuns).containsKey(RUN_ID);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("the tail settle of the newest cycle is still FULL: a failure among places that "
            + "mostly got through counts, even with an older cycle already settled")
    void tailSettleOfNewestCycle_isFull() {
        settledRuns.put(RUN_ID - 1, TRIGGER.minusSeconds(3600));
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));

        settle(evidence);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
        assertThat(storedCounts.get(10L)).isEqualTo(1);
        assertThat(messages(Level.INFO)).first().isEqualTo(FULL_MODE_LOG);
    }

    // ---- the durable retry sweep ----

    private static final Instant SWEEP_SINCE = NOW.minus(java.time.Duration.ofDays(7));

    private void unsettled(PipelineRunEntity... runs) {
        org.mockito.Mockito.doReturn(List.of(runs)).when(pipelineRunRepository)
                .findUnsettledSince(SWEEP_SINCE, PipelineRunStatus.RUNNING);
    }

    @Test
    @DisplayName("the sweep window is seven days and the sweep never asks for a RUNNING run")
    void sweepWindow_isSevenDays() {
        assertThat(LocationFailureService.SWEEP_WINDOW).isEqualTo(java.time.Duration.ofDays(7));
    }

    @Test
    @DisplayName("the sweep settles each unclaimed run in trigger order, RESETS_ONLY: a place at 2 "
            + "that succeeded is reset, a place that failed is not counted, and it is reported")
    void sweep_settlesUnclaimedRuns_resetsOnly() {
        storedCounts.put(1L, 2);
        storedCounts.put(2L, 1);
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        evidence.put(1L, CyclePlaceEvidence.scoredIn(Lane.SKY));
        put(evidence, 3, 11, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(2L, CyclePlaceEvidence.failedIn(Lane.SKY));
        PipelineRunEntity first = run(RUN_ID, CycleType.NIGHTLY, TRIGGER);
        PipelineRunEntity second = run(RUN_ID + 1, CycleType.INTRADAY, TRIGGER.plusSeconds(3600));
        unsettled(first, second);
        when(resolver.resolve(RUN_ID)).thenReturn(evidence);
        when(resolver.resolve(RUN_ID + 1)).thenReturn(Map.of());

        int settled = service.sweepUnsettledRuns();

        assertThat(settled).isEqualTo(2);
        org.mockito.InOrder order = Mockito.inOrder(resolver);
        order.verify(resolver).resolve(RUN_ID);
        order.verify(resolver).resolve(RUN_ID + 1);
        assertThat(storedCounts.get(1L)).isZero();
        assertThat(storedCounts.get(2L)).isEqualTo(1);
        verify(locationRepository, never()).recordFailure(2L, NOW_UTC);
        assertThat(settledRuns).containsKeys(RUN_ID, RUN_ID + 1);
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("settle mode RESETS_ONLY: "
                + "settled by the sweep, not at its own tail"));
    }

    @Test
    @DisplayName("a run the sweep cannot settle is logged at ERROR and left for the next sweep, "
            + "and the runs after it are still settled")
    void sweep_oneRunFailsAgain_loggedAtError_othersStillSettled() {
        PipelineRunEntity bad = run(RUN_ID, CycleType.NIGHTLY, TRIGGER);
        PipelineRunEntity good = run(RUN_ID + 1, CycleType.INTRADAY, TRIGGER.plusSeconds(3600));
        unsettled(bad, good);
        when(resolver.resolve(RUN_ID)).thenThrow(new IllegalStateException("db down"));
        when(resolver.resolve(RUN_ID + 1)).thenReturn(Map.of());

        int settled = service.sweepUnsettledRuns();

        assertThat(settled).isEqualTo(1);
        assertThat(transactionManager.rollbacks.get()).isEqualTo(1);
        assertThat(messages(Level.ERROR)).hasSize(1).first().asString()
                .contains("pipeline run 300 could not be settled, left unclaimed for the next "
                        + "sweep: db down");
        verify(resolver).resolve(RUN_ID + 1);
    }

    @Test
    @DisplayName("a sweep whose listing fails is logged at ERROR, settles nothing and does not throw")
    void sweep_listingFails_loggedAtError() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(pipelineRunRepository)
                .findUnsettledSince(SWEEP_SINCE, PipelineRunStatus.RUNNING);

        int settled = service.sweepUnsettledRuns();

        assertThat(settled).isZero();
        assertThat(messages(Level.ERROR)).hasSize(1);
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("a tail settle sweeps earlier unclaimed runs first (RESETS_ONLY), then settles its "
            + "own cycle FULL, all under the one lock")
    void tailSettle_sweepsFirst_thenOwnCycleFull() {
        storedCounts.put(1L, 2);
        Map<Long, CyclePlaceEvidence> earlier = cycle();
        earlier.put(1L, CyclePlaceEvidence.scoredIn(Lane.SKY));
        PipelineRunEntity earlierRun = run(RUN_ID - 1, CycleType.NIGHTLY, TRIGGER.minusSeconds(3600));
        unsettled(earlierRun);
        when(resolver.resolve(RUN_ID - 1)).thenReturn(earlier);
        Map<Long, CyclePlaceEvidence> own = cycle();
        put(own, 2, 10, CyclePlaceEvidence.scoredIn(Lane.SKY));
        own.put(11L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(locationRepository.findAllById(List.of(11L)))
                .thenReturn(List.of(place(11L, "Bamburgh", 0, true)));

        settle(own);

        org.mockito.InOrder order = Mockito.inOrder(resolver);
        order.verify(resolver).resolve(RUN_ID - 1);
        order.verify(resolver).resolve(RUN_ID);
        assertThat(storedCounts.get(1L)).isZero();
        assertThat(storedCounts.get(11L)).isEqualTo(1);
        assertThat(messages(Level.INFO)).contains(FULL_MODE_LOG);
    }

    private com.gregochr.goldenhour.entity.ForecastBatchEntity batchIn(
            com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus status) {
        com.gregochr.goldenhour.entity.ForecastBatchEntity batch =
                new com.gregochr.goldenhour.entity.ForecastBatchEntity("msgbatch_poll",
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST, 1,
                        NOW);
        batch.setStatus(status);
        return batch;
    }

    @Test
    @DisplayName("a run with a forecast batch still being polled is deferred, never claimed: the "
            + "sweep logs INFO naming the batch, resolves nothing and leaves it unclaimed")
    void sweep_runWithPollingBatch_isDeferred() {
        PipelineRunEntity failed = run(RUN_ID, CycleType.NIGHTLY, TRIGGER);
        unsettled(failed);
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of(batchIn(
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.SUBMITTED)));

        service.sweepUnsettledRuns();

        verifyNoInteractions(resolver);
        assertThat(settledRuns).doesNotContainKey(RUN_ID);
        assertThat(messages(Level.INFO)).containsExactly(
                "Pipeline run 300: location failure settle deferred, forecast batch msgbatch_poll "
                        + "is still SUBMITTED and the poller may yet write results; the run is "
                        + "left unclaimed for a later sweep");
    }

    private static final String OLDER_UNRESOLVED_LOG =
            "Pipeline run 300 (triggered 2026-10-02T01:00:00Z): location failure settle mode "
                    + "RESETS_ONLY: older cycle 299 (FAILED) still unresolved, and counting this cycle "
                    + "first could disable a place that cycle's success would have reset";

    private PipelineRunEntity finishedRun(long id, Instant trigger) {
        PipelineRunEntity finished = run(id, CycleType.NIGHTLY, trigger);
        finished.setStatus(PipelineRunStatus.FAILED);
        return finished;
    }

    private void runningUnsettled(PipelineRunEntity... runs) {
        org.mockito.Mockito.doReturn(List.of(runs)).when(pipelineRunRepository)
                .findUnsettledSinceWithStatus(SWEEP_SINCE, PipelineRunStatus.RUNNING);
    }

    @Test
    @DisplayName("a tail settle with an older run still RUNNING (the nightly in WAIT while an "
            + "intraday Run now finishes first) is RESETS_ONLY, naming the run and its status: a "
            + "place at 2 that failed stays at 2")
    void tail_olderRunStillRunning_isResetsOnly() {
        runningUnsettled(run(RUN_ID - 1, CycleType.NIGHTLY, TRIGGER.minusSeconds(3600)));

        settle(failureThatWouldDisable());

        verify(locationRepository, never()).recordFailure(10L, NOW_UTC);
        assertThat(storedCounts.get(10L)).isEqualTo(2);
        assertThat(settledRuns).containsKey(RUN_ID);
        assertThat(messages(Level.INFO)).contains(
                "Pipeline run 300 (triggered 2026-10-02T01:00:00Z): location failure settle mode "
                        + "RESETS_ONLY: older cycle 299 (RUNNING) still unresolved, and counting "
                        + "this cycle first could disable a place that cycle's success would have "
                        + "reset");
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a NEWER run still RUNNING does not block a tail: only an older cycle can pre-empt")
    void tail_newerRunStillRunning_stillFull() {
        runningUnsettled(run(RUN_ID + 1, CycleType.INTRADAY, TRIGGER.plusSeconds(3600)));
        Map<Long, CyclePlaceEvidence> evidence = failureThatWouldDisable();
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);

        settle(evidence);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
        assertThat(messages(Level.INFO)).contains(FULL_MODE_LOG);
    }

    @Test
    @DisplayName("a tail whose RUNNING-run listing fails cannot rule an older cycle out: RESETS_ONLY")
    void tail_runningListingFails_isResetsOnly() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(pipelineRunRepository)
                .findUnsettledSinceWithStatus(SWEEP_SINCE, PipelineRunStatus.RUNNING);

        settle(failureThatWouldDisable());

        verify(locationRepository, never()).recordFailure(10L, NOW_UTC);
        assertThat(storedCounts.get(10L)).isEqualTo(2);
    }

    /** A tail whose evidence would count place 10 from 2 to 3 (and so disable it) if FULL. */
    private Map<Long, CyclePlaceEvidence> failureThatWouldDisable() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        place(10L, "Bamburgh", 2, true);
        return evidence;
    }

    private void assertNothingCountedAndOlderLogged() {
        verify(locationRepository, never()).recordFailure(10L, NOW_UTC);
        verify(locationRepository, never()).autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON);
        assertThat(storedCounts.get(10L)).isEqualTo(2);
        assertThat(settledRuns).containsKey(RUN_ID);
        assertThat(messages(Level.INFO)).contains(OLDER_UNRESOLVED_LOG);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a tail settle after an older run the sweep DEFERRED (a batch still polling) is "
            + "RESETS_ONLY: a place at 2 that failed in the newer cycle stays at 2, enabled, and "
            + "the newer cycle is claimed")
    void tail_olderRunDeferred_isResetsOnly() {
        unsettled(finishedRun(RUN_ID - 1, TRIGGER.minusSeconds(3600)));
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID - 1,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of(batchIn(
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.SUBMITTED)));
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of());

        settle(failureThatWouldDisable());

        assertNothingCountedAndOlderLogged();
        verify(resolver, never()).resolve(RUN_ID - 1);
    }

    @Test
    @DisplayName("a tail settle after an older run the sweep FAILED to settle (it threw) is "
            + "RESETS_ONLY too")
    void tail_olderRunFailedToSettle_isResetsOnly() {
        unsettled(finishedRun(RUN_ID - 1, TRIGGER.minusSeconds(3600)));
        when(resolver.resolve(RUN_ID - 1)).thenThrow(new IllegalStateException("db down"));

        settle(failureThatWouldDisable());

        assertNothingCountedAndOlderLogged();
    }

    @Test
    @DisplayName("a tail settle whose sweep could not even list the unsettled runs cannot rule an "
            + "older cycle out, so it is RESETS_ONLY")
    void tail_sweepListingFails_isResetsOnly() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(pipelineRunRepository)
                .findUnsettledSince(SWEEP_SINCE, PipelineRunStatus.RUNNING);

        settle(failureThatWouldDisable());

        verify(locationRepository, never()).recordFailure(10L, NOW_UTC);
        assertThat(storedCounts.get(10L)).isEqualTo(2);
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("settle mode RESETS_ONLY: the "
                + "unsettled runs could not be listed, so no older unresolved cycle can be ruled "
                + "out"));
    }

    @Test
    @DisplayName("an unresolved run triggered AFTER the tail does not stop it counting: only older "
            + "cycles can have a success the tail's count would pre-empt")
    void tail_newerRunDeferred_stillFull() {
        unsettled(run(RUN_ID + 1, CycleType.INTRADAY, TRIGGER.plusSeconds(3600)));
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID + 1,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of(batchIn(
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.SUBMITTED)));
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of());
        Map<Long, CyclePlaceEvidence> evidence = failureThatWouldDisable();
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, EVALUATION_REASON)).thenReturn(1);

        settle(evidence);

        verify(locationRepository).recordFailure(10L, NOW_UTC);
        assertThat(messages(Level.INFO)).contains(FULL_MODE_LOG);
    }

    @Test
    @DisplayName("a tail settle is deferred the same way for its own run with a polling batch")
    void tailSettle_runWithPollingBatch_isNotClaimed() {
        when(forecastBatchRepository.findByPipelineRunIdAndBatchTypeAndStatusNotIn(RUN_ID,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType.FORECAST,
                com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL))
                .thenReturn(List.of(batchIn(
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.SUBMITTED)));

        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY, TRIGGER));

        verifyNoInteractions(resolver);
        assertThat(settledRuns).doesNotContainKey(RUN_ID);
    }

    @Test
    @DisplayName("the terminal batch statuses are exactly those the poller stops polling: every "
            + "status except SUBMITTED")
    void terminalStatuses_areEveryStatusButSubmitted() {
        assertThat(com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.TERMINAL)
                .containsExactlyInAnyOrder(
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.COMPLETED,
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.FAILED,
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.EXPIRED,
                        com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus.CANCELLED);
    }

    @Test
    @DisplayName("a tail settle never sweeps the run it is itself settling")
    void tailSettle_doesNotSweepItsOwnRun() {
        unsettled(run(RUN_ID, CycleType.NIGHTLY, TRIGGER));
        when(resolver.resolve(RUN_ID)).thenReturn(Map.of());

        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY, TRIGGER));

        verify(resolver, times(1)).resolve(RUN_ID);
        assertThat(messages(Level.INFO)).contains(FULL_MODE_LOG);
    }

    @Test
    @DisplayName("a cycle triggered at the same instant as the newest settled one is still settled "
            + "(RESETS_ONLY: it is not newer)")
    void sameTriggerTime_isSettled() {
        when(resolver.resolve(RUN_ID)).thenReturn(Map.of());
        when(resolver.resolve(RUN_ID + 1)).thenReturn(Map.of());

        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY, TRIGGER));
        service.settleCycle(run(RUN_ID + 1, CycleType.INTRADAY, TRIGGER));

        verify(resolver).resolve(RUN_ID);
        verify(resolver).resolve(RUN_ID + 1);
    }

    // ---- idempotence ----

    @Test
    @DisplayName("the same cycle settled twice counts once: the resolver is consulted and the "
            + "counter written exactly once")
    void sameCycleSettledTwice_countedOnce() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(resolver.resolve(RUN_ID)).thenReturn(evidence);
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));

        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY));
        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY));

        verify(resolver, times(1)).resolve(RUN_ID);
        verify(locationRepository, times(1)).recordFailure(10L, NOW_UTC);
        verify(locationRepository, times(1)).resetFailureCounts(ids(1, 9));
        assertThat(storedCounts.get(10L)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cycle whose claim is already held in the database (settled before a restart, or "
            + "by a crashed-and-resumed earlier attempt that committed) is refused without being "
            + "resolved, and nothing in this JVM's memory is involved")
    void alreadyClaimedInTheDatabase_isRefused_withoutResolving() {
        settledRuns.put(RUN_ID, TRIGGER);

        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY));

        verify(resolver, never()).resolve(RUN_ID);
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("a service instance built after a 'restart' sees the first instance's claim, because "
            + "the claim lives in pipeline_run, not in memory")
    void claimSurvivesANewServiceInstance() {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(resolver.resolve(RUN_ID)).thenReturn(evidence);
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));
        service.settleCycle(run(RUN_ID, CycleType.NIGHTLY));
        LocationFailureService restarted = new LocationFailureService(resolver, locationRepository,
                adminAlertService, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager,
                pipelineRunRepository, forecastBatchRepository);

        restarted.settleCycle(run(RUN_ID, CycleType.NIGHTLY));

        verify(resolver, times(1)).resolve(RUN_ID);
        assertThat(storedCounts.get(10L)).isEqualTo(1);
    }

    @Test
    @DisplayName("two settles of ONE cycle started together from two threads: exactly one claims "
            + "and applies it, the other is refused")
    void concurrentSettlesOfOneCycle_onlyOneClaims() throws Exception {
        Map<Long, CyclePlaceEvidence> evidence = cycle();
        put(evidence, 1, 9, CyclePlaceEvidence.scoredIn(Lane.SKY));
        evidence.put(10L, CyclePlaceEvidence.failedIn(Lane.SKY));
        when(resolver.resolve(RUN_ID)).thenReturn(evidence);
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        PipelineRunEntity cycleRun = run(RUN_ID, CycleType.NIGHTLY);
        Runnable settle = () -> {
            try {
                go.await();
                service.settleCycle(cycleRun);
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Thread first = new Thread(settle);
        Thread second = new Thread(settle);
        first.start();
        second.start();

        go.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(failure.get()).isNull();
        verify(resolver, times(1)).resolve(RUN_ID);
        assertThat(storedCounts.get(10L)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cycle that recorded nothing (what a hand-started run, never tagged with a "
            + "pipeline run, resolves to) never touches a location row or the admin channel")
    void nothingRecorded_touchesNothing() {
        settle(Map.of());

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("the constants are the owner's decision: disable at 3, at most 5 per cycle")
    void constants() {
        assertThat(LocationFailureService.AUTO_DISABLE_THRESHOLD).isEqualTo(3);
        assertThat(LocationFailureService.MAX_DISABLED_PER_CYCLE).isEqualTo(5);
    }
}
