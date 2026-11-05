package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the call gate in {@link AnthropicApiClient#createAskMessage}: a refusing gate means no
 * paid request is made, a permitting gate sends the request on the transport-retry-free Ask client.
 */
class AnthropicApiClientAskGateTest {

    private AnthropicClient sharedClient;
    private AnthropicClient askClient;
    private MessageService askMessages;
    private AnthropicApiClient apiClient;
    private final MessageCreateParams params = MessageCreateParams.builder()
            .model("claude-haiku-4-5").maxTokens(100).addUserMessage("hello").build();
    private final RequestOptions options = RequestOptions.none();

    @BeforeEach
    void setUp() {
        sharedClient = mock(AnthropicClient.class);
        askClient = mock(AnthropicClient.class);
        askMessages = mock(MessageService.class);
        // The argument is a lambda configuring the builder; it cannot be matched by value.
        when(sharedClient.withOptions(any())).thenReturn(askClient);
        apiClient = new AnthropicApiClient(sharedClient);
    }

    @Test
    @DisplayName("a gate that refuses the attempt throws CallRefusedException and makes no request")
    void refusingGate_throwsAndMakesNoRequest() {
        assertThatThrownBy(() -> apiClient.createAskMessage(params, options, () -> false))
                .isInstanceOf(AnthropicApiClient.CallRefusedException.class)
                .hasMessage("the call gate refused this attempt")
                .hasNoCause();

        verify(askClient, never()).messages();
        verifyNoInteractions(askMessages);
    }

    @Test
    @DisplayName("a gate that permits the attempt sends the request on the Ask client with the given options")
    void permittingGate_sendsOnTheAskClient() {
        Message response = mock(Message.class);
        when(askClient.messages()).thenReturn(askMessages);
        when(askMessages.create(params, options)).thenReturn(response);

        Message result = apiClient.createAskMessage(params, options, () -> true);

        assertThat(result).isSameAs(response);
        verify(askMessages).create(params, options);
    }

    @Test
    @DisplayName("CallRefusedException is control flow: it carries no stack trace")
    void callRefusedException_hasNoStackTrace() {
        AnthropicApiClient.CallRefusedException refused = new AnthropicApiClient.CallRefusedException();

        assertThat(refused.getStackTrace()).isEmpty();
        assertThat(refused.getSuppressed()).isEmpty();
    }
}
