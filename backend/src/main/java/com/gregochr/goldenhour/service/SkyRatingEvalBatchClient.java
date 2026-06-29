package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.ClaudeBatchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin, testable wrapper over the three Anthropic Batch API calls the sky-rating eval needs:
 * submit, await completion, and collect results. It exists so {@link SkyRatingEvalBatchService}
 * can orchestrate against plain records ({@link ClaudeBatchOutcome}) and a boolean, mocking this
 * seam rather than the deep SDK batch types.
 *
 * <p>Deliberately separate from the forecast pipeline's {@code BatchSubmissionService} /
 * {@code BatchPollingService} / {@code BatchResultProcessor}: those are coupled to
 * {@code ForecastBatchEntity} persistence, dispositions, and pipeline runs. The eval is a small,
 * self-contained weekly job (fixtures × runs × models = a few hundred requests) that completes in
 * minutes, so it polls inline rather than threading its batches through the forecast machinery.
 */
@Service
public class SkyRatingEvalBatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(SkyRatingEvalBatchClient.class);

    private final AnthropicClient anthropicClient;

    /**
     * Constructs the client.
     *
     * @param anthropicClient the raw Anthropic SDK client
     */
    public SkyRatingEvalBatchClient(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    /**
     * Submits the requests as a single Anthropic message batch.
     *
     * @param requests the batch requests (must be non-empty)
     * @return the Anthropic batch id
     */
    public String submit(List<BatchCreateParams.Request> requests) {
        MessageBatch batch = anthropicClient.messages().batches().create(
                BatchCreateParams.builder().requests(requests).build());
        LOG.info("Sky-rating eval batch submitted: batchId={}, {} request(s), expires={}",
                batch.id(), requests.size(), batch.expiresAt());
        return batch.id();
    }

    /**
     * Polls the batch until its processing status is {@code ENDED} or the timeout elapses.
     *
     * <p>Checks the status before each sleep, so a batch that is already ended returns immediately
     * without waiting. Anthropic always drives a batch to {@code ENDED} once processing finishes
     * (even if every request errored), so polling for that single terminal state is sufficient.
     *
     * @param batchId      the Anthropic batch id
     * @param timeout      give up after this long
     * @param pollInterval wait this long between status checks
     * @return {@code true} if the batch ended within the timeout, {@code false} otherwise
     */
    public boolean awaitEnded(String batchId, Duration timeout, Duration pollInterval) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            MessageBatch.ProcessingStatus status =
                    anthropicClient.messages().batches().retrieve(batchId).processingStatus();
            if (status.equals(MessageBatch.ProcessingStatus.ENDED)) {
                LOG.info("Sky-rating eval batch {} ENDED", batchId);
                return true;
            }
            if (System.currentTimeMillis() + pollInterval.toMillis() > deadline) {
                LOG.warn("Sky-rating eval batch {} did not end within {} (last status {})",
                        batchId, timeout, status);
                return false;
            }
            LOG.debug("Sky-rating eval batch {} status {}, waiting {}", batchId, status, pollInterval);
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Sky-rating eval batch {} polling interrupted", batchId);
                return false;
            }
        }
    }

    /**
     * Streams the batch's individual responses and drains each into a {@link ClaudeBatchOutcome}.
     *
     * @param batchId the Anthropic batch id (must have ended)
     * @return one outcome per response, in stream order
     */
    public List<ClaudeBatchOutcome> collectResults(String batchId) {
        List<ClaudeBatchOutcome> outcomes = new ArrayList<>();
        try (var streamResp = anthropicClient.messages().batches().resultsStreaming(batchId)) {
            for (MessageBatchIndividualResponse response : (Iterable<MessageBatchIndividualResponse>)
                    streamResp.stream()::iterator) {
                outcomes.add(toOutcome(response));
            }
        }
        return outcomes;
    }

    private static ClaudeBatchOutcome toOutcome(MessageBatchIndividualResponse response) {
        String customId = response.customId();
        if (!response.result().isSucceeded()) {
            return ClaudeBatchOutcome.failure(customId, "ERRORED", "batch_error",
                    "request did not succeed");
        }
        Message message = response.result().succeeded()
                .map(succeeded -> succeeded.message())
                .orElse(null);
        if (message == null) {
            return ClaudeBatchOutcome.failure(customId, "NO_MESSAGE", "extraction_error",
                    "succeeded but no message");
        }
        String text = message.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse(null);
        if (text == null) {
            return ClaudeBatchOutcome.failure(customId, "NO_TEXT", "extraction_error",
                    "no text content blocks");
        }
        Usage usage = message.usage();
        TokenUsage tokens = new TokenUsage(
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
        return ClaudeBatchOutcome.success(customId, text, tokens, null);
    }
}
