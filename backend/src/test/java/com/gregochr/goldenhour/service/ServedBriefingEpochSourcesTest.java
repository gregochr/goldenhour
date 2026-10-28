package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.EvaluationDeltaLogRepository;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One test per serve-epoch source (performance plan P1b), each driving the REAL service method
 * with a real {@link ServedBriefingEpoch}: if a writer stops bumping, the memoised served
 * briefing would silently outlive its write. The roster, region, disposition and batch writers
 * are asserted in their own service tests ({@code LocationServiceTest}, {@code RegionServiceTest},
 * {@code ForecastDispositionServiceTest}, {@code BatchResultProcessorTest}).
 */
class ServedBriefingEpochSourcesTest {

    private ServedBriefingEpoch epoch;

    @BeforeEach
    void setUp() {
        epoch = new ServedBriefingEpoch();
    }

    @Test
    @DisplayName("HotTopicSimulationService.setEnabled bumps")
    void simulationSetEnabled_bumps() {
        HotTopicSimulationService simulation = new HotTopicSimulationService(epoch);
        long before = epoch.current();
        simulation.setEnabled(true);
        assertThat(epoch.current()).isGreaterThan(before);
    }

    @Test
    @DisplayName("HotTopicSimulationService.setTypeActive bumps, on and off")
    void simulationSetTypeActive_bumps() {
        HotTopicSimulationService simulation = new HotTopicSimulationService(epoch);
        long before = epoch.current();
        simulation.setTypeActive("BLUEBELL", true);
        long afterOn = epoch.current();
        simulation.setTypeActive("BLUEBELL", false);
        assertThat(afterOn).isGreaterThan(before);
        assertThat(epoch.current()).isGreaterThan(afterOn);
    }

    @Test
    @DisplayName("AuroraStateCache.activateSimulation bumps")
    void auroraActivate_bumps() {
        AuroraStateCache cache = new AuroraStateCache(epoch);
        long before = epoch.current();
        cache.activateSimulation(AlertLevel.MODERATE,
                new AuroraStateCache.SimulatedNoaaData(6.0, 50.0, -8.0, "G2"));
        assertThat(epoch.current()).isGreaterThan(before);
    }

    @Test
    @DisplayName("AuroraStateCache.endSimulation (clear) bumps; reset bumps")
    void auroraClearAndReset_bump() {
        AuroraStateCache cache = new AuroraStateCache(epoch);
        cache.activateSimulation(AlertLevel.MODERATE,
                new AuroraStateCache.SimulatedNoaaData(6.0, 50.0, -8.0, "G2"));
        long beforeClear = epoch.current();
        assertThat(cache.endSimulation()).isTrue();
        long afterClear = epoch.current();
        cache.reset();
        assertThat(afterClear).isGreaterThan(beforeClear);
        assertThat(epoch.current()).isGreaterThan(afterClear);
    }

    @Test
    @DisplayName("AuroraStateCache.evaluate (a real alert starting) bumps")
    void auroraEvaluate_bumps() {
        AuroraStateCache cache = new AuroraStateCache(epoch);
        long before = epoch.current();
        cache.evaluate(AlertLevel.MODERATE);
        assertThat(epoch.current()).isGreaterThan(before);
    }

    @Test
    @DisplayName("BriefingEvaluationService: a cached_evaluation write and a wipe both bump")
    void cachedEvaluationWriteAndClear_bump() {
        BriefingEvaluationService evaluations = new BriefingEvaluationService(
                mock(CachedEvaluationRepository.class), mock(EvaluationDeltaLogRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                mock(FreshnessResolver.class),
                mock(StabilitySnapshotProvider.class),
                mock(com.gregochr.goldenhour.service.evaluation.SupersedingDispositionService.class), epoch);
        long before = epoch.current();

        evaluations.writeFromBatch("North East|2026-10-29|SUNRISE",
                List.of(new BriefingEvaluationResult("Bamburgh", 4, 70, 65, "Coastal.")));
        long afterWrite = epoch.current();
        evaluations.clearCache();

        assertThat(afterWrite).isGreaterThan(before);
        assertThat(epoch.current()).isGreaterThan(afterWrite);
    }

    @Test
    @DisplayName("TravelDayService: create and delete both bump")
    void travelDayCreateAndDelete_bump() {
        TravelDayRepository repository = mock(TravelDayRepository.class);
        when(repository.save(any(TravelDayEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.existsById(1L)).thenReturn(true);
        TravelDayService travel = new TravelDayService(repository, epoch);
        long before = epoch.current();

        travel.add(new TravelDayRequest(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), "away"));
        long afterAdd = epoch.current();
        travel.delete(1L);

        assertThat(afterAdd).isGreaterThan(before);
        assertThat(epoch.current()).isGreaterThan(afterAdd);
    }
}
