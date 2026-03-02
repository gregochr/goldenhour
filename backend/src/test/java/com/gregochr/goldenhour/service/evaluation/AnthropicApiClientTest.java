package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnthropicApiClient}.
 */
@ExtendWith(MockitoExtension.class)
class AnthropicApiClientTest {

    @Mock
    private AnthropicClient client;

    @Mock
    private MessageService messageService;

    @InjectMocks
    private AnthropicApiClient anthropicApiClient;

    @Test
    @DisplayName("createMessage() delegates to the Anthropic SDK client")
    void createMessage_delegatesToClient() {
        Message response = Message.builder()
                .id("msg_test")
                .model(Model.of("claude-sonnet-4-5-20250929"))
                .content(List.of())
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(0)
                                .build())
                        .cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder()
                                .webSearchRequests(0)
                                .webFetchRequests(0)
                                .build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
                        .build())
                .build();

        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(response);

        MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-sonnet-4-5-20250929")
                .maxTokens(100)
                .addUserMessage("Hello")
                .build();

        Message result = anthropicApiClient.createMessage(params);

        assertThat(result.id()).isEqualTo("msg_test");
        verify(client).messages();
        verify(messageService).create(params);
    }
}
