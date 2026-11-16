package com.gregochr.goldenhour.service.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code [BATCH SHARE]} shadow line of {@link EvaluationServiceImpl#submit}: measurement
 * only, never changes a request, never stops a submission.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplBatchShareTest {

    private static final LocalDate TODAY = LocalDate.of(2031, 3, 10);
    private static final LocalDateTime TIME = LocalDateTime.of(2031, 3, 10, 7, 23, 45);
    private static final EvaluationModel MODEL = EvaluationModel.SONNET_55;
    private static final String SUBMIT_DESCRIPTION = "EvaluationService forecast (SCHEDULED)";

    @Mock
    private BatchSubmissionService batchSubmissionService;
    @Mock
    private BatchRequestFactory batchRequestFactory;
    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastPromptStore forecastPromptStore;

    private EvaluationServiceImpl service;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2031-03-10T12:00:00Z"), ZoneOffset.UTC);
        service = new EvaluationServiceImpl(batchSubmissionService, batchRequestFactory,
                anthropicApiClient, claudeAuroraInterpreter, jobRunService, List.of(),
                forecastPromptStore, clock);
        logger = (Logger) LoggerFactory.getLogger(EvaluationServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private static AtmosphericData data(String name, LocalDateTime time) {
        return TestAtmosphericData.builder().locationName(name).solarEventTime(time)
                .targetType(TargetType.SUNRISE).build();
    }

    private static EvaluationTask.Forecast task(long id, String name, LocalDate date,
            LocalDateTime time, Long evalRowId, boolean forced) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        return new EvaluationTask.Forecast(loc, date, TargetType.SUNRISE, MODEL, data(name, time),
                EvaluationTask.Forecast.PromptKind.SKY, evalRowId, forced);
    }

    private BatchCreateParams.Request stubBuild(EvaluationTask.Forecast t, String prefixKey) {
        BatchCreateParams.Request request = mock(BatchCreateParams.Request.class);
        String customId = CustomIdFactory.forForecast(t.location().getId(), t.date(),
                t.targetType(), t.evalRowId(), t.forced());
        String message = "Location: " + t.data().locationName() + ". SUNRISE: "
                + t.data().solarEventTime() + " UTC.\nCloud: Low 10%";
        when(batchRequestFactory.buildForecastRequestAndPrompt(eq(customId), eq(MODEL),
                eq(t.data()), eq(MODEL.getMaxTokens())))
                .thenReturn(new BatchRequestFactory.ForecastRequest(request, message, "h"));
        when(batchRequestFactory.cachePrefixKey(MODEL, t.data())).thenReturn(prefixKey);
        return request;
    }

    private List<String> shareLines() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("[BATCH SHARE]")).toList();
    }

    @Test
    @DisplayName("a mixed batch logs one INFO line per bucket with the shareable counts")
    void mixedBatch_logsCountsPerBucket() {
        logger.setLevel(Level.DEBUG);
        String inland = MODEL.getModelId() + "|inland";
        String coastal = MODEL.getModelId() + "|coastal";
        EvaluationTask.Forecast a = task(1L, "Bamburgh", TODAY, TIME, 101L, false);
        EvaluationTask.Forecast b = task(2L, "Dunstanburgh", TODAY, TIME, 102L, false);
        EvaluationTask.Forecast c = task(3L, "Craster", TODAY, TIME.plusSeconds(7), 103L, false);
        EvaluationTask.Forecast forced = task(4L, "Forced", TODAY, TIME, 104L, true);
        EvaluationTask.Forecast noRow = task(5L, "NoRow", TODAY, TIME, null, false);
        EvaluationTask.Forecast far = task(6L, "Far", TODAY.plusDays(5), TIME, 106L, false);
        EvaluationTask.Forecast sea = task(7L, "Seaham", TODAY, TIME, 107L, false);
        List<EvaluationTask.Forecast> tasks = List.of(a, b, c, forced, noRow, far, sea);
        List<BatchCreateParams.Request> expected = new java.util.ArrayList<>();
        for (EvaluationTask.Forecast t : tasks) {
            expected.add(stubBuild(t, t == sea ? coastal : inland));
        }
        when(batchSubmissionService.submit(eq(expected), eq(BatchType.FORECAST),
                eq(BatchTriggerSource.SCHEDULED), eq(SUBMIT_DESCRIPTION), isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(9L, "msgbatch_x", 7));

        EvaluationHandle handle = service.submit(tasks, BatchTriggerSource.SCHEDULED);

        assertThat(handle.batchId()).isEqualTo("msgbatch_x");
        assertThat(shareLines()).containsExactly(
                "[BATCH SHARE] trigger=SCHEDULED retry=false bucket=inland-near tasks=5 eligible=3 "
                        + "strictShareable=1 minuteShareable=2 strictGroups=1 minuteGroups=1",
                "[BATCH SHARE] trigger=SCHEDULED retry=false bucket=inland-far tasks=1 eligible=1 "
                        + "strictShareable=0 minuteShareable=0 strictGroups=0 minuteGroups=0",
                "[BATCH SHARE] trigger=SCHEDULED retry=false bucket=coastal-near tasks=1 eligible=1 "
                        + "strictShareable=0 minuteShareable=0 strictGroups=0 minuteGroups=0");
        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage).toList()).containsExactly(
                "[BATCH SHARE] bucket=inland-near strict group [1=Bamburgh, 2=Dunstanburgh]",
                "[BATCH SHARE] bucket=inland-near minute group "
                        + "[1=Bamburgh, 2=Dunstanburgh, 3=Craster]");
    }

    @Test
    @DisplayName("a failure computing the keys logs a WARN and the submission is unchanged")
    void keyFailure_doesNotStopSubmission() {
        EvaluationTask.Forecast a = task(1L, "Bamburgh", TODAY, TIME, 101L, false);
        BatchCreateParams.Request request = mock(BatchCreateParams.Request.class);
        when(batchRequestFactory.buildForecastRequestAndPrompt(
                eq(CustomIdFactory.forForecast(1L, TODAY, TargetType.SUNRISE, 101L, false)),
                eq(MODEL), eq(a.data()), eq(MODEL.getMaxTokens())))
                .thenReturn(new BatchRequestFactory.ForecastRequest(request, "msg", "h"));
        when(batchRequestFactory.cachePrefixKey(MODEL, a.data()))
                .thenThrow(new IllegalStateException("boom"));
        when(batchSubmissionService.submit(eq(List.of(request)), eq(BatchType.FORECAST),
                eq(BatchTriggerSource.SCHEDULED), eq(SUBMIT_DESCRIPTION), isNull(), eq(false)))
                .thenReturn(new BatchSubmitResult(9L, "msgbatch_x", 1));

        EvaluationHandle handle = service.submit(List.of(a), BatchTriggerSource.SCHEDULED);

        assertThat(handle.batchId()).isEqualTo("msgbatch_x");
        assertThat(shareLines()).isEmpty();
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("[BATCH SHARE] key computation failed");
        });
        verify(forecastPromptStore).store(java.util.Map.of(101L, "msg"));
    }

    @Test
    @DisplayName("a failure writing the INFO line logs a WARN and the submission is unchanged")
    void summaryFailure_doesNotStopSubmission() {
        ListAppender<ILoggingEvent> throwing = new ListAppender<>() {
            @Override
            public synchronized void doAppend(ILoggingEvent event) {
                super.doAppend(event);
                if (event.getFormattedMessage().startsWith("[BATCH SHARE] trigger=")) {
                    throw new IllegalStateException("appender down");
                }
            }
        };
        throwing.start();
        logger.addAppender(throwing);
        try {
            EvaluationTask.Forecast a = task(1L, "Bamburgh", TODAY, TIME, 101L, false);
            BatchCreateParams.Request request = stubBuild(a, MODEL.getModelId() + "|inland");
            when(batchSubmissionService.submit(eq(List.of(request)), eq(BatchType.FORECAST),
                    eq(BatchTriggerSource.SCHEDULED), eq(SUBMIT_DESCRIPTION), isNull(), eq(false)))
                    .thenReturn(new BatchSubmitResult(9L, "msgbatch_x", 1));

            EvaluationHandle handle = service.submit(List.of(a), BatchTriggerSource.SCHEDULED);

            assertThat(handle.batchId()).isEqualTo("msgbatch_x");
            assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("[BATCH SHARE] summary failed"));
        } finally {
            logger.detachAppender(throwing);
        }
    }
}
