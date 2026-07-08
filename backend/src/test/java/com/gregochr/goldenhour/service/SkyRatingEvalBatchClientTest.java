package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.anthropic.models.messages.batches.MessageBatchSucceededResult;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SkyRatingEvalBatchClient} — the thin Anthropic Batch API wrapper. The SDK
 * client is mocked; the tests verify submit returns the batch id, isEnded reflects the ENDED
 * processing status, and collectResults drains success/failure rows into {@link ClaudeBatchOutcome}s.
 */
class SkyRatingEvalBatchClientTest {

    private AnthropicClient anthropicClient;
    private MessageService messageService;
    private BatchService batchService;
    private SkyRatingEvalBatchClient client;

    @BeforeEach
    void setUp() {
        anthropicClient = mock(AnthropicClient.class);
        messageService = mock(MessageService.class);
        batchService = mock(BatchService.class);
        when(anthropicClient.messages()).thenReturn(messageService);
        when(messageService.batches()).thenReturn(batchService);
        client = new SkyRatingEvalBatchClient(anthropicClient);
    }

    @Test
    @DisplayName("submit creates the batch and returns its id")
    void submitReturnsBatchId() {
        MessageBatch batch = mock(MessageBatch.class);
        when(batch.id()).thenReturn("msgbatch_123");
        when(batch.expiresAt()).thenReturn(OffsetDateTime.parse("2026-07-01T00:00:00Z"));
        when(batchService.create(org.mockito.ArgumentMatchers.any(BatchCreateParams.class)))
                .thenReturn(batch);

        String id = client.submit(List.of(mock(BatchCreateParams.Request.class)));

        assertThat(id).isEqualTo("msgbatch_123");
    }

    @Test
    @DisplayName("isEnded returns true when the batch processing status is ENDED")
    void isEndedTrueWhenEnded() {
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.ENDED);
        when(batchService.retrieve("msgbatch_done")).thenReturn(status);

        assertThat(client.isEnded("msgbatch_done")).isTrue();
    }

    @Test
    @DisplayName("isEnded returns false while the batch is still in progress")
    void isEndedFalseWhenInProgress() {
        MessageBatch status = mock(MessageBatch.class);
        when(status.processingStatus()).thenReturn(MessageBatch.ProcessingStatus.IN_PROGRESS);
        when(batchService.retrieve("msgbatch_slow")).thenReturn(status);

        assertThat(client.isEnded("msgbatch_slow")).isFalse();
    }

    @Test
    @DisplayName("collectResults drains success and failure rows into outcomes")
    @SuppressWarnings("unchecked")
    void collectResultsDrainsRows() {
        MessageBatchIndividualResponse ok = mock(MessageBatchIndividualResponse.class);
        when(ok.customId()).thenReturn("e_5_0_1");
        MessageBatchResult okResult = mock(MessageBatchResult.class);
        when(okResult.isSucceeded()).thenReturn(true);
        MessageBatchSucceededResult succeeded = mock(MessageBatchSucceededResult.class);
        when(okResult.succeeded()).thenReturn(Optional.of(succeeded));
        Message message = mock(Message.class);
        when(succeeded.message()).thenReturn(message);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn("{\"rating\":4}");
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.isText()).thenReturn(true);
        when(contentBlock.asText()).thenReturn(textBlock);
        when(message.content()).thenReturn(List.of(contentBlock));
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(3_800L);
        when(usage.outputTokens()).thenReturn(180L);
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(0L));
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(0L));
        when(message.usage()).thenReturn(usage);
        when(ok.result()).thenReturn(okResult);

        MessageBatchIndividualResponse bad = mock(MessageBatchIndividualResponse.class);
        when(bad.customId()).thenReturn("e_5_1_1");
        MessageBatchResult badResult = mock(MessageBatchResult.class);
        when(badResult.isSucceeded()).thenReturn(false);
        when(bad.result()).thenReturn(badResult);

        StreamResponse<MessageBatchIndividualResponse> stream = mock(StreamResponse.class);
        when(stream.stream()).thenReturn(Stream.of(ok, bad));
        when(batchService.resultsStreaming("msgbatch_done")).thenReturn(stream);

        List<ClaudeBatchOutcome> outcomes = client.collectResults("msgbatch_done");

        assertThat(outcomes).hasSize(2);
        ClaudeBatchOutcome first = outcomes.get(0);
        assertThat(first.customId()).isEqualTo("e_5_0_1");
        assertThat(first.succeeded()).isTrue();
        assertThat(first.rawText()).isEqualTo("{\"rating\":4}");
        assertThat(first.tokenUsage().inputTokens()).isEqualTo(3_800L);
        assertThat(outcomes.get(1).succeeded()).isFalse();
    }
}
