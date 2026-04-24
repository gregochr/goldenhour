package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the unified batch submission service that replaced {@code submitBatch} and
 * {@code submitBatchWithResult} in Pass 2.
 */
@ExtendWith(MockitoExtension.class)
class BatchSubmissionServiceTest {

    @Mock
    private AnthropicClient anthropicClient;
    @Mock
    private MessageService messageService;
    @Mock
    private BatchService batchService;
    @Mock
    private ForecastBatchRepository batchRepository;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private MessageBatch messageBatch;
    @Mock
    private AsyncStreamResponse<String> streamResponse;

    private BatchSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new BatchSubmissionService(anthropicClient, batchRepository, jobRunService);
    }

    private void stubBatchCreate(String batchId) {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        when(batchService.create(any(BatchCreateParams.class))).thenReturn(messageBatch);
        when(messageBatch.id()).thenReturn(batchId);
        when(messageBatch.expiresAt()).thenReturn(OffsetDateTime.now().plusHours(24));
    }

    @Test
    @DisplayName("submit: empty request list returns null without contacting Anthropic")
    void submit_emptyRequests_returnsNull() {
        BatchSubmitResult result = service.submit(List.of(), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test");

        assertThat(result).isNull();
        verify(anthropicClient, never()).messages();
        verify(batchRepository, never()).save(any());
        verify(jobRunService, never()).startBatchRun(anyInt(), anyString());
    }

    @Test
    @DisplayName("submit: successful scheduled submission persists entity and returns result")
    void submit_scheduledSucceeds_persistsAndReturns() {
        stubBatchCreate("msgbatch_scheduled");
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(jobRun);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("fc-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.SCHEDULED, "Test scheduled");

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_scheduled");
        assertThat(result.requestCount()).isEqualTo(1);

        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getAnthropicBatchId())
                .isEqualTo("msgbatch_scheduled");
        assertThat(entityCaptor.getValue().getBatchType()).isEqualTo(BatchType.FORECAST);
        assertThat(entityCaptor.getValue().getJobRunId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("submit: force trigger is accepted and flagged through logs/result the same way")
    void submit_forceTrigger_producesResultSameShape() {
        stubBatchCreate("msgbatch_force");
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(null);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("force-LakeDist-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(512)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.FORCE, "Force test");

        assertThat(result).isNotNull();
        assertThat(result.batchId()).isEqualTo("msgbatch_force");
    }

    @Test
    @DisplayName("submit: Anthropic exception returns null without rethrowing")
    void submit_anthropicThrows_returnsNull() {
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        when(batchService.create(any(BatchCreateParams.class)))
                .thenThrow(new RuntimeException("Anthropic 529"));

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("fc-1-2026-04-16-SUNRISE")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.FORECAST,
                BatchTriggerSource.JFDI, "JFDI test");

        assertThat(result).isNull();
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: null job run still persists batch entity without linking")
    void submit_nullJobRun_persistsWithoutLink() {
        stubBatchCreate("msgbatch_nojobrun");
        when(jobRunService.startBatchRun(anyInt(), anyString())).thenReturn(null);

        BatchCreateParams.Request request = BatchCreateParams.Request.builder()
                .customId("au-MODERATE-2026-04-16")
                .params(BatchCreateParams.Request.Params.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(1024)
                        .addUserMessage("test")
                        .build())
                .build();

        BatchSubmitResult result = service.submit(List.of(request), BatchType.AURORA,
                BatchTriggerSource.SCHEDULED, "Aurora");

        assertThat(result).isNotNull();
        ArgumentCaptor<ForecastBatchEntity> entityCaptor =
                ArgumentCaptor.forClass(ForecastBatchEntity.class);
        verify(batchRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getJobRunId()).isNull();
        assertThat(entityCaptor.getValue().getBatchType()).isEqualTo(BatchType.AURORA);
    }
}
