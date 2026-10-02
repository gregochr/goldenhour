package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.model.CyclePlaceOutcome;
import com.gregochr.goldenhour.model.CyclePlaceOutcome.FailureKind;
import com.gregochr.goldenhour.repository.LocationRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationFailureService}: the counting rule, the threshold, the per-cycle
 * cap, idempotence and the alert, all on literal outcomes and a fixed clock.
 */
@ExtendWith(MockitoExtension.class)
class LocationFailureServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 2, 3, 0);
    private static final Instant TRIGGER = Instant.parse("2026-10-02T01:00:00Z");
    private static final long RUN_ID = 300L;
    private static final String WEATHER_REASON =
            "Auto-disabled after 3 consecutive failed scheduled runs "
                    + "(last 2026-10-02: weather data could not be fetched).";

    @Mock
    private CycleLocationOutcomeResolver resolver;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private AdminAlertService adminAlertService;

    private LocationFailureService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new LocationFailureService(resolver, locationRepository, adminAlertService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        serviceLogger = (Logger) LoggerFactory.getLogger(LocationFailureService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private static LocationEntity place(long id, String name, Integer failures, boolean enabled) {
        return LocationEntity.builder().id(id).name(name).lat(54.0).lon(-1.0)
                .enabled(enabled).consecutiveFailures(failures).build();
    }

    private static PipelineRunEntity run(long id) {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, TRIGGER);
        run.setId(id);
        return run;
    }

    /** Ids {@code first..last} inclusive, all with the same outcome, appended in order. */
    private static void add(Map<Long, CyclePlaceOutcome> outcomes, long first, long last,
            CyclePlaceOutcome outcome) {
        for (long id = first; id <= last; id++) {
            outcomes.put(id, outcome);
        }
    }

    private static List<Long> ids(long first, long last) {
        List<Long> ids = new ArrayList<>();
        for (long id = first; id <= last; id++) {
            ids.add(id);
        }
        return ids;
    }

    private List<String> messages(Level level) {
        return logAppender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("1 failed of 10 attempted counts: the failed place goes 0 -> 1 with the clock's "
            + "time, and the 9 that got through are reset")
    void oneFailedOfTen_counts() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.EVALUATION));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verify(locationRepository).recordFailure(10L, 1, NOW_UTC);
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
        assertThat(messages(Level.INFO)).containsExactly(
                "Pipeline run 300: location 'Bamburgh' failed this cycle (EVALUATION) — "
                        + "consecutive failures now 1");
    }

    @Test
    @DisplayName("6 failed of 10 attempted counts for none: for each, only 4 of the other 9 got "
            + "through, fewer than half. The 4 that got through are still reset")
    void sixFailedOfTen_countsForNone() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 4, CyclePlaceOutcome.gotThrough());
        add(outcomes, 5, 10, CyclePlaceOutcome.failed(FailureKind.EVALUATION));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 4));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
        assertThat(messages(Level.WARN)).containsExactly(
                "Pipeline run 300: 6 place(s) failed but only 4 of the 9 other attempted place(s) "
                        + "got through — treated as systemic, no failure counted for anyone");
    }

    @Test
    @DisplayName("exactly half of the others got through counts (the rule is 'at least half'): "
            + "5 through and 6 failed leaves 5 of the 10 others through for each failed place")
    void exactlyHalfOfTheOthers_counts() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 5, CyclePlaceOutcome.gotThrough());
        add(outcomes, 6, 11, CyclePlaceOutcome.failed(FailureKind.EVALUATION));
        when(locationRepository.findAllById(ids(6, 11))).thenReturn(List.of(
                place(6L, "P6", 0, true), place(7L, "P7", 0, true), place(8L, "P8", 0, true),
                place(9L, "P9", 0, true), place(10L, "P10", 0, true), place(11L, "P11", 0, true)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        for (long id = 6; id <= 11; id++) {
            verify(locationRepository).recordFailure(id, 1, NOW_UTC);
        }
    }

    @Test
    @DisplayName("one more failure than half counts for none: 4 through and 7 failed leaves 4 of "
            + "the 10 others through for each failed place")
    void justUnderHalfOfTheOthers_countsForNone() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 4, CyclePlaceOutcome.gotThrough());
        add(outcomes, 5, 11, CyclePlaceOutcome.failed(FailureKind.EVALUATION));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 4));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a cycle that fails everything counts for nobody: 510 failed, none through")
    void everythingFailed_countsForNobody() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 510, CyclePlaceOutcome.failed(FailureKind.EVALUATION));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a lone failed place with no other attempted place offers no evidence the "
            + "pipeline was working, so it is not counted")
    void loneFailedPlace_notCounted() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put(1L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        outcomes.put(2L, CyclePlaceOutcome.notAttempted());

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("a failed place beside one that got through counts: 1 of the 1 other got through")
    void failedBesideOneThatGotThrough_counts() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put(1L, CyclePlaceOutcome.gotThrough());
        outcomes.put(2L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(2L)))
                .thenReturn(List.of(place(2L, "Alnwick", 1, true)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).recordFailure(2L, 2, NOW_UTC);
    }

    @Test
    @DisplayName("a place that got through is reset through the repository's column-scoped reset")
    void gotThrough_resetsCounter() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put(7L, CyclePlaceOutcome.gotThrough());

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(List.of(7L));
        verifyNoMoreInteractions(locationRepository);
    }

    @Test
    @DisplayName("a place not attempted this cycle is neither counted nor reset: the repository "
            + "is never asked to touch it")
    void notAttempted_keepsItsCount() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put(1L, CyclePlaceOutcome.notAttempted());
        outcomes.put(2L, CyclePlaceOutcome.notAttempted());

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verifyNoInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a place reaching 3 is disabled with the literal reason and the clock's time, "
            + "and the admin is told once")
    void reachingThree_disablesWithLiteralReason() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, WEATHER_REASON)).thenReturn(1);

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).recordFailure(10L, 3, NOW_UTC);
        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, WEATHER_REASON);
        verify(adminAlertService).sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY,
                TRIGGER, List.of(new DisabledLocation("Bamburgh", WEATHER_REASON)));
        assertThat(messages(Level.WARN)).containsExactly(
                "Pipeline run 300: location 'Bamburgh' AUTO-DISABLED — " + WEATHER_REASON);
    }

    @Test
    @DisplayName("an evaluation failure is worded as such in the stored reason, never with a "
            + "raw exception message")
    void evaluationFailure_reasonWording() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.EVALUATION));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        String reason = "Auto-disabled after 3 consecutive failed scheduled runs "
                + "(last 2026-10-02: the Claude evaluation request failed).";
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, reason)).thenReturn(1);

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, reason);
    }

    @Test
    @DisplayName("a place reaching 2 is counted but not disabled and nobody is told")
    void reachingTwo_notDisabled() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 1, true)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verify(locationRepository).recordFailure(10L, 2, NOW_UTC);
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("exactly 5 places reaching 3 in one cycle (the cap itself) are all disabled")
    void fivePlacesReachingThree_allDisabled() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 20, CyclePlaceOutcome.gotThrough());
        add(outcomes, 21, 25, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        List<LocationEntity> failing = new ArrayList<>();
        List<DisabledLocation> expected = new ArrayList<>();
        for (long id = 21; id <= 25; id++) {
            failing.add(place(id, "P" + id, 2, true));
            when(locationRepository.autoDisable(id, 3, NOW_UTC, WEATHER_REASON)).thenReturn(1);
            expected.add(new DisabledLocation("P" + id, WEATHER_REASON));
        }
        when(locationRepository.findAllById(ids(21, 25))).thenReturn(failing);

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(adminAlertService).sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY,
                TRIGGER, expected);
        verifyNoMoreInteractions(adminAlertService);
    }

    @Test
    @DisplayName("6 places reaching 3 in one cycle exceed the cap: none is disabled, an ERROR is "
            + "logged and the admin is sent the cap alert, while the counters still advance")
    void sixPlacesReachingThree_noneDisabled_capAlertSent() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 20, CyclePlaceOutcome.gotThrough());
        add(outcomes, 21, 26, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        List<LocationEntity> failing = new ArrayList<>();
        for (long id = 21; id <= 26; id++) {
            failing.add(place(id, "P" + id, 2, true));
        }
        when(locationRepository.findAllById(ids(21, 26))).thenReturn(failing);

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 20));
        verify(locationRepository).findAllById(ids(21, 26));
        for (long id = 21; id <= 26; id++) {
            verify(locationRepository).recordFailure(id, 3, NOW_UTC);
        }
        verifyNoMoreInteractions(locationRepository);
        verify(adminAlertService).sendLocationDisableCapAlert(RUN_ID, CycleType.NIGHTLY, TRIGGER,
                List.of("P21", "P22", "P23", "P24", "P25", "P26"), 5);
        verifyNoMoreInteractions(adminAlertService);
        assertThat(messages(Level.ERROR)).containsExactly(
                "Pipeline run 300: 6 places reached 3 consecutive failed cycles in one cycle, more "
                        + "than the cap of 5 — something systemic is wrong, NO place disabled: "
                        + "P21, P22, P23, P24, P25, P26");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("a place an admin disabled mid-cycle is skipped, not counted and not disabled")
    void alreadyDisabledPlace_skipped() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, false)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).resetFailureCounts(ids(1, 9));
        verify(locationRepository).findAllById(List.of(10L));
        verifyNoMoreInteractions(locationRepository);
        verifyNoInteractions(adminAlertService);
    }

    @Test
    @DisplayName("a null stored counter counts as zero")
    void nullCounter_countsFromZero() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", null, true)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).recordFailure(10L, 1, NOW_UTC);
    }

    @Test
    @DisplayName("an alert dispatch that throws is swallowed: the place stays disabled")
    void alertThrows_placeStaysDisabled() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 2, true)));
        when(locationRepository.autoDisable(10L, 3, NOW_UTC, WEATHER_REASON)).thenReturn(1);
        doThrow(new IllegalStateException("mail down")).when(adminAlertService)
                .sendLocationsAutoDisabledAlert(RUN_ID, CycleType.NIGHTLY, TRIGGER,
                        List.of(new DisabledLocation("Bamburgh", WEATHER_REASON)));

        service.applyOutcomes(RUN_ID, CycleType.NIGHTLY, TRIGGER, outcomes);

        verify(locationRepository).autoDisable(10L, 3, NOW_UTC, WEATHER_REASON);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("mail down"));
    }

    @Test
    @DisplayName("the same cycle settled twice counts once: the resolver is consulted and the "
            + "counter written exactly once")
    void sameCycleSettledTwice_countedOnce() {
        Map<Long, CyclePlaceOutcome> outcomes = new LinkedHashMap<>();
        add(outcomes, 1, 9, CyclePlaceOutcome.gotThrough());
        outcomes.put(10L, CyclePlaceOutcome.failed(FailureKind.WEATHER_DATA));
        when(resolver.resolve(RUN_ID)).thenReturn(outcomes);
        when(locationRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(place(10L, "Bamburgh", 0, true)));

        service.settleCycle(run(RUN_ID));
        service.settleCycle(run(RUN_ID));

        verify(resolver, times(1)).resolve(RUN_ID);
        verify(locationRepository, times(1)).recordFailure(10L, 1, NOW_UTC);
        verify(locationRepository, times(1)).resetFailureCounts(ids(1, 9));
    }

    @Test
    @DisplayName("a cycle older than one already settled is ignored: a late old cycle cannot "
            + "count after a newer one")
    void olderCycleAfterNewer_ignored() {
        when(resolver.resolve(RUN_ID)).thenReturn(Map.of());

        service.settleCycle(run(RUN_ID));
        service.settleCycle(run(RUN_ID - 1));

        verify(resolver, times(1)).resolve(RUN_ID);
        verify(resolver, never()).resolve(RUN_ID - 1);
    }

    @Test
    @DisplayName("a newer cycle is settled after an older one")
    void newerCycle_isSettled() {
        when(resolver.resolve(RUN_ID)).thenReturn(Map.of());
        when(resolver.resolve(RUN_ID + 1)).thenReturn(Map.of());

        service.settleCycle(run(RUN_ID));
        service.settleCycle(run(RUN_ID + 1));

        verify(resolver).resolve(RUN_ID);
        verify(resolver).resolve(RUN_ID + 1);
    }

    @Test
    @DisplayName("a cycle that recorded nothing (which is what a hand-started run resolves to) "
            + "never touches a location row or the admin channel")
    void nothingRecorded_touchesNothing() {
        when(resolver.resolve(RUN_ID)).thenReturn(Map.of());

        service.settleCycle(run(RUN_ID));

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
