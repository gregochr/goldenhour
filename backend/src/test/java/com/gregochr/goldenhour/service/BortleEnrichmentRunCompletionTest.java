package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.LightPollutionClient.SkyBrightnessResult;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Bortle enrichment guard against a REAL {@link RunProgressTracker}: the unit tests in
 * {@code BortleEnrichmentServiceTest} use a mocked tracker whose {@code getProgress} is null, so the
 * sweep of unfinished tasks never runs there. Lives in this package for the tracker's package-private
 * emitter hook.
 */
@ExtendWith(MockitoExtension.class)
class BortleEnrichmentRunCompletionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private LightPollutionClient lightPollutionClient;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;
    @Mock
    private ScheduledExecutorService graceScheduler;

    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString().replace("event:", "").replace("\ndata:", "|").replace("\n\n", ""));
            super.send(builder);
        }
    }

    private static LocationEntity location(String name, double lat) {
        return LocationEntity.builder().id(1L).name(name).lat(lat).lon(-1.7).enabled(true).build();
    }

    @Test
    @DisplayName("a throw part-way through: the task it was on ends FAILED from EVALUATING, run-complete reports "
            + "PARTIAL with the generic reason, and the job_run closes with the tracker's counts")
    void enrichAll_throwsMidRun_stuckTaskFailedRunCompletedWithRealTracker() throws Exception {
        RunProgressTracker tracker = new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L) {
            @Override
            SseEmitter newEmitter() {
                return new RecordingEmitter();
            }
        };
        List<LocationTaskEvent> published = new ArrayList<>();
        BortleEnrichmentService service = new BortleEnrichmentService(locationRepository, lightPollutionClient,
                jobRunService, tracker, event -> {
                    if (event instanceof LocationTaskEvent taskEvent) {
                        published.add(taskEvent);
                        tracker.onTaskEvent(taskEvent);
                    }
                });
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(1L);
        LocationEntity good = location("Bamburgh", 55.6);
        LocationEntity bad = location("Kielder", 55.2);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(good, bad));
        when(lightPollutionClient.querySkyBrightness(55.6, -1.7, "key"))
                .thenReturn(new SkyBrightnessResult(21.75, 3));
        when(lightPollutionClient.querySkyBrightness(55.2, -1.7, "key"))
                .thenThrow(new IllegalStateException("socket reset key=secret"));
        RecordingEmitter panel = (RecordingEmitter) tracker.subscribe(1L);

        service.enrichAll("key", jobRun);

        assertThat(published).filteredOn(e -> e.getState() == LocationTaskState.FAILED).singleElement()
                .satisfies(e -> {
                    assertThat(e.getTaskKey()).isEqualTo("Kielder|–|BORTLE");
                    assertThat(e.getFailedStep()).isEqualTo("EVALUATING");
                    assertThat(e.getErrorMessage())
                            .isEqualTo("Did not finish: the run stopped before this place was evaluated.");
                });
        List<String> completes = panel.events.stream().filter(e -> e.startsWith("run-complete|")).toList();
        assertThat(completes).hasSize(1);
        JsonNode complete = JSON.readTree(completes.getFirst().substring("run-complete|".length()));
        assertThat(complete.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(complete.get("reason").asText()).isEqualTo("The run stopped unexpectedly. See the server log.");
        assertThat(complete.get("completed").asInt()).isEqualTo(1);
        assertThat(complete.get("failed").asInt()).isEqualTo(1);
        assertThat(panel.events).noneMatch(e -> e.contains("secret"));
        verify(jobRunService).completeRun(jobRun, 1, 1);
    }

    @Test
    @DisplayName("a normal run: one place enriched, one that returned no data failed, and the job_run closes with "
            + "the real tracker's counts, which are what run-complete reports")
    void enrichAll_normalRun_closesWithTheRealTrackersCounts() throws Exception {
        RunProgressTracker tracker = new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L) {
            @Override
            SseEmitter newEmitter() {
                return new RecordingEmitter();
            }
        };
        BortleEnrichmentService service = new BortleEnrichmentService(locationRepository, lightPollutionClient,
                jobRunService, tracker, event -> {
                    if (event instanceof LocationTaskEvent taskEvent) {
                        tracker.onTaskEvent(taskEvent);
                    }
                });
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(1L);
        when(locationRepository.findByBortleClassIsNull())
                .thenReturn(List.of(location("Bamburgh", 55.6), location("Kielder", 55.2)));
        when(lightPollutionClient.querySkyBrightness(55.6, -1.7, "key"))
                .thenReturn(new SkyBrightnessResult(21.75, 3));
        when(lightPollutionClient.querySkyBrightness(55.2, -1.7, "key")).thenReturn(null);
        RecordingEmitter panel = (RecordingEmitter) tracker.subscribe(1L);

        service.enrichAll("key", jobRun);

        List<String> completes = panel.events.stream().filter(e -> e.startsWith("run-complete|")).toList();
        assertThat(completes).hasSize(1);
        JsonNode complete = JSON.readTree(completes.getFirst().substring("run-complete|".length()));
        assertThat(complete.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(complete.get("completed").asInt()).isEqualTo(1);
        assertThat(complete.get("failed").asInt()).isEqualTo(1);
        assertThat(complete.get("reason").isNull()).isTrue();
        verify(jobRunService).completeRun(jobRun, 1, 1);
    }
}
