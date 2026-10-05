package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builders for the SDK {@link Message}s the Ask engine tests have the mocked client return. */
final class AskMessages {

    private AskMessages() {
    }

    static Usage usage(long input, long output) {
        return Usage.builder()
                .inputTokens(input)
                .outputTokens(output)
                .cacheCreation(CacheCreation.builder().ephemeral5mInputTokens(0).ephemeral1hInputTokens(0).build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo("us")
                .serverToolUse(ServerToolUsage.builder().webSearchRequests(0).webFetchRequests(0).build())
                .serviceTier(Usage.ServiceTier.of("standard"))
                .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(0).build())
                .build();
    }

    /** A message that stopped for the given reason with the given content. */
    static Message message(StopReason stop, ContentBlock... blocks) {
        return Message.builder()
                .id("msg_test")
                .model(Model.of("claude-haiku-4-5-20251001"))
                .content(List.of(blocks))
                .stopReason(stop)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .usage(usage(1_000, 100))
                .build();
    }

    /** One {@code tool_use} block. */
    static ContentBlock tool(String id, String name, Map<String, Object> input) {
        return ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(JsonValue.from(input))
                .caller(DirectCaller.builder().build())
                .build());
    }

    /** A {@code tool_use} block whose input is not JSON of any particular shape. */
    static ContentBlock toolWithRawInput(String id, String name, JsonValue input) {
        return ContentBlock.ofToolUse(ToolUseBlock.builder().id(id).name(name).input(input)
                .caller(DirectCaller.builder().build()).build());
    }

    /** A turn that calls tools and stops for {@code tool_use}. */
    static Message toolTurn(ContentBlock... tools) {
        return message(StopReason.TOOL_USE, tools);
    }

    /** A turn whose only call is {@code submit_answer}. */
    static Message submit(Map<String, Object> input) {
        return toolTurn(tool("toolu_submit", AskToolSchemas.SUBMIT_ANSWER, input));
    }

    /** A turn of plain text that ends normally. */
    static Message text(String text) {
        return message(StopReason.END_TURN, ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of())
                .build()));
    }
}
