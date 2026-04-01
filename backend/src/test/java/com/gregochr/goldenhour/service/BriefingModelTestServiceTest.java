package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.BriefingModelTestResultRepository;
import com.gregochr.goldenhour.repository.BriefingModelTestRunRepository;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingModelTestService}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingModelTestServiceTest {

    @Mock private BriefingService briefingService;
    @Mock private BriefingBestBetAdvisor bestBetAdvisor;
    @Mock private BriefingModelTestRunRepository runRepository;
    @Mock private BriefingModelTestResultRepository resultRepository;
    @Mock private CostCalculator costCalculator;
    @Mock private ExchangeRateService exchangeRateService;

    private BriefingModelTestService service;

    @BeforeEach
    void setUp() {
        service = new BriefingModelTestService(
                briefingService, bestBetAdvisor,
                runRepository, resultRepository, costCalculator,
                exchangeRateService, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("Throws IllegalStateException when no cached briefing")
    void noCachedBriefingThrows() {
        when(briefingService.getCachedBriefing()).thenReturn(null);

        assertThatThrownBy(() -> service.runComparison())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No cached briefing");
    }

    @Test
    @DisplayName("Success case — 3 results saved with correct models")
    void successCase() throws Exception {
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.now(), "Test headline", List.of(), List.of(),
                null, null, false, false, 0, "Opus");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);

        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);

        BestBet pick = new BestBet(1, "Go shoot", "Clear.", "2026-04-01_sunset",
                "Northumberland", Confidence.HIGH, null, null, null, null);
        TokenUsage usage = new TokenUsage(500, 100, 0, 0);

        BriefingBestBetAdvisor.ComparisonRun comparisonRun = new BriefingBestBetAdvisor.ComparisonRun(
                "{\"test\":\"rollup\"}", List.of(
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.HAIKU, "raw1", List.of(pick), List.of(pick), 100, usage),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.SONNET, "raw2", List.of(pick), List.of(pick), 200, usage),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.OPUS, "raw3", List.of(pick), List.of(pick), 300, usage)));

        when(bestBetAdvisor.compareModels(any(), any())).thenReturn(comparisonRun);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(500L);

        BriefingModelTestRunEntity savedRun = BriefingModelTestRunEntity.builder()
                .id(1L).succeeded(3).failed(0).build();
        when(runRepository.save(any())).thenReturn(savedRun);

        service.runComparison();

        // Verify run saved with correct counts
        ArgumentCaptor<BriefingModelTestRunEntity> runCaptor =
                ArgumentCaptor.forClass(BriefingModelTestRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        BriefingModelTestRunEntity capturedRun = runCaptor.getValue();
        assertThat(capturedRun.getSucceeded()).isEqualTo(3);
        assertThat(capturedRun.getFailed()).isEqualTo(0);
        assertThat(capturedRun.getRollupJson()).isEqualTo("{\"test\":\"rollup\"}");
        assertThat(capturedRun.getExchangeRateGbpPerUsd()).isEqualTo(0.79);

        // Verify 3 results saved
        ArgumentCaptor<BriefingModelTestResultEntity> resultCaptor =
                ArgumentCaptor.forClass(BriefingModelTestResultEntity.class);
        verify(resultRepository, times(3)).save(resultCaptor.capture());
        List<BriefingModelTestResultEntity> capturedResults = resultCaptor.getAllValues();
        assertThat(capturedResults).hasSize(3);
        assertThat(capturedResults.get(0).getEvaluationModel()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(capturedResults.get(1).getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(capturedResults.get(2).getEvaluationModel()).isEqualTo(EvaluationModel.OPUS);
        assertThat(capturedResults.stream().allMatch(BriefingModelTestResultEntity::getSucceeded)).isTrue();
    }

    @Test
    @DisplayName("One model failure — succeeded=2, failed=1")
    void oneModelFailure() throws Exception {
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.now(), "Test", List.of(), List.of(),
                null, null, false, false, 0, "Opus");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);

        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);

        BestBet pick = new BestBet(1, "Go", "Clear.", "2026-04-01_sunset",
                "Northumberland", Confidence.HIGH, null, null, null, null);
        TokenUsage usage = new TokenUsage(500, 100, 0, 0);

        BriefingBestBetAdvisor.ComparisonRun comparisonRun = new BriefingBestBetAdvisor.ComparisonRun(
                "{}", List.of(
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.HAIKU, "raw", List.of(pick), List.of(pick), 100, usage),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.SONNET, null, List.of(), List.of(), 0, TokenUsage.EMPTY),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.OPUS, "raw", List.of(pick), List.of(pick), 300, usage)));

        when(bestBetAdvisor.compareModels(any(), any())).thenReturn(comparisonRun);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(500L);

        BriefingModelTestRunEntity savedRun = BriefingModelTestRunEntity.builder()
                .id(1L).succeeded(2).failed(1).build();
        when(runRepository.save(any())).thenReturn(savedRun);

        service.runComparison();

        ArgumentCaptor<BriefingModelTestRunEntity> runCaptor =
                ArgumentCaptor.forClass(BriefingModelTestRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getSucceeded()).isEqualTo(2);
        assertThat(runCaptor.getValue().getFailed()).isEqualTo(1);

        ArgumentCaptor<BriefingModelTestResultEntity> resultCaptor =
                ArgumentCaptor.forClass(BriefingModelTestResultEntity.class);
        verify(resultRepository, times(3)).save(resultCaptor.capture());
        BriefingModelTestResultEntity failedResult = resultCaptor.getAllValues().get(1);
        assertThat(failedResult.getSucceeded()).isFalse();
        assertThat(failedResult.getErrorMessage()).isEqualTo("Model call failed");
    }

    @Test
    @DisplayName("Validation stats persisted — picksReturned and picksValid")
    void validationStatsPersisted() throws Exception {
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.now(), "Test", List.of(), List.of(),
                null, null, false, false, 0, "Opus");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);

        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);

        BestBet pick1 = new BestBet(1, "Go", "Clear.", "e1", "R1",
                Confidence.HIGH, null, null, null, null);
        BestBet pick2 = new BestBet(2, "Also", "Nice.", "e2", "R2",
                Confidence.MEDIUM, null, null, null, null);
        TokenUsage usage = new TokenUsage(500, 100, 0, 0);

        // 2 parsed, 1 validated (simulating one pick failing validation)
        BriefingBestBetAdvisor.ComparisonRun comparisonRun = new BriefingBestBetAdvisor.ComparisonRun(
                "{}", List.of(
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.HAIKU, "raw",
                                List.of(pick1, pick2), List.of(pick1), 100, usage),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.SONNET, "raw",
                                List.of(pick1, pick2), List.of(pick1, pick2), 200, usage),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.OPUS, "raw",
                                List.of(pick1), List.of(pick1), 300, usage)));

        when(bestBetAdvisor.compareModels(any(), any())).thenReturn(comparisonRun);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(500L);

        BriefingModelTestRunEntity savedRun = BriefingModelTestRunEntity.builder()
                .id(1L).succeeded(3).failed(0).build();
        when(runRepository.save(any())).thenReturn(savedRun);

        service.runComparison();

        ArgumentCaptor<BriefingModelTestResultEntity> captor =
                ArgumentCaptor.forClass(BriefingModelTestResultEntity.class);
        verify(resultRepository, times(3)).save(captor.capture());

        BriefingModelTestResultEntity haiku = captor.getAllValues().get(0);
        assertThat(haiku.getPicksReturned()).isEqualTo(2);
        assertThat(haiku.getPicksValid()).isEqualTo(1);

        BriefingModelTestResultEntity sonnet = captor.getAllValues().get(1);
        assertThat(sonnet.getPicksReturned()).isEqualTo(2);
        assertThat(sonnet.getPicksValid()).isEqualTo(2);

        BriefingModelTestResultEntity opus = captor.getAllValues().get(2);
        assertThat(opus.getPicksReturned()).isEqualTo(1);
        assertThat(opus.getPicksValid()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rollup JSON persisted on run entity")
    void rollupJsonPersisted() throws Exception {
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.now(), "Test", List.of(), List.of(),
                null, null, false, false, 0, "Opus");
        when(briefingService.getCachedBriefing()).thenReturn(briefing);

        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);

        String rollupJson = "{\"events\":[],\"validEvents\":[]}";
        BriefingBestBetAdvisor.ComparisonRun comparisonRun = new BriefingBestBetAdvisor.ComparisonRun(
                rollupJson, List.of(
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.HAIKU, "raw", List.of(), List.of(), 100,
                                new TokenUsage(100, 50, 0, 0)),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.SONNET, "raw", List.of(), List.of(), 200,
                                new TokenUsage(100, 50, 0, 0)),
                        new BriefingBestBetAdvisor.ModelComparisonResult(
                                EvaluationModel.OPUS, "raw", List.of(), List.of(), 300,
                                new TokenUsage(100, 50, 0, 0))));

        when(bestBetAdvisor.compareModels(any(), any())).thenReturn(comparisonRun);
        when(costCalculator.calculateCostMicroDollars(any(EvaluationModel.class), any(TokenUsage.class)))
                .thenReturn(100L);

        BriefingModelTestRunEntity savedRun = BriefingModelTestRunEntity.builder().id(1L).build();
        when(runRepository.save(any())).thenReturn(savedRun);

        service.runComparison();

        ArgumentCaptor<BriefingModelTestRunEntity> captor =
                ArgumentCaptor.forClass(BriefingModelTestRunEntity.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getRollupJson()).isEqualTo(rollupJson);
    }

    @Test
    @DisplayName("getRecentRuns delegates to repository")
    void getRecentRunsDelegates() {
        List<BriefingModelTestRunEntity> expected = List.of(
                BriefingModelTestRunEntity.builder().id(1L).build());
        when(runRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(expected);

        assertThat(service.getRecentRuns()).isEqualTo(expected);
    }

    @Test
    @DisplayName("getResults delegates to repository")
    void getResultsDelegates() {
        List<BriefingModelTestResultEntity> expected = List.of(
                BriefingModelTestResultEntity.builder().id(1L).build());
        when(resultRepository.findByTestRunIdOrderByEvaluationModelAsc(42L)).thenReturn(expected);

        assertThat(service.getResults(42L)).isEqualTo(expected);
    }
}
