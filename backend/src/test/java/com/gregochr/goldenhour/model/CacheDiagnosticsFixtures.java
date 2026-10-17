package com.gregochr.goldenhour.model;

import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.CacheMissReason;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Diagnostics;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

import java.util.List;
import java.util.Optional;

/** SDK messages carrying (or not carrying) prompt-cache diagnostics, for the tests that log them. */
public final class CacheDiagnosticsFixtures {

    /** What the API reports when a request diverged from its previous message in the messages. */
    public static final Diagnostics MESSAGES_CHANGED = Diagnostics.of(CacheMissReason.ofMessagesChanged(1234L));

    /** {@link #MESSAGES_CHANGED} as this application keeps it. */
    public static final CacheDiagnostics MESSAGES_CHANGED_READ =
            new CacheDiagnostics(CacheDiagnostics.Status.MISS, "messages_changed", 1234L);

    private CacheDiagnosticsFixtures() {
    }

    /**
     * A succeeded message with the given diagnostics field and a plain text reply, with no cache usage.
     *
     * @param diagnostics what the response's {@code diagnostics} field holds
     * @param text        the reply text
     * @return the message
     */
    public static Message message(Optional<Diagnostics> diagnostics, String text) {
        return message("msg_test", diagnostics, text, 0, 0, 0);
    }

    /**
     * A succeeded message with explicit cache usage.
     *
     * @param id           the Anthropic message id
     * @param diagnostics  what the response's {@code diagnostics} field holds
     * @param text         the reply text
     * @param cacheWrite   total tokens written to the cache
     * @param cacheWrite1h the one-hour part of {@code cacheWrite}
     * @param cacheRead    tokens read from the cache
     * @return the message
     */
    public static Message message(String id, Optional<Diagnostics> diagnostics, String text,
            long cacheWrite, long cacheWrite1h, long cacheRead) {
        return Message.builder()
                .id(id)
                .model(Model.of("claude-haiku-4-5-20251001"))
                .content(List.of(ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(Optional.empty()).build())))
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .diagnostics(diagnostics)
                .usage(Usage.builder()
                        .inputTokens(1_000)
                        .outputTokens(100)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(cacheWrite - cacheWrite1h)
                                .ephemeral1hInputTokens(cacheWrite1h).build())
                        .cacheCreationInputTokens(cacheWrite)
                        .cacheReadInputTokens(cacheRead)
                        .inferenceGeo("us")
                        .serverToolUse(ServerToolUsage.builder().webSearchRequests(0).webFetchRequests(0).build())
                        .serviceTier(Usage.ServiceTier.of("standard"))
                        .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(0).build())
                        .build())
                .build();
    }
}
