package com.gregochr.goldenhour.testsupport.promptusage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;

/**
 * An {@link AnthropicApiClient} that records the token usage of every paid call it makes
 * (docs/engineering/ci-spend-plan.md, C1). Used only by the paid prompt-regression tests.
 *
 * <p>Usage is keyed by the <em>requested</em> model ({@code params.model().asString()}), which always equals an
 * {@code EvaluationModel} id; the API's echoed id may carry a dated suffix. A call that throws counts one errored
 * call and is rethrown unchanged, except a gate refusal, which made no request and so cost nothing.
 */
public class RecordingAnthropicApiClient extends AnthropicApiClient {

    private final PromptUsageRecorder recorder;

    /**
     * Records to the process-wide recorder.
     *
     * @param client the configured Anthropic SDK client
     */
    public RecordingAnthropicApiClient(AnthropicClient client) {
        this(client, PromptUsageRecorder.shared());
    }

    /**
     * Records to the given recorder.
     *
     * @param client   the configured Anthropic SDK client
     * @param recorder where usage is recorded
     */
    public RecordingAnthropicApiClient(AnthropicClient client, PromptUsageRecorder recorder) {
        super(client);
        this.recorder = recorder;
    }

    @Override
    public Message createMessage(MessageCreateParams params) {
        String model = params.model().asString();
        Message message;
        try {
            message = super.createMessage(params);
        } catch (RuntimeException e) {
            recorder.recordError(model);
            throw e;
        }
        recorder.recordCall(model, TokenUsage.from(message.usage()));
        return message;
    }

    @Override
    public Message createAskMessage(MessageCreateParams params, RequestOptions options, CallGate gate) {
        String model = params.model().asString();
        Message message;
        try {
            message = super.createAskMessage(params, options, gate);
        } catch (CallRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            recorder.recordError(model);
            throw e;
        }
        recorder.recordCall(model, TokenUsage.from(message.usage()));
        return message;
    }
}
