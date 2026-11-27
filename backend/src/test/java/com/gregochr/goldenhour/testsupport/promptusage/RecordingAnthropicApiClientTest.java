package com.gregochr.goldenhour.testsupport.promptusage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecordingAnthropicApiClient}.
 */
@ExtendWith(MockitoExtension.class)
class RecordingAnthropicApiClientTest {

    private static final String REQUESTED = "claude-sonnet-5-5";

    private AnthropicClient client;

    @Mock
    private AnthropicClient askClient;

    @Mock
    private MessageService messageService;

    private PromptUsageRecorder recorder;

    private final AtomicReference<Consumer<ClientOptions.Builder>> capturedOptions = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        recorder = new PromptUsageRecorder(null);
        client = Mockito.mock(AnthropicClient.class);
    }

    /**
     * Makes {@code client.withOptions(...)} hand back {@code askClient} and remember the consumer it was given,
     * without a broad argument matcher. Called before the recording client is built, as its constructor derives
     * the Ask client.
     */
    @SuppressWarnings("unchecked")
    private void wireAskClient() {
        client = Mockito.mock(AnthropicClient.class, invocation -> {
            if ("withOptions".equals(invocation.getMethod().getName())) {
                capturedOptions.set(invocation.getArgument(0));
                return askClient;
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static MessageCreateParams params() {
        return MessageCreateParams.builder().model(REQUESTED).maxTokens(100).addUserMessage("Hello").build();
    }

    private static Message message(long input, long output, long cacheWrite, long cacheRead, long oneHour) {
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-5-5-20261101"))
                .content(List.of())
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .diagnostics(Optional.empty())
                .usage(Usage.builder().speed(Usage.Speed.STANDARD)
                        .inputTokens(input)
                        .outputTokens(output)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(oneHour)
                                .build())
                        .cacheCreationInputTokens(cacheWrite)
                        .cacheReadInputTokens(cacheRead)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder().webSearchRequests(0).webFetchRequests(0).build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
                        .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(0).build())
                        .build())
                .build();
    }

    @Test
    @DisplayName("createMessage records the clamped usage under the requested model and returns the message")
    void createMessageRecords() {
        Message response = message(10, 20, 30, 40, 500);
        when(client.messages()).thenReturn(messageService);
        MessageCreateParams params = params();
        when(messageService.create(params)).thenReturn(response);

        Message result = new RecordingAnthropicApiClient(client, recorder).createMessage(params);

        assertThat(result).isSameAs(response);
        assertThat(recorder.toJson()).isEqualTo("{\"models\":[{\"model\":\"claude-sonnet-5-5\",\"calls\":1,"
                + "\"erroredCalls\":0,\"input\":10,\"output\":20,\"cacheRead\":40,\"cacheWrite\":30,"
                + "\"cacheWrite1h\":30}]}\n");
    }

    @Test
    @DisplayName("createMessage counts a thrown call as errored and rethrows it")
    void createMessageErrors() {
        RuntimeException boom = new IllegalStateException("boom");
        when(client.messages()).thenReturn(messageService);
        MessageCreateParams params = params();
        when(messageService.create(params)).thenThrow(boom);
        RecordingAnthropicApiClient api = new RecordingAnthropicApiClient(client, recorder);

        assertThatThrownBy(() -> api.createMessage(params)).isSameAs(boom);

        assertThat(recorder.toJson()).contains("\"calls\":0,\"erroredCalls\":1");
    }

    @Test
    @DisplayName("createAskMessage records the usage under the requested model")
    void createAskMessageRecords() {
        Message response = message(1, 2, 3, 4, 0);
        wireAskClient();
        when(askClient.messages()).thenReturn(messageService);
        MessageCreateParams params = params();
        RequestOptions options = RequestOptions.none();
        when(messageService.create(params, options)).thenReturn(response);

        Message result = new RecordingAnthropicApiClient(client, recorder)
                .createAskMessage(params, options, () -> true);

        assertThat(result).isSameAs(response);
        assertThat(recorder.toJson()).isEqualTo("{\"models\":[{\"model\":\"claude-sonnet-5-5\",\"calls\":1,"
                + "\"erroredCalls\":0,\"input\":1,\"output\":2,\"cacheRead\":4,\"cacheWrite\":3,"
                + "\"cacheWrite1h\":0}]}\n");
    }

    @Test
    @DisplayName("the Ask client is still derived with the SDK's own retries switched off")
    void askClientDisablesSdkRetries() {
        wireAskClient();
        new RecordingAnthropicApiClient(client, recorder);

        ClientOptions.Builder builder = Mockito.mock(ClientOptions.Builder.class);
        capturedOptions.get().accept(builder);

        verify(builder).maxRetries(0);
    }

    @Test
    @DisplayName("createAskMessage counts a thrown call as errored and rethrows it")
    void createAskMessageErrors() {
        RuntimeException boom = new IllegalStateException("boom");
        wireAskClient();
        when(askClient.messages()).thenReturn(messageService);
        MessageCreateParams params = params();
        RequestOptions options = RequestOptions.none();
        when(messageService.create(params, options)).thenThrow(boom);
        RecordingAnthropicApiClient api = new RecordingAnthropicApiClient(client, recorder);

        assertThatThrownBy(() -> api.createAskMessage(params, options, () -> true)).isSameAs(boom);

        assertThat(recorder.toJson()).contains("\"calls\":0,\"erroredCalls\":1");
    }

    @Test
    @DisplayName("a refused gate made no request, so it is rethrown and not counted")
    void refusedGateIsNotCounted() {
        wireAskClient();
        MessageCreateParams params = params();
        RequestOptions options = RequestOptions.none();
        RecordingAnthropicApiClient api = new RecordingAnthropicApiClient(client, recorder);

        assertThatThrownBy(() -> api.createAskMessage(params, options, () -> false))
                .isInstanceOf(AnthropicApiClient.CallRefusedException.class);

        assertThat(recorder.toJson()).isEqualTo("{\"models\":[]}\n");
        verify(askClient, never()).messages();
    }

    @Test
    @DisplayName("the one-argument constructor records to the shared recorder")
    void defaultConstructorUsesSharedRecorder() {
        Message response = message(1, 1, 0, 0, 0);
        when(client.messages()).thenReturn(messageService);
        MessageCreateParams params = params();
        when(messageService.create(params)).thenReturn(response);

        assertThat(new RecordingAnthropicApiClient(client).createMessage(params)).isSameAs(response);

        assertThat(PromptUsageRecorder.shared().toJson()).contains("\"model\":\"claude-sonnet-5-5\"");
    }
}
