package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.client.NoaaSwpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests that the CACHED gate in {@code collectForecastTasks} uses freshness-aware
 * cache checks, so stale entries are refreshed by the overnight batch.
 */
@ExtendWith(MockitoExtension.class)
class CollectForecastTasksCachedGateTest {

    @Mock private AnthropicClient anthropicClient;
    @Mock private ForecastBatchRepository batchRepository;
    @Mock private LocationService locationService;
    @Mock private BriefingService briefingService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private ForecastService forecastService;
    @Mock private ForecastStabilityClassifier stabilityClassifier;
    @Mock private PromptBuilder promptBuilder;
    @Mock private CoastalPromptBuilder coastalPromptBuilder;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private NoaaSwpcClient noaaSwpcClient;
    @Mock private WeatherTriageService weatherTriageService;
    @Mock private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock private AuroraOrchestrator auroraOrchestrator;
    @Mock private LocationRepository locationRepository;
    @Mock private AuroraProperties auroraProperties;
    @Mock private DynamicSchedulerService dynamicSchedulerService;
    @Mock private JobRunService jobRunService;
    @Mock private OpenMeteoService openMeteoService;
    @Mock private SolarService solarService;

    private ScheduledBatchEvaluationService service;

    /** Tomorrow's date — always in the future so PAST_DATE doesn't trigger. */
    private final LocalDate tomorrow = LocalDate.now().plusDays(1);

    @BeforeEach
    void setUp() {
        service = new ScheduledBatchEvaluationService(
                anthropicClient, batchRepository, locationService, briefingService,
                briefingEvaluationService, forecastService, stabilityClassifier,
                promptBuilder, coastalPromptBuilder, modelSelectionService,
                noaaSwpcClient, weatherTriageService, claudeAuroraInterpreter,
                auroraOrchestrator, locationRepository, auroraProperties,
                dynamicSchedulerService, jobRunService, openMeteoService, solarService,
                18, 0.5);  // 18-hour freshness threshold, 50% min prefetch ratio
    }

    /**
     * Invokes the private {@code collectForecastTasks} method via reflection.
     */
    @SuppressWarnings("unchecked")
    private List<?> invokeCollectForecastTasks(DailyBriefingResponse briefing) throws Exception {
        Method method = ScheduledBatchEvaluationService.class
                .getDeclaredMethod("collectForecastTasks", DailyBriefingResponse.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(service, briefing);
    }

    private DailyBriefingResponse briefingWithOneSlot(String regionName, String locationName) {
        BriefingSlot slot = new BriefingSlot(
                locationName, LocalDateTime.now().plusDays(1), Verdict.GO,
                null, null, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                regionName, Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary eventSummary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        BriefingDay day = new BriefingDay(tomorrow, List.of(eventSummary));
        return new DailyBriefingResponse(
                LocalDateTime.now(), null, List.of(day), List.of(),
                null, null, false, false, 0, null, List.of(), List.of());
    }

    private LocationEntity locationEntity(String name, long id) {
        LocationEntity loc = new LocationEntity();
        loc.setName(name);
        loc.setId(id);
        return loc;
    }

    @Test
    @DisplayName("stale cache entry (24h old) is NOT skipped — slot becomes a task")
    void staleCacheEntryRefreshed() throws Exception {
        String cacheKey = "North East|" + tomorrow + "|SUNRISE";
        when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                eq(Duration.ofHours(18)))).thenReturn(false);
        when(locationService.findAllEnabled())
                .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

        List<?> tasks = invokeCollectForecastTasks(
                briefingWithOneSlot("North East", "Bamburgh"));

        assertThat(tasks).hasSize(1);
    }

    @Test
    @DisplayName("fresh cache entry (3h old) IS skipped — slot does not become a task")
    void freshCacheEntrySkipped() throws Exception {
        String cacheKey = "North East|" + tomorrow + "|SUNRISE";
        when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                eq(Duration.ofHours(18)))).thenReturn(true);

        List<?> tasks = invokeCollectForecastTasks(
                briefingWithOneSlot("North East", "Bamburgh"));

        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("absent cache entry falls through to other filters")
    void absentCacheEntryPassesThrough() throws Exception {
        String cacheKey = "North East|" + tomorrow + "|SUNRISE";
        when(briefingEvaluationService.hasFreshEvaluation(eq(cacheKey),
                eq(Duration.ofHours(18)))).thenReturn(false);
        when(locationService.findAllEnabled())
                .thenReturn(List.of(locationEntity("Bamburgh", 42L)));

        List<?> tasks = invokeCollectForecastTasks(
                briefingWithOneSlot("North East", "Bamburgh"));

        assertThat(tasks).hasSize(1);
    }
}
